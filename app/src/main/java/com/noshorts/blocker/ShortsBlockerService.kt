package com.noshorts.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /** True from the first Back press until we have confirmed the result. */
    private var escaping = false
    private var backAttempts = 0

    /** Set when Back has repeatedly failed, to stop hammering the device. */
    private var quietUntil = 0L

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

        // An escape is already in flight; let it finish and verify itself
        // rather than stacking another Back press on top of the animation.
        if (escaping) return

        val root = rootInActiveWindow ?: return

        if (prefs.logViewIds) logViewIds(root)

        if (prefs.blockPlayer && isShortsPlayerOnScreen(root)) {
            escapeShorts()
            return
        }

        // Out of the player, so a previous stand-down no longer applies.
        quietUntil = 0L

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

    /** Drops overlays and cancels any escape still waiting to be verified. */
    private fun teardown() {
        handler.removeCallbacks(escapeCheck)
        escaping = false
        backAttempts = 0
        clearMasks()
    }

    // ---------------------------------------------------------------- escape

    /**
     * Presses Back exactly once, then waits [ESCAPE_SETTLE_MS] and looks again.
     *
     * The wait is the important part. YouTube keeps the reel views in the tree
     * for the whole of its exit animation, so reacting to every event that
     * still matches means firing Back two or three more times -- and those
     * extra presses pop the screens *behind* Shorts, which walks the user out
     * of YouTube entirely. One press, then verify.
     */
    private fun escapeShorts() {
        if (escaping) return
        if (SystemClock.uptimeMillis() < quietUntil) return

        escaping = true
        backAttempts = 1

        // The overlays belong to the feed; drop them before we leave it.
        clearMasks()
        Log.i(TAG, "Shorts player detected; pressing Back")
        performGlobalAction(GLOBAL_ACTION_BACK)
        prefs.blockedCount = prefs.blockedCount + 1

        handler.postDelayed(escapeCheck, ESCAPE_SETTLE_MS)
    }

    /** Runs after each Back press to see whether it actually worked. */
    private val escapeCheck = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val stillInShorts = root != null &&
                root.packageName == ShortsSignals.YOUTUBE_PACKAGE &&
                isShortsPlayerOnScreen(root)

            if (!stillInShorts) {
                escaping = false
                backAttempts = 0
                return
            }

            if (backAttempts >= MAX_BACK_ATTEMPTS) {
                Log.i(TAG, "Back did not clear Shorts")
                // Back genuinely cannot get out: a deep link opened Shorts as
                // the only screen in the task.
                if (prefs.exitAppFallback) {
                    Log.i(TAG, "Back failed " + backAttempts + " times; leaving YouTube")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    // Back off instead of minimising the app behind the user.
                    Log.i(TAG, "Back failed " + backAttempts + " times; standing down")
                    quietUntil = SystemClock.uptimeMillis() + QUIET_PERIOD_MS
                }
                escaping = false
                backAttempts = 0
                return
            }

            backAttempts++
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed(this, ESCAPE_SETTLE_MS)
        }
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

        var found = false
        forEachNode(root) { node ->
            if (node.isVisibleToUser &&
                ShortsSignals.matches(node.viewIdResourceName, ShortsSignals.PLAYER_ID_PREFIXES)
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= minHeight) {
                    if (prefs.logViewIds) {
                        Log.d(TAG, "player match: " + node.viewIdResourceName + " " + bounds)
                    }
                    found = true
                    return@forEachNode false
                }
            }
            true
        }
        return found
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
         * How long to let a Back press land before checking whether it worked.
         * Needs to outlast YouTube's exit animation plus the lag before the
         * accessibility tree catches up, or the check sees stale reel views and
         * fires a second, harmful Back press.
         */
        private const val ESCAPE_SETTLE_MS = 900L

        /**
         * Back presses per Short. Deliberately 1: if the first press did not
         * clear the player, a second one acts on whatever screen is showing
         * now, and three in a row is exactly enough to walk out of YouTube.
         * A Short that survives one Back is worth far less than the app
         * closing under the user.
         */
        private const val MAX_BACK_ATTEMPTS = 1

        /**
         * Fraction of screen height a reel view must cover to count as the
         * full-screen player rather than a cached fragment or a feed row.
         */
        private const val PLAYER_MIN_HEIGHT_RATIO = 0.6

        /** How long to leave Shorts alone after Back has proved useless. */
        private const val QUIET_PERIOD_MS = 5_000L

        private const val WATCHDOG_INTERVAL_MS = 800L
        private const val MAX_NODES = 2_500
        private const val MIN_MASK_PX = 40
        private const val MASK_COLOR = 0xFF000000.toInt()
    }
}
