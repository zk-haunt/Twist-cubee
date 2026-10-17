package app.twistcubee.game

import android.view.MotionEvent
import app.twistcubee.data.GameSave
import app.twistcubee.math.Quat
import app.twistcubee.model.CubeState
import app.twistcubee.model.Move
import app.twistcubee.model.MoveList
import app.twistcubee.model.Scrambler
import app.twistcubee.solver.SolverService
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * The game: cube state, gestures, animations, move queue, history, timer and hints.
 *
 * Touch input and UI commands arrive on the main thread, [update] runs on the GL thread once per
 * frame; everything is guarded by [lock]. Nothing here allocates in steady state: moves are packed
 * ints, all math uses preallocated scratch arrays, and events are delivered as a bit set.
 */
class CubeGame(private val listener: Listener) {

    interface Listener {
        /** Bit set of EV_* flags. Called from any thread while the game lock is held. */
        fun onGameEvents(events: Int)

        /** Asks for a new frame (used by callbacks that do not come from the GL or main thread). */
        fun requestRender()
    }

    /** Snapshot of everything the HUD shows. */
    class Hud {
        var n = 3
        var moves = 0
        var status = ST_FREE
        var canUndo = false
        var canRedo = false
        var solved = true
        var hintMove = -1
        var solverBusy = false
        var solverFailed = false
        var autoSolving = false
        var queueDone = 0
        var queueTotal = 0
        var timerRunning = false
        var inspecting = false
        var locked = false
        /** 3x3 hint: center color (solved-face index) of the face to turn, or -1. */
        var hintFaceColor = -1
        /** 3x3 hint direction as seen looking at that face: 0 = counter-clockwise, 1 = clockwise, 2 = half turn. */
        var hintDir = 0
    }

    val lock = Any()

    var n = 3
        private set
    private var cube = CubeState(3)
    private val camera = Camera()

    private val view = Quat()
    private val defaultView = Quat()
    private val tq = Quat()
    private val tq2 = Quat()
    private val resetFrom = Quat()

    private val scrambleMoves = MoveList(64)
    private val userMoves = MoveList(64)
    private val redoMoves = MoveList(16)
    private var solvedMark = -1

    private var status = ST_FREE
    private var assisted = false
    private var screen = SCREEN_MENU
    private var locked = false
    private var animSpeed = SPEED_NORMAL
    private var colorsVersion = 1
    private var geometryVersion = 1

    @Volatile private var timerRunning = false
    @Volatile private var timerAccumMs = 0L
    @Volatile private var timerStartNs = 0L

    /** Details of the most recent solve, valid after [EV_SOLVED]. */
    var lastSolveMs = 0L
        private set
    var lastSolveMoves = 0
        private set
    var lastSolveTimed = false
        private set
    var lastSolveAssisted = false
        private set

    // Layer currently drawn rotated (drag, snap, queued move or hint nudge).
    private var layerAxis = -1
    private var layerLo = 0
    private var layerHi = 0
    private var layerAngle = 0f

    private var animActive = false
    private var animFrom = 0f
    private var animTo = 0f
    private var animStartNs = 0L
    private var animDurNs = 1L
    private var animMove = 0
    private var animKind = K_USER
    private var animEaseOut = true

    private val queue = MoveList(64)
    private var queuePos = 0
    private var queueActive = false
    private var queueKind = K_USER
    private var queueMoveNs = 0L

    private var hintActive = false
    private var hintMove = 0
    private var hintStartNs = 0L
    private var hintSolution: IntArray? = null
    private var hintIndex = 0
    private var hintVersion = -1
    private var solverBusy = false
    private var solverFailed = false

    private var touchMode = T_NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var hitFace = 0
    private val hit = FloatArray(3)
    private var dragDirX = 0f
    private var dragDirY = 0f
    private var dragPxPerRad = 1f
    private var dragSign = 1f
    private val sampleT = LongArray(SAMPLES)
    private val sampleA = FloatArray(SAMPLES)
    private var sampleCount = 0
    private var sampleHead = 0
    private var velX = 0f
    private var velY = 0f
    private var lastMoveNs = 0L
    private var inertia = false
    private var lastTapNs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var mtX = 0f
    private var mtY = 0f
    private var mtDist = 0f
    private var mtAngle = 0f
    private var density = 1f

    private var resetActive = false
    private var resetStartNs = 0L
    private var zoomFrom = 1f
    private var spinActive = false
    private var spinStartNs = 0L
    private var spinAngle = 0f
    private var insetActive = false
    private var insetStartNs = 0L
    private val insetFrom = FloatArray(4)
    private val insetTo = FloatArray(4)
    private var lastFrameNs = 0L
    private var lastInteractionNs = System.nanoTime()
    private var pendingEvents = 0

    private val palette = FloatArray(18)
    private var paletteVersion = 0
    private val body = FloatArray(3)
    private val background = FloatArray(9)
    private var tileStyle = 0

    private var inspectionEnabled = false
    private var inspecting = false
    private var inspectStartNs = 0L

    private val v3 = FloatArray(3)
    private val v3b = FloatArray(3)
    private val sp0 = FloatArray(2)
    private val sp1 = FloatArray(2)
    private val rnd = Random()

    init {
        // Three-quarter view showing the U, F and R faces.
        defaultView.setAxisAngle(0f, 1f, 0f, Math.toRadians(DEFAULT_YAW).toFloat())
        tq.setAxisAngle(1f, 0f, 0f, Math.toRadians(DEFAULT_TILT).toFloat())
        defaultView.preMultiply(tq)
        view.set(defaultView)
    }

    // ------------------------------------------------------------------ configuration

    fun setDensity(d: Float) = synchronized(lock) { density = d }

    fun setAnimSpeed(speed: Int) = synchronized(lock) { animSpeed = speed }

    fun setStyle(colors: IntArray, bodyColor: Int) = synchronized(lock) {
        for (i in 0 until 6) {
            palette[3 * i] = (colors[i] shr 16 and 0xFF) / 255f
            palette[3 * i + 1] = (colors[i] shr 8 and 0xFF) / 255f
            palette[3 * i + 2] = (colors[i] and 0xFF) / 255f
        }
        paletteVersion++
        body[0] = (bodyColor shr 16 and 0xFF) / 255f
        body[1] = (bodyColor shr 8 and 0xFF) / 255f
        body[2] = (bodyColor and 0xFF) / 255f
    }

    /** 0 = stickerless tiles, 1 = classic stickers. Rebuilds the cube geometry. */
    fun setTileStyle(style: Int) = synchronized(lock) {
        if (style != tileStyle) {
            tileStyle = style
            geometryVersion++
        }
    }

    /** Background gradient (top, bottom) and the glow behind the cube, as 0xRRGGBB. */
    fun setBackground(colors: IntArray) = synchronized(lock) {
        for (i in 0 until 3) {
            background[3 * i] = (colors[i] shr 16 and 0xFF) / 255f
            background[3 * i + 1] = (colors[i] shr 8 and 0xFF) / 255f
            background[3 * i + 2] = (colors[i] and 0xFF) / 255f
        }
    }

    /** WCA-style inspection: after a scramble the player gets 15 s before the timer starts. */
    fun setInspection(enabled: Boolean) = synchronized(lock) {
        inspectionEnabled = enabled
        if (!enabled && inspecting) {
            inspecting = false
            emit(EV_HUD)
            flush()
        }
    }

    fun setSurfaceSize(w: Int, h: Int) = synchronized(lock) {
        camera.setSize(w, h)
        camera.update()
    }

    /** Screen space (px) covered by UI on each side; the cube is fitted into the rest. */
    fun setInsets(left: Float, top: Float, right: Float, bottom: Float, animate: Boolean) = synchronized(lock) {
        if (left == insetTo[0] && top == insetTo[1] && right == insetTo[2] && bottom == insetTo[3]) return@synchronized
        insetTo[0] = left
        insetTo[1] = top
        insetTo[2] = right
        insetTo[3] = bottom
        if (!animate || camera.width <= 1) {
            insetActive = false
            applyInsets(1f)
        } else {
            insetFrom[0] = camera.insetLeft
            insetFrom[1] = camera.insetTop
            insetFrom[2] = camera.insetRight
            insetFrom[3] = camera.insetBottom
            insetStartNs = System.nanoTime()
            insetActive = true
        }
    }

    private fun applyInsets(t: Float) {
        if (t >= 1f) {
            camera.insetLeft = insetTo[0]
            camera.insetTop = insetTo[1]
            camera.insetRight = insetTo[2]
            camera.insetBottom = insetTo[3]
        } else {
            camera.insetLeft = insetFrom[0] + (insetTo[0] - insetFrom[0]) * t
            camera.insetTop = insetFrom[1] + (insetTo[1] - insetFrom[1]) * t
            camera.insetRight = insetFrom[2] + (insetTo[2] - insetFrom[2]) * t
            camera.insetBottom = insetFrom[3] + (insetTo[3] - insetFrom[3]) * t
        }
        camera.update()
    }

    fun setScreen(s: Int) = synchronized(lock) {
        if (screen == s) return@synchronized
        screen = s
        lastInteractionNs = System.nanoTime()
        startReset(lastInteractionNs)
        if (s == SCREEN_GAME) {
            resumeTimer()
            startInspectionIfReady()
        } else {
            pauseTimer()
            hintActive = false
            inspecting = false
        }
        emit(EV_HUD)
        flush()
    }

    fun toggleLock(): Boolean = synchronized(lock) {
        locked = !locked
        if (locked) inertia = false
        emit(EV_HUD)
        flush()
        locked
    }

    // ------------------------------------------------------------------ state & persistence

    fun load(size: Int, save: GameSave?) = synchronized(lock) {
        stopEverything()
        lastInteractionNs = System.nanoTime()
        if (size != n) {
            n = size
            cube = CubeState(size)
            geometryVersion++
        }
        cube.reset()
        scrambleMoves.clear()
        userMoves.clear()
        redoMoves.clear()
        solvedMark = -1
        status = ST_FREE
        assisted = false
        timerRunning = false
        timerAccumMs = 0L
        inspecting = false
        if (save != null && save.n == size && save.colors.size == cube.size) {
            save.colors.copyInto(cube.colors)
            scrambleMoves.setAll(save.scramble)
            userMoves.setAll(save.user)
            redoMoves.setAll(save.redo)
            solvedMark = if (save.solvedMark in 0..userMoves.size) save.solvedMark else -1
            status = if (save.status == ST_READY || save.status == ST_SOLVING) save.status else ST_FREE
            if (cube.isSolved) status = ST_FREE
            assisted = save.assisted
            timerAccumMs = save.elapsedMs
        }
        colorsVersion++
        resetHint()
        emit(EV_HUD)
        flush()
    }

    /** Finishes any animation and returns the state to persist. */
    fun snapshot(): GameSave = synchronized(lock) {
        completeAll(System.nanoTime())
        flush()
        buildSave()
    }

    /** State to persist, or null while something is moving (so colors and history agree). */
    fun snapshotIfIdle(): GameSave? = synchronized(lock) {
        if (animActive || queueActive || touchMode == T_LAYER) null else buildSave()
    }

    private fun buildSave(): GameSave =
        GameSave(
            n = n,
            colors = cube.colors.copyOf(),
            scramble = scrambleMoves.toIntArray(),
            user = userMoves.toIntArray(),
            redo = redoMoves.toIntArray(),
            status = status,
            assisted = assisted,
            elapsedMs = elapsedMs(),
            solvedMark = solvedMark,
        )

    fun hasProgress(): Boolean = synchronized(lock) { !cube.isSolved || status == ST_SCRAMBLING || status == ST_READY }

    fun hud(out: Hud) = synchronized(lock) {
        out.n = n
        out.moves = userMoves.size
        out.status = status
        val busyQueue = queueActive && (queueKind == K_SOLVE || queueKind == K_SCRAMBLE)
        out.canUndo = !userMoves.isEmpty && status != ST_SCRAMBLING && !busyQueue && !solverBusy
        out.canRedo = !redoMoves.isEmpty && status != ST_SCRAMBLING && !busyQueue && !solverBusy
        out.solved = cube.isSolved
        out.hintMove = if (hintActive) hintMove else -1
        out.solverBusy = solverBusy
        out.solverFailed = solverFailed
        out.autoSolving = queueActive && queueKind == K_SOLVE
        out.queueDone = queuePos
        out.queueTotal = queue.size
        out.timerRunning = timerRunning
        out.inspecting = inspecting
        out.locked = locked
        out.hintFaceColor = -1
        if (hintActive && n == 3) {
            val lo = Move.lo(hintMove)
            if (lo == Move.hi(hintMove) && (lo == 0 || lo == n - 1)) {
                val positive = lo == n - 1
                val face = CubeState.faceFor(Move.axis(hintMove), if (positive) 1 else -1)
                out.hintFaceColor = cube.colors[face * 9 + 4].toInt()
                val t = Move.turns(hintMove)
                out.hintDir = when {
                    t == 2 -> 2
                    t == (if (positive) 3 else 1) -> 1
                    else -> 0
                }
            }
        }
    }

    // ------------------------------------------------------------------ timer

    fun elapsedMs(): Long {
        val acc = timerAccumMs
        return if (timerRunning) acc + (System.nanoTime() - timerStartNs) / 1_000_000L else acc
    }

    fun isTimerRunning(): Boolean = timerRunning

    fun pauseTimer() = synchronized(lock) {
        inspecting = false
        if (timerRunning) {
            timerAccumMs += (System.nanoTime() - timerStartNs) / 1_000_000L
            timerRunning = false
        }
    }

    fun resumeTimer() = synchronized(lock) {
        if (status == ST_SOLVING && !timerRunning && screen == SCREEN_GAME) {
            timerStartNs = System.nanoTime()
            timerRunning = true
        }
        startInspectionIfReady()
        flush()
    }

    /** Milliseconds of inspection left, or -1 when not inspecting. Starts the timer at zero. */
    fun tickInspection(): Long = synchronized(lock) {
        if (!inspecting) return@synchronized -1L
        val left = INSPECTION_MS - (System.nanoTime() - inspectStartNs) / 1_000_000L
        if (left <= 0L) {
            startTimer()
            flush()
            return@synchronized -1L
        }
        left
    }

    private fun startInspectionIfReady() {
        if (inspectionEnabled && status == ST_READY && screen == SCREEN_GAME && !inspecting) {
            inspecting = true
            inspectStartNs = System.nanoTime()
            emit(EV_HUD)
        }
    }

    private fun startTimer() {
        inspecting = false
        timerAccumMs = 0L
        timerStartNs = System.nanoTime()
        timerRunning = true
        status = ST_SOLVING
        emit(EV_HUD)
    }

    // ------------------------------------------------------------------ commands

    fun newGame() = synchronized(lock) {
        stopEverything()
        inspecting = false
        cube.reset()
        scrambleMoves.clear()
        userMoves.clear()
        redoMoves.clear()
        solvedMark = -1
        Scrambler.generate(n, rnd, scrambleMoves)
        assisted = false
        timerRunning = false
        timerAccumMs = 0L
        resetHint()
        val count = scrambleMoves.size
        val perMs = (1600f / count).coerceIn(30f, 80f)
        val maxAnimated = (2600f / perMs).toInt()
        val instant = if (animSpeed == SPEED_INSTANT) count else max(0, count - maxAnimated)
        for (i in 0 until instant) cube.apply(scrambleMoves[i])
        colorsVersion++
        if (instant >= count) {
            status = ST_READY
            startInspectionIfReady()
            emit(EV_HUD or EV_SCRAMBLED)
        } else {
            status = ST_SCRAMBLING
            queueActive = true
            queueKind = K_SCRAMBLE
            queue.clear()
            queuePos = 0
            for (i in instant until count) queue.add(scrambleMoves[i])
            queueMoveNs = (perMs * 1e6f).toLong()
            emit(EV_HUD)
        }
        flush()
    }

    fun undo() = synchronized(lock) {
        if (!historyAvailable()) return@synchronized
        completeAll(System.nanoTime())
        if (userMoves.isEmpty) return@synchronized
        val m = userMoves.removeLast()
        redoMoves.add(m)
        if (solvedMark > userMoves.size) solvedMark = -1
        enqueue(K_UNDO, Move.inverse(m), queuedMoveNs())
        emit(EV_HUD)
        flush()
    }

    fun redo() = synchronized(lock) {
        if (!historyAvailable()) return@synchronized
        completeAll(System.nanoTime())
        if (redoMoves.isEmpty) return@synchronized
        val m = redoMoves.removeLast()
        userMoves.add(m)
        enqueue(K_REDO, m, queuedMoveNs())
        emit(EV_HUD)
        flush()
    }

    /**
     * 2x2/3x3: asks the solver and plays its solution. Bigger cubes: rewinds the move history.
     * Returns false when there is nothing to do.
     */
    fun autoSolve(): Boolean = synchronized(lock) {
        if (cube.isSolved || status == ST_SCRAMBLING || solverBusy || (queueActive && queueKind == K_SOLVE)) {
            return@synchronized false
        }
        completeAll(System.nanoTime())
        if (cube.isSolved) return@synchronized false
        hintActive = false
        hintSolution = null
        assisted = true
        // Auto-solving ends the timed attempt (the UI asks for confirmation first).
        pauseTimer()
        if (status == ST_READY || status == ST_SOLVING) status = ST_FREE
        if (SolverService.supports(n)) {
            solverBusy = true
            solverFailed = false
            val version = colorsVersion
            val size = n
            SolverService.solve(size, cube.colors, 700L) { sol -> onSolution(sol, version, size, false) }
        } else {
            val list = rewindMoves()
            if (list.isEmpty) {
                solverFailed = true
            } else {
                queueActive = true
                queueKind = K_SOLVE
                queue.clear()
                queuePos = 0
                for (i in 0 until list.size) queue.add(list[i])
                val perMs = (6500f / list.size).coerceIn(25f, queuedMoveMs().coerceAtLeast(25f))
                queueMoveNs = if (animSpeed == SPEED_INSTANT) 0L else (perMs * 1e6f).toLong()
            }
        }
        emit(EV_HUD)
        flush()
        true
    }

    /** First tap shows the next solver move; a second tap plays it. 2x2 and 3x3 only. */
    fun requestHint() = synchronized(lock) {
        if (!SolverService.supports(n) || status == ST_SCRAMBLING || solverBusy) return@synchronized
        if (queueActive && queueKind == K_SOLVE) return@synchronized
        val now = System.nanoTime()
        if (hintActive) {
            val m = hintMove
            completeAll(now)
            if (!hintActive) return@synchronized
            hintActive = false
            if (status == ST_READY) startTimer()
            enqueue(K_HINT, m, queuedMoveNs())
            emit(EV_HUD)
            flush()
            return@synchronized
        }
        completeAll(now)
        solverFailed = false
        if (cube.isSolved) {
            emit(EV_HUD)
            flush()
            return@synchronized
        }
        assisted = true
        val sol = hintSolution
        if (sol != null && hintVersion == colorsVersion && hintIndex < sol.size) {
            showHint(sol[hintIndex], now)
        } else {
            solverBusy = true
            val version = colorsVersion
            val size = n
            SolverService.solve(size, cube.colors, 250L) { s -> onSolution(s, version, size, true) }
        }
        emit(EV_HUD)
        flush()
    }

    fun resetView() = synchronized(lock) { startReset(System.nanoTime()) }

    private fun historyAvailable(): Boolean =
        status != ST_SCRAMBLING && !solverBusy && !(queueActive && queueKind == K_SOLVE)

    private fun onSolution(sol: IntArray?, version: Int, size: Int, forHint: Boolean) {
        synchronized(lock) {
            solverBusy = false
            if (size != n || version != colorsVersion) {
                emit(EV_HUD)
                flush()
                return@synchronized
            }
            if (sol == null) {
                solverFailed = true
            } else if (forHint) {
                hintSolution = sol
                hintIndex = 0
                hintVersion = version
                if (sol.isNotEmpty()) showHint(sol[0], System.nanoTime())
            } else if (sol.isNotEmpty()) {
                queueActive = true
                queueKind = K_SOLVE
                queue.clear()
                queuePos = 0
                for (m in sol) queue.add(m)
                queueMoveNs = queuedMoveNs()
            }
            emit(EV_HUD)
            flush()
        }
        listener.requestRender()
    }

    private fun showHint(m: Int, now: Long) {
        hintActive = true
        hintMove = m
        hintStartNs = now
        emit(EV_HUD)
    }

    private fun resetHint() {
        hintActive = false
        hintSolution = null
        hintIndex = 0
        hintVersion = -1
        solverFailed = false
    }

    /** Inverse of the history since the cube was last solved, with adjacent turns merged. */
    private fun rewindMoves(): MoveList {
        val out = MoveList(scrambleMoves.size + userMoves.size + 1)
        val from = if (solvedMark >= 0) solvedMark else 0
        for (i in userMoves.size - 1 downTo from) pushInverse(out, userMoves[i])
        if (solvedMark < 0) for (i in scrambleMoves.size - 1 downTo 0) pushInverse(out, scrambleMoves[i])
        return out
    }

    private fun pushInverse(out: MoveList, m: Int) {
        val inv = Move.inverse(m)
        if (!out.isEmpty && Move.sameSlice(out.last(), inv)) {
            val t = (Move.turns(out.last()) + Move.turns(inv)) and 3
            out.removeLast()
            if (t != 0) out.add(Move.withTurns(inv, t))
        } else {
            out.add(inv)
        }
    }

    // ------------------------------------------------------------------ frame update (GL thread)

    /** Advances animations and fills [out]. Returns true while another frame is needed. */
    fun update(now: Long, out: FrameData): Boolean {
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNs = now
        var active = false

        if (insetActive) {
            val t = ((now - insetStartNs) / 380e6f).coerceIn(0f, 1f)
            applyInsets(if (t >= 1f) 1f else easeInOut(t))
            if (t >= 1f) insetActive = false
            active = true
        }

        if (resetActive) {
            val t = ((now - resetStartNs) / 480e6f).coerceIn(0f, 1f)
            val e = easeInOut(t)
            view.slerp(resetFrom, defaultView, e)
            camera.zoom = zoomFrom + (1f - zoomFrom) * e
            camera.update()
            if (t >= 1f) resetActive = false
            active = true
        } else if (inertia) {
            rotateViewBy(velX * dt, velY * dt)
            val decay = exp(-4.5f * dt)
            velX *= decay
            velY *= decay
            if (hypot(velX, velY) < 20f * density) inertia = false
            active = true
        } else if (screen == SCREEN_MENU && touchMode == T_NONE) {
            // Turntable: spin about the cube's own (tilted) vertical axis. It eases to a stop after
            // a while without interaction so an idle menu stops rendering (and draining battery).
            val idle = (now - lastInteractionNs) / 1e9f
            val speed = ((MENU_SPIN_SECONDS - idle) / 2f).coerceIn(0f, 1f)
            if (speed > 0f) {
                tq.setAxisAngle(0f, TURNTABLE_Y, TURNTABLE_Z, 0.42f * speed * dt)
                view.preMultiply(tq).normalize()
                active = true
            }
        }

        if (spinActive) {
            val t = ((now - spinStartNs) / 1300e6f).coerceIn(0f, 1f)
            spinAngle = TWO_PI * easeOutCubic(t)
            if (t >= 1f) {
                spinActive = false
                spinAngle = 0f
            }
            active = true
        }

        if (animActive) {
            val t = if (animDurNs <= 0L) 1f else ((now - animStartNs).toFloat() / animDurNs).coerceIn(0f, 1f)
            val e = if (animEaseOut) easeOutCubic(t) else easeInOut(t)
            layerAngle = animFrom + (animTo - animFrom) * e
            if (t >= 1f) finishAnim()
            active = true
        }
        if (!animActive && queueActive && touchMode != T_LAYER) {
            var guard = 0
            while (!animActive && queueActive && queuePos < queue.size && guard++ < 4096) {
                startNextQueued(now)
                if (animDurNs <= 0L) finishAnim()
            }
            if (!animActive && queueActive && queuePos >= queue.size) endQueue()
            active = true
        }
        if (!animActive && touchMode != T_LAYER) {
            if (hintActive && touchMode == T_NONE) {
                // Nudge the hinted layer in its turning direction so the hint reads at a glance.
                layerAxis = Move.axis(hintMove)
                layerLo = Move.lo(hintMove)
                layerHi = Move.hi(hintMove)
                val phase = ((now - hintStartNs) / 1e9f) % 1.6f
                val dir = if (Move.turns(hintMove) == 3) -1f else 1f
                layerAngle = if (phase < 0.7f) dir * 0.32f * sin(phase / 0.7f * PI.toFloat()) else 0f
                active = true
            } else {
                layerAxis = -1
                layerAngle = 0f
            }
        }

        fill(out)
        flush()
        return active
    }

    private fun fill(out: FrameData) {
        out.n = n
        out.geometryVersion = geometryVersion
        camera.viewProj.copyInto(out.viewProj)
        tq.set(view)
        if (spinActive) {
            tq2.setAxisAngle(0f, 1f, 0f, spinAngle)
            tq.preMultiply(tq2)
        }
        tq.toMatrix(out.model)
        val s = 2f / n
        for (i in 0 until 3) {
            out.model[i] *= s
            out.model[4 + i] *= s
            out.model[8 + i] *= s
        }
        out.eye[0] = 0f
        out.eye[1] = 0f
        out.eye[2] = camera.distance
        out.shiftX = camera.shiftX
        out.shiftY = camera.shiftY
        out.aspect = camera.width.toFloat() / camera.height
        out.animAxis = layerAxis
        out.animLo = layerLo
        out.animHi = layerHi
        out.animAngle = layerAngle
        if (hintActive) {
            out.hintAxis = Move.axis(hintMove)
            out.hintLo = Move.lo(hintMove)
            out.hintHi = Move.hi(hintMove)
            out.hintAmount = 1f
        } else {
            out.hintAxis = -1
            out.hintAmount = 0f
        }
        if (out.colorsVersion != colorsVersion) {
            if (out.colors.size != cube.size) out.colors = ByteArray(cube.size)
            cube.colors.copyInto(out.colors)
            out.colorsVersion = colorsVersion
        }
        if (out.paletteVersion != paletteVersion) {
            palette.copyInto(out.palette)
            out.paletteVersion = paletteVersion
        }
        body.copyInto(out.body)
        background.copyInto(out.background)
        out.tileStyle = tileStyle
    }

    // ------------------------------------------------------------------ animation & queue

    private fun startNextQueued(now: Long) {
        val m = queue[queuePos++]
        layerAxis = Move.axis(m)
        layerLo = Move.lo(m)
        layerHi = Move.hi(m)
        layerAngle = 0f
        val t = Move.turns(m)
        animFrom = 0f
        animTo = when (t) {
            1 -> HALF_PI
            2 -> PI.toFloat()
            else -> -HALF_PI
        }
        animMove = m
        animKind = queueKind
        animEaseOut = false
        animStartNs = now
        animDurNs = if (t == 2) queueMoveNs * 3 / 2 else queueMoveNs
        animActive = true
    }

    private fun finishAnim() {
        animActive = false
        val m = animMove
        val kind = animKind
        layerAxis = -1
        layerAngle = 0f
        if (Move.turns(m) != 0) commit(m, kind)
        if (kind != K_USER && queueActive && queuePos >= queue.size) endQueue()
    }

    private fun enqueue(kind: Int, m: Int, durNs: Long) {
        if (!queueActive) {
            queueActive = true
            queueKind = kind
            queue.clear()
            queuePos = 0
        }
        queue.add(m)
        queueMoveNs = durNs
    }

    private fun endQueue() {
        val kind = queueKind
        queueActive = false
        queue.clear()
        queuePos = 0
        if (kind == K_SCRAMBLE) {
            status = ST_READY
            startInspectionIfReady()
            emit(EV_SCRAMBLED)
        }
        emit(EV_HUD)
    }

    /** Instantly completes the running animation and every queued move. */
    private fun completeAll(now: Long) {
        if (touchMode == T_LAYER) {
            layerAxis = -1
            layerAngle = 0f
            touchMode = T_IGNORE
        }
        var guard = 0
        while (guard++ < 100_000) {
            if (animActive) {
                finishAnim()
            } else if (queueActive && queuePos < queue.size) {
                startNextQueued(now)
            } else {
                if (queueActive) endQueue()
                break
            }
        }
    }

    private fun stopEverything() {
        animActive = false
        queueActive = false
        queue.clear()
        queuePos = 0
        layerAxis = -1
        layerAngle = 0f
        if (touchMode != T_NONE) touchMode = T_IGNORE
        spinActive = false
        spinAngle = 0f
        inertia = false
        hintActive = false
        solverBusy = false
    }

    private fun commit(m: Int, kind: Int) {
        cube.apply(m)
        colorsVersion++
        when (kind) {
            K_USER, K_HINT -> {
                userMoves.add(m)
                redoMoves.clear()
                emit(EV_MOVE_USER)
                // A manual move invalidates a solve that is waiting to play.
                if (kind == K_USER && queueActive && queueKind == K_SOLVE) {
                    queue.truncate(queuePos)
                    endQueue()
                }
            }
            K_SOLVE -> {
                userMoves.add(m)
                redoMoves.clear()
                emit(EV_MOVE_AUTO)
            }
            else -> emit(EV_MOVE_AUTO)
        }
        // Keep the cached hint solution while the player follows it.
        val sol = hintSolution
        if (sol != null && hintVersion == colorsVersion - 1 && hintIndex < sol.size && sol[hintIndex] == m) {
            hintIndex++
            hintVersion = colorsVersion
        }
        hintActive = false
        emit(EV_HUD)
        if (kind != K_SCRAMBLE && cube.isSolved) {
            solvedMark = userMoves.size
            if (kind != K_UNDO && kind != K_REDO) onCubeSolved(kind)
        }
    }

    private fun onCubeSolved(kind: Int) {
        val timed = status == ST_SOLVING || status == ST_READY
        if (!timed && kind != K_SOLVE) return
        pauseTimer()
        lastSolveMs = elapsedMs()
        lastSolveMoves = userMoves.size
        lastSolveTimed = timed
        lastSolveAssisted = assisted
        status = ST_FREE
        spinActive = true
        spinStartNs = System.nanoTime()
        spinAngle = 0f
        emit(EV_SOLVED or EV_HUD)
    }

    private fun queuedMoveMs(): Float = when (animSpeed) {
        SPEED_SLOW -> 420f
        SPEED_FAST -> 170f
        SPEED_INSTANT -> 0f
        else -> 270f
    }

    private fun queuedMoveNs(): Long = (queuedMoveMs() * 1e6f).toLong()

    private fun snapMs(): Float = when (animSpeed) {
        SPEED_SLOW -> 240f
        SPEED_FAST -> 100f
        SPEED_INSTANT -> 45f
        else -> 160f
    }

    // ------------------------------------------------------------------ input (main thread)

    fun onTouch(e: MotionEvent) = synchronized(lock) {
        val now = System.nanoTime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e.x, e.y, now)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e, now)
            MotionEvent.ACTION_MOVE -> onMove(e, now)
            MotionEvent.ACTION_POINTER_UP -> if (touchMode == T_MULTI) touchMode = T_IGNORE
            MotionEvent.ACTION_UP -> onUp(e.x, e.y, now)
            MotionEvent.ACTION_CANCEL -> {
                if (touchMode == T_LAYER) releaseLayer(now, true)
                touchMode = T_NONE
            }
        }
        flush()
    }

    private fun onDown(x: Float, y: Float, now: Long) {
        lastInteractionNs = now
        inertia = false
        resetActive = false
        foldSpin()
        // Settle whatever is moving so the new gesture starts from a resting cube.
        if (animActive && animKind == K_USER) finishAnim()
        if (queueActive) {
            when (queueKind) {
                K_SOLVE -> {
                    queue.truncate(queuePos)
                    if (animActive) finishAnim() else endQueue()
                }
                K_UNDO, K_REDO, K_HINT -> completeAll(now)
                else -> {} // the scramble keeps playing; only the view can be rotated meanwhile
            }
        }
        downX = x
        downY = y
        lastX = x
        lastY = y
        lastMoveNs = now
        velX = 0f
        velY = 0f
        val canTurn = screen == SCREEN_GAME && status != ST_SCRAMBLING && !animActive
        touchMode = if (canTurn && raycast(x, y)) T_PENDING_LAYER else T_PENDING_VIEW
    }

    private fun onPointerDown(e: MotionEvent, now: Long) {
        if (touchMode == T_LAYER) releaseLayer(now, true)
        if (e.pointerCount >= 2) {
            touchMode = T_MULTI
            val x0 = e.getX(0)
            val y0 = e.getY(0)
            val x1 = e.getX(1)
            val y1 = e.getY(1)
            mtX = (x0 + x1) * 0.5f
            mtY = (y0 + y1) * 0.5f
            mtDist = hypot(x1 - x0, y1 - y0)
            mtAngle = atan2(y1 - y0, x1 - x0)
        }
    }

    private fun onMove(e: MotionEvent, now: Long) {
        when (touchMode) {
            T_PENDING_LAYER -> {
                lastX = e.x
                lastY = e.y
                if (hypot(lastX - downX, lastY - downY) > slop()) beginLayerDrag(now)
            }
            T_LAYER -> {
                lastX = e.x
                lastY = e.y
                updateLayerDrag(now)
            }
            T_PENDING_VIEW -> {
                if (hypot(e.x - downX, e.y - downY) > slop() * 0.5f) {
                    touchMode = T_VIEW
                    dragView(e.x, e.y, now)
                }
            }
            T_VIEW -> dragView(e.x, e.y, now)
            T_MULTI -> if (e.pointerCount >= 2) {
                val x0 = e.getX(0)
                val y0 = e.getY(0)
                val x1 = e.getX(1)
                val y1 = e.getY(1)
                val cx = (x0 + x1) * 0.5f
                val cy = (y0 + y1) * 0.5f
                val dist = hypot(x1 - x0, y1 - y0)
                val ang = atan2(y1 - y0, x1 - x0)
                rotateViewBy(cx - mtX, cy - mtY)
                if (mtDist > 20f && dist > 20f) {
                    camera.zoom = (camera.zoom * dist / mtDist).coerceIn(0.55f, 3f)
                    camera.update()
                }
                var da = ang - mtAngle
                if (da > PI) da -= TWO_PI
                if (da < -PI) da += TWO_PI
                if (!locked) {
                    tq.setAxisAngle(0f, 0f, 1f, -da)
                    view.preMultiply(tq).normalize()
                }
                mtX = cx
                mtY = cy
                mtDist = dist
                mtAngle = ang
            }
        }
    }

    private fun onUp(x: Float, y: Float, now: Long) {
        when (touchMode) {
            T_LAYER -> {
                lastX = x
                lastY = y
                updateLayerDrag(now)
                releaseLayer(now, false)
            }
            T_VIEW -> {
                if (!locked && now - lastMoveNs < 60_000_000L && hypot(velX, velY) > 150f * density) inertia = true
            }
            T_PENDING_VIEW -> {
                if (now - lastTapNs < 320_000_000L && hypot(x - lastTapX, y - lastTapY) < 60f * density) {
                    startReset(now)
                    lastTapNs = 0L
                } else {
                    lastTapNs = now
                    lastTapX = x
                    lastTapY = y
                }
            }
        }
        touchMode = T_NONE
    }

    private fun slop(): Float = 9f * density

    private fun dragView(x: Float, y: Float, now: Long) {
        val dx = x - lastX
        val dy = y - lastY
        rotateViewBy(dx, dy)
        val dtS = ((now - lastMoveNs) / 1e9f).coerceAtLeast(0.004f)
        velX = velX * 0.25f + dx / dtS * 0.75f
        velY = velY * 0.25f + dy / dtS * 0.75f
        lastX = x
        lastY = y
        lastMoveNs = now
    }

    private fun rotateViewBy(dx: Float, dy: Float) {
        if (locked) return
        val len = hypot(dx, dy)
        if (len < 1e-3f) return
        val angle = len * PI.toFloat() * 1.1f / min(camera.width, camera.height)
        tq.setAxisAngle(dy / len, dx / len, 0f, angle)
        view.preMultiply(tq).normalize()
    }

    private fun beginLayerDrag(now: Long) {
        val normalAxis = CubeState.FACE_AXIS[hitFace]
        val faceSign = CubeState.FACE_SIGN[hitFace].toFloat()
        val dx = lastX - downX
        val dy = lastY - downY
        val dl = hypot(dx, dy)
        var bestT = -1
        var bestCos = -1f
        var bestSx = 0f
        var bestSy = 0f
        var maxLen = 0f
        // Pick the in-face direction whose on-screen projection best matches the drag.
        for (t in 0..2) {
            if (t == normalAxis) continue
            projectHitOffset(t, -0.5f, sp0)
            projectHitOffset(t, 0.5f, sp1)
            val sx = sp1[0] - sp0[0]
            val sy = sp1[1] - sp0[1]
            val sl = hypot(sx, sy)
            if (sl > maxLen) maxLen = sl
            if (sl < 1e-4f) continue
            val c = abs(sx * dx + sy * dy) / (sl * dl)
            if (c > bestCos) {
                bestCos = c
                bestT = t
                bestSx = sx
                bestSy = sy
            }
        }
        if (bestT < 0) {
            touchMode = T_IGNORE
            return
        }
        val axis = 3 - normalAxis - bestT
        val half = n * 0.5f
        val layer = floor(hit[axis] + half).toInt().coerceIn(0, n - 1)
        val sl = hypot(bestSx, bestSy)
        dragDirX = bestSx / sl
        dragDirY = bestSy / sl
        // Keep the touched sticker under the finger: turning by θ moves the face plane (n/2 cube
        // units from the axis) by θ·n/2 units, and one unit spans `sl` pixels on screen. The floor
        // avoids hyper-sensitive turns when the face is seen almost edge-on.
        dragPxPerRad = max(sl, maxLen * 0.45f) * half
        // Sign: a positive rotation about `axis` moves the touched face along +bestT when
        // (axis, normalAxis, bestT) is an even permutation, flipped for faces on the negative side.
        dragSign = faceSign * if ((axis + 1) % 3 == normalAxis) 1f else -1f
        layerAxis = axis
        layerLo = layer
        layerHi = layer
        layerAngle = 0f
        sampleCount = 0
        touchMode = T_LAYER
        if (status == ST_READY) startTimer() else if (status == ST_SOLVING && !timerRunning) resumeTimer()
        updateLayerDrag(now)
    }

    private fun updateLayerDrag(now: Long) {
        val s = (lastX - downX) * dragDirX + (lastY - downY) * dragDirY
        layerAngle = dragSign * s / dragPxPerRad
        sampleT[sampleHead] = now
        sampleA[sampleHead] = layerAngle
        sampleHead = (sampleHead + 1) % SAMPLES
        if (sampleCount < SAMPLES) sampleCount++
    }

    private fun releaseLayer(now: Long, cancel: Boolean) {
        var omega = 0f
        if (sampleCount >= 2) {
            val newest = (sampleHead - 1 + SAMPLES) % SAMPLES
            var oldest = newest
            for (k in 1 until sampleCount) {
                val idx = (newest - k + SAMPLES) % SAMPLES
                if (sampleT[newest] - sampleT[idx] > 100_000_000L) break
                oldest = idx
            }
            val dtS = (sampleT[newest] - sampleT[oldest]) / 1e9f
            if (dtS > 0.005f && now - sampleT[newest] < 80_000_000L) {
                omega = (sampleA[newest] - sampleA[oldest]) / dtS
            }
        }
        val cur = layerAngle / HALF_PI
        val base = round(cur)
        var target = if (cancel) base else round(cur + omega * 0.12f / HALF_PI)
        target = target.coerceIn(base - 1f, base + 1f)
        val turns = ((target.toInt() % 4) + 4) % 4
        animFrom = layerAngle
        animTo = target * HALF_PI
        val frac = min(1f, abs(animTo - animFrom) / HALF_PI)
        animDurNs = (snapMs() * (0.35f + 0.65f * frac) * 1e6f).toLong()
        animMove = Move.of(layerAxis, layerLo, layerHi, turns)
        animKind = K_USER
        animEaseOut = true
        animStartNs = now
        animActive = true
        touchMode = T_NONE
    }

    /** Projects (hit point + offset along local axis [t]) to screen pixels. */
    private fun projectHitOffset(t: Int, offset: Float, out: FloatArray) {
        val s = 2f / n
        val lx = (hit[0] + if (t == 0) offset else 0f) * s
        val ly = (hit[1] + if (t == 1) offset else 0f) * s
        val lz = (hit[2] + if (t == 2) offset else 0f) * s
        view.rotate(lx, ly, lz, v3)
        camera.project(v3[0], v3[1], v3[2], out)
    }

    /** Casts a ray through a screen point against the cube; fills [hit] and [hitFace]. */
    private fun raycast(sx: Float, sy: Float): Boolean {
        if (camera.width <= 1) return false
        val s = 2f / n
        camera.rayDirection(sx, sy, v3)
        // Camera at (0, 0, distance) in world space; bring the ray into cube units.
        view.rotateInverse(0f, 0f, camera.distance, v3b)
        val ox = v3b[0] / s
        val oy = v3b[1] / s
        val oz = v3b[2] / s
        view.rotateInverse(v3[0], v3[1], v3[2], v3b)
        val half = n * 0.5f
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE
        var axis = -1
        var sign = 0
        for (a in 0..2) {
            val o = when (a) { 0 -> ox; 1 -> oy; else -> oz }
            val d = v3b[a]
            if (abs(d) < 1e-9f) {
                if (o < -half || o > half) return false
                continue
            }
            var t1 = (-half - o) / d
            var t2 = (half - o) / d
            var sg = -1
            if (t1 > t2) {
                val tt = t1; t1 = t2; t2 = tt
                sg = 1
            }
            if (t1 > tMin) {
                tMin = t1
                axis = a
                sign = sg
            }
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return false
        }
        if (axis < 0 || tMax < 0f) return false
        hit[0] = ox + v3b[0] * tMin
        hit[1] = oy + v3b[1] * tMin
        hit[2] = oz + v3b[2] * tMin
        hitFace = CubeState.faceFor(axis, sign)
        return true
    }

    private fun startReset(now: Long) {
        foldSpin()
        inertia = false
        resetFrom.set(view)
        zoomFrom = camera.zoom
        resetStartNs = now
        resetActive = true
    }

    private fun foldSpin() {
        if (!spinActive) return
        tq2.setAxisAngle(0f, 1f, 0f, spinAngle)
        view.preMultiply(tq2).normalize()
        spinActive = false
        spinAngle = 0f
    }

    private fun emit(ev: Int) {
        pendingEvents = pendingEvents or ev
    }

    private fun flush() {
        val ev = pendingEvents
        if (ev != 0) {
            pendingEvents = 0
            listener.onGameEvents(ev)
        }
    }

    companion object {
        const val EV_MOVE_USER = 1
        const val EV_MOVE_AUTO = 2
        const val EV_HUD = 4
        const val EV_SOLVED = 8
        const val EV_SCRAMBLED = 16

        const val SCREEN_MENU = 0
        const val SCREEN_GAME = 1

        const val ST_FREE = 0
        const val ST_SCRAMBLING = 1
        const val ST_READY = 2
        const val ST_SOLVING = 3

        const val SPEED_SLOW = 0
        const val SPEED_NORMAL = 1
        const val SPEED_FAST = 2
        const val SPEED_INSTANT = 3

        const val MIN_SIZE = 2
        const val MAX_SIZE = 20

        private const val K_USER = 0
        private const val K_UNDO = 1
        private const val K_REDO = 2
        private const val K_SCRAMBLE = 3
        private const val K_SOLVE = 4
        private const val K_HINT = 5

        private const val T_NONE = 0
        private const val T_PENDING_LAYER = 1
        private const val T_LAYER = 2
        private const val T_PENDING_VIEW = 3
        private const val T_VIEW = 4
        private const val T_MULTI = 5
        private const val T_IGNORE = 6

        private const val SAMPLES = 8
        private const val DEFAULT_YAW = -36.0
        private const val DEFAULT_TILT = 27.0
        private const val MENU_SPIN_SECONDS = 25f
        private const val INSPECTION_MS = 15_000L
        private val TURNTABLE_Y = kotlin.math.cos(Math.toRadians(DEFAULT_TILT)).toFloat()
        private val TURNTABLE_Z = kotlin.math.sin(Math.toRadians(DEFAULT_TILT)).toFloat()
        private const val HALF_PI = (PI / 2).toFloat()
        private const val TWO_PI = (PI * 2).toFloat()

        private fun easeOutCubic(t: Float): Float {
            val u = 1f - t
            return 1f - u * u * u
        }

        private fun easeInOut(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    }
}
