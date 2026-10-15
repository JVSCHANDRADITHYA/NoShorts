package com.noshorts.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches the YouTube app view tree and reacts to Shorts.
 *
 * Two independent behaviours:
 *  - the full-screen Shorts player gets escaped with a single Back press;
 *  - Shorts shelves in the feeds get covered by touch-transparent overlays, so
 *    they are invisible but still scroll normally. If you do manage to tap one,
 *    the player blocker catches it a moment later.
 *
 * The YouTube process is never touched: this reads the accessibility tree the
 * framework already exposes and presses the same global buttons a user can.
 */
class ShortsBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var windowManager: WindowManager? = null

    private val masks = mutableListOf<View>()
    private var maskedRects: List<Rect> = emptyList()

    /**
     * Whether the Shorts player was on screen the last time we looked.
     *
     * This is the entire safety mechanism. Back is pressed only on the
     * transition from "not in Shorts" to "in Shorts" -- never while the flag
     * stays true. So each entry into Shorts costs exactly one Back press, and
     * a detection that is stuck true (a false positive, or a Short that Back
     * cannot close) can never accumulate presses and walk the user out of the
     * app. See escapeShorts().
     */
    private var wasInShorts = false

    /** Clears leftover overlays once YouTube is no longer the foreground app. */
    private val maskWatchdog = object : Runnable {
        override fun run() {
            if (masks.isEmpty()) return
            val stillInYouTube =
                rootInActiveWindow?.packageName == ShortsSignals.YOUTUBE_PACKAGE
            if (stillInYouTube) {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            } else {
                clearMasks()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        windowManager = getSystemService(WindowManager::class.java)
        Log.i(TAG, "Shorts blocker connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != ShortsSignals.YOUTUBE_PACKAGE) return

        val root = rootInActiveWindow ?: return

        if (prefs.logViewIds) logViewIds(root)

        val inShorts = prefs.blockPlayer && isShortsPlayerOnScreen(root)

        if (inShorts) {
            if (!wasInShorts) escapeShorts()    // rising edge only
            wasInShorts = true
            return
        }

        // Out of the player. Arm the edge detector for the next entry.
        wasInShorts = false

        if (prefs.hideShelves) {
            updateShelfMasks(root)
        } else {
            clearMasks()
        }
    }

    override fun onInterrupt() = teardown()

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    /** Drops overlays and re-arms the edge detector. */
    private fun teardown() {
        wasInShorts = false
        clearMasks()
    }

    // ---------------------------------------------------------------- escape

    /**
     * Presses Back exactly once, and then does nothing further.
     *
     * There is deliberately no retry, no timer and no fallback. Earlier
     * versions verified the press and tried again if Shorts was still showing,
     * which sounds reasonable and is how this walked users out of YouTube: a
     * detection that stays true -- because a leftover view matched, or because
     * Back genuinely cannot close that Short -- turned into press after press,
     * each one popping whatever screen was behind.
     *
     * The caller only invokes this on a rising edge, so the ceiling is one
     * press per entry into Shorts. A Short that survives it simply plays. That
     * is a far better failure than the app closing under the user, and it is
     * now a property of the control flow rather than a setting anyone can
     * switch off.
     *
     * GLOBAL_ACTION_HOME appears nowhere in this file, by design.
     */
    private fun escapeShorts() {
        // The overlays belong to the feed; drop them before we leave it.
        clearMasks()
        Log.i(TAG, "Shorts player detected; pressing Back once")
        performGlobalAction(GLOBAL_ACTION_BACK)
        prefs.blockedCount = prefs.blockedCount + 1
    }

    // ----------------------------------------------------------------- masks

    private fun updateShelfMasks(root: AccessibilityNodeInfo) {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val rects = collectRects(root, ShortsSignals.SHELF_ID_PREFIXES)
        if (rects == maskedRects) return

        clearMasks()
        if (rects.isEmpty()) return
        maskedRects = rects

        for (rect in rects) {
            if (rect.width() < MIN_MASK_PX || rect.height() < MIN_MASK_PX) continue
            val view = View(this)
            view.setBackgroundColor(MASK_COLOR)
            val params = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                rect.left,
                rect.top,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // NOT_TOUCHABLE matters: the mask hides the shelf but lets your
                // finger through, so the feed still scrolls under it.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            )
            params.gravity = Gravity.TOP or Gravity.START
            try {
                wm.addView(view, params)
                masks.add(view)
            } catch (e: Exception) {
                Log.w(TAG, "Could not add mask overlay", e)
            }
        }

        handler.removeCallbacks(maskWatchdog)
        handler.postDelayed(maskWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun clearMasks() {
        handler.removeCallbacks(maskWatchdog)
        maskedRects = emptyList()
        if (masks.isEmpty()) return
        val wm = windowManager
        for (view in masks) {
            try {
                wm?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove mask overlay", e)
            }
        }
        masks.clear()
    }

    // ------------------------------------------------------------- traversal

    /**
     * Breadth-first walk, bounded by [MAX_NODES] so a pathological tree can
     * never stall the UI thread. The visitor returns false to stop early.
     */
    private fun forEachNode(
        root: AccessibilityNodeInfo,
        visitor: (AccessibilityNodeInfo) -> Boolean
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (!visitor(node)) return
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.addLast(child)
            }
        }
    }

    /**
     * True only when the full-screen Shorts player is actually on screen.
     *
     * Matching the reel ids alone is not enough. YouTube's bottom-nav tabs are
     * backed by fragments that stay in the tree after you leave them, so a
     * cached Shorts fragment keeps matching while you are back on the home
     * feed -- and acting on that walks the user out of the app one Back press
     * at a time. Two extra conditions kill the false positive:
     *
     *  - the node has to be visible to the user, not just present;
     *  - it has to be big. The Shorts player fills the display, while leftover
     *    fragments and feed rows do not.
     */
    private fun isShortsPlayerOnScreen(root: AccessibilityNodeInfo): Boolean {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        if (screen.height() <= 0) return false
        val minHeight = (screen.height() * PLAYER_MIN_HEIGHT_RATIO).toInt()

        val signals = HashSet<String>()
        forEachNode(root) { node ->
            val id = node.viewIdResourceName
            if (node.isVisibleToUser &&
                ShortsSignals.matches(id, ShortsSignals.PLAYER_ID_PREFIXES) &&
                !ShortsSignals.matches(id, ShortsSignals.KNOWN_FALSE_POSITIVES)
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= minHeight) {
                    signals.add(id!!.substringAfterLast('/'))
                }
            }
            // Stop as soon as we have seen enough distinct signals.
            signals.size < ShortsSignals.MIN_PLAYER_SIGNALS
        }
        return signals.size >= ShortsSignals.MIN_PLAYER_SIGNALS
    }

    private fun collectRects(
        root: AccessibilityNodeInfo,
        prefixes: List<String>
    ): List<Rect> {
        val rects = mutableListOf<Rect>()
        forEachNode(root) { node ->
            if (ShortsSignals.matches(node.viewIdResourceName, prefixes) && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // Skip anything already covered by an ancestor we matched.
                if (rects.none { it.contains(rect) }) rects.add(rect)
            }
            true
        }
        return rects
    }

    private fun logViewIds(root: AccessibilityNodeInfo) {
        forEachNode(root) { node ->
            val id = node.viewIdResourceName
            if (id != null) Log.d(TAG, id)
            true
        }
    }

    companion object {
        private const val TAG = "NoShorts"

        /**
         * Back presses per Short. Deliberately 1: if the first press did not
         * clear the player, a second one acts on whatever screen is showing
         * now, and three in a row is exactly enough to walk out of YouTube.
         * A Short that survives one Back is worth far less than the app
         * closing under the user.
         */

        /**
         * Fraction of screen height a reel view must cover to count as the
         * full-screen player rather than a cached fragment or a feed row.
         */
        private const val PLAYER_MIN_HEIGHT_RATIO = 0.6

        private const val WATCHDOG_INTERVAL_MS = 800L
        private const val MAX_NODES = 2_500
        private const val MIN_MASK_PX = 40
        private const val MASK_COLOR = 0xFF000000.toInt()
    }
}
