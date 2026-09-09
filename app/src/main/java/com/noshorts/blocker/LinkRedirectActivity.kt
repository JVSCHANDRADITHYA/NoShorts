package com.noshorts.blocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Intercepts youtube.com/shorts/<id> links so a shared Short never opens the
 * Shorts player in the first place.
 *
 * A Shorts id is just a normal video id, so rewriting the URL to /watch?v=<id>
 * hands YouTube the same video in the regular player. Android will only route
 * links here once you set this app as a handler for youtube.com/shorts links in
 * Settings > Apps > NoShorts > Open by default.
 */
class LinkRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        val videoId = extractShortsId(intent?.data)

        if (videoId == null) {
            finish()
            return
        }

        prefs.blockedCount = prefs.blockedCount + 1

        when (prefs.linkMode) {
            Prefs.LINK_IN_BROWSER -> openInBrowser(videoId)
            Prefs.LINK_DISCARD -> toast(getString(R.string.toast_link_discarded))
            else -> openAsRegularVideo(videoId)
        }

        finish()
    }

    private fun openAsRegularVideo(videoId: String) {
        val watchUri = Uri.parse(WATCH_URL_PREFIX + videoId)

        // Explicitly target YouTube first, otherwise the chooser can bounce the
        // link straight back to whichever app sent it here.
        val direct = Intent(Intent.ACTION_VIEW, watchUri)
            .setPackage(YOUTUBE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStart(direct)) return

        // YouTube missing or disabled: let the system pick. The /watch URL does
        // not match this app manifest filter, so this cannot loop back here.
        val fallback = Intent(Intent.ACTION_VIEW, watchUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(fallback)) {
            toast(getString(R.string.toast_no_handler))
        }
    }

    private fun openInBrowser(videoId: String) {
        val watchUri = Uri.parse(WATCH_URL_PREFIX + videoId)
        val browser = defaultBrowserPackage()

        val intent = Intent(Intent.ACTION_VIEW, watchUri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (browser != null) intent.setPackage(browser)

        if (!tryStart(intent)) {
            toast(getString(R.string.toast_no_handler))
        }
    }

    /** Resolves whatever handles a plain http URL, which is the browser. */
    private fun defaultBrowserPackage(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val info = packageManager.resolveActivity(probe, 0) ?: return null
        val pkg = info.activityInfo?.packageName
        // Never hand the link to YouTube or back to ourselves.
        return if (pkg == null || pkg == YOUTUBE_PACKAGE || pkg == packageName) null else pkg
    }

    private fun tryStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val YOUTUBE_PACKAGE = ShortsSignals.YOUTUBE_PACKAGE
        private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="

        /**
         * Pulls the id out of .../shorts/<id> and drops any trailing path or
         * query. Returns null for anything that is not a Shorts URL.
         */
        fun extractShortsId(uri: Uri?): String? {
            if (uri == null) return null
            val segments = uri.pathSegments ?: return null
            val index = segments.indexOf("shorts")
            if (index < 0 || index + 1 >= segments.size) return null
            val id = segments[index + 1].trim()
            return if (id.isEmpty()) null else id
        }
    }
}
