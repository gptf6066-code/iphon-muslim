package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Як пардаи тайёр: ҷой дар экран ва тасвири хира бо шакли бадан (шаффоф дар берун) */
class Cover(val rect: RectF, val bitmap: Bitmap?)

/** Қабат болои ҳамаи аппҳо, ки танҳо ҷои занҳоро тира мекунад */
class OverlayView(context: Context) : View(context) {

    private var covers: List<Cover> = emptyList()
    private val loc = IntArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun setCovers(list: List<Cover>) {
        covers = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (covers.isEmpty()) return
        getLocationOnScreen(loc)
        for (c in covers) {
            val b = c.bitmap ?: continue
            if (b.isRecycled) continue
            val r = RectF(c.rect.left - loc[0], c.rect.top - loc[1], c.rect.right - loc[0], c.rect.bottom - loc[1])
            canvas.drawBitmap(b, null, r, paint)
        }
    }
}
