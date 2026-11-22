package tj.bikhon.iphonmuslim

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/**
 * Хизмати асосӣ. Танҳо ҳамин як иҷозат лозим аст:
 *  • пардаҳоро пурра торик мекашад (Android ба пардаҳои оддӣ иҷозат намедиҳад);
 *  • дар Android 11+ худаш экранро мебинад — бе саволи «сабти экран» ва
 *    баъд аз хомӯш/фурӯзон кардани телефон худкор боз кор мекунад.
 */
class FilterAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "IphonMuslim"
        private const val MIN_INTERVAL = 340L   // маҳдудияти Android барои скриншот
        @Volatile var instance: FilterAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.contains(ctx.packageName + "/", ignoreCase = true) &&
                list.contains("FilterAccessibilityService", ignoreCase = true)
        }

        fun isPaused(ctx: Context) =
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.PAUSED, false)
    }

    var overlay: OverlayView? = null
        private set
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var looping = false
    @Volatile private var inFlight = false
    private var lastShot = 0L
    private val shootR = Runnable { if (Build.VERSION.SDK_INT >= 30) shoot() }

    private fun schedule(delay: Long) {
        main.removeCallbacks(shootR)
        main.postDelayed(shootR, delay.coerceAtLeast(0))
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Engine.init(this)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = OverlayView(this)
        try { wm.addView(v, lp); overlay = v } catch (e: Exception) { Log.e(TAG, "overlay", e) }
        Log.i(TAG, "accessibility connected")
        // ҳолати тез асосӣ аст: баъд аз фурӯзон шудани телефон худкор тасдиқро мепурсем
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.FAST, true) && !ScreenFilterService.running && !isPaused(this)) {
            main.postDelayed({ try { CaptureActivity.start(this) } catch (e: Exception) { Log.e(TAG, "capture", e) } }, 1500)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            looping = true
            schedule(0)
        }
    }

    /** Ҳангоми скролл ё ивазшавии экран — фавран кадри нав */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val et = event?.eventType ?: 0
        if (et == AccessibilityEvent.TYPE_VIEW_SCROLLED || et == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            requestFollow()
        }
        if (et == AccessibilityEvent.TYPE_VIEW_SCROLLED && event?.packageName?.toString() != packageName) {
            onScrolling(event!!)
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() != packageName) {
            Engine.onScreenChanged()
            if (Build.VERSION.SDK_INT < 30 && !ScreenFilterService.running) showCovers(emptyList())
        }
        if (Build.VERSION.SDK_INT >= 30 && looping && !inFlight) {
            schedule(MIN_INTERVAL - (SystemClock.uptimeMillis() - lastShot))
        }
    }

    fun showCovers(list: List<Cover>) {
        main.post { overlay?.setCovers(list) }
    }

    @RequiresApi(30)
    private fun shoot() {
        if (!looping || inFlight) return
        if (ScreenFilterService.running || isPaused(this)) {
            if (!ScreenFilterService.running) showCovers(emptyList())
            schedule(1000)
            return
        }
        val since = SystemClock.uptimeMillis() - lastShot
        if (since < MIN_INTERVAL) { schedule(MIN_INTERVAL - since); return }
        inFlight = true
        lastShot = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= 34 && shootWindow()) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        hb.close()
                        if (hw != null) {
                            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (!ScreenFilterService.running) { Engine.process(sw, 1f); afterProcess(lastShot) }
                        }
                    } catch (e: Throwable) { Log.e(TAG, "shot", e) }
                    inFlight = false
                    schedule(0)
                }

                override fun onFailure(errorCode: Int) {
                    inFlight = false
                    schedule(MIN_INTERVAL)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "takeScreenshot", e)
            inFlight = false
            schedule(1000)
        }
    }

    /** Равзанаҳое, ки акси алоҳидаашон намешавад (масалан видео/SurfaceView) */
    private val badWindows = HashSet<Int>()

    /**
     * Android 14+: акси танҳо равзанаи апп (бе пардаҳои мо) — ҳар кадр аз нав
     * ва дақиқ таҳлил мешавад, видео «зинда» хира менамояд.
     */
    @RequiresApi(34)
    private fun shootWindow(): Boolean {
        val target = try {
            val ws = windows
            ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (e: Throwable) { null } ?: return false
        if (target.id in badWindows) return false
        val b = Rect()
        target.getBoundsInScreen(b)
        return try {
            takeScreenshotOfWindow(target.id, exec, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        hb.close()
                        if (hw != null) {
                            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (!ScreenFilterService.running) {
                                Engine.process(sw, 1f, b.left, b.top, clean = true)
                                afterProcess(lastShot)
                            }
                        }
                    } catch (e: Throwable) { Log.e(TAG, "winshot", e) }
                    inFlight = false
                    schedule(0)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "window screenshot failed $errorCode")
                    if (badWindows.size > 50) badWindows.clear()
                    badWindows.add(target.id)
                    inFlight = false
                    schedule(MIN_INTERVAL)
                }
            })
            true
        } catch (e: Throwable) {
            Log.e(TAG, "takeScreenshotOfWindow", e)
            badWindows.add(target.id)
            false
        }
    }

    // ---------- ҳангоми скролл: лента хира ----------
    private var guardUntil = 0L
    private var guardRect: Rect? = null
    private val guardOff = Runnable { checkGuard() }

    private fun onScrolling(e: AccessibilityEvent) {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Prefs.SCROLL_GUARD, true) || isPaused(this)) return
        if (!ScreenFilterService.running && Build.VERSION.SDK_INT < 30) return
        val r = Rect()
        val src = try { e.source } catch (t: Throwable) { null }
        if (src != null) src.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        if (r.isEmpty || r.width() < dm.widthPixels / 3) r.set(0, 0, dm.widthPixels, dm.heightPixels)
        guardUntil = SystemClock.uptimeMillis() + 350
        if (guardRect != r) {
            guardRect = Rect(r)
            overlay?.setGuard(guardRect, Engine.frameBlur, Engine.frameRect)
        }
        main.removeCallbacks(guardOff)
        main.postDelayed(guardOff, 360)
    }

    private fun checkGuard() {
        val left = guardUntil - SystemClock.uptimeMillis()
        if (left > 0) { main.postDelayed(guardOff, left + 10); return }
        // скролл қатъ шуд: кадри навро зуд мегирем; тирагӣ баъд аз таҳлили ҳамон кадр гум мешавад
        if (Build.VERSION.SDK_INT >= 30 && !ScreenFilterService.running) schedule(0)
        main.postDelayed({ if (SystemClock.uptimeMillis() >= guardUntil + 2400) hideGuard() }, 2500)
    }

    private fun hideGuard() {
        if (guardRect == null) return
        guardRect = null
        overlay?.setGuard(null, null, null)
    }

    // ---------- пардаҳо бо суратҳо якҷоя ҳаракат мекунанд ----------
    private val followExec = Executors.newSingleThreadExecutor()
    @Volatile private var followPending = false

    /** Баъд аз таҳлили кадр: гиреҳҳоро пайваст, ҷойҳоро нав ва пардаҳоро нишон медиҳем */
    /** @param frameTime вақти гирифтани кадр (uptime) */
    fun afterProcess(frameTime: Long) {
        followExec.execute {
            try {
                Engine.bindAnchors { findAnchor(it) }
                Engine.follow { refreshNode(it) }
            } catch (e: Throwable) { Log.e(TAG, "anchors", e) }
            val covers = Engine.covers()
            main.post {
                overlay?.setCovers(covers)
                // кадри баъд аз қатъи скролл таҳлил шуд → лентаро мекушоем (пардаҳои зан аллакай ҳастанд)
                if (guardRect != null && frameTime >= guardUntil + 20) hideGuard()
                else if (guardRect != null) overlay?.setGuard(guardRect, Engine.frameBlur, Engine.frameRect)
            }
        }
    }

    private fun requestFollow() {
        if (followPending) return
        followPending = true
        followExec.execute {
            followPending = false
            try { if (Engine.follow { refreshNode(it) }) showCovers(Engine.covers()) } catch (e: Throwable) { Log.e(TAG, "follow", e) }
        }
    }

    private fun screenArea(): Float {
        val dm = resources.displayMetrics
        return dm.widthPixels.toFloat() * dm.heightPixels
    }

    /** Хурдтарин гиреҳе, ки маркази пардаро дар бар мегирад ва аз парда хеле хурд нест */
    private fun findAnchor(r: RectF): Engine.Anchor? {
        val cx = r.centerX().toInt(); val cy = r.centerY().toInt()
        val area = r.width() * r.height()
        val ws = try { windows } catch (e: Throwable) { emptyList<AccessibilityWindowInfo>() }
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root ?: continue
            val n = deepest(root, cx, cy, area, 0) ?: continue
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.width().toFloat() * b.height() > screenArea() * 0.85f) return null   // тамоми экран — ҳаракат намекунад
            return Engine.Anchor(n, b, sig(n))
        }
        return null
    }

    private fun deepest(n: AccessibilityNodeInfo, cx: Int, cy: Int, area: Float, depth: Int): AccessibilityNodeInfo? {
        if (depth > 45) return null
        val b = Rect(); n.getBoundsInScreen(b)
        if (!b.contains(cx, cy)) return null
        if (b.width().toFloat() * b.height() < area * 0.35f) return null
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (e: Throwable) { null } ?: continue
            val d = deepest(c, cx, cy, area, depth + 1)
            if (d != null) return d
        }
        return n
    }

    private fun sig(n: AccessibilityNodeInfo) =
        "${n.className}|${n.contentDescription}|${n.text}|${n.viewIdResourceName}"

    private fun refreshNode(o: Any): Pair<Rect, String>? {
        val n = o as AccessibilityNodeInfo
        if (!n.refresh() || !n.isVisibleToUser) return null
        val b = Rect(); n.getBoundsInScreen(b)
        if (b.isEmpty) return null
        return b to sig(n)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        looping = false
        instance = null
        main.removeCallbacksAndMessages(null)
        overlay?.let {
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        exec.shutdown()
        super.onDestroy()
    }
}
