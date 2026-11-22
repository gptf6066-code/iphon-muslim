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
import android.graphics.Rect
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
                            if (!ScreenFilterService.running) showCovers(Engine.process(sw, 1f))
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
                                showCovers(Engine.process(sw, 1f, b.left, b.top, clean = true))
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
