package tj.bikhon.iphonmuslim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Хизмати асосӣ: экранро ҳар ~180 мс мегирад, рӯйҳоро бо ML Kit меёбад
 * ва болои онҳо (ва бадан) пардаи сахт мекашад.
 */
class ScreenFilterService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "filter"
        private const val NOTIF_ID = 7
        private const val SCALE = 0.5f          // экран бо нисфи андоза таҳлил мешавад
        private const val INTERVAL_MS = 180L
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ownOverlay: OverlayView? = null
    private val overlay: OverlayView?
        get() = FilterAccessibilityService.instance?.overlay ?: ownOverlay
    private var detector: FaceDetector? = null

    private val main = Handler(Looper.getMainLooper())
    private var workThread: HandlerThread? = null
    private var work: Handler? = null
    @Volatile private var busy = false

    private var capW = 0
    private var capH = 0
    private var screenW = 0
    private var screenH = 0

    /** Пардаҳои фаъол (дар координатаҳои тасвири гирифташуда) */
    private val boxes = mutableListOf<Box>()

    private class Box(var rect: RectF, var ring: IntArray, var blurred: Bitmap?)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                startAsForeground()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                if (p == null) { stopSelf(); return START_NOT_STICKY }
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { stopEverything(); stopSelf() }
                    }
                }, main)
                begin()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "iPhon Muslim", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenFilterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("iPhon Muslim фаъол аст")
            .setContentText("Экран муҳофизат мешавад")
            .setContentIntent(openPi)
            .addAction(0, "Хомӯш кардан", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIF_ID, n)
    }

    private fun begin() {
        running = true
        Log.i("IphonMuslim", "service started")
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(if (prefs.getBoolean(Prefs.SMALL_FACES, true)) 0.04f else 0.1f)
            .build()
        detector = FaceDetection.getClient(opts)

        workThread = HandlerThread("capture").also { it.start() }
        work = Handler(workThread!!.looper)

        if (FilterAccessibilityService.instance?.overlay == null) addOverlay()
        setupCapture()
        main.postDelayed(tick, INTERVAL_MS)
    }

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }
    }

    private fun setupCapture() {
        readScreenSize()
        capW = max(1, (screenW * SCALE).toInt())
        capH = max(1, (screenH * SCALE).toInt())
        val dpi = resources.displayMetrics.densityDpi
        val newReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "iphon-muslim", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, work
            )
        } else {
            vd.resize(capW, capH, dpi)
            vd.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
        synchronized(boxes) { boxes.clear() }
        main.post { overlay?.setCovers(emptyList(), 1f / SCALE) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) work?.post { setupCapture() }   // гардиши экран
    }

    private fun addOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
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
        try { wm.addView(v, lp); ownOverlay = v } catch (_: Exception) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!busy) work?.post { grabAndDetect() }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun grabAndDetect() {
        val r = reader ?: return
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        busy = true
        val bmp: Bitmap = (try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            if (full.width != image.width)
                Bitmap.createBitmap(full, 0, 0, image.width, image.height) else full
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }) ?: run { busy = false; return }

        val det = detector ?: run { busy = false; return }
        det.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { faces ->
                val coverBody = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.COVER_BODY, true)
                val found = faces.map { toCoverRect(it.boundingBox, bmp, coverBody) }
                Log.i("IphonMuslim", "frame ${bmp.width}x${bmp.height} faces=${faces.size} boxes=${boxes.size}")
                work?.post {
                    updateBoxes(bmp, found)
                    busy = false
                }
            }
            .addOnFailureListener { e -> Log.e("IphonMuslim", "detect failed", e); busy = false }
    }

    /** Аз чорчӯбаи рӯй минтақаи пӯшониданро месозад (сар + мӯй, ихтиёрӣ бадан) */
    private fun toCoverRect(face: Rect, bmp: Bitmap, coverBody: Boolean): RectF {
        val w = face.width().toFloat()
        val h = face.height().toFloat()
        val left: Float; val right: Float; val top: Float; val bottom: Float
        if (coverBody) {
            left = face.left - w * 1.3f
            right = face.right + w * 1.3f
            top = face.top - h * 1.1f
            bottom = face.bottom + h * 6.5f
        } else {
            left = face.left - w * 0.35f
            right = face.right + w * 0.35f
            top = face.top - h * 1.1f
            bottom = face.bottom + h * 0.4f
        }
        return RectF(
            max(0f, left), max(0f, top),
            min(bmp.width.toFloat(), right), min(bmp.height.toFloat(), bottom)
        )
    }

    /**
     * Нигоҳ доштани пардаҳо: пардае, ки рӯйро пӯшонд, дигар рӯйро «намебинад».
     * Бинобар ин атрофи пардаро месанҷем — агар атроф иваз шуд (скролл, видео),
     * парда хориҷ мешавад ва рӯй дар ҷои нав дубора ёфта мешавад.
     */
    private fun updateBoxes(bmp: Bitmap, found: List<RectF>) {
        val list: List<Cover>
        synchronized(boxes) {
            val iter = boxes.iterator()
            while (iter.hasNext()) {
                val b = iter.next()
                if (ringChanged(b.ring, ringSignature(bmp, b.rect))) iter.remove()
            }
            for (f in found) {
                val existing = boxes.firstOrNull { overlap(it.rect, f) > 0.25f }
                if (existing != null) {
                    val before = RectF(existing.rect)
                    existing.rect.union(f)
                    existing.ring = ringSignature(bmp, existing.rect)
                    if (before != existing.rect) existing.blurred = blurRegion(bmp, existing.rect)
                } else {
                    boxes.add(Box(RectF(f), ringSignature(bmp, f), blurRegion(bmp, f)))
                }
            }
            list = boxes.map { Cover(RectF(it.rect), it.blurred) }
        }
        main.post { overlay?.setCovers(list, 1f / SCALE) }
    }

    /** Ҷойро аз тасвир бурида, хеле хурд ва баъд калон мекунад — натиҷа сахт хира мешавад */
    private fun blurRegion(bmp: Bitmap, r: RectF): Bitmap? {
        return try {
            val l = r.left.toInt().coerceIn(0, bmp.width - 1)
            val t = r.top.toInt().coerceIn(0, bmp.height - 1)
            val w = r.width().toInt().coerceIn(1, bmp.width - l)
            val h = r.height().toInt().coerceIn(1, bmp.height - t)
            val crop = Bitmap.createBitmap(bmp, l, t, w, h)
            val tw = max(2, w / 45); val th = max(2, h / 45)
            val tiny = Bitmap.createScaledBitmap(crop, tw, th, true)
            val mid = Bitmap.createScaledBitmap(tiny, max(4, w / 4), max(4, h / 4), true)
            val tiny2 = Bitmap.createScaledBitmap(mid, tw, th, true)
            Bitmap.createScaledBitmap(tiny2, max(4, w / 3), max(4, h / 3), true)
        } catch (e: Exception) { null }
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else inter / smaller
    }

    private val RING_N = 14

    /** Равшании нуқтаҳои атрофи парда (берун аз он) */
    private fun ringSignature(bmp: Bitmap, r: RectF): IntArray {
        val m = 6f
        val out = IntArray(RING_N * 4)
        var k = 0
        fun sample(x: Float, y: Float) {
            val xi = x.toInt(); val yi = y.toInt()
            out[k++] = if (xi < 0 || yi < 0 || xi >= bmp.width || yi >= bmp.height) -1 else {
                val c = bmp.getPixel(xi, yi)
                ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
            }
        }
        for (i in 0 until RING_N) {
            val t = (i + 0.5f) / RING_N
            sample(r.left + t * r.width(), r.top - m)
            sample(r.left + t * r.width(), r.bottom + m)
            sample(r.left - m, r.top + t * r.height())
            sample(r.right + m, r.top + t * r.height())
        }
        return out
    }

    private fun ringChanged(old: IntArray, now: IntArray): Boolean {
        var valid = 0; var changed = 0
        for (i in old.indices) {
            if (old[i] < 0 || now[i] < 0) continue
            valid++
            if (abs(old[i] - now[i]) > 28) changed++
        }
        if (valid < 6) return true
        return changed.toFloat() / valid > 0.25f
    }

    private fun stopEverything() {
        running = false
        main.removeCallbacks(tick)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        FilterAccessibilityService.instance?.overlay?.setCovers(emptyList(), 1f)
        ownOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        ownOverlay = null
        detector?.close()
        detector = null
        workThread?.quitSafely()
        workThread = null
        work = null
        synchronized(boxes) { boxes.clear() }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        if (running) stopEverything()
        super.onDestroy()
    }
}
