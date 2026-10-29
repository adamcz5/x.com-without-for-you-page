package com.su.noforyou

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * While the X app is in the foreground: covers the "For you" tab with an overlay that swallows
 * taps, and taps "Following" whenever "For you" ends up selected. Only the package name of other
 * apps is looked at; nothing is stored or sent anywhere.
 */
class ForYouBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var overlay: View? = null
    private var overlayBounds = Rect()
    private var lastAutoClick = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private val process = Runnable {
        pending = false
        update()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        eventCount++
        if (pending) return
        pending = true
        handler.postDelayed(process, DEBOUNCE_MS)
    }

    override fun onInterrupt() = removeOverlay()

    override fun onDestroy() {
        handler.removeCallbacks(process)
        removeOverlay()
        super.onDestroy()
    }

    private fun update() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)) {      // switched off in the app
            removeOverlay()
            return
        }
        val root = rootInActiveWindow
        lastPackage = root?.packageName?.toString() ?: "(no window)"
        if (root == null || root.packageName != X_PACKAGE) {
            removeOverlay()
            return
        }
        val forYou = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }
        val now0 = System.currentTimeMillis()
        if (now0 - lastDumpAt > DUMP_EVERY_MS) {      // keep a readable copy of what X shows, for the in-app report
            lastDumpAt = now0
            lastReport = "For you tab found: ${forYou != null}\n\nNODES MENTIONING For you / Following:\n" +
                scanTabs(root) + "\nALL TEXT ON SCREEN:\n" + dump(root)
        }
        if (forYou == null) {
            removeOverlay()
            return
        }
        val tab = clickableAncestor(forYou)
        val bounds = Rect().also { tab.getBoundsInScreen(it) }
        showOverlay(bounds)

        if (isSelected(forYou) || isSelected(tab)) {
            val now = System.currentTimeMillis()
            if (now - lastAutoClick > AUTO_CLICK_COOLDOWN_MS) {
                findFollowingInRow(root, bounds)?.let {
                    lastAutoClick = now
                    clickableAncestor(it).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
    }

    /** Every node whose text or description mentions the tab names, with its details. */
    private fun scanTabs(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            val all = "$t $d".lowercase()
            if ("for you" in all || "following" in all) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                sb.append("text='$t' desc='$d' class=${node.className} visible=${node.isVisibleToUser} " +
                    "clickable=${node.isClickable} selected=${node.isSelected} @${b.toShortString()}\n")
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return sb.toString().ifEmpty { "(no node mentions them)\n" }
    }

    /** Text and descriptions of everything X exposes on screen (capped), one per line. */
    private fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= 500) return
            val t = node.text?.toString()
            val d = node.contentDescription?.toString()
            if (!t.isNullOrBlank() || !d.isNullOrBlank()) {
                count++
                val b = Rect().also { node.getBoundsInScreen(it) }
                if (node.isSelected) sb.append("[selected] ")
                if (node.isClickable) sb.append("[clickable] ")
                sb.append(t ?: "").append(" | ").append(d ?: "").append("  @").append(b.toShortString()).append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()

    /** A visible node near the top whose label starts with the tab name (e.g. "For you", "For you, tab 1 of 2"). */
    private fun findTab(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val maxTop = resources.displayMetrics.heightPixels * 0.3
        return root.findAccessibilityNodeInfosByText(name).firstOrNull {
            val l = label(it)
            val top = Rect().also { r -> it.getBoundsInScreen(r) }.top
            it.isVisibleToUser && l.startsWith(name, ignoreCase = true) && l.length <= name.length + 40 && top < maxTop
        }
    }

    /** "Following" also appears elsewhere (profiles, buttons), so only accept the one in the same row as "For you". */
    private fun findFollowingInRow(root: AccessibilityNodeInfo, row: Rect): AccessibilityNodeInfo? =
        FOLLOWING.firstNotNullOfOrNull { name ->
            root.findAccessibilityNodeInfosByText(name).firstOrNull {
                val b = Rect().also { r -> it.getBoundsInScreen(r) }
                it.isVisibleToUser && label(it).startsWith(name, ignoreCase = true) &&
                    b.centerY() in row.top..row.bottom && b.left >= row.right - 1
            }
        }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            if (n == null) return node
            if (n!!.isClickable) return n!!
            n = n!!.parent
        }
        return node
    }

    private fun isSelected(node: AccessibilityNodeInfo): Boolean =
        node.isSelected || node.parent?.isSelected == true

    private fun showOverlay(bounds: Rect) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        val existing = overlay
        if (existing == null) {
            val v = View(this).apply {
                setBackgroundColor(Color.argb(235, 20, 20, 20))
                setOnTouchListener { _, _ -> true }
            }
            wm.addView(v, params)
            overlay = v
        } else if (bounds != overlayBounds) {
            wm.updateViewLayout(existing, params)
        }
        overlayBounds = Rect(bounds)
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        overlay = null
    }

    companion object {
        @Volatile var instance: ForYouBlockerService? = null
        @Volatile var eventCount = 0
        @Volatile var lastPackage = ""
        @Volatile var lastReport = ""
        private var lastDumpAt = 0L
        const val PREFS = "prefs"
        const val KEY_ENABLED = "enabled"
        private const val DUMP_EVERY_MS = 1500L
        private const val X_PACKAGE = "com.twitter.android"
        // Tab labels in X's interface languages (the service matches by label, so the phone/app language matters)
        private val FOR_YOU = listOf("For you", "Pro vás", "Pre vás", "Für dich", "Dla Ciebie", "Para ti", "Pour vous", "Per te", "Para você", "Voor jou")
        private val FOLLOWING = listOf("Following", "Sledujete", "Sledované", "Folge ich", "Obserwowani", "Siguiendo", "Abonnements", "Seguiti", "Seguindo", "Volgend")
        private const val DEBOUNCE_MS = 80L
        private const val AUTO_CLICK_COOLDOWN_MS = 1000L
    }
}
