package com.noshorts.blocker

import android.content.Context
import android.content.SharedPreferences

/** Thin typed wrapper over SharedPreferences, shared by the UI and the service. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("noshorts", Context.MODE_PRIVATE)

    var blockPlayer: Boolean
        get() = sp.getBoolean(KEY_BLOCK_PLAYER, true)
        set(value) = sp.edit().putBoolean(KEY_BLOCK_PLAYER, value).apply()

    var hideShelves: Boolean
        get() = sp.getBoolean(KEY_HIDE_SHELVES, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_SHELVES, value).apply()

    /**
     * When Back provably cannot escape Shorts, leave YouTube altogether.
     * Off by default: minimising the app out from under someone is a nasty
     * surprise, and with the verify-then-retry escape it is rarely needed.
     */
    var exitAppFallback: Boolean
        get() = sp.getBoolean(KEY_EXIT_APP_FALLBACK, false)
        set(value) = sp.edit().putBoolean(KEY_EXIT_APP_FALLBACK, value).apply()

    var logViewIds: Boolean
        get() = sp.getBoolean(KEY_LOG_VIEW_IDS, false)
        set(value) = sp.edit().putBoolean(KEY_LOG_VIEW_IDS, value).apply()

    /** One of [LINK_AS_VIDEO], [LINK_IN_BROWSER], [LINK_DISCARD]. */
    var linkMode: Int
        get() = sp.getInt(KEY_LINK_MODE, LINK_AS_VIDEO)
        set(value) = sp.edit().putInt(KEY_LINK_MODE, value).apply()

    var blockedCount: Int
        get() = sp.getInt(KEY_BLOCKED_COUNT, 0)
        set(value) = sp.edit().putInt(KEY_BLOCKED_COUNT, value).apply()

    companion object {
        const val LINK_AS_VIDEO = 0
        const val LINK_IN_BROWSER = 1
        const val LINK_DISCARD = 2

        private const val KEY_BLOCK_PLAYER = "block_player"
        private const val KEY_HIDE_SHELVES = "hide_shelves"
        private const val KEY_EXIT_APP_FALLBACK = "exit_app_fallback"
        private const val KEY_LOG_VIEW_IDS = "log_view_ids"
        private const val KEY_LINK_MODE = "link_mode"
        private const val KEY_BLOCKED_COUNT = "blocked_count"
    }
}
