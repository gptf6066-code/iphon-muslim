package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Як пардаи тайёр: ҷой дар экран ва тасвири хира бо шакли бадан (шаффоф дар берун) */
class Cover(val rect: RectF, val bitmap: Bitmap?)

/** Қабат болои ҳамаи аппҳо, ки танҳо ҷои занҳоро тира мекунад */
class OverlayView(context: Context) : View(context) {

    private var covers: List<Cover> = emptyList()
    private val loc = IntArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private var guard: Rect? = null
    private var guardBmp: Bitmap? = null
    private var guardSrcRect: RectF? = null
    private val guardFill = Paint().apply { color = android.graphics.Color.argb(245, 205, 210, 214) }

    /** Ҳангоми скролл: минтақаи лента пурра хира мешавад */
    fun setGuard(r: Rect?, bmp: Bitmap?, bmpScreenRect: RectF?) {
        guard = r; guardBmp = bmp; guardSrcRect = bmpScreenRect
        invalidate()
    }

    fun setCovers(list: List<Cover>) {
        covers = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        drawCovers(canvas)
        val g = guard ?: return
        val dst = RectF(g.left - loc[0].toFloat(), g.top - loc[1].toFloat(), g.right - loc[0].toFloat(), g.bottom - loc[1].toFloat())
        val b = guardBmp; val sr = guardSrcRect
        if (b != null && !b.isRecycled && sr != null && sr.width() > 0 && sr.height() > 0) {
            // ҳамон қисми тасвири хираро, ки ба минтақаи лента рост меояд
            val sx = b.width / sr.width(); val sy = b.height / sr.height()
            val src = Rect(
                ((g.left - sr.left) * sx).toInt().coerceIn(0, b.width - 1), ((g.top - sr.top) * sy).toInt().coerceIn(0, b.height - 1),
                ((g.right - sr.left) * sx).toInt().coerceIn(1, b.width), ((g.bottom - sr.top) * sy).toInt().coerceIn(1, b.height)
            )
            canvas.drawRect(dst, guardFill)
            canvas.drawBitmap(b, src, dst, paint)
        } else canvas.drawRect(dst, guardFill)
    }

    private fun drawCovers(canvas: Canvas) {
        if (covers.isEmpty()) return
        for (c in covers) {
            val b = c.bitmap ?: continue
            if (b.isRecycled) continue
            val r = RectF(c.rect.left - loc[0], c.rect.top - loc[1], c.rect.right - loc[0], c.rect.bottom - loc[1])
            canvas.drawBitmap(b, null, r, paint)
        }
    }
}
