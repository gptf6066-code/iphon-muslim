package tj.bikhon.iphonmuslim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Қабати шаффоф болои ҳамаи аппҳо, ки пардаҳоро мекашад */
class OverlayView(context: Context) : View(context) {

    private var boxes: List<RectF> = emptyList()
    private var scale = 1f
    private val loc = IntArray(2)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B5D3B") }
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14804F"); strokeWidth = 6f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }

    fun setBoxes(list: List<RectF>, scale: Float) {
        boxes = list
        this.scale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        // агар қабат аз кунҷи экран сар нашавад (масалан зери статус-бар), ислоҳ мекунем
        getLocationOnScreen(loc)
        val corner = 18f * resources.displayMetrics.density
        for (b in boxes) {
            val r = RectF(
                b.left * scale - loc[0], b.top * scale - loc[1],
                b.right * scale - loc[0], b.bottom * scale - loc[1]
            )
            canvas.drawRoundRect(r, corner, corner, fill)
            canvas.save()
            canvas.clipRect(r)
            var x = r.left - r.height()
            while (x < r.right) {
                canvas.drawLine(x, r.bottom, x + r.height(), r.top, stripe)
                x += 28f
            }
            canvas.restore()
            text.textSize = (r.width() / 9f).coerceIn(14f, 48f)
            canvas.drawText("☪", r.centerX(), r.centerY() + text.textSize / 3f, text)
        }
    }
}
