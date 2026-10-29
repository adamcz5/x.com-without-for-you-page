package com.su.noforyou

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
    private var tappedThisVisit = false
    private var coveredThisVisit = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private var startCover: View? = null
    private val hideStartCover = Runnable { removeStartCover() }

    private fun showStartCover() {
        if (startCover != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        wm.addView(v, params)
        startCover = v
        handler.postDelayed(hideStartCover, START_COVER_MAX_MS)      // never stay black for long, whatever happens
    }

    private fun removeStartCover() {
        handler.removeCallbacks(hideStartCover)
        val v = startCover ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        startCover = null
    }

    private var hidePending = false
    private val hide = Runnable {
        hidePending = false
        removeOverlay()
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
        removeStartCover()
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
            removeStartCover()
            tappedThisVisit = false      // next time X comes to the front, switch to Following again
            coveredThisVisit = false
            return
        }
        if (!coveredThisVisit) {      // X just opened: black screen until we are on Following (once per visit)
            coveredThisVisit = true
            showStartCover()
        }
        val forYou = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }
        val now0 = System.currentTimeMillis()
        if (now0 - lastDumpAt > DUMP_EVERY_MS) {      // keep a readable copy of what X shows, for the in-app report
            lastDumpAt = now0
            lastReport = "For you tab found: ${forYou != null}\n\nNODES MENTIONING For you / Following:\n" +
                scanTabs(root) + "\nALL TEXT ON SCREEN:\n" + dump(root)
        }
        if (forYou == null) {
            if (overlay != null && !hidePending) {      // X redraws its header a lot; only hide if it stays gone
                hidePending = true
                handler.postDelayed(hide, HIDE_DELAY_MS)
            }
            return
        }
        hidePending = false
        handler.removeCallbacks(hide)
        val bounds = Rect().also { forYou.getBoundsInScreen(it) }
        showOverlay(bounds)

        // X does not mark the selected tab, so: tap "Following" once per visit to X, and again whenever For you reports selected
        val now = System.currentTimeMillis()
        if ((!tappedThisVisit || isSelected(forYou)) && now - lastAutoClick > AUTO_CLICK_COOLDOWN_MS) {
            FOLLOWING.firstNotNullOfOrNull { findFollowingInRow(root, it, bounds) }?.let {
                lastAutoClick = now
                tappedThisVisit = true
                tap(it)
                handler.postDelayed(hideStartCover, AFTER_TAP_MS)      // show the screen again once Following is up
            }
        }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val b = Rect().also { node.getBoundsInScreen(it) }
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
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

    /** All visible nodes whose label starts with [name] (own tree walk: the system's text search missed X's tabs). */
    private fun findNodes(root: AccessibilityNodeInfo, name: String): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val l = label(node)
            if (node.isVisibleToUser && l.startsWith(name, ignoreCase = true) && l.length <= name.length + 40) out.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return out
    }

    private fun area(node: AccessibilityNodeInfo): Int {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.width() * r.height()
    }

    /** The tab near the top of the screen; of several matches (the tab view and its text) the biggest one. */
    private fun findTab(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val maxTop = resources.displayMetrics.heightPixels * 0.3
        return findNodes(root, name)
            .filter { Rect().also { r -> it.getBoundsInScreen(r) }.top < maxTop }
            .maxByOrNull { area(it) }
    }

    /** "Following" also appears elsewhere (profiles, buttons), so only accept the one in the same row, right of "For you". */
    private fun findFollowingInRow(root: AccessibilityNodeInfo, name: String, row: Rect): AccessibilityNodeInfo? =
        findNodes(root, name).filter {
            val b = Rect().also { r -> it.getBoundsInScreen(r) }
            b.centerY() in row.top..row.bottom && b.left >= row.right - 1
        }.maxByOrNull { area(it) }

    private fun isSelected(node: AccessibilityNodeInfo): Boolean =
        node.isSelected || node.parent?.isSelected == true

    private fun showOverlay(bounds: Rect) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
                setBackgroundColor(Color.BLACK)
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
        private const val HIDE_DELAY_MS = 450L
        private const val AUTO_CLICK_COOLDOWN_MS = 1000L
        private const val START_COVER_MAX_MS = 4000L
        private const val AFTER_TAP_MS = 700L
    }
}
