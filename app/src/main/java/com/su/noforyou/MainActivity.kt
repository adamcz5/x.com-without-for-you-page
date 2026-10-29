package com.su.noforyou

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var found: Updater.Release? = null

    @Suppress("DEPRECATION")
    private fun installedBuild(): Int = packageManager.getPackageInfo(packageName, 0).versionCode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences(ForYouBlockerService.PREFS, MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            textSize = 20f
            text = getString(R.string.app_name)
        })
        layout.addView(Switch(this).apply {
            text = "Block the For You tab"
            textSize = 16f
            isChecked = prefs.getBoolean(ForYouBlockerService.KEY_ENABLED, true)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(ForYouBlockerService.KEY_ENABLED, on).apply() }
        })
        val holdLabel = TextView(this).apply { textSize = 16f }
        fun showHold() { holdLabel.text = "Black screen for ${prefs.getInt(ForYouBlockerService.KEY_HOLD_SECONDS, ForYouBlockerService.DEFAULT_HOLD_SECONDS)} s when X opens" }
        fun changeHold(d: Int) {
            val v = (prefs.getInt(ForYouBlockerService.KEY_HOLD_SECONDS, ForYouBlockerService.DEFAULT_HOLD_SECONDS) + d).coerceIn(0, 15)
            prefs.edit().putInt(ForYouBlockerService.KEY_HOLD_SECONDS, v).apply()
            showHold()
        }
        showHold()
        layout.addView(holdLabel)
        layout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "  -  "; setOnClickListener { changeHold(-1) } })
            addView(Button(this@MainActivity).apply { text = "  +  "; setOnClickListener { changeHold(1) } })
        })
        layout.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "First turn on the service: tap \"Open Accessibility settings\" and enable " +
                "\"${getString(R.string.app_name)}\" (Installed / Downloaded apps)."
        })
        layout.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Open X"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage("com.twitter.android")?.let(::startActivity)
            }
        })

        updateStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Installed build: ${installedBuild()}"
        }
        layout.addView(updateStatus)
        layout.addView(Button(this).apply {
            text = "Check for update"
            setOnClickListener { checkUpdate() }
        })
        updateButton = Button(this).apply {
            text = "Update now"
            visibility = android.view.View.GONE
            setOnClickListener { doUpdate() }
        }
        layout.addView(updateButton)

        status = TextView(this).apply { textSize = 13f; setPadding(0, pad, 0, pad / 2) }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "Refresh report"
            setOnClickListener { refresh() }
        })
        layout.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("report", status.text.toString() + "\n\n" + report.text))
            }
        })
        report = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        layout.addView(report)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = ForYouBlockerService.instance != null
        status.text = "Service running: $running\n" +
            "Events seen: ${ForYouBlockerService.eventCount}\n" +
            "Last app in front: ${ForYouBlockerService.lastPackage.ifEmpty { "-" }}\n" +
            "Last window event from: ${ForYouBlockerService.lastEventPackage.ifEmpty { "-" }}\n" +
            "To fill the report: open X for a few seconds, come back, tap Refresh."
        report.text = ForYouBlockerService.lastReport.ifEmpty { "(nothing from X yet)" }
    }

    private fun checkUpdate() {
        updateStatus.text = "Checking for update..."
        Thread {
            try {
                val r = Updater.latest()
                runOnUiThread {
                    if (r.build > installedBuild()) {
                        found = r
                        updateStatus.text = "New build ${r.build} available (installed: ${installedBuild()})"
                        updateButton.visibility = android.view.View.VISIBLE
                    } else {
                        updateStatus.text = "Up to date (build ${installedBuild()})"
                        updateButton.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Update check failed: ${e.message}" }
            }
        }.start()
    }

    private fun doUpdate() {
        val r = found ?: return
        updateStatus.text = "Downloading build ${r.build}..."
        Thread {
            try {
                Updater.downloadAndInstall(this, r)
                runOnUiThread { updateStatus.text = "Downloaded. Android will ask you to confirm the install." }
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Update failed: ${e.message}" }
            }
        }.start()
    }
}
