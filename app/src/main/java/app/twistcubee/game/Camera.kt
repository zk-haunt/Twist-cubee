package app.twistcubee.game

import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Perspective camera on the +Z axis looking at the origin. The projection is shifted (off-axis) so
 * the cube is centered in the screen area left free by the UI on each side, and the distance is
 * chosen so the cube always fits that area regardless of screen shape or orientation.
 */
class Camera {
    var width = 1
        private set
    var height = 1
        private set
    var insetLeft = 0f
    var insetTop = 0f
    var insetRight = 0f
    var insetBottom = 0f
    var zoom = 1f

    /** Distance from the camera to the cube center, in world units (cube spans [-1, 1]). */
    var distance = 6f
        private set

    /** NDC offset of the cube center. */
    var shiftX = 0f
        private set
    var shiftY = 0f
        private set

    val proj = FloatArray(16)
    val view = FloatArray(16)
    val viewProj = FloatArray(16)

    private val tanHalf = tan(Math.toRadians(FOV_DEG / 2.0)).toFloat()
    private var aspect = 1f

    fun setSize(w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
    }

    fun update() {
        val w = width.toFloat()
        val h = height.toFloat()
        aspect = w / h
        val freeW = max(w - insetLeft - insetRight, w * 0.3f)
        val freeH = max(h - insetTop - insetBottom, h * 0.3f)
        val avail = min(freeH, freeW)
        distance = RADIUS * h / (avail * FIT * tanHalf) / zoom
        val near = max(0.05f, distance - 2.5f)
        val far = distance + 2.5f
        Matrix.perspectiveM(proj, 0, FOV_DEG.toFloat(), aspect, near, far)
        shiftX = (insetLeft + freeW * 0.5f - w * 0.5f) * 2f / w
        shiftY = -(insetTop + freeH * 0.5f - h * 0.5f) * 2f / h
        // Off-axis shift: clip.xy += shift * clip.w, and clip.w = -z_view.
        proj[8] -= shiftX
        proj[9] -= shiftY
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -distance)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
    }

    /** View-space direction (camera at origin looking down -Z) of the ray through a screen pixel. */
    fun rayDirection(sx: Float, sy: Float, out: FloatArray) {
        val ndcX = 2f * sx / width - 1f
        val ndcY = 1f - 2f * sy / height
        out[0] = (ndcX - shiftX) * tanHalf * aspect
        out[1] = (ndcY - shiftY) * tanHalf
        out[2] = -1f
    }

    /** Projects a world-space point to screen pixels. */
    fun project(wx: Float, wy: Float, wz: Float, out: FloatArray) {
        val vz = wz - distance
        val cw = -vz
        val cx = proj[0] * wx + proj[8] * vz
        val cy = proj[5] * wy + proj[9] * vz
        out[0] = (cx / cw * 0.5f + 0.5f) * width
        out[1] = (0.5f - cy / cw * 0.5f) * height
    }

    companion object {
        const val FOV_DEG = 30.0
        /** Bounding radius of the cube to keep on screen (the true sphere is sqrt(3)). */
        private const val RADIUS = 1.55f
        private const val FIT = 0.94f
    }
}
