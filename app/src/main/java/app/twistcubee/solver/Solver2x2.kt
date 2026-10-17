package app.twistcubee.solver

/**
 * Optimal 2x2x2 solver. The DBL corner is kept fixed, so only U, R and F turns are needed and the
 * whole state space (7! * 3^6 = 3,674,160 states) fits in a 3.6 MB distance table built by a
 * breadth-first search. Solving is then a greedy walk down the distance gradient (<= 11 moves).
 */
internal class Solver2x2 {
    private val permMove = ShortArray(N_PERM * N_MOVES)
    private val twistMove = ShortArray(N_TWIST * N_MOVES)
    private val dist = ByteArray(N_PERM * N_TWIST)
    private val lehmer = IntArray(7)

    init {
        build()
    }

    private fun build() {
        val moves = CubieCube.moveCube
        val c = CubieCube()
        for (i in 0 until N_PERM) {
            for (f in 0 until 3) {
                setPerm(c, i)
                for (p in 0 until 3) {
                    c.cornerMultiply(moves[f])
                    permMove[i * N_MOVES + 3 * f + p] = getPerm(c).toShort()
                }
            }
        }
        for (i in 0 until N_TWIST) {
            for (f in 0 until 3) {
                setTwist(c, i)
                for (p in 0 until 3) {
                    c.cornerMultiply(moves[f])
                    twistMove[i * N_MOVES + 3 * f + p] = getTwist(c).toShort()
                }
            }
        }
        dist.fill(-1)
        dist[0] = 0
        var done = 1
        var depth = 0
        val size = N_PERM * N_TWIST
        while (done < size) {
            val next = (depth + 1).toByte()
            for (s in 0 until size) {
                if (dist[s].toInt() != depth) continue
                val pBase = (s / N_TWIST) * N_MOVES
                val tBase = (s % N_TWIST) * N_MOVES
                for (m in 0 until N_MOVES) {
                    val ns = permMove[pBase + m] * N_TWIST + twistMove[tBase + m]
                    if (dist[ns].toInt() == -1) {
                        dist[ns] = next
                        done++
                    }
                }
            }
            depth++
        }
    }

    /**
     * Optimal solution as moves `3 * face + power - 1` with faces U, R, F (0..2). The cube's DBL
     * corner must be solved (in place and oriented).
     */
    fun solve(cube: CubieCube): IntArray {
        var perm = getPerm(cube)
        var twist = getTwist(cube)
        var d = dist[perm * N_TWIST + twist].toInt()
        val out = IntArray(d)
        var k = 0
        while (d > 0) {
            var found = false
            for (m in 0 until N_MOVES) {
                val np = permMove[perm * N_MOVES + m].toInt()
                val nt = twistMove[twist * N_MOVES + m].toInt()
                if (dist[np * N_TWIST + nt].toInt() == d - 1) {
                    out[k++] = m
                    perm = np
                    twist = nt
                    d--
                    found = true
                    break
                }
            }
            check(found)
        }
        return out
    }

    /** Lehmer rank of the 7 corners other than DBL. */
    private fun getPerm(c: CubieCube): Int {
        for (k in 0 until 7) lehmer[k] = squeeze(c.cp[POS[k]])
        var rank = 0
        for (i in 0 until 7) {
            var smaller = 0
            for (j in i + 1 until 7) if (lehmer[j] < lehmer[i]) smaller++
            rank = rank * (7 - i) + smaller
        }
        return rank
    }

    private fun setPerm(c: CubieCube, rank: Int) {
        c.reset()
        var r = rank
        val digits = lehmer
        for (i in 6 downTo 0) {
            digits[i] = r % (7 - i)
            r /= 7 - i
        }
        var used = 0
        for (i in 0 until 7) {
            // digits[i]-th unused value
            var count = digits[i]
            var v = 0
            while (true) {
                if (used and (1 shl v) == 0) {
                    if (count == 0) break
                    count--
                }
                v++
            }
            used = used or (1 shl v)
            c.cp[POS[i]] = if (v == 6) CubieCube.DRB else v
        }
        c.cp[CubieCube.DBL] = CubieCube.DBL
    }

    private fun getTwist(c: CubieCube): Int {
        var t = 0
        for (i in 0 until 6) t = 3 * t + c.co[i]
        return t
    }

    private fun setTwist(c: CubieCube, twist: Int) {
        c.reset()
        var t = twist
        var sum = 0
        for (i in 5 downTo 0) {
            c.co[i] = t % 3
            sum += c.co[i]
            t /= 3
        }
        c.co[CubieCube.DBL] = 0
        c.co[CubieCube.DRB] = (3 - sum % 3) % 3
    }

    private fun squeeze(corner: Int): Int = if (corner == CubieCube.DRB) 6 else corner

    companion object {
        private const val N_PERM = 5040
        private const val N_TWIST = 729
        private const val N_MOVES = 9

        /** Corner positions other than DBL. */
        private val POS = intArrayOf(0, 1, 2, 3, 4, 5, 7)
    }
}
