package app.twistcubee

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import app.twistcubee.game.CubeGame
import app.twistcubee.render.ConfigChooser
import app.twistcubee.render.CubeRenderer

/**
 * GL surface hosting the cube. Renders only on demand: a frame is drawn when input arrives or while
 * an animation is running, so an idle cube costs no GPU time or battery at all.
 */
class CubeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private var game: CubeGame? = null

    fun init(game: CubeGame) {
        this.game = game
        setEGLContextClientVersion(3)
        setEGLConfigChooser(ConfigChooser())
        preserveEGLContextOnPause = true
        val frameRequest = { requestRender() }
        setRenderer(CubeRenderer(game, frameRequest))
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = game ?: return false
        g.onTouch(event)
        requestRender()
        return true
    }
}
