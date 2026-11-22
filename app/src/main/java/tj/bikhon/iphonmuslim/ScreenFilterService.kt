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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.max

/**
 * «Ҳолати тез»: экранро тавассути сабти экран мегирад — ҳар кадри нав
 * фавран таҳлил мешавад (≈10 маротиба дар сония). Пардаҳоро хизмати
 * Специальные возможности мекашад.
 */
class ScreenFilterService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "filter"
        private const val NOTIF_ID = 7
        private const val SCALE = 0.6f
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    private var workThread: HandlerThread? = null
    private var work: Handler? = null
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything(); stopSelf(); return START_NOT_STICKY
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
                val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
                if (p == null) { stopSelf(); return START_NOT_STICKY }
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { stopEverything(); stopSelf() } }
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
            this, 1, Intent(this, ScreenFilterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val lc = L10n.ctx(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(lc.getString(R.string.notif_title))
            .setContentText(lc.getString(R.string.notif_text))
            .setContentIntent(openPi)
            .addAction(0, lc.getString(R.string.btn_stop), stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIF_ID, n)
    }

    private fun begin() {
        running = true
        Engine.init(this)
        Engine.reset()
        workThread = HandlerThread("capture").also { it.start() }
        work = Handler(workThread!!.looper)
        work?.post { setupCapture() }
        Log.i("IphonMuslim", "fast mode started")
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
        val capW = max(1, (screenW * SCALE).toInt())
        val capH = max(1, (screenH * SCALE).toInt())
        val dpi = resources.displayMetrics.densityDpi
        val newReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        // ҳар кадри нав (экран иваз шуд) — фавран таҳлил
        newReader.setOnImageAvailableListener({ r -> onFrame(r) }, work)
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
        Engine.reset()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) work?.post { setupCapture() }
    }

    private fun onFrame(r: ImageReader) {
        if (!running) return
        val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
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
        }) ?: return
        val covers = Engine.process(bmp, screenW.toFloat() / bmp.width)
        FilterAccessibilityService.instance?.showCovers(covers)
    }

    private fun stopEverything() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        workThread?.quitSafely()
        workThread = null
        work = null
        Engine.reset()
        FilterAccessibilityService.instance?.showCovers(emptyList())
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        if (running) stopEverything()
        super.onDestroy()
    }
}
