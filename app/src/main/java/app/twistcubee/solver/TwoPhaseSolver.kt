package app.twistcubee.solver

import kotlin.math.max
import kotlin.math.min

/**
 * Kociemba two-phase search (IDA* in both phases). Phase 1 brings the cube into the subgroup
 * G1 = <U, D, R2, F2, L2, B2>, phase 2 solves it inside G1. After the first solution is found the
 * search keeps looking for shorter ones until the time budget runs out, so typical results are
 * 19-22 moves.
 *
 * Not thread safe: use one instance per thread.
 */
internal class TwoPhaseSolver(private val t: TwoPhaseTables) {
    private val ax = IntArray(MAX_LENGTH + 2)
    private val po = IntArray(MAX_LENGTH + 2)

    private var flip0 = 0
    private var twist0 = 0
    private var slice0 = 0
    private var parity0 = 0
    private var urfToDlf0 = 0
    private var frToBr0 = 0
    private var urToUl0 = 0
    private var ubToDf0 = 0

    private var best: IntArray? = null
    private var bestLen = 0
    private var deadline = 0L
    private var nodes = 0
    private var abort = false

    /**
     * Returns moves numbered `3 * face + power - 1` (faces U R F D L B), an empty array for a
     * solved cube, or null when no solution of at most [maxLength] moves exists.
     */
    fun solve(cube: CubieCube, maxLength: Int = MAX_LENGTH, timeLimitMs: Long = 100): IntArray? {
        val limit = min(maxLength, MAX_LENGTH)
        flip0 = cube.flip
        twist0 = cube.twist
        frToBr0 = cube.frToBr
        slice0 = frToBr0 / 24
        parity0 = cube.cornerParity()
        urfToDlf0 = cube.urfToDlf
        urToUl0 = cube.urToUl
        ubToDf0 = cube.ubToDf
        best = null
        bestLen = limit + 1
        abort = false
        nodes = 0
        deadline = System.nanoTime() + timeLimitMs * 1_000_000L

        var depth = max(
            t.sliceFlipPrun[TwoPhaseTables.N_SLICE1 * flip0 + slice0].toInt(),
            t.sliceTwistPrun[TwoPhaseTables.N_SLICE1 * twist0 + slice0].toInt(),
        )
        while (depth < bestLen && !abort) {
            search1(0, flip0, twist0, slice0, depth)
            depth++
        }
        return best
    }

    private fun timeUp(): Boolean {
        if (++nodes and 0x3FF != 0 || best == null) return false
        if (System.nanoTime() > deadline) abort = true
        return abort
    }

    private fun search1(n: Int, flip: Int, twist: Int, slice: Int, togo: Int) {
        if (togo == 0) {
            // In G1 now; the last phase-1 move must not itself be a G1 move, otherwise this
            // phase-1 path was already examined at a smaller depth.
            if (flip == 0 && twist == 0 && slice == 0) {
                if (n == 0 || isPhase1Final(ax[n - 1], po[n - 1])) phase2(n)
            }
            return
        }
        if (timeUp()) return
        val tm = t.twistMove
        val fm = t.flipMove
        val sm = t.frToBrMove
        val twistPrun = t.sliceTwistPrun
        val flipPrun = t.sliceFlipPrun
        val prev = if (n > 0) ax[n - 1] else -10
        for (a in 0 until 6) {
            if (a == prev || a == prev - 3) continue
            for (p in 1..3) {
                val m = 3 * a + p - 1
                val nf = fm[flip * 18 + m].toInt()
                val nt = tm[twist * 18 + m].toInt()
                val ns = sm[slice * 432 + m] / 24
                val d = max(
                    flipPrun[TwoPhaseTables.N_SLICE1 * nf + ns].toInt(),
                    twistPrun[TwoPhaseTables.N_SLICE1 * nt + ns].toInt(),
                )
                if (d >= togo) continue
                ax[n] = a
                po[n] = p
                search1(n + 1, nf, nt, ns, togo - 1)
                if (abort) return
            }
        }
    }

    private fun phase2(n1: Int) {
        val maxD2 = min(bestLen - 1 - n1, MAX_PHASE2)
        if (maxD2 < 0) return
        var urf = urfToDlf0
        var fr = frToBr0
        var par = parity0
        var ul = urToUl0
        var ub = ubToDf0
        for (i in 0 until n1) {
            val m = 3 * ax[i] + po[i] - 1
            urf = t.urfToDlfMove[urf * 18 + m].toInt()
            fr = t.frToBrMove[fr * 18 + m].toInt()
            par = TwoPhaseTables.PARITY_MOVE[par * 18 + m]
            ul = t.urToUlMove[ul * 18 + m].toInt()
            ub = t.ubToDfMove[ub * 18 + m].toInt()
        }
        val d1 = t.sliceUrfToDlfParityPrun[(24 * urf + fr) * 2 + par].toInt()
        if (d1 > maxD2) return
        if (ul >= 336 || ub >= 336) return
        val ed = t.mergeUrToDf[336 * ul + ub].toInt()
        if (ed < 0) return
        val d2 = t.sliceUrToDfParityPrun[(24 * ed + fr) * 2 + par].toInt()
        if (d2 > maxD2) return
        for (d in max(d1, d2)..maxD2) {
            if (search2(n1, urf, fr, par, ed, d)) {
                val len = n1 + d
                best = IntArray(len) { 3 * ax[it] + po[it] - 1 }
                bestLen = len
                return
            }
            if (abort) return
        }
    }

    private fun search2(n: Int, urf: Int, fr: Int, par: Int, ed: Int, togo: Int): Boolean {
        if (togo == 0) return urf == 0 && fr == 0 && par == 0 && ed == 0
        if (timeUp()) return false
        val cornerPrun = t.sliceUrfToDlfParityPrun
        val edgePrun = t.sliceUrToDfParityPrun
        val prev = if (n > 0) ax[n - 1] else -10
        for (a in 0 until 6) {
            if (a == prev || a == prev - 3) continue
            val quarter = a == 0 || a == 3
            var p = if (quarter) 1 else 2
            val pEnd = if (quarter) 3 else 2
            while (p <= pEnd) {
                val m = 3 * a + p - 1
                val nUrf = t.urfToDlfMove[urf * 18 + m].toInt()
                val nFr = t.frToBrMove[fr * 18 + m].toInt()
                val nPar = TwoPhaseTables.PARITY_MOVE[par * 18 + m]
                val nEd = t.urToDfMove[ed * 18 + m].toInt()
                val d = max(
                    cornerPrun[(24 * nUrf + nFr) * 2 + nPar].toInt(),
                    edgePrun[(24 * nEd + nFr) * 2 + nPar].toInt(),
                )
                if (d < togo) {
                    ax[n] = a
                    po[n] = p
                    if (search2(n + 1, nUrf, nFr, nPar, nEd, togo - 1)) return true
                    if (abort) return false
                }
                p++
            }
        }
        return false
    }

    private fun isPhase1Final(axis: Int, power: Int): Boolean = axis != 0 && axis != 3 && power != 2

    companion object {
        const val MAX_LENGTH = 30
        private const val MAX_PHASE2 = 12
    }
}
