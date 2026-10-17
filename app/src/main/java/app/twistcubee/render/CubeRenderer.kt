package app.twistcubee.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.twistcubee.game.CubeGame
import app.twistcubee.game.FrameData
import app.twistcubee.model.CubeState
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the cube with a constant number of draw calls regardless of its size:
 * background (1) + core boxes (1-3) + all cubie bodies (1, instanced) + all stickers (1, instanced).
 *
 * Static per-size data (meshes, cubie and sticker positions) lives in GPU buffers built once per
 * size. The only per-move upload is one byte per sticker (its color); a frame in which nothing
 * changed is never drawn at all (render-on-demand).
 */
internal class CubeRenderer(
    private val game: CubeGame,
    private val requestFrame: () -> Unit,
) : GLSurfaceView.Renderer {

    private val frame = FrameData()

    private var bodyProg = 0
    private var stickerProg = 0
    private var coreProg = 0
    private var bgProg = 0

    private val bodyU = AnimUniforms()
    private val stickerU = AnimUniforms()
    private var bodyColorLoc = 0
    private var bodyEyeLoc = 0
    private var stickerEyeLoc = 0
    private var stickerLiftLoc = 0
    private var stickerHintAxisLoc = 0
    private var stickerHintRangeLoc = 0
    private var stickerHintAmountLoc = 0
    private var coreMvpLoc = 0
    private var coreColorLoc = 0
    private var bgTopLoc = 0
    private var bgBottomLoc = 0
    private var bgGlowLoc = 0
    private var bgGlowPosLoc = 0
    private var bgAspectLoc = 0

    private val vao = IntArray(4)
    private val vbo = IntArray(10)

    private var builtGeometry = -1
    private var builtN = -1
    private var uploadedColors = -1
    private var uploadedPalette = -1
    private var bodyIndexCount = 0
    private var bodyInstances = 0
    private var stickerIndexCount = 0
    private var stickerInstances = 0
    private var coreIndexCount = 0
    private var colorBuffer: ByteBuffer? = null

    private val animRot3 = FloatArray(9)
    private val animRot4 = FloatArray(16)
    private val animAxis = FloatArray(3)
    private val hintAxis = FloatArray(3)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val tmpC = FloatArray(16)
    private val mvp = FloatArray(16)
    private val coreColor = FloatArray(3)
    private val invalidateDepth = intArrayOf(GLES30.GL_DEPTH)
    private val rgb = ByteArray(18)

    private class AnimUniforms {
        var viewProj = 0
        var model = 0
        var animRot = 0
        var animAxis = 0
        var animRange = 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        bodyProg = GlUtil.program(Shaders.BODY_VS, Shaders.BODY_FS)
        stickerProg = GlUtil.program(Shaders.STICKER_VS, Shaders.STICKER_FS)
        coreProg = GlUtil.program(Shaders.CORE_VS, Shaders.CORE_FS)
        bgProg = GlUtil.program(Shaders.BG_VS, Shaders.BG_FS)
        bindAnimUniforms(bodyProg, bodyU)
        bindAnimUniforms(stickerProg, stickerU)
        bodyColorLoc = GLES30.glGetUniformLocation(bodyProg, "uColor")
        bodyEyeLoc = GLES30.glGetUniformLocation(bodyProg, "uEye")
        stickerEyeLoc = GLES30.glGetUniformLocation(stickerProg, "uEye")
        stickerLiftLoc = GLES30.glGetUniformLocation(stickerProg, "uLift")
        stickerHintAxisLoc = GLES30.glGetUniformLocation(stickerProg, "uHintAxis")
        stickerHintRangeLoc = GLES30.glGetUniformLocation(stickerProg, "uHintRange")
        stickerHintAmountLoc = GLES30.glGetUniformLocation(stickerProg, "uHintAmount")
        coreMvpLoc = GLES30.glGetUniformLocation(coreProg, "uMvp")
        coreColorLoc = GLES30.glGetUniformLocation(coreProg, "uColor")
        bgTopLoc = GLES30.glGetUniformLocation(bgProg, "uTop")
        bgBottomLoc = GLES30.glGetUniformLocation(bgProg, "uBottom")
        bgGlowLoc = GLES30.glGetUniformLocation(bgProg, "uGlow")
        bgGlowPosLoc = GLES30.glGetUniformLocation(bgProg, "uGlowPos")
        bgAspectLoc = GLES30.glGetUniformLocation(bgProg, "uAspect")

        GLES30.glGenVertexArrays(4, vao, 0)
        GLES30.glGenBuffers(10, vbo, 0)

        // Core box (positions only).
        val box = Meshes.box()
        GLES30.glBindVertexArray(vao[VAO_CORE])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_CORE_V])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, box.vertices.size * 4, GlUtil.floatBuffer(box.vertices), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[VBO_CORE_I])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, box.indices.size * 2, GlUtil.shortBuffer(box.indices), GLES30.GL_STATIC_DRAW)
        coreIndexCount = box.indices.size

        // Full-screen triangle for the background.
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        GLES30.glBindVertexArray(vao[VAO_BG])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_BG])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, GlUtil.floatBuffer(tri), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)

        GLES30.glClearColor(0.035f, 0.047f, 0.078f, 1f)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        builtGeometry = -1
        builtN = -1
        uploadedColors = -1
        frame.colorsVersion = -1 // context (re)created: force a full color upload
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        game.setSurfaceSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val more: Boolean
        synchronized(game.lock) {
            more = game.update(System.nanoTime(), frame)
        }
        val f = frame
        if (f.geometryVersion != builtGeometry || f.n != builtN) buildGeometry(f.n, f.geometryVersion)
        if (f.colorsVersion != uploadedColors || f.paletteVersion != uploadedPalette) uploadColors(f)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawBackground(f)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)

        val half = f.n * 0.5f
        val offset = (f.n - 1) * 0.5f
        computeAnim(f)
        // Slab of cubie centers that is currently turning, in cube units.
        val lo = if (f.animAxis >= 0) f.animLo - offset - 0.5f else 1f
        val hi = if (f.animAxis >= 0) f.animHi - offset + 0.5f else -1f

        drawCores(f, half)

        GLES30.glUseProgram(bodyProg)
        setAnimUniforms(bodyU, f, lo, hi)
        GLES30.glUniform3fv(bodyColorLoc, 1, f.body, 0)
        GLES30.glUniform3fv(bodyEyeLoc, 1, f.eye, 0)
        GLES30.glBindVertexArray(vao[VAO_BODY])
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, bodyIndexCount, GLES30.GL_UNSIGNED_SHORT, 0, bodyInstances)

        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)
        GLES30.glUseProgram(stickerProg)
        setAnimUniforms(stickerU, f, lo, hi)
        GLES30.glUniform3fv(stickerEyeLoc, 1, f.eye, 0)
        GLES30.glUniform1f(stickerLiftLoc, CUBIE_HALF + STICKER_LIFT)
        if (f.hintAxis >= 0) {
            hintAxis[0] = 0f; hintAxis[1] = 0f; hintAxis[2] = 0f
            hintAxis[f.hintAxis] = 1f
            GLES30.glUniform3fv(stickerHintAxisLoc, 1, hintAxis, 0)
            GLES30.glUniform2f(stickerHintRangeLoc, f.hintLo - offset - 0.5f, f.hintHi - offset + 0.5f)
            GLES30.glUniform1f(stickerHintAmountLoc, f.hintAmount)
        } else {
            GLES30.glUniform3f(stickerHintAxisLoc, 0f, 0f, 0f)
            GLES30.glUniform2f(stickerHintRangeLoc, 1f, -1f)
            GLES30.glUniform1f(stickerHintAmountLoc, 0f)
        }
        GLES30.glBindVertexArray(vao[VAO_STICKER])
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, stickerIndexCount, GLES30.GL_UNSIGNED_SHORT, 0, stickerInstances)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glBindVertexArray(0)

        // The depth buffer is never read back: tell tile-based GPUs not to write it to memory.
        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateDepth, 0)

        if (more) requestFrame()
    }

    private fun drawBackground(f: FrameData) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(bgProg)
        GLES30.glUniform3fv(bgTopLoc, 1, f.background, 0)
        GLES30.glUniform3fv(bgBottomLoc, 1, f.background, 3)
        GLES30.glUniform3fv(bgGlowLoc, 1, f.background, 6)
        GLES30.glUniform2f(bgGlowPosLoc, 0.5f + f.shiftX * 0.5f, 0.5f + f.shiftY * 0.5f)
        GLES30.glUniform1f(bgAspectLoc, f.aspect)
        GLES30.glBindVertexArray(vao[VAO_BG])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Dark boxes filling the inside of the cube. They hide the hollow interior (only surface cubies
     * exist) through the gaps between cubies, and while a slice turns they act as the caps of the
     * exposed cut faces. One box at rest, up to three during a turn.
     */
    private fun drawCores(f: FrameData, half: Float) {
        GLES30.glUseProgram(coreProg)
        coreColor[0] = f.body[0] * 0.45f
        coreColor[1] = f.body[1] * 0.45f
        coreColor[2] = f.body[2] * 0.45f
        GLES30.glUniform3fv(coreColorLoc, 1, coreColor, 0)
        GLES30.glBindVertexArray(vao[VAO_CORE])
        val inset = CORE_INSET
        val outer = half - inset
        val axis = f.animAxis
        if (axis < 0 || (f.animLo == 0 && f.animHi == f.n - 1)) {
            drawCoreBox(f, 0f, 0f, 0f, outer, outer, outer, axis >= 0)
            return
        }
        val b1 = f.animLo - half
        val b2 = f.animHi + 1 - half
        if (f.animLo > 0) drawCorePart(f, axis, -half, b1, half, outer, false)
        drawCorePart(f, axis, b1, b2, half, outer, true)
        if (f.animHi < f.n - 1) drawCorePart(f, axis, b2, half, half, outer, false)
    }

    private fun drawCorePart(f: FrameData, axis: Int, start: Float, end: Float, half: Float, outer: Float, rotating: Boolean) {
        val a0 = start + if (start <= -half + 1e-3f) CORE_INSET else CORE_GAP
        val a1 = end - if (end >= half - 1e-3f) CORE_INSET else CORE_GAP
        val c = (a0 + a1) * 0.5f
        val e = (a1 - a0) * 0.5f
        when (axis) {
            0 -> drawCoreBox(f, c, 0f, 0f, e, outer, outer, rotating)
            1 -> drawCoreBox(f, 0f, c, 0f, outer, e, outer, rotating)
            else -> drawCoreBox(f, 0f, 0f, c, outer, outer, e, rotating)
        }
    }

    private fun drawCoreBox(f: FrameData, cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, rotating: Boolean) {
        Matrix.setIdentityM(tmpA, 0)
        Matrix.translateM(tmpA, 0, cx, cy, cz)
        Matrix.scaleM(tmpA, 0, hx, hy, hz)
        if (rotating) {
            Matrix.multiplyMM(tmpB, 0, animRot4, 0, tmpA, 0)
            Matrix.multiplyMM(tmpC, 0, f.model, 0, tmpB, 0)
        } else {
            Matrix.multiplyMM(tmpC, 0, f.model, 0, tmpA, 0)
        }
        Matrix.multiplyMM(mvp, 0, f.viewProj, 0, tmpC, 0)
        GLES30.glUniformMatrix4fv(coreMvpLoc, 1, false, mvp, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, coreIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun computeAnim(f: FrameData) {
        val c = cos(f.animAngle)
        val s = sin(f.animAngle)
        animAxis[0] = 0f; animAxis[1] = 0f; animAxis[2] = 0f
        // Column-major rotation about the animated axis.
        when (f.animAxis) {
            0 -> set3(1f, 0f, 0f, 0f, c, s, 0f, -s, c)
            1 -> set3(c, 0f, -s, 0f, 1f, 0f, s, 0f, c)
            2 -> set3(c, s, 0f, -s, c, 0f, 0f, 0f, 1f)
            else -> set3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        if (f.animAxis >= 0) animAxis[f.animAxis] = 1f
        Matrix.setIdentityM(animRot4, 0)
        for (col in 0 until 3) for (row in 0 until 3) animRot4[col * 4 + row] = animRot3[col * 3 + row]
    }

    private fun set3(m0: Float, m1: Float, m2: Float, m3: Float, m4: Float, m5: Float, m6: Float, m7: Float, m8: Float) {
        animRot3[0] = m0; animRot3[1] = m1; animRot3[2] = m2
        animRot3[3] = m3; animRot3[4] = m4; animRot3[5] = m5
        animRot3[6] = m6; animRot3[7] = m7; animRot3[8] = m8
    }

    private fun bindAnimUniforms(prog: Int, u: AnimUniforms) {
        u.viewProj = GLES30.glGetUniformLocation(prog, "uViewProj")
        u.model = GLES30.glGetUniformLocation(prog, "uModel")
        u.animRot = GLES30.glGetUniformLocation(prog, "uAnimRot")
        u.animAxis = GLES30.glGetUniformLocation(prog, "uAnimAxis")
        u.animRange = GLES30.glGetUniformLocation(prog, "uAnimRange")
    }

    private fun setAnimUniforms(u: AnimUniforms, f: FrameData, lo: Float, hi: Float) {
        GLES30.glUniformMatrix4fv(u.viewProj, 1, false, f.viewProj, 0)
        GLES30.glUniformMatrix4fv(u.model, 1, false, f.model, 0)
        GLES30.glUniformMatrix3fv(u.animRot, 1, false, animRot3, 0)
        GLES30.glUniform3fv(u.animAxis, 1, animAxis, 0)
        GLES30.glUniform2f(u.animRange, lo, hi)
    }

    /** (Re)builds every size-dependent buffer: meshes at the right level of detail and instances. */
    private fun buildGeometry(n: Int, version: Int) {
        val seg = when {
            n <= 3 -> 5
            n <= 5 -> 4
            n <= 8 -> 3
            n <= 12 -> 2
            else -> 1
        }
        val stickerSeg = when {
            n <= 5 -> 6
            n <= 10 -> 4
            else -> 2
        }
        val offset = (n - 1) * 0.5f

        // Bodies: one instance per visible (surface) cubie.
        // Stickerless tiles nearly cover each face, so the cubie edges are rounded less to keep
        // the tile on the flat part of the face.
        val stickerless = frame.tileStyle == 0
        val body = Meshes.roundedCube(CUBIE_HALF, if (stickerless) TILE_ROUND else CUBIE_ROUND, seg)
        var count = 0
        val centers = FloatArray(n * n * n * 3)
        for (x in 0 until n) for (y in 0 until n) for (z in 0 until n) {
            val surface = x == 0 || y == 0 || z == 0 || x == n - 1 || y == n - 1 || z == n - 1
            if (!surface) continue
            centers[count * 3] = x - offset
            centers[count * 3 + 1] = y - offset
            centers[count * 3 + 2] = z - offset
            count++
        }
        GLES30.glBindVertexArray(vao[VAO_BODY])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_BODY_V])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, body.vertices.size * 4, GlUtil.floatBuffer(body.vertices), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_BODY_INST])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 12, GlUtil.floatBuffer(centers.copyOf(count * 3)), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[VBO_BODY_I])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, body.indices.size * 2, GlUtil.shortBuffer(body.indices), GLES30.GL_STATIC_DRAW)
        bodyIndexCount = body.indices.size
        bodyInstances = count

        // Stickers: one instance per facelet, in CubeState order so colors upload as-is.
        val sticker = if (stickerless) {
            Meshes.roundedSquare(TILE_HALF, TILE_CORNER, stickerSeg)
        } else {
            Meshes.roundedSquare(STICKER_HALF, STICKER_ROUND, stickerSeg)
        }
        val geo = CubeState(n)
        val inst = FloatArray(geo.size * 9)
        for (i in 0 until geo.size) {
            val face = geo.faceOf(i)
            val o = i * 9
            inst[o] = geo.px[i] - offset
            inst[o + 1] = geo.py[i] - offset
            inst[o + 2] = geo.pz[i] - offset
            for (k in 0 until 3) {
                inst[o + 3 + k] = FACE_NORMAL[face * 3 + k]
                inst[o + 6 + k] = FACE_TANGENT[face * 3 + k]
            }
        }
        GLES30.glBindVertexArray(vao[VAO_STICKER])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_STICKER_V])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, sticker.vertices.size * 4, GlUtil.floatBuffer(sticker.vertices), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_STICKER_INST])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, inst.size * 4, GlUtil.floatBuffer(inst), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 36, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, 36, 12)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 3, GLES30.GL_FLOAT, false, 36, 24)
        GLES30.glVertexAttribDivisor(4, 1)
        // Colors: RGB + padding byte per sticker, kept 4-byte aligned.
        val colors = GlUtil.byteBuffer(geo.size * 4)
        colorBuffer = colors
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_STICKER_COLOR])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, geo.size * 4, colors, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, 4, 0)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[VBO_STICKER_I])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, sticker.indices.size * 2, GlUtil.shortBuffer(sticker.indices), GLES30.GL_STATIC_DRAW)
        stickerIndexCount = sticker.indices.size
        stickerInstances = geo.size
        GLES30.glBindVertexArray(0)

        builtGeometry = version
        builtN = n
        uploadedColors = -1
    }

    /** Resolves palette indices to RGB on the CPU (one write per sticker, only after a move). */
    private fun uploadColors(f: FrameData) {
        val buf = colorBuffer ?: return
        val count = stickerInstances
        if (f.colors.size != count) return
        for (c in 0 until 6) {
            rgb[c * 3] = (f.palette[c * 3] * 255f + 0.5f).toInt().toByte()
            rgb[c * 3 + 1] = (f.palette[c * 3 + 1] * 255f + 0.5f).toInt().toByte()
            rgb[c * 3 + 2] = (f.palette[c * 3 + 2] * 255f + 0.5f).toInt().toByte()
        }
        for (i in 0 until count) {
            val c = f.colors[i] * 3
            buf.put(i * 4, rgb[c])
            buf.put(i * 4 + 1, rgb[c + 1])
            buf.put(i * 4 + 2, rgb[c + 2])
        }
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[VBO_STICKER_COLOR])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 4, buf)
        uploadedColors = f.colorsVersion
        uploadedPalette = f.paletteVersion
    }

    private companion object {
        const val VAO_BODY = 0
        const val VAO_STICKER = 1
        const val VAO_CORE = 2
        const val VAO_BG = 3

        const val VBO_BODY_V = 0
        const val VBO_BODY_I = 1
        const val VBO_BODY_INST = 2
        const val VBO_STICKER_V = 3
        const val VBO_STICKER_I = 4
        const val VBO_STICKER_INST = 5
        const val VBO_STICKER_COLOR = 6
        const val VBO_CORE_V = 7
        const val VBO_CORE_I = 8
        const val VBO_BG = 9

        /** Cubie half-size in cube units; the rest of 0.5 is the gap between cubies. */
        const val CUBIE_HALF = 0.485f
        const val CUBIE_ROUND = 0.075f
        const val STICKER_HALF = 0.405f
        const val STICKER_ROUND = 0.085f
        // Stickerless style: colored tiles almost to the edge, separated by thin dark lines.
        const val TILE_ROUND = 0.045f
        const val TILE_HALF = 0.438f
        const val TILE_CORNER = 0.07f
        const val STICKER_LIFT = 0.004f
        const val CORE_INSET = 0.09f
        const val CORE_GAP = 0.004f

        // Outward normal and in-face tangent of each face, order U R F D L B.
        val FACE_NORMAL = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, -1f)
        val FACE_TANGENT = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f)
    }
}
