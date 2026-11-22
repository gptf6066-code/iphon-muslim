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
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Мағзи апп:
 *  1) рӯйҳоро меёбад (ML Kit);
 *  2) зан ё мард будани ҳар рӯйро муайян мекунад (InsightFace, бо 6 буриш + натиҷаи чанд кадр);
 *  3) тамоми сурат/видеоеро, ки дар он зан ҳаст, меёбад (то канори якранги экран);
 *  4) онро мисли шишаи обдор хира мекунад.
 */
object Engine {
    private const val TAG = "IphonMuslim"
    private const val FEMALE_THRESHOLD = 0.5f
    private const val DETECT_WIDTH = 480
    private const val HOLD_MS = 900L          // парда пас аз нопадид шудани рӯй боз чанд вақт мемонад
    private const val MALE_RECHECK_MS = 1500L

    private var faces: FaceDetector? = null
    private var gender: GenderModel? = null

    /** Хотираи рӯйҳо: эҳтимоли зан будан аз чанд кадр ҷамъ мешавад */
    private class FaceMemo(var rect: RectF, var prob: Float, var n: Int, var seen: Long, val born: Long)
    private val memos = mutableListOf<FaceMemo>()

    /** Пардаи фаъол (координатаҳои кадри хурд) */
    private class Track(var region: RectF, var cover: Cover, var seen: Long, var ring: IntArray?)
    private val tracks = mutableListOf<Track>()

    private var lastKey = ""

    @Synchronized
    fun init(ctx: Context) {
        if (faces != null) return
        faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.03f)
                .build()
        )
        gender = try { GenderModel(ctx.applicationContext) } catch (e: Throwable) { Log.e(TAG, "gender", e); null }
        Thread {
            try {
                val dummy = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                faces?.let { Tasks.await(it.process(InputImage.fromBitmap(dummy, 0)), 15, TimeUnit.SECONDS) }
                gender?.femaleProb(dummy, Rect(10, 10, 80, 80))
                Log.i(TAG, "models ready")
            } catch (e: Throwable) { Log.e(TAG, "warmup", e) }
        }.start()
    }

    @Synchronized
    fun reset() { tracks.clear(); memos.clear() }

    /** Экран (апп ё саҳифа) иваз шуд — пардаҳои кӯҳнаро, ки бо атрофашон санҷида намешаванд, хориҷ мекунем */
    @Synchronized
    fun onScreenChanged() { if (!lastKey.endsWith("/true")) tracks.clear() }

    /**
     * @param full кадр (бо андозаи пурра ё наздик)
     * @param fullToScreen зарб барои гузаштан ба координатаҳои экран
     * @param offX,offY ҷойи кадр дар экран (агар танҳо як равзана гирифта шуда бошад)
     * @param clean true — дар кадр пардаҳои худи мо нестанд (ҳар кадр аз нав ҳисоб мешавад)
     */
    @Synchronized
    fun process(full: Bitmap, fullToScreen: Float, offX: Int = 0, offY: Int = 0, clean: Boolean = false): List<Cover> {
        val bmp = if (full.width > DETECT_WIDTH * 1.15f) Bitmap.createScaledBitmap(
            full, DETECT_WIDTH, max(1, full.height * DETECT_WIDTH / full.width), true
        ) else full
        val up = full.width.toFloat() / bmp.width
        val toScreen = fullToScreen * up
        val key = "${bmp.width}x${bmp.height}@$toScreen+$offX,$offY/$clean"
        if (key != lastKey) { tracks.clear(); memos.clear(); lastKey = key }

        val det = faces ?: return emptyList()
        val t0 = SystemClock.uptimeMillis()
        val found = try {
            Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), 2, TimeUnit.SECONDS)
        } catch (e: Exception) { Log.e(TAG, "detect", e); return current() }
        val now = SystemClock.uptimeMillis()

        // --- 1) зан ё мард: хотираи рӯйҳо + натиҷаи нав ---
        memos.removeAll { now - it.seen > 1200 }
        val womenFaces = ArrayList<Rect>()
        for (f in found) {
            val fr = RectF(f.boundingBox)
            val memo = memos.maxByOrNull { iou(it.rect, fr) }?.takeIf { iou(it.rect, fr) > 0.35f }
            val needNew = memo == null || memo.n < 3 || (memo.prob < FEMALE_THRESHOLD && now - memo.born > MALE_RECHECK_MS)
            var p = memo?.prob ?: 0.5f
            if (needNew) {
                val b = f.boundingBox
                val fullBox = Rect((b.left * up).toInt(), (b.top * up).toInt(), (b.right * up).toInt(), (b.bottom * up).toInt())
                val np = try { gender?.femaleProb(full, fullBox) ?: 1f } catch (e: Throwable) { 1f }
                if (memo == null) {
                    memos.add(FaceMemo(fr, np, 1, now, now)); p = np
                } else {
                    memo.prob = (memo.prob * memo.n + np) / (memo.n + 1); memo.n = min(memo.n + 1, 6)
                    p = memo.prob
                }
            }
            memo?.let { it.rect = fr; it.seen = now }
            Log.i(TAG, "face ${f.boundingBox.width()}px female=${"%.2f".format(p)}")
            if (p >= FEMALE_THRESHOLD) womenFaces.add(f.boundingBox)
        }

        // --- 2) пардаҳои кӯҳна ---
        if (clean) {
            tracks.removeAll { now - it.seen > HOLD_MS }
        } else {
            // пардаҳо дар кадр ҳастанд → атрофи онҳоро месанҷем (скролл, иваз шудани экран)
            tracks.removeAll {
                val r = it.ring
                val ch = if (r == null) null else ringChanged(r, ringSignature(bmp, it.region))
                ch == true
            }
        }

        // --- 3) барои ҳар зан — тамоми сурат/видео ---
        for (fb in womenFaces) {
            val region = growRegion(bmp, fb)
            val existing = tracks.firstOrNull { overlap(it.region, region) > 0.6f }
            if (existing != null) {
                existing.seen = now
                if (clean) {           // кадр тоза аст → тасвири хираро нав мекунем (видео «зинда» менамояд)
                    existing.region = region
                    makeCover(bmp, region, toScreen, offX, offY)?.let { existing.cover = it }
                }
                continue
            }
            if (!clean && tracks.any { it.region.contains(fb.exactCenterX(), fb.exactCenterY()) }) continue
            val cover = makeCover(bmp, region, toScreen, offX, offY) ?: continue
            tracks.add(Track(region, cover, now, if (clean) null else ringSignature(bmp, region)))
        }
        Log.i(TAG, "frame ${bmp.width}x${bmp.height} faces=${found.size} women=${womenFaces.size} " +
            "covers=${tracks.size} clean=$clean ${SystemClock.uptimeMillis() - t0}ms")
        return current()
    }

    private fun current(): List<Cover> = tracks.map { it.cover }

    /**
     * Аз рӯй сар карда, минтақаро калон мекунем то он даме, ки ба хатти якранг
     * (заминаи экран, ҳошия) ё канори экран бирасем — ин ҳудуди сурат/видео аст.
     */
    private fun growRegion(bmp: Bitmap, face: Rect): RectF {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun lum(x: Int, y: Int): Int {
            val c = px[y * w + x]
            return ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        fun rowUniform(y: Int, x0: Int, x1: Int): Boolean {
            var lo = 255; var hi = 0
            var x = x0
            while (x <= x1) { val l = lum(x, y); if (l < lo) lo = l; if (l > hi) hi = l; x += 2 }
            return hi - lo <= 10
        }
        fun colUniform(x: Int, y0: Int, y1: Int): Boolean {
            var lo = 255; var hi = 0
            var y = y0
            while (y <= y1) { val l = lum(x, y); if (l < lo) lo = l; if (l > hi) hi = l; y += 2 }
            return hi - lo <= 10
        }
        val fw = face.width(); val fh = face.height()
        var l = max(0, face.left - fw / 2); var r = min(w - 1, face.right + fw / 2)
        var t = max(0, face.top - fh / 2); var b = min(h - 1, face.bottom + fh / 2)
        var goL = true; var goR = true; var goT = true; var goB = true
        var guard = 0
        while ((goL || goR || goT || goB) && guard++ < 2000) {
            if (goT) { if (t <= 0) goT = false else if (rowUniform(t - 1, l, r) && (t < 2 || rowUniform(t - 2, l, r))) goT = false else t-- }
            if (goB) { if (b >= h - 1) goB = false else if (rowUniform(b + 1, l, r) && (b > h - 3 || rowUniform(b + 2, l, r))) goB = false else b++ }
            if (goL) { if (l <= 0) goL = false else if (colUniform(l - 1, t, b) && (l < 2 || colUniform(l - 2, t, b))) goL = false else l-- }
            if (goR) { if (r >= w - 1) goR = false else if (colUniform(r + 1, t, b) && (r > w - 3 || colUniform(r + 2, t, b))) goR = false else r++ }
        }
        // ҳадди ақал: сар ва бадан
        val minR = RectF(
            face.left - fw * 1.0f, face.top - fh * 0.9f, face.right + fw * 1.0f, face.bottom + fh * 5f
        )
        minR.intersect(0f, 0f, w.toFloat(), h.toFloat())
        val out = RectF(l.toFloat(), t.toFloat(), (r + 1).toFloat(), (b + 1).toFloat())
        // агар ҳудуди сурат хеле наздик ба рӯй ёфт шавад (минтақаи якранг дар худи сурат) — ҳадди ақал
        if (out.height() < fh * 3f || out.width() < fw * 2.2f) out.union(minR)
        return out
    }

    /** Хираи «шишаи обдор»: шакл ва ранг маълум, вале кӣ будан не */
    private fun makeCover(bmp: Bitmap, region: RectF, toScreen: Float, offX: Int, offY: Int): Cover? {
        val l = region.left.toInt().coerceIn(0, bmp.width - 1)
        val t = region.top.toInt().coerceIn(0, bmp.height - 1)
        val w = region.width().toInt().coerceIn(1, bmp.width - l)
        val h = region.height().toInt().coerceIn(1, bmp.height - t)
        if (w < 4 || h < 4) return null
        return try {
            val crop = Bitmap.createBitmap(bmp, l, t, w, h)
            // хурд кардан → хираи сахт (3 гузар) → бо филтр калон мешавад, нарм ва бе катакча
            val sw = max(3, w / 10); val sh = max(3, h / 10)
            val small = Bitmap.createScaledBitmap(crop, sw, sh, true)
            val p = IntArray(sw * sh)
            small.getPixels(p, 0, sw, 0, 0, sw, sh)
            val rad = max(2, min(sw, sh) / 6)
            repeat(3) { boxBlur(p, sw, sh, rad) }
            for (i in p.indices) {              // каме сафед/хокистарӣ — мисли шишаи обдор
                val c = p[i]
                val rr = ((c shr 16 and 0xFF) * 70 + 200 * 30) / 100
                val gg = ((c shr 8 and 0xFF) * 70 + 205 * 30) / 100
                val bb = ((c and 0xFF) * 70 + 210 * 30) / 100
                p[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(p, 0, sw, 0, 0, sw, sh)
            Cover(
                RectF(l * toScreen + offX, t * toScreen + offY, (l + w) * toScreen + offX, (t + h) * toScreen + offY),
                out
            )
        } catch (e: Exception) { Log.e(TAG, "cover", e); null }
    }

    /** Хираи қуттӣ (уфуқӣ + амудӣ) */
    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(p.size)
        for (pass in 0..1) {
            val src = if (pass == 0) p else tmp
            val dst = if (pass == 0) tmp else p
            for (y in 0 until h) for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (k in -r..r) {
                    val xx = if (pass == 0) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (pass == 0) y else (y + k).coerceIn(0, h - 1)
                    val c = src[yy * w + xx]
                    sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF; n++
                }
                dst[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val smaller = min(a.width() * a.height(), b.width() * b.height())
        return if (smaller <= 0f) 0f else ix * iy / smaller
    }

    private const val RING_N = 14

    private fun ringSignature(bmp: Bitmap, r: RectF): IntArray {
        val m = 4f
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

    /** true — иваз шуд, false — не, null — маълум нест */
    private fun ringChanged(old: IntArray, now: IntArray): Boolean? {
        var valid = 0; var changed = 0
        for (i in old.indices) {
            if (old[i] < 0 || now[i] < 0) continue
            valid++
            if (abs(old[i] - now[i]) > 24) changed++
        }
        if (valid < 6) return null
        return changed.toFloat() / valid > 0.2f
    }
}
