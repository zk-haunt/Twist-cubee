package app.twistcubee.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Lightweight stopwatch display. Formats into a reused char array and draws it directly, so it can
 * refresh every frame without allocating strings or triggering layout passes.
 */
class TimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics)
        fontFeatureSettings = "tnum"
        setShadowLayer(6f, 0f, 2f, 0x66000000)
    }
    private val chars = CharArray(16)
    private var len = 0
    private var shownCentis = -1L

    init {
        setMillis(0L)
    }

    fun setColor(color: Int) {
        if (paint.color != color) {
            paint.color = color
            invalidate()
        }
    }

    fun setMillis(ms: Long) {
        val centis = ms / 10
        if (centis == shownCentis) return
        shownCentis = centis
        len = format(ms, chars)
        invalidate()
    }

    /** Inspection countdown: whole seconds left, e.g. "15". */
    fun setSeconds(seconds: Int) {
        val key = -1000L - seconds
        if (key == shownCentis) return
        shownCentis = key
        len = putNumber(seconds.toLong(), chars, 0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText("00:00.00") + paddingLeft + paddingRight
        val fm = paint.fontMetrics
        val h = fm.descent - fm.ascent + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(w.toInt() + 2, widthMeasureSpec),
            resolveSize(h.toInt() + 2, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val baseline = (height - fm.descent - fm.ascent) * 0.5f
        canvas.drawText(chars, 0, len, width * 0.5f, baseline, paint)
    }

    companion object {
        /** m:ss.cc (or h:mm:ss beyond an hour) written into [out]; returns the length. */
        fun format(ms: Long, out: CharArray): Int {
            var k = 0
            val totalSec = ms / 1000
            if (totalSec >= 3600) {
                val h = totalSec / 3600
                k = putNumber(h, out, k)
                out[k++] = ':'
                k = put2(((totalSec / 60) % 60).toInt(), out, k)
                out[k++] = ':'
                k = put2((totalSec % 60).toInt(), out, k)
                return k
            }
            k = putNumber(totalSec / 60, out, k)
            out[k++] = ':'
            k = put2((totalSec % 60).toInt(), out, k)
            out[k++] = '.'
            k = put2(((ms / 10) % 100).toInt(), out, k)
            return k
        }

        fun format(ms: Long): String {
            val c = CharArray(16)
            return String(c, 0, format(ms, c))
        }

        private fun put2(v: Int, out: CharArray, k: Int): Int {
            out[k] = ('0' + v / 10)
            out[k + 1] = ('0' + v % 10)
            return k + 2
        }

        private fun putNumber(v: Long, out: CharArray, start: Int): Int {
            if (v < 10) {
                out[start] = '0' + v.toInt()
                return start + 1
            }
            var digits = 0
            var t = v
            while (t > 0) { digits++; t /= 10 }
            var x = v
            for (i in digits - 1 downTo 0) {
                out[start + i] = '0' + (x % 10).toInt()
                x /= 10
            }
            return start + digits
        }
    }
}
