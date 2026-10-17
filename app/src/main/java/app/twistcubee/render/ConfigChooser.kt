package app.twistcubee.render

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Picks an RGB888 config with 4x MSAA (nearly free on tile-based mobile GPUs) and a depth buffer,
 * falling back step by step to whatever the device offers.
 */
internal class ConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (renderable in intArrayOf(EGL_OPENGL_ES3_BIT, EGL_OPENGL_ES2_BIT)) {
            for (samples in intArrayOf(4, 2, 0)) {
                for (depth in intArrayOf(24, 16)) {
                    choose(egl, display, renderable, samples, depth)?.let { return it }
                }
            }
        }
        throw IllegalStateException("No suitable EGL config")
    }

    private fun choose(egl: EGL10, display: EGLDisplay, renderable: Int, samples: Int, depth: Int): EGLConfig? {
        val spec = if (samples > 0) {
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, renderable,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE,
            )
        } else {
            intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, renderable, EGL10.EGL_NONE,
            )
        }
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, spec, null, 0, num) || num[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(num[0])
        if (!egl.eglChooseConfig(display, spec, configs, num[0], num)) return null
        val value = IntArray(1)
        var fallback: EGLConfig? = null
        for (c in configs) {
            if (c == null) continue
            if (fallback == null) fallback = c
            val r = attr(egl, display, c, EGL10.EGL_RED_SIZE, value)
            val g = attr(egl, display, c, EGL10.EGL_GREEN_SIZE, value)
            val b = attr(egl, display, c, EGL10.EGL_BLUE_SIZE, value)
            val a = attr(egl, display, c, EGL10.EGL_ALPHA_SIZE, value)
            if (r == 8 && g == 8 && b == 8 && a == 0) return c
        }
        return fallback
    }

    private fun attr(egl: EGL10, display: EGLDisplay, c: EGLConfig, key: Int, value: IntArray): Int =
        if (egl.eglGetConfigAttrib(display, c, key, value)) value[0] else -1

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 0x0004
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
