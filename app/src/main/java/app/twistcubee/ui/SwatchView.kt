package app.twistcubee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Tiny 3x2 grid of rounded squares previewing a sticker color scheme (created from code only). */
@SuppressLint("ViewConstructor")
class SwatchView(context: Context, private val colors: IntArray, private val body: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((54 * density).toInt(), (38 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = 0xFF000000.toInt() or body
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)
        val pad = 3 * density
        val cw = (w - pad * 4) / 3
        val ch = (h - pad * 3) / 2
        for (i in 0 until 6) {
            val col = i % 3
            val row = i / 3
            val x = pad + col * (cw + pad)
            val y = pad + row * (ch + pad)
            rect.set(x, y, x + cw, y + ch)
            paint.color = 0xFF000000.toInt() or colors[i]
            canvas.drawRoundRect(rect, 3 * density, 3 * density, paint)
        }
    }
}
