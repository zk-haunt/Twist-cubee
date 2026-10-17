package app.twistcubee.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

/** Short celebratory confetti burst. All particle state lives in preallocated primitive arrays. */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val px = FloatArray(COUNT)
    private val py = FloatArray(COUNT)
    private val vx = FloatArray(COUNT)
    private val vy = FloatArray(COUNT)
    private val rot = FloatArray(COUNT)
    private val vr = FloatArray(COUNT)
    private val size = FloatArray(COUNT)
    private val wobble = FloatArray(COUNT)
    private val color = IntArray(COUNT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random()
    private var startNs = 0L
    private var lastNs = 0L
    private var running = false
    private val density = resources.displayMetrics.density

    init {
        visibility = GONE
        isClickable = false
    }

    fun burst(colors: IntArray) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        for (i in 0 until COUNT) {
            val left = i % 2 == 0
            px[i] = if (left) -10f * density else w + 10f * density
            py[i] = h * (0.55f + rnd.nextFloat() * 0.25f)
            val speed = (900f + rnd.nextFloat() * 900f) * density
            val angle = Math.toRadians(if (left) -50.0 - rnd.nextDouble() * 30 else -130.0 + rnd.nextDouble() * 30).toFloat()
            vx[i] = speed * kotlin.math.cos(angle)
            vy[i] = speed * kotlin.math.sin(angle)
            rot[i] = rnd.nextFloat() * 360f
            vr[i] = (rnd.nextFloat() - 0.5f) * 900f
            size[i] = (4f + rnd.nextFloat() * 5f) * density
            wobble[i] = rnd.nextFloat() * 6.28f
            color[i] = 0xFF000000.toInt() or colors[i % colors.size]
        }
        startNs = System.nanoTime()
        lastNs = startNs
        running = true
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = now
        val t = (now - startNs) / 1e9f
        val gravity = 1500f * density
        val alpha = if (t > DURATION - 0.8f) ((DURATION - t) / 0.8f).coerceIn(0f, 1f) else 1f
        for (i in 0 until COUNT) {
            vy[i] += gravity * dt
            vx[i] *= 1f - 1.2f * dt
            vy[i] *= 1f - 0.9f * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            rot[i] += vr[i] * dt
            val s = size[i]
            val flip = kotlin.math.abs(kotlin.math.sin(t * 6f + wobble[i]))
            paint.color = color[i]
            paint.alpha = (alpha * 255).toInt()
            canvas.save()
            canvas.translate(px[i], py[i])
            canvas.rotate(rot[i])
            canvas.drawRect(-s, -s * 0.55f * flip, s, s * 0.55f * flip, paint)
            canvas.restore()
        }
        if (t < DURATION) postInvalidateOnAnimation() else stop()
    }

    private companion object {
        const val COUNT = 140
        const val DURATION = 3.2f
    }
}
