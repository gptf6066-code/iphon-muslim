package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Мағзи апп: аз як кадри экран рӯйҳоро меёбад, занҳоро ҷудо мекунад
 * ва барои ҳар зан пардаи хира бо шакли худи бадан месозад.
 */
object Engine {
    private const val TAG = "IphonMuslim"
    private const val FEMALE_THRESHOLD = 0.40f   // каме сахтгир: дар шубҳа мепӯшонад
    private const val MALE_RECHECK_MS = 1200L

    private var faces: FaceDetector? = null
    private var segmenter: Segmenter? = null
    private var gender: GenderModel? = null
    @Volatile var coverBody = true

    /** cover == null — мард (намепӯшонем, фақат дар хотир нигоҳ медорем) */
    private class Box(
        val face: RectF, val region: RectF, var ring: IntArray,
        val cover: Cover?, val created: Long
    )

    private val boxes = mutableListOf<Box>()
    private var lastScale = 0f
    private var lastW = 0
    private var lastH = 0

    @Synchronized
    fun init(ctx: Context) {
        coverBody = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.COVER_BODY, true)
        if (faces != null) return
        faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f)
                .build()
        )
        segmenter = try {
            Segmentation.getClient(
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
            )
        } catch (e: Throwable) { Log.e(TAG, "segmenter", e); null }
        gender = try { GenderModel(ctx.applicationContext) } catch (e: Throwable) { Log.e(TAG, "gender", e); null }
    }

    @Synchronized
    fun reset() { boxes.clear() }

    /**
     * @param bmp кадри экран (хурдшуда)
     * @param toScreen зарб барои гузаштан аз координатаҳои кадр ба экран
     * @return пардаҳо барои кашидан (бо координатаҳои экран)
     */
    @Synchronized
    fun process(bmp: Bitmap, toScreen: Float): List<Cover> {
        if (toScreen != lastScale || bmp.width != lastW || bmp.height != lastH) {
            boxes.clear(); lastScale = toScreen; lastW = bmp.width; lastH = bmp.height
        }
        val det = faces ?: return emptyList()
        val t0 = SystemClock.uptimeMillis()
        val found = try {
            Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), 2, TimeUnit.SECONDS)
        } catch (e: Exception) { Log.e(TAG, "detect", e); return current() }

        val now = SystemClock.uptimeMillis()
        // Агар атрофи парда иваз шуда бошад (скролл, видео) — пардаи кӯҳна хориҷ
        boxes.removeAll {
            val ch = ringChanged(it.ring, ringSignature(bmp, it.region))
            ch == true || (ch == null && now - it.created > 2000) ||
                (it.cover == null && now - it.created > MALE_RECHECK_MS)
        }

        var women = 0; var men = 0
        for (f in found) {
            val fb = RectF(f.boundingBox)
            if (boxes.any { overlap(it.face, fb) > 0.3f }) continue
            val p = try { gender?.femaleProb(bmp, f.boundingBox) ?: 1f } catch (e: Exception) { 1f }
            val region = coverRegion(f.boundingBox, bmp)
            if (p < FEMALE_THRESHOLD) {
                men++
                boxes.add(Box(fb, region, ringSignature(bmp, region), null, now))
                continue
            }
            women++
            val cover = makeCover(bmp, region, f.boundingBox, toScreen) ?: continue
            boxes.add(Box(fb, region, ringSignature(bmp, region), cover, now))
        }
        Log.i(TAG, "frame ${bmp.width}x${bmp.height} faces=${found.size} women=$women men=$men " +
            "covers=${boxes.count { it.cover != null }} ${SystemClock.uptimeMillis() - t0}ms")
        return current()
    }

    private fun current(): List<Cover> = boxes.mapNotNull { it.cover }

    /** Минтақаи сар + бадан (ё танҳо сар) дар координатаҳои кадр */
    private fun coverRegion(face: Rect, bmp: Bitmap): RectF {
        val w = face.width().toFloat(); val h = face.height().toFloat()
        val r = if (coverBody) RectF(
            face.left - w * 1.05f, face.top - h * 0.9f,
            face.right + w * 1.05f, face.bottom + h * 6.0f
        ) else RectF(
            face.left - w * 0.4f, face.top - h * 0.9f,
            face.right + w * 0.4f, face.bottom + h * 0.5f
        )
        r.intersect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        return r
    }

    /** Пардаи хира бо шакли бадан: танҳо ҷои зан тира мешавад */
    private fun makeCover(bmp: Bitmap, region: RectF, face: Rect, toScreen: Float): Cover? {
        val l = region.left.toInt().coerceIn(0, bmp.width - 1)
        val t = region.top.toInt().coerceIn(0, bmp.height - 1)
        val w = region.width().toInt().coerceIn(1, bmp.width - l)
        val h = region.height().toInt().coerceIn(1, bmp.height - t)
        if (w < 6 || h < 6) return null
        return try {
            val crop = Bitmap.createBitmap(bmp, l, t, w, h)
            val ow = max(6, w / 3); val oh = max(6, h / 3)
            // хираи сахт: хеле хурд → калон (ду бор)
            val tw = max(2, w / 40); val th = max(2, h / 40)
            val tiny = Bitmap.createScaledBitmap(crop, tw, th, true)
            val mid = Bitmap.createScaledBitmap(tiny, max(4, w / 6), max(4, h / 6), true)
            val tiny2 = Bitmap.createScaledBitmap(mid, tw, th, true)
            val blurred = Bitmap.createScaledBitmap(tiny2, ow, oh, true)

            val alpha = FloatArray(ow * oh)
            // 1) шакли бадан аз сегментатсия
            val seg = segmenter
            if (coverBody && seg != null) {
                val k = min(1f, 256f / max(w, h))
                val small = if (k < 1f) Bitmap.createScaledBitmap(crop, max(8, (w * k).toInt()), max(8, (h * k).toInt()), true) else crop
                val m = Tasks.await(seg.process(InputImage.fromBitmap(small, 0)), 2, TimeUnit.SECONDS)
                val mw = m.width; val mh = m.height
                val buf = m.buffer; buf.rewind()
                val mask = FloatArray(mw * mh) { buf.float }
                for (y in 0 until oh) {
                    val my = min(mh - 1, y * mh / oh)
                    for (x in 0 until ow) {
                        val mx = min(mw - 1, x * mw / ow)
                        if (mask[my * mw + mx] > 0.35f) alpha[y * ow + x] = 1f
                    }
                }
            } else if (coverBody) {
                java.util.Arrays.fill(alpha, 1f)   // агар сегментатсия набошад — тамоми минтақа
            }
            // 2) сар ва мӯй ҳамеша (байзӣ атрофи рӯй)
            val sx = ow.toFloat() / w; val sy = oh.toFloat() / h
            val cx = (face.exactCenterX() - l) * sx
            val cy = (face.exactCenterY() - t - face.height() * 0.15f) * sy
            val rx = face.width() * 0.85f * sx; val ry = face.height() * 1.05f * sy
            for (y in 0 until oh) for (x in 0 until ow) {
                val dx = (x - cx) / rx; val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f) alpha[y * ow + x] = 1f
            }
            // 3) каме васеъ ва нарм кардани канорҳо
            var a = dilate(alpha, ow, oh); a = dilate(a, ow, oh); a = boxBlur(a, ow, oh)

            val px = IntArray(ow * oh)
            blurred.getPixels(px, 0, ow, 0, 0, ow, oh)
            for (i in px.indices) {
                val c = px[i]
                val al = (min(1f, a[i] * 1.6f) * 255).toInt()
                val r = ((c shr 16) and 0xFF) * 45 / 100
                val g = ((c shr 8) and 0xFF) * 45 / 100
                val b = (c and 0xFF) * 45 / 100
                px[i] = (al shl 24) or (r shl 16) or (g shl 8) or b
            }
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, ow, 0, 0, ow, oh)
            Cover(RectF(l * toScreen, t * toScreen, (l + w) * toScreen, (t + h) * toScreen), out)
        } catch (e: Exception) {
            Log.e(TAG, "cover", e); null
        }
    }

    private fun dilate(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) m = max(m, a[yy * w + xx])
            }
            o[y * w + x] = m
        }
        return o
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int): FloatArray {
        val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += a[yy * w + xx]; n++ }
            }
            o[y * w + x] = s / n
        }
        return o
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else ix * iy / smaller
    }

    private const val RING_N = 14

    /** Равшании нуқтаҳои атрофи минтақа (берун аз он) */
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
            val f = (i + 0.5f) / RING_N
            sample(r.left + f * r.width(), r.top - m)
            sample(r.left + f * r.width(), r.bottom + m)
            sample(r.left - m, r.top + f * r.height())
            sample(r.right + m, r.top + f * r.height())
        }
        return out
    }

    /** true — иваз шуд, false — не, null — маълум нест (минтақа то канори экран) */
    private fun ringChanged(old: IntArray, now: IntArray): Boolean? {
        var valid = 0; var changed = 0
        for (i in old.indices) {
            if (old[i] < 0 || now[i] < 0) continue
            valid++
            if (abs(old[i] - now[i]) > 28) changed++
        }
        if (valid < 6) return null
        return changed.toFloat() / valid > 0.25f
    }
}
