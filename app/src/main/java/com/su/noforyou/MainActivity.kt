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
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            textSize = 15f
            text = "1. Tap \"Enable\" and turn on \"No For You\" under Accessibility " +
                "(Installed apps / Downloaded apps).\n" +
                "2. Use the normal X app. The For You tab is covered and X stays on Following."
        })
        layout.addView(Button(this).apply {
            text = "Enable"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        layout.addView(Button(this).apply {
            text = "Open X"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage("com.twitter.android")?.let(::startActivity)
            }
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, pad / 2) }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        })
        layout.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("No For You report", status.text.toString() + "\n\n" + report.text))
            }
        })
        report = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(report) })
        setContentView(layout)
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
            "Version 1.1 - open X for a few seconds, come back here and tap Refresh."
        report.text = ForYouBlockerService.lastReport.ifEmpty { "(nothing from X yet)" }
    }
}
