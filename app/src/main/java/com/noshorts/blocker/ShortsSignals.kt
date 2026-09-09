package com.noshorts.blocker

/**
 * View-id fingerprints for the Shorts UI inside the YouTube app.
 *
 * Everything is matched on the part after "id/", by prefix, because YouTube
 * renames leaf views between releases far more often than it renames the whole
 * "reel_*" family. If a YouTube update ever slips past the blocker, turn on
 * "Log view ids" in the app, open Shorts, and run:
 *
 *     adb logcat -s NoShorts
 *
 * then add whatever new prefix shows up to the right list below.
 */
object ShortsSignals {

    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /**
     * Ids that only ever exist while the full-screen Shorts player is on top.
     * A single hit here is enough to bounce out, so nothing that can appear in
     * the home feed belongs in this list.
     */
    val PLAYER_ID_PREFIXES = listOf(
        "reel_recycler",
        "reel_player",
        "reel_watch",
        "reel_dyn",
        "reel_progress_bar",
        "reel_time_bar",
        "reel_multi_video",
        "shorts_player",
        "shorts_video_container"
    )

    /**
     * Ids for the horizontal Shorts *shelf* that gets injected into the home,
     * subscriptions and search feeds. These are masked, not escaped from --
     * bouncing the whole feed because a shelf scrolled into view would make the
     * app unusable.
     */
    val SHELF_ID_PREFIXES = listOf(
        "reel_shelf",
        "shorts_shelf",
        "reel_item",
        "shorts_lockup"
    )

    fun matches(viewIdResourceName: String?, prefixes: List<String>): Boolean {
        if (viewIdResourceName == null) return false
        val leaf = viewIdResourceName.substringAfterLast('/')
        return prefixes.any { leaf.startsWith(it) }
    }
}
