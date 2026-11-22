package tj.bikhon.iphonmuslim

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Муайян кардани зан/мард аз рӯй — модели genderage-и InsightFace.
 * Вуруд: 96×96 RGB (0..255), NCHW. Баромад: [зан, мард, синну сол].
 */
class GenderModel(ctx: Context) {

    private val n = 96
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val crop = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(crop)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val px = IntArray(n * n)
    private val data = FloatBuffer.allocate(3 * n * n)

    init {
        val bytes = ctx.assets.open("genderage.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    /**
     * Эҳтимоли зан будан (0..1). face — дар координатаҳои bmp.
     * Барои дақиқӣ: 3 андозаи буриш × оддӣ/оинавӣ = 6 натиҷа, миёнаашон.
     */
    fun femaleProb(bmp: Bitmap, face: Rect): Float {
        val side = max(face.width(), face.height()).toFloat()
        if (side < 6f) return 0.5f
        var sum = 0f; var n = 0
        for (k in floatArrayOf(1.3f, 1.5f, 1.7f)) {
            for (mirror in booleanArrayOf(false, true)) {
                sum += once(bmp, face.exactCenterX(), face.exactCenterY(), side * k, mirror); n++
            }
        }
        return sum / n
    }

    private fun once(bmp: Bitmap, cx: Float, cy: Float, size: Float, mirror: Boolean): Float {
        val k = n / size
        matrix.reset()
        matrix.postTranslate(-cx, -cy)
        matrix.postScale(if (mirror) -k else k, k)
        matrix.postTranslate(n / 2f, n / 2f)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, matrix, paint)
        crop.getPixels(px, 0, n, 0, 0, n, n)
        data.clear()
        val plane = n * n
        for (i in 0 until plane) data.put(i, ((px[i] shr 16) and 0xFF).toFloat())
        for (i in 0 until plane) data.put(plane + i, ((px[i] shr 8) and 0xFF).toFloat())
        for (i in 0 until plane) data.put(2 * plane + i, (px[i] and 0xFF).toFloat())
        data.rewind()
        OnnxTensor.createTensor(env, data, longArrayOf(1, 3, n.toLong(), n.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val o = (r[0].value as Array<FloatArray>)[0]
                val m = max(o[0], o[1])
                val f = exp((o[0] - m).toDouble()); val mm = exp((o[1] - m).toDouble())
                return (f / (f + mm)).toFloat()
            }
        }
    }
}
