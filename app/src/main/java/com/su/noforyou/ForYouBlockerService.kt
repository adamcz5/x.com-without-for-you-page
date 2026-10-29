package com.su.noforyou

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * While the X app is in the foreground and its tab row is visible:
 *  - the "For you" tab is covered with a black box,
 *  - the screen is checked (screenshot, only the underline under the active tab is looked at) to see which tab is showing,
 *  - while "For you" is the page showing, the whole feed under the tabs is covered with black and "Following" is tapped.
 * Nothing is stored or sent anywhere.
 */
class ForYouBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastAutoClick = 0L
    private var tappedThisVisit = false
    private var visitStart = 0L

    // which tab is showing, from the screenshot check (null = unknown)
    private var activeIsForYou: Boolean? = null
    private var activeAt = 0L
    private var shotPending = false
    private var lastShotAt = 0L
    private var lastScores = "-"

    private inner class Cover {
        private var view: View? = null
        private var bounds = Rect()

        fun show(b: Rect) {
            if (b.width() <= 0 || b.height() <= 0) {
                hide()
                return
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                b.width(), b.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = b.left
                y = b.top
            }
            val existing = view
            if (existing == null) {
                val v = View(this@ForYouBlockerService).apply {
                    setBackgroundColor(Color.BLACK)
                    setOnTouchListener { _, _ -> true }
                }
                wm.addView(v, params)
                view = v
            } else if (b != bounds) {
                wm.updateViewLayout(existing, params)
            }
            bounds = Rect(b)
        }

        fun hide() {
            val v = view ?: return
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            view = null
        }

        val showing get() = view != null
    }

    private val tabCover = Cover()      // over the "For you" tab
    private val feedCover = Cover()     // over the whole feed while "For you" is the page showing

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private var hidePending = false
    private val hide = Runnable {
        hidePending = false
        removeCovers()
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

    override fun onInterrupt() = removeCovers()

    override fun onDestroy() {
        handler.removeCallbacks(process)
        handler.removeCallbacks(hide)
        removeCovers()
        super.onDestroy()
    }

    private fun removeCovers() {
        tabCover.hide()
        feedCover.hide()
    }

    private fun update() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true)) {      // switched off in the app
            removeCovers()
            return
        }
        val root = rootInActiveWindow
        lastPackage = root?.packageName?.toString() ?: "(no window)"
        if (root == null || root.packageName != X_PACKAGE) {
            removeCovers()
            tappedThisVisit = false      // next time X comes to the front, start again
            visitStart = 0L
            activeIsForYou = null
            return
        }
        val forYou = FOR_YOU.firstNotNullOfOrNull { findTab(root, it) }
        val now = System.currentTimeMillis()
        val bounds = forYou?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        val following = bounds?.let { row -> FOLLOWING.firstNotNullOfOrNull { findFollowingInRow(root, it, row) } }
        val followingBounds = following?.let { n -> Rect().also { n.getBoundsInScreen(it) } }

        if (now - lastDumpAt > DUMP_EVERY_MS) {      // keep a readable copy of what X shows, for the in-app report
            lastDumpAt = now
            lastReport = "For you tab found: ${forYou != null}\nActive tab: ${describeActive(now)}  (scores $lastScores)\n\n" +
                "NODES MENTIONING For you / Following:\n" + scanTabs(root) + "\nALL TEXT ON SCREEN:\n" + dump(root)
        }

        if (bounds == null) {
            if ((tabCover.showing || feedCover.showing) && !hidePending) {      // X redraws its header a lot; only hide if it stays gone
                hidePending = true
                handler.postDelayed(hide, HIDE_DELAY_MS)
            }
            return
        }
        hidePending = false
        handler.removeCallbacks(hide)
        if (visitStart == 0L) visitStart = now

        // the underline under the tab is left uncovered: that is how the screen check tells which tab is showing
        tabCover.show(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom - UNDERLINE_PX))
        requestActiveTab(bounds, followingBounds)

        val active = if (now - activeAt < ACTIVE_STALE_MS) activeIsForYou else null
        val covering = active == true || (active == null && now - visitStart < UNKNOWN_COVER_MS)
        if (covering) {
            val m = resources.displayMetrics
            val home = findNodes(root, "Home").maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.top }
            val bottom = (home?.let { Rect().also { r -> it.getBoundsInScreen(r) }.top } ?: (m.heightPixels * 0.9).toInt()) - 20
            feedCover.show(Rect(0, bounds.bottom, m.widthPixels, bottom))
        } else {
            feedCover.hide()
        }

        // switch to Following: right away while "For you" is showing, and once per visit when we cannot tell
        if (followingBounds != null && now - lastAutoClick > AUTO_CLICK_COOLDOWN_MS &&
            (active == true || (active == null && !tappedThisVisit))
        ) {
            lastAutoClick = now
            tappedThisVisit = true
            tap(followingBounds)
        }

        if (active != false && !pending) {      // keep checking while we are covering or do not know yet
            pending = true
            handler.postDelayed(process, RECHECK_MS)
        }
    }

    private fun describeActive(now: Long): String = when {
        now - activeAt >= ACTIVE_STALE_MS -> "unknown"
        activeIsForYou == true -> "For you"
        activeIsForYou == false -> "Following"
        else -> "unknown"
    }

    private fun tap(b: Rect) {
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    /** Screenshot (throttled) to see which tab has the underline; the answer arrives a moment later in [activeIsForYou]. */
    private fun requestActiveTab(forYou: Rect, following: Rect?) {
        if (Build.VERSION.SDK_INT < 30) return
        val now = System.currentTimeMillis()
        if (shotPending || now - lastShotAt < SHOT_EVERY_MS) return
        shotPending = true
        lastShotAt = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bmp != null) {
                        activeIsForYou = decide(bmp, forYou, following)
                        activeAt = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    lastScores = "error: ${e.message}"
                } finally {
                    shotPending = false
                }
                if (!pending) {
                    pending = true
                    handler.post(process)
                }
            }

            override fun onFailure(errorCode: Int) {
                shotPending = false
                lastScores = "screenshot failed ($errorCode)"
            }
        })
    }

    /** The active tab has a bright/coloured underline at the bottom of its box; the other one looks like the background. */
    private fun decide(bmp: Bitmap, forYou: Rect, following: Rect?): Boolean? {
        fun px(x: Int, y: Int) = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
        fun dist(a: Int, b: Int) = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))
        fun score(cx: Int): Int {
            var best = 0
            for (y in forYou.bottom - 14..forYou.bottom - 2) best = maxOf(best, dist(px(cx, y), px(4, y)))
            return best
        }
        val sForYou = score(forYou.centerX())
        val sFollowing = following?.let { score(it.centerX()) } ?: 0
        lastScores = "For you=$sForYou Following=$sFollowing"
        return when {
            sForYou > LINE_MIN && sForYou > sFollowing -> true
            sFollowing > LINE_MIN -> false
            else -> null
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
            if (node == null || count >= 300) return
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
        private const val RECHECK_MS = 350L
        private const val SHOT_EVERY_MS = 350L
        private const val ACTIVE_STALE_MS = 1500L
        private const val UNKNOWN_COVER_MS = 1500L      // at the start of a visit the feed stays covered until the check says Following
        private const val HIDE_DELAY_MS = 450L
        private const val AUTO_CLICK_COOLDOWN_MS = 1000L
        private const val UNDERLINE_PX = 24
        private const val LINE_MIN = 120
    }
}
