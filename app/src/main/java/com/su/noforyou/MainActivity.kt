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
        fun toggle(label: String, key: String) = Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = prefs.getBoolean(key, true)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        // a "- N +" picker for a number of seconds
        fun picker(label: (Int) -> String, key: String, default: Int, max: Int): LinearLayout {
            val text = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 0) }
            fun show() { text.text = label(prefs.getInt(key, default)) }
            fun change(d: Int) {
                prefs.edit().putInt(key, (prefs.getInt(key, default) + d).coerceIn(0, max)).apply()
                show()
            }
            show()
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@MainActivity).apply { this.text = "  -  "; setOnClickListener { change(-1) } })
                    addView(Button(this@MainActivity).apply { this.text = "  +  "; setOnClickListener { change(1) } })
                })
            }
        }
        layout.addView(toggle("Black screen (hides the For You page)", ForYouBlockerService.KEY_BLACK))
        layout.addView(toggle("Redirect to Following", ForYouBlockerService.KEY_REDIRECT))
        layout.addView(picker(
            { "After opening X, wait $it s, then go to Following" },
            ForYouBlockerService.KEY_OPEN_SECONDS, ForYouBlockerService.DEFAULT_OPEN_SECONDS, 15
        ))
        layout.addView(picker(
            { "After you land on For you again, wait $it s, then go back to Following" },
            ForYouBlockerService.KEY_BACK_SECONDS, ForYouBlockerService.DEFAULT_BACK_SECONDS, 10
        ))
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
            "Last tap: ${ForYouBlockerService.lastTap}\n" +
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
