package app.twistcubee.solver

import app.twistcubee.model.CubeState
import app.twistcubee.model.Move
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the solver tables and runs every solve on a single low-priority background thread, so the
 * UI and GL threads never block. Tables are built lazily on first use (or via [prewarm]).
 *
 * Solutions are returned as packed [Move] ints ready to be queued on the cube.
 */
object SolverService {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cube-solver").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }
    }

    @Volatile private var tables: TwoPhaseTables? = null
    @Volatile private var solver2: Solver2x2? = null
    private var twoPhase: TwoPhaseSolver? = null

    fun supports(n: Int): Boolean = n == 2 || n == 3

    fun isReady(n: Int): Boolean = when (n) {
        2 -> solver2 != null
        3 -> tables != null
        else -> false
    }

    /** Starts building the tables for an [n]x[n] solver in the background. */
    fun prewarm(n: Int) {
        if (supports(n) && !isReady(n)) executor.execute { ensure(n) }
    }

    /**
     * Solves a copy of [colors] (an [n]x[n] sticker state) in the background and delivers the
     * result on the solver thread: a move list (empty when already solved) or null on failure.
     */
    fun solve(n: Int, colors: ByteArray, timeLimitMs: Long, callback: (IntArray?) -> Unit) {
        val copy = colors.copyOf()
        executor.execute {
            val result = try {
                solveNow(n, copy, timeLimitMs)
            } catch (t: Throwable) {
                null
            }
            callback(result)
        }
    }

    /** Synchronous solve; call from a background thread only. */
    fun solveNow(n: Int, colors: ByteArray, timeLimitMs: Long): IntArray? {
        ensure(n)
        return when (n) {
            2 -> solve2(colors)
            3 -> solve3(colors, timeLimitMs)
            else -> null
        }
    }

    @Synchronized
    private fun ensure(n: Int) {
        when (n) {
            2 -> if (solver2 == null) solver2 = Solver2x2()
            3 -> if (tables == null) {
                val t = TwoPhaseTables()
                twoPhase = TwoPhaseSolver(t)
                tables = t
            }
        }
    }

    private fun solve3(colors: ByteArray, timeLimitMs: Long): IntArray? {
        if (colors.size != 54) return null
        // Faces are identified by their (possibly moved) center stickers.
        val faceOfColor = IntArray(6) { -1 }
        for (f in 0 until 6) faceOfColor[colors[f * 9 + 4].toInt()] = f
        if (faceOfColor.any { it < 0 }) return null
        val facelets = IntArray(54) { faceOfColor[colors[it].toInt()] }
        val cube = CubieCube.fromFacelets(facelets) ?: return null
        val solver = synchronized(this) { twoPhase } ?: return null
        val sol = solver.solve(cube, TwoPhaseSolver.MAX_LENGTH, timeLimitMs) ?: return null
        return IntArray(sol.size) { Move.face(sol[it] / 3, sol[it] % 3 + 1, 3) }
    }

    private fun solve2(colors: ByteArray): IntArray? {
        if (colors.size != 24) return null
        // Expand to a 3x3 facelet layout using the 2x2 stickers as the corner facelets.
        val c3 = IntArray(54) { -1 }
        for (f in 0 until 6) for (r in 0 until 2) for (c in 0 until 2) {
            c3[f * 9 + (r * 2) * 3 + c * 2] = colors[f * 4 + r * 2 + c].toInt()
        }
        // A 2x2 has no centers: define the faces so that the DBL corner counts as solved.
        val dColor = c3[CubieCube.DBL_FACELETS[0]]
        val bColor = c3[CubieCube.DBL_FACELETS[1]]
        val lColor = c3[CubieCube.DBL_FACELETS[2]]
        val faceOfColor = IntArray(6) { -1 }
        faceOfColor[dColor] = CubeState.D
        faceOfColor[(dColor + 3) % 6] = CubeState.U
        faceOfColor[bColor] = CubeState.B
        faceOfColor[(bColor + 3) % 6] = CubeState.F
        faceOfColor[lColor] = CubeState.L
        faceOfColor[(lColor + 3) % 6] = CubeState.R
        if (faceOfColor.any { it < 0 }) return null
        val facelets = IntArray(54) { i ->
            val color = c3[i]
            // Centers and edges are virtual and always solved.
            if (color < 0) i / 9 else faceOfColor[color]
        }
        val cube = CubieCube.fromFacelets(facelets, validate = false) ?: return null
        if (!CubieCube.cornersValid(cube) || cube.cp[CubieCube.DBL] != CubieCube.DBL) return null
        val sol = requireNotNull(solver2).solve(cube)
        return IntArray(sol.size) { Move.face(sol[it] / 3, sol[it] % 3 + 1, 2) }
    }
}
