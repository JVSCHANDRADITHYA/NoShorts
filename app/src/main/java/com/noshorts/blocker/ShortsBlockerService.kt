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
 *  - the full-screen Shorts player gets escaped (back, then home if back keeps
 *    landing back in Shorts, which is what happens when Shorts was the entry
 *    point of the task);
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

    private var lastEscapeAt = 0L
    private var escapeStreak = 0

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

        if (prefs.blockPlayer && hasAny(root, ShortsSignals.PLAYER_ID_PREFIXES)) {
            escapeShorts()
            return
        }

        // Not in the player, so any streak of failed escapes is stale.
        escapeStreak = 0

        if (prefs.hideShelves) {
            updateShelfMasks(root)
        } else {
            clearMasks()
        }
    }

    override fun onInterrupt() = clearMasks()

    override fun onUnbind(intent: Intent?): Boolean {
        clearMasks()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearMasks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- escape

    private fun escapeShorts() {
        val now = SystemClock.uptimeMillis()
        if (now - lastEscapeAt < ESCAPE_DEBOUNCE_MS) return
        if (now - lastEscapeAt > STREAK_RESET_MS) escapeStreak = 0
        lastEscapeAt = now
        escapeStreak++

        // The overlays belong to the feed; drop them before we leave it.
        clearMasks()

        if (escapeStreak >= MAX_BACK_ATTEMPTS) {
            // Back keeps returning to Shorts (deep link, or a task that started
            // in Shorts). Leave the app entirely rather than fight a loop.
            Log.i(TAG, "Back did not escape Shorts after " + escapeStreak + " tries; going home")
            performGlobalAction(GLOBAL_ACTION_HOME)
            escapeStreak = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

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

    private fun hasAny(root: AccessibilityNodeInfo, prefixes: List<String>): Boolean {
        var found = false
        forEachNode(root) { node ->
            if (ShortsSignals.matches(node.viewIdResourceName, prefixes)) {
                found = true
                false
            } else {
                true
            }
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

        /** Ignore repeat detections while the back press is still animating. */
        private const val ESCAPE_DEBOUNCE_MS = 350L

        /** Escapes further apart than this are unrelated, not a stuck loop. */
        private const val STREAK_RESET_MS = 3_000L

        /** After this many failed back presses, leave YouTube outright. */
        private const val MAX_BACK_ATTEMPTS = 3

        private const val WATCHDOG_INTERVAL_MS = 800L
        private const val MAX_NODES = 2_500
        private const val MIN_MASK_PX = 40
        private const val MASK_COLOR = 0xFF000000.toInt()
    }
}
