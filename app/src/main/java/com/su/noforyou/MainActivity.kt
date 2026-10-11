package com.su.noforyou

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            textSize = 16f
            text = "1. Tap \"Enable\" and turn on \"No For You\" under Accessibility " +
                "(Installed apps / Downloaded apps).\n\n" +
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
        setContentView(layout)
    }
}
