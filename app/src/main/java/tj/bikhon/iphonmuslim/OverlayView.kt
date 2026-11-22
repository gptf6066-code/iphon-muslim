package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Як минтақаи пӯшонидашуда: ҷой ва тасвири сахт хирашуда */
class Cover(val rect: RectF, val blurred: Bitmap?)

/** Қабати шаффоф болои ҳамаи аппҳо, ки ҷойҳоро хира ва тира мекунад */
class OverlayView(context: Context) : View(context) {

    private var covers: List<Cover> = emptyList()
    private var scale = 1f
    private val loc = IntArray(2)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
    private val path = Path()

    fun setCovers(list: List<Cover>, scale: Float) {
        covers = list
        this.scale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (covers.isEmpty()) return
        getLocationOnScreen(loc)
        val corner = 14f * resources.displayMetrics.density
        for (c in covers) {
            val b = c.rect
            val r = RectF(
                b.left * scale - loc[0], b.top * scale - loc[1],
                b.right * scale - loc[0], b.bottom * scale - loc[1]
            )
            canvas.save()
            path.reset()
            path.addRoundRect(r, corner, corner, Path.Direction.CW)
            canvas.clipPath(path)
            val bmp = c.blurred
            if (bmp != null && !bmp.isRecycled) canvas.drawBitmap(bmp, null, r, bmpPaint)
            else canvas.drawRect(r, fallback)
            canvas.drawRect(r, shade)
            canvas.restore()
        }
    }
}
