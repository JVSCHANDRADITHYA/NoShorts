package com.noshorts.blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/** Setup and toggles. All the real work happens in [ShortsBlockerService]. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var enableButton: Button
    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.status_text)
        statsText = findViewById(R.id.stats_text)
        enableButton = findViewById(R.id.enable_button)
        overlayButton = findViewById(R.id.overlay_button)

        enableButton.setOnClickListener {
            startSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        overlayButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + packageName)
                    )
                )
            }
        }

        findViewById<Button>(R.id.links_button).setOnClickListener {
            // "Open by default" lives in the app details screen on every version
            // that has it, so this is the one reliable destination.
            startSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)
                )
            )
        }

        bindSwitch(R.id.switch_player, prefs.blockPlayer) { prefs.blockPlayer = it }
        bindSwitch(R.id.switch_exit_fallback, prefs.exitAppFallback) { prefs.exitAppFallback = it }
        bindSwitch(R.id.switch_shelves, prefs.hideShelves) { prefs.hideShelves = it }
        bindSwitch(R.id.switch_log, prefs.logViewIds) { prefs.logViewIds = it }

        val linkGroup = findViewById<RadioGroup>(R.id.link_mode_group)
        linkGroup.check(
            when (prefs.linkMode) {
                Prefs.LINK_IN_BROWSER -> R.id.link_browser
                Prefs.LINK_DISCARD -> R.id.link_discard
                else -> R.id.link_video
            }
        )
        linkGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.linkMode = when (checkedId) {
                R.id.link_browser -> Prefs.LINK_IN_BROWSER
                R.id.link_discard -> Prefs.LINK_DISCARD
                else -> Prefs.LINK_AS_VIDEO
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val view = findViewById<Switch>(id)
        view.isChecked = initial
        view.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onChange(checked) }
    }

    private fun refreshStatus() {
        val serviceOn = isAccessibilityServiceEnabled(this)
        statusText.text = getString(
            if (serviceOn) R.string.status_active else R.string.status_inactive
        )
        enableButton.text = getString(
            if (serviceOn) R.string.action_open_accessibility else R.string.action_enable_service
        )

        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        overlayButton.isEnabled = !overlayOk
        overlayButton.text = getString(
            if (overlayOk) R.string.action_overlay_granted else R.string.action_grant_overlay
        )

        statsText.text = getString(R.string.stats_blocked, prefs.blockedCount)
    }

    private fun startSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            statusText.text = getString(R.string.status_settings_failed)
        }
    }

    companion object {
        /**
         * Reads the platform list of enabled services rather than tracking our
         * own flag, so the UI stays correct when the user flips the switch in
         * Settings instead of here.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + ShortsBlockerService::class.java.name
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (entry.equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
