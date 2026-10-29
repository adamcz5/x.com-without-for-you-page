package com.su.noforyou

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
 * Keeps X on "Following". While the X app is in front:
 *  - black screen (option): over the middle of the screen while the "For you" page is showing, and for a set time after X opens
 *  - redirect (option): taps "Following" a set time after X opens, and again a set time after you land on "For you"
 * "For you" is showing when its tab is the wider of the two tabs (the active tab carries the little dropdown arrow).
 * Nothing is stored or sent anywhere.
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

    // ---- settings (changed in the app)
    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private fun black() = prefs().getBoolean(KEY_BLACK, true)
    private fun redirectOn() = prefs().getBoolean(KEY_REDIRECT, true)
    private fun enabled() = black() || redirectOn()
    private fun openMs() = prefs().getInt(KEY_OPEN_SECONDS, DEFAULT_OPEN_SECONDS) * 1000L
    private fun backMs() = prefs().getInt(KEY_BACK_SECONDS, DEFAULT_BACK_SECONDS) * 1000L

    // ---- the black screen over the middle of X (profile / X logo row and bottom menu stay visible)
    private var startCover: View? = null
    private var coverParams: WindowManager.LayoutParams? = null
    private val hideStartCover = Runnable { removeStartCover() }

    private fun showStartCover() {
        if (startCover != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val h = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels
        val top = (h * COVER_TOP).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (h * COVER_BOTTOM).toInt() - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = top
        }
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        wm.addView(v, params)
        startCover = v
        coverParams = params
        handler.postDelayed(hideStartCover, COVER_MAX_MS)      // never stay black for long, whatever happens
    }

    private fun removeStartCover() {
        handler.removeCallbacks(hideStartCover)
        val v = startCover ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        startCover = null
    }

    private fun coverNow(want: Boolean) {
        if (want) showStartCover() else removeStartCover()
    }

    // ---- when to do what
    private var inX = false
    private var tabTopMax = 0
    private var openUntil = 0L              // X was just opened: until then the page counts as "For you"
    private var openRedirectPending = false
    private var redirectAt = 0L             // when to tap Following (0 = nothing planned)
    private var holdOffUntil = 0L           // just after our tap: leave the cover as it is while the page switches

    private val process = Runnable {
        pending = false
        update()
    }
    private val openEnd = Runnable { update() }

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

    /** X came to the front: black screen (if on) for the set time, then the redirect (if on). */
    private fun xOpened() {
        if (!enabled()) return
        inX = true
        openUntil = System.currentTimeMillis() + openMs()
        openRedirectPending = true
        redirectAt = 0L
        holdOffUntil = 0L
        if (black()) showStartCover()
        handler.postDelayed(openEnd, openMs() + 50)      // look again when the opening time is over
    }

    private fun xClosed() {
        inX = false
        tabTopMax = 0
        openUntil = 0L
        openRedirectPending = false
        redirectAt = 0L
        handler.removeCallbacks(openEnd)
        removeStartCover()
        removeOverlay()
    }

    override fun onInterrupt() = removeOverlay()

    override fun onDestroy() {
        handler.removeCallbacks(process)
        handler.removeCallbacks(openEnd)
        removeOverlay()
        removeStartCover()
        super.onDestroy()
    }

    private fun update() {
        if (!enabled()) {      // both options off in the app
            removeOverlay()
            removeStartCover()
            return
        }
        val now = System.currentTimeMillis()
        val opening = now < openUntil
        val root = rootInActiveWindow
        lastPackage = root?.packageName?.toString() ?: "(no window)"
        if (root == null || root.packageName != X_PACKAGE) {
            removeOverlay()
            tabTopMax = 0
            if (inX && now >= holdOffUntil) coverNow(black() && opening)      // cannot read X: only the opening time applies
            if (opening && !pending) { pending = true; handler.postDelayed(process, RECHECK_MS) }
            return
        }
        val forYou = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }
        val bounds = forYou?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val followingBounds = bounds?.let { row ->
            FOLLOWING.firstNotNullOfOrNull { findFollowingInRow(root, it, row) }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        }
        // "For you" is the page showing when its tab is the wider one; null = cannot tell (header not on screen)
        val forYouShowing: Boolean? =
            if (bounds != null && followingBounds != null) bounds.width() > followingBounds.width() + TAB_WIDTH_MARGIN else null
        if (now - lastDumpAt > DUMP_EVERY_MS) {      // keep a readable copy of what X shows, for the in-app report
            lastDumpAt = now
            lastReport = "For you tab found: ${forYou != null}\n" +
                "Tab widths: For you=${bounds?.width()} Following=${followingBounds?.width()} (the wider one is the active tab)\n" +
                "For you page showing: ${forYouShowing ?: "unknown"}\n\n" +
                "NODES MENTIONING For you / Following:\n" + scanTabs(root) + "\nALL TEXT ON SCREEN:\n" + dump(root)
        }

        // black screen: while opening, and while the For you page is showing
        if (now >= holdOffUntil) coverNow(black() && (opening || forYouShowing == true))

        // redirect
        if (redirectOn()) {
            if (openRedirectPending && !opening) {      // the opening time is over
                openRedirectPending = false
                if (forYouShowing != false) redirectNow(now)
            } else if (!opening) {
                if (forYouShowing == true && redirectAt == 0L && now >= holdOffUntil) redirectAt = now + backMs()      // landed on For you
                if (forYouShowing == false) redirectAt = 0L
                if (redirectAt != 0L && now >= redirectAt) redirectNow(now)
            }
        } else {
            openRedirectPending = false
        }

        // small black box over the "For you" tab, only while the header is in its default position
        if (bounds == null) {
            removeOverlay()
        } else {
            if (bounds.top > tabTopMax) tabTopMax = bounds.top
            if (bounds.top < tabTopMax - MOVED_PX) removeOverlay() else showOverlay(bounds)
        }

        if ((opening || redirectAt != 0L || openRedirectPending) && !pending) {      // come back while a timer runs
            pending = true
            handler.postDelayed(process, RECHECK_MS)
        }
    }

    private fun redirectNow(now: Long) {
        redirectAt = 0L
        holdOffUntil = now + HOLD_OFF_MS
        tapFollowing()
        handler.postDelayed(hideStartCover, AFTER_TAP_MS)      // show the screen again once Following is up
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
        val base = "tap at ${x.toInt()},${y.toInt()}"
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { lastTap = "$base: done" }
                override fun onCancelled(gestureDescription: GestureDescription?) { lastTap = "$base: cancelled by Android" }
            }, null
        )
        lastTap = "$base: sent=$sent"
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
        @Volatile var lastTap = "-"
        @Volatile var lastReport = ""
        private var lastDumpAt = 0L
        const val PREFS = "prefs"
        const val KEY_BLACK = "black"
        const val KEY_REDIRECT = "redirect"
        const val KEY_OPEN_SECONDS = "open_seconds"
        const val KEY_BACK_SECONDS = "back_seconds"
        const val DEFAULT_OPEN_SECONDS = 2
        const val DEFAULT_BACK_SECONDS = 1
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
        private const val COVER_MAX_MS = 60000L
        private const val HOLD_OFF_MS = 900L
        private const val RECHECK_MS = 250L
        private const val AFTER_TAP_MS = 700L
    }
}
