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

    private val process = Runnable {
        pending = false
        update()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        val root = rootInActiveWindow
        if (root == null || root.packageName != X_PACKAGE) {
            removeOverlay()
            return
        }
        val forYou = findTab(root, "For you")
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

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()

    /** A visible node whose label is the tab name (optionally followed by ", selected" etc.). */
    private fun findTab(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(name).firstOrNull {
            val l = label(it)
            it.isVisibleToUser && (l.equals(name, ignoreCase = true) || l.startsWith("$name,", ignoreCase = true))
        }

    /** "Following" also appears elsewhere (profiles, buttons), so only accept the one in the same row as "For you". */
    private fun findFollowingInRow(root: AccessibilityNodeInfo, row: Rect): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText("Following").firstOrNull {
            val b = Rect().also { r -> it.getBoundsInScreen(r) }
            it.isVisibleToUser && label(it).startsWith("Following", ignoreCase = true) &&
                b.centerY() in row.top..row.bottom && b.left >= row.right - 1
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

    private companion object {
        const val X_PACKAGE = "com.twitter.android"
        const val DEBOUNCE_MS = 80L
        const val AUTO_CLICK_COOLDOWN_MS = 1000L
    }
}
