package app.twistcubee.solver

import app.twistcubee.model.CubeState
import app.twistcubee.model.Move

/**
 * 3x3 cube on the cubie level (Kociemba's representation): corner/edge permutation + orientation.
 *
 * Corners: URF UFL ULB UBR DFR DLF DBL DRB (0..7)
 * Edges:   UR UF UL UB DR DF DL DB FR FL BL BR (0..11)
 *
 * `cp[i]` is the corner sitting in position i. Multiplication `a.multiply(b)` applies b after a.
 * The coordinate getters/setters follow Kociemba's original two-phase implementation.
 */
internal class CubieCube {
    val cp = IntArray(8) { it }
    val co = IntArray(8)
    val ep = IntArray(12) { it }
    val eo = IntArray(12)

    private val tp = IntArray(12)
    private val tor = IntArray(12)
    private val perm = IntArray(12)

    fun reset(): CubieCube {
        for (i in 0 until 8) { cp[i] = i; co[i] = 0 }
        for (i in 0 until 12) { ep[i] = i; eo[i] = 0 }
        return this
    }

    fun copyFrom(o: CubieCube): CubieCube {
        o.cp.copyInto(cp); o.co.copyInto(co); o.ep.copyInto(ep); o.eo.copyInto(eo)
        return this
    }

    fun cornerMultiply(b: CubieCube) {
        for (i in 0 until 8) {
            val src = b.cp[i]
            tp[i] = cp[src]
            tor[i] = (co[src] + b.co[i]) % 3
        }
        for (i in 0 until 8) { cp[i] = tp[i]; co[i] = tor[i] }
    }

    fun edgeMultiply(b: CubieCube) {
        for (i in 0 until 12) {
            val src = b.ep[i]
            tp[i] = ep[src]
            tor[i] = (eo[src] + b.eo[i]) and 1
        }
        for (i in 0 until 12) { ep[i] = tp[i]; eo[i] = tor[i] }
    }

    fun multiply(b: CubieCube) {
        cornerMultiply(b)
        edgeMultiply(b)
    }

    // ---------------------------------------------------------------- orientation coordinates

    /** Corner twist, 0 <= twist < 3^7. */
    var twist: Int
        get() {
            var r = 0
            for (i in 0 until 7) r = 3 * r + co[i]
            return r
        }
        set(value) {
            var t = value
            var sum = 0
            for (i in 6 downTo 0) {
                co[i] = t % 3
                sum += co[i]
                t /= 3
            }
            co[7] = (3 - sum % 3) % 3
        }

    /** Edge flip, 0 <= flip < 2^11. */
    var flip: Int
        get() {
            var r = 0
            for (i in 0 until 11) r = 2 * r + eo[i]
            return r
        }
        set(value) {
            var f = value
            var sum = 0
            for (i in 10 downTo 0) {
                eo[i] = f and 1
                sum += eo[i]
                f = f shr 1
            }
            eo[11] = sum and 1
        }

    fun cornerParity(): Int {
        var s = 0
        for (i in 7 downTo 1) for (j in i - 1 downTo 0) if (cp[j] > cp[i]) s++
        return s and 1
    }

    fun edgeParity(): Int {
        var s = 0
        for (i in 11 downTo 1) for (j in i - 1 downTo 0) if (ep[j] > ep[i]) s++
        return s and 1
    }

    // ---------------------------------------------------------------- permutation coordinates

    /** Permutation and position of the UD-slice edges FR FL BL BR; < 24 in phase 2. */
    var frToBr: Int
        get() {
            var a = 0
            var x = 0
            for (j in 11 downTo 0) {
                if (ep[j] >= FR) {
                    a += cnk(11 - j, x + 1)
                    perm[3 - x] = ep[j]
                    x++
                }
            }
            var b = 0
            for (j in 3 downTo 1) {
                var k = 0
                while (perm[j] != j + 8) {
                    rotateLeft(perm, 0, j)
                    k++
                }
                b = (j + 1) * b + k
            }
            return 24 * a + b
        }
        set(idx) {
            for (i in 0 until 4) perm[i] = FR + i
            var b = idx % 24
            var a = idx / 24
            for (i in 0 until 12) ep[i] = DB
            for (j in 1 until 4) {
                var k = b % (j + 1)
                b /= j + 1
                while (k-- > 0) rotateRight(perm, 0, j)
            }
            var x = 3
            for (j in 0 until 12) {
                if (x < 0) break
                val c = cnk(11 - j, x + 1)
                if (a - c >= 0) {
                    ep[j] = perm[3 - x]
                    a -= c
                    x--
                }
            }
            x = 0
            for (j in 0 until 12) if (ep[j] == DB) ep[j] = x++
        }

    /** Permutation of the corners URF..DLF (6 of 8). */
    var urfToDlf: Int
        get() {
            var a = 0
            var x = 0
            for (j in 0 until 8) {
                if (cp[j] <= DLF) {
                    a += cnk(j, x + 1)
                    perm[x++] = cp[j]
                }
            }
            var b = 0
            for (j in 5 downTo 1) {
                var k = 0
                while (perm[j] != j) {
                    rotateLeft(perm, 0, j)
                    k++
                }
                b = (j + 1) * b + k
            }
            return 720 * a + b
        }
        set(idx) {
            for (i in 0 until 6) perm[i] = i
            var b = idx % 720
            var a = idx / 720
            for (i in 0 until 8) cp[i] = DRB
            for (j in 1 until 6) {
                var k = b % (j + 1)
                b /= j + 1
                while (k-- > 0) rotateRight(perm, 0, j)
            }
            var x = 5
            for (j in 7 downTo 0) {
                if (x < 0) break
                val c = cnk(j, x + 1)
                if (a - c >= 0) {
                    cp[j] = perm[x]
                    a -= c
                    x--
                }
            }
            x = DBL
            for (j in 0 until 8) if (cp[j] == DRB) cp[j] = x++
        }

    /** Permutation of the edges UR..DF (6 of 12); < 20160 in phase 2. */
    var urToDf: Int
        get() = getEdgeSet(0, 6)
        set(idx) {
            setEdgeSet(idx, 0, 6)
            var x = DL
            for (j in 0 until 12) if (ep[j] == INVALID) ep[j] = x++
        }

    /** Permutation of the edges UR UF UL. */
    var urToUl: Int
        get() = getEdgeSet(0, 3)
        set(idx) = setEdgeSet(idx, 0, 3)

    /** Permutation of the edges UB DR DF. */
    var ubToDf: Int
        get() = getEdgeSet(UB, 3)
        set(idx) = setEdgeSet(idx, UB, 3)

    /** Ranks the positions and order of the [count] edges first..first+count-1. */
    private fun getEdgeSet(first: Int, count: Int): Int {
        var a = 0
        var x = 0
        val last = first + count - 1
        for (j in 0 until 12) {
            val e = ep[j]
            if (e in first..last) {
                a += cnk(j, x + 1)
                perm[x++] = e
            }
        }
        var b = 0
        for (j in count - 1 downTo 1) {
            var k = 0
            while (perm[j] != first + j) {
                rotateLeft(perm, 0, j)
                k++
            }
            b = (j + 1) * b + k
        }
        return FACT[count] * a + b
    }

    /** Inverse of [getEdgeSet]; every other edge slot is set to [INVALID]. */
    private fun setEdgeSet(idx: Int, first: Int, count: Int) {
        for (i in 0 until count) perm[i] = first + i
        var b = idx % FACT[count]
        var a = idx / FACT[count]
        for (i in 0 until 12) ep[i] = INVALID
        for (j in 1 until count) {
            var k = b % (j + 1)
            b /= j + 1
            while (k-- > 0) rotateRight(perm, 0, j)
        }
        var x = count - 1
        for (j in 11 downTo 0) {
            if (x < 0) break
            val c = cnk(j, x + 1)
            if (a - c >= 0) {
                ep[j] = perm[x]
                a -= c
                x--
            }
        }
    }

    /** True for a cube reachable by legal moves. */
    fun isValid(): Boolean {
        var seenC = 0
        var seenE = 0
        var twistSum = 0
        var flipSum = 0
        for (i in 0 until 8) {
            if (cp[i] !in 0..7 || co[i] !in 0..2) return false
            seenC = seenC or (1 shl cp[i])
            twistSum += co[i]
        }
        for (i in 0 until 12) {
            if (ep[i] !in 0..11 || eo[i] !in 0..1) return false
            seenE = seenE or (1 shl ep[i])
            flipSum += eo[i]
        }
        return seenC == 0xFF && seenE == 0xFFF && twistSum % 3 == 0 && flipSum % 2 == 0 &&
            cornerParity() == edgeParity()
    }

    companion object {
        // Corners
        const val URF = 0; const val UFL = 1; const val ULB = 2; const val UBR = 3
        const val DFR = 4; const val DLF = 5; const val DBL = 6; const val DRB = 7

        // Edges
        const val UR = 0; const val UF = 1; const val UL = 2; const val UB = 3
        const val DR = 4; const val DF = 5; const val DL = 6; const val DB = 7
        const val FR = 8; const val FL = 9; const val BL = 10; const val BR = 11

        /** Marker for edge slots left undefined by the partial edge-set setters. */
        const val INVALID = 15
        private val FACT = intArrayOf(1, 1, 2, 6, 24, 120, 720)

        // Facelet indices (U1..U9 = 0..8, R = 9.., F = 18.., D = 27.., L = 36.., B = 45..).
        // The first facelet of every corner/edge is the one defining its orientation.
        private val CORNER_FACELET = arrayOf(
            intArrayOf(8, 9, 20), intArrayOf(6, 18, 38), intArrayOf(0, 36, 47), intArrayOf(2, 45, 11),
            intArrayOf(29, 26, 15), intArrayOf(27, 44, 24), intArrayOf(33, 53, 42), intArrayOf(35, 17, 51),
        )
        private val EDGE_FACELET = arrayOf(
            intArrayOf(5, 10), intArrayOf(7, 19), intArrayOf(3, 37), intArrayOf(1, 46),
            intArrayOf(32, 16), intArrayOf(28, 25), intArrayOf(30, 43), intArrayOf(34, 52),
            intArrayOf(23, 12), intArrayOf(21, 41), intArrayOf(50, 39), intArrayOf(48, 14),
        )
        private const val FU = CubeState.U; private const val FR_ = CubeState.R; private const val FF = CubeState.F
        private const val FD = CubeState.D; private const val FL_ = CubeState.L; private const val FB = CubeState.B
        private val CORNER_COLOR = arrayOf(
            intArrayOf(FU, FR_, FF), intArrayOf(FU, FF, FL_), intArrayOf(FU, FL_, FB), intArrayOf(FU, FB, FR_),
            intArrayOf(FD, FF, FR_), intArrayOf(FD, FL_, FF), intArrayOf(FD, FB, FL_), intArrayOf(FD, FR_, FB),
        )
        private val EDGE_COLOR = arrayOf(
            intArrayOf(FU, FR_), intArrayOf(FU, FF), intArrayOf(FU, FL_), intArrayOf(FU, FB),
            intArrayOf(FD, FR_), intArrayOf(FD, FF), intArrayOf(FD, FL_), intArrayOf(FD, FB),
            intArrayOf(FF, FR_), intArrayOf(FF, FL_), intArrayOf(FB, FL_), intArrayOf(FB, FR_),
        )

        /** Corner facelets of the DBL corner: D7, B9, L7. */
        val DBL_FACELETS = intArrayOf(33, 53, 42)

        /**
         * Builds a cubie cube from 54 facelets given as face indices (U R F D L B = 0..5).
         * Returns null if the facelets do not describe a valid cube (with [validate] false only
         * the per-piece lookup is checked, which is what the corners-only 2x2 solver needs).
         */
        fun fromFacelets(f: IntArray, validate: Boolean = true): CubieCube? {
            val cc = CubieCube()
            for (i in 0 until 8) {
                var ori = 0
                while (ori < 3) {
                    val c = f[CORNER_FACELET[i][ori]]
                    if (c == FU || c == FD) break
                    ori++
                }
                if (ori == 3) return null
                val col1 = f[CORNER_FACELET[i][(ori + 1) % 3]]
                val col2 = f[CORNER_FACELET[i][(ori + 2) % 3]]
                var found = false
                for (j in 0 until 8) {
                    if (col1 == CORNER_COLOR[j][1] && col2 == CORNER_COLOR[j][2]) {
                        cc.cp[i] = j
                        cc.co[i] = ori % 3
                        found = true
                        break
                    }
                }
                if (!found) return null
            }
            for (i in 0 until 12) {
                val a = f[EDGE_FACELET[i][0]]
                val b = f[EDGE_FACELET[i][1]]
                var found = false
                for (j in 0 until 12) {
                    if (a == EDGE_COLOR[j][0] && b == EDGE_COLOR[j][1]) {
                        cc.ep[i] = j; cc.eo[i] = 0; found = true; break
                    }
                    if (a == EDGE_COLOR[j][1] && b == EDGE_COLOR[j][0]) {
                        cc.ep[i] = j; cc.eo[i] = 1; found = true; break
                    }
                }
                if (!found) return null
            }
            return if (!validate || cc.isValid()) cc else null
        }

        /** True when the corners alone form a reachable 2x2 state (permutation + twist sum). */
        fun cornersValid(cc: CubieCube): Boolean {
            var seen = 0
            var twistSum = 0
            for (i in 0 until 8) {
                seen = seen or (1 shl cc.cp[i])
                twistSum += cc.co[i]
            }
            return seen == 0xFF && twistSum % 3 == 0
        }

        /**
         * The six clockwise face turns U R F D L B, derived from the sticker model so that the
         * solver and the game can never disagree about what a move does.
         */
        val moveCube: Array<CubieCube> by lazy {
            Array(6) { face ->
                val s = CubeState(3)
                s.apply(Move.face(face, 1, 3))
                requireNotNull(fromFacelets(IntArray(54) { s.colors[it].toInt() }))
            }
        }

        fun cnk(n: Int, k: Int): Int {
            if (n < k) return 0
            val kk = if (k > n / 2) n - k else k
            var s = 1
            var i = n
            var j = 1
            while (i != n - kk) {
                s *= i
                s /= j
                i--
                j++
            }
            return s
        }

        private fun rotateLeft(arr: IntArray, l: Int, r: Int) {
            val t = arr[l]
            for (i in l until r) arr[i] = arr[i + 1]
            arr[r] = t
        }

        private fun rotateRight(arr: IntArray, l: Int, r: Int) {
            val t = arr[r]
            for (i in r downTo l + 1) arr[i] = arr[i - 1]
            arr[l] = t
        }
    }
}
