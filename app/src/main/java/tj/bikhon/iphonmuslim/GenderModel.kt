package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Модели хурди муайян кардани зан/мард аз рӯй (128×128, баромад: [мард, зан]).
 * Модел: shubham0204/Age-Gender_Estimation_TF-Android (MIT).
 */
class GenderModel(ctx: Context) {

    private val size = 128
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }
    private val px = IntArray(size * size)

    init {
        val fd = ctx.assets.openFd("gender.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buf, Interpreter.Options().setNumThreads(2))
    }

    /** Эҳтимоли он ки рӯй аз они зан бошад (0..1) */
    fun femaleProb(bmp: Bitmap, face: Rect): Float {
        val s = max(face.width(), face.height()) * 1.1f
        val cx = face.exactCenterX(); val cy = face.exactCenterY()
        val l = max(0, (cx - s / 2).toInt())
        val t = max(0, (cy - s / 2).toInt())
        val r = min(bmp.width, (cx + s / 2).toInt())
        val b = min(bmp.height, (cy + s / 2).toInt())
        if (r - l < 6 || b - t < 6) return 1f
        val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
        val sc = Bitmap.createScaledBitmap(crop, size, size, true)
        sc.getPixels(px, 0, size, 0, 0, size, size)
        input.rewind()
        for (c in px) {
            input.putFloat(((c shr 16) and 0xFF) / 255f)
            input.putFloat(((c shr 8) and 0xFF) / 255f)
            input.putFloat((c and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        return output[0][1]
    }
}
