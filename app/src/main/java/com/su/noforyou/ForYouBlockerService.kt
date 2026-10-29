package com.su.noforyou

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
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

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) = xClosed()      // locked: next time X shows counts as a fresh open
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try { unregisterReceiver(screenOff) } catch (e: Exception) { /* not registered */ }
        return super.onUnbind(intent)
    }

    private var startCover: View? = null
    private val hideStartCover = Runnable { removeStartCover() }

    private fun showStartCover(maxMs: Long) {
        if (startCover != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // black over the middle only: the top bar (profile, X logo) and the bottom menu stay visible
        val h = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels
        val top = (h * COVER_TOP).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (h * COVER_BOTTOM).toInt() - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = top
        }
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        wm.addView(v, params)
        startCover = v
        handler.postDelayed(hideStartCover, maxMs)      // never stay black for long, whatever happens
    }

    private fun removeStartCover() {
        handler.removeCallbacks(hideStartCover)
        val v = startCover ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        startCover = null
    }

    private val process = Runnable {
        pending = false
        update()
    }

    private var inX = false
    private var tabTopMax = 0
    private var lastForce = 0L
    private val redirect = Runnable {
        tapFollowing()
        handler.postDelayed(hideStartCover, AFTER_TAP_MS)      // show the screen again once Following is up
    }

    private fun enabled() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Windows that can appear on top of X without X being left (our own covers, the status bar, the keyboard). */
    private fun isIgnored(pkg: String) =
        pkg == packageName || pkg.startsWith("com.android.systemui") || pkg == "android" ||
            pkg.contains("inputmethod") || pkg.contains("honeyboard")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        eventCount++
        val e = event ?: return
        val pkg = e.packageName?.toString()
        if (pkg != null && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isIgnored(pkg)) {
            lastEventPackage = pkg
            if (pkg == X_PACKAGE) {
                if (!inX) xOpened()
            } else if (inX) {
                xClosed()
            }
        }
        if (pending) return
        pending = true
        handler.postDelayed(process, DEBOUNCE_MS)
    }

    /** X came to the front: black screen for the set time (leave X meanwhile if you like), then switch to Following. */
    private fun xOpened() {
        if (!enabled()) return
        inX = true
        val holdMs = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_HOLD_SECONDS, DEFAULT_HOLD_SECONDS) * 1000L
        showStartCover(holdMs + START_COVER_EXTRA_MS)
        handler.postDelayed(redirect, holdMs)
    }

    private fun xClosed() {
        inX = false
        tabTopMax = 0
        handler.removeCallbacks(redirect)
        removeStartCover()
        removeOverlay()
    }

    override fun onInterrupt() = removeOverlay()

    override fun onDestroy() {
        handler.removeCallbacks(process)
        removeOverlay()
        removeStartCover()
        super.onDestroy()
    }

    private fun update() {
        if (!enabled()) {      // switched off in the app
            removeOverlay()
            return
        }
        val root = rootInActiveWindow
        lastPackage = root?.packageName?.toString() ?: "(no window)"
        if (root == null || root.packageName != X_PACKAGE) {
            removeOverlay()
            tabTopMax = 0
            return
        }
        val forYou = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }
        val now = System.currentTimeMillis()
        val bounds = forYou?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val followingBounds = bounds?.let { row ->
            FOLLOWING.firstNotNullOfOrNull { findFollowingInRow(root, it, row) }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        }
        if (now - lastDumpAt > DUMP_EVERY_MS) {      // keep a readable copy of what X shows, for the in-app report
            lastDumpAt = now
            lastReport = "For you tab found: ${forYou != null}\n" +
                "Tab widths: For you=${bounds?.width()} Following=${followingBounds?.width()} (the wider one is the active tab)\n\n" +
                "NODES MENTIONING For you / Following:\n" + scanTabs(root) + "\nALL TEXT ON SCREEN:\n" + dump(root)
        }
        if (bounds == null) {      // header scrolled away: no box
            removeOverlay()
            return
        }
        if (bounds.top > tabTopMax) tabTopMax = bounds.top      // the highest position seen is the default one
        if (bounds.top < tabTopMax - MOVED_PX) {      // header is moving (scrolling): drop the box until it is back in place
            removeOverlay()
            return
        }
        showOverlay(bounds)      // black box over the "For you" tab

        // swiped over to the For you page (its tab is the wider one): black out and go back to Following
        if (followingBounds != null && startCover == null &&
            bounds.width() > followingBounds.width() + TAB_WIDTH_MARGIN && now - lastForce > FORCE_COOLDOWN_MS
        ) {
            lastForce = now
            showStartCover(FORCE_COVER_MAX_MS)
            tapFollowing()
            handler.postDelayed(hideStartCover, AFTER_TAP_MS)
        }
    }

    /** Tap "Following": where X says it is, or (if X cannot be read) where it sits on this phone's screen. */
    private fun tapFollowing() {
        val m = resources.displayMetrics
        var x = m.widthPixels * 0.67f
        var y = m.heightPixels * 0.15f
        val root = rootInActiveWindow
        if (root != null && root.packageName == X_PACKAGE) {
            val row = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
            val following = row?.let { r -> FOLLOWING.firstNotNullOfOrNull { findFollowingInRow(root, it, r) } }
            if (following != null) {
                val b = Rect().also { following.getBoundsInScreen(it) }
                x = b.exactCenterX()
                y = b.exactCenterY()
            }
        }
        val path = Path().apply { moveTo(x, y) }
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
        @Volatile var lastEventPackage = ""
        @Volatile var lastReport = ""
        private var lastDumpAt = 0L
        const val PREFS = "prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOLD_SECONDS = "hold_seconds"
        const val DEFAULT_HOLD_SECONDS = 2
        private const val DUMP_EVERY_MS = 1500L
        private const val X_PACKAGE = "com.twitter.android"
        // Tab labels in X's interface languages (the service matches by label, so the phone/app language matters)
        private val FOR_YOU = listOf("For you", "Pro vás", "Pre vás", "Für dich", "Dla Ciebie", "Para ti", "Pour vous", "Per te", "Para você", "Voor jou")
        private val FOLLOWING = listOf("Following", "Sledujete", "Sledované", "Folge ich", "Obserwowani", "Siguiendo", "Abonnements", "Seguiti", "Seguindo", "Volgend")
        private const val DEBOUNCE_MS = 80L
        private const val COVER_TOP = 0.115f       // black starts under the profile / X logo row (share of the screen height)
        private const val COVER_BOTTOM = 0.872f     // and ends above the bottom menu
        private const val MOVED_PX = 6
        private const val TAB_WIDTH_MARGIN = 15
        private const val FORCE_COOLDOWN_MS = 1500L
        private const val FORCE_COVER_MAX_MS = 3000L
        private const val START_COVER_EXTRA_MS = 4000L
        private const val AFTER_TAP_MS = 700L
    }
}
