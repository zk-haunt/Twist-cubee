package app.twistcubee.solver

/**
 * Move and pruning tables for Kociemba's two-phase algorithm (about 6 MB, built once).
 *
 * Every table is a flat primitive array indexed `[coordinate * 18 + move]` so lookups are a single
 * array access with no object indirection. Moves are numbered `3 * face + power - 1` with faces
 * U R F D L B (0..5) and power 1..3 clockwise quarter turns.
 */
internal class TwoPhaseTables {
    val twistMove = ShortArray(N_TWIST * N_MOVE)
    val flipMove = ShortArray(N_FLIP * N_MOVE)
    val frToBrMove = ShortArray(N_FR_TO_BR * N_MOVE)
    val urfToDlfMove = ShortArray(N_URF_TO_DLF * N_MOVE)
    /** Only filled for phase-2 moves (the others can leave the phase-2 coordinate range). */
    val urToDfMove = ShortArray(N_UR_TO_DF * N_MOVE)
    val urToUlMove = ShortArray(N_UR_TO_UL * N_MOVE)
    val ubToDfMove = ShortArray(N_UB_TO_DF * N_MOVE)
    /** URtoDF coordinate at the start of phase 2 from (URtoUL, UBtoDF), both < 336. */
    val mergeUrToDf = ShortArray(336 * 336)

    val sliceTwistPrun = ByteArray(N_SLICE1 * N_TWIST)
    val sliceFlipPrun = ByteArray(N_SLICE1 * N_FLIP)
    val sliceUrfToDlfParityPrun = ByteArray(N_SLICE2 * N_URF_TO_DLF * N_PARITY)
    val sliceUrToDfParityPrun = ByteArray(N_SLICE2 * N_UR_TO_DF * N_PARITY)

    init {
        buildMoveTables()
        buildMergeTable()
        buildPhase1Pruning(sliceTwistPrun, twistMove, N_TWIST)
        buildPhase1Pruning(sliceFlipPrun, flipMove, N_FLIP)
        buildPhase2Pruning(sliceUrfToDlfParityPrun, urfToDlfMove, N_URF_TO_DLF)
        buildPhase2Pruning(sliceUrToDfParityPrun, urToDfMove, N_UR_TO_DF)
    }

    private fun buildMoveTables() {
        val moves = CubieCube.moveCube
        val c = CubieCube()
        for (i in 0 until N_TWIST) {
            c.reset()
            c.twist = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.cornerMultiply(moves[f])
                    twistMove[i * N_MOVE + 3 * f + p] = c.twist.toShort()
                }
                c.cornerMultiply(moves[f]) // fourth quarter turn restores the cube
            }
        }
        for (i in 0 until N_FLIP) {
            c.reset()
            c.flip = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.edgeMultiply(moves[f])
                    flipMove[i * N_MOVE + 3 * f + p] = c.flip.toShort()
                }
                c.edgeMultiply(moves[f])
            }
        }
        for (i in 0 until N_FR_TO_BR) {
            c.reset()
            c.frToBr = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.edgeMultiply(moves[f])
                    frToBrMove[i * N_MOVE + 3 * f + p] = c.frToBr.toShort()
                }
                c.edgeMultiply(moves[f])
            }
        }
        for (i in 0 until N_URF_TO_DLF) {
            c.reset()
            c.urfToDlf = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.cornerMultiply(moves[f])
                    urfToDlfMove[i * N_MOVE + 3 * f + p] = c.urfToDlf.toShort()
                }
                c.cornerMultiply(moves[f])
            }
        }
        for (i in 0 until N_UR_TO_DF) {
            c.reset()
            c.urToDf = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.edgeMultiply(moves[f])
                    val m = 3 * f + p
                    if (isPhase2Move(m)) urToDfMove[i * N_MOVE + m] = c.urToDf.toShort()
                }
                c.edgeMultiply(moves[f])
            }
        }
        for (i in 0 until N_UR_TO_UL) {
            c.reset()
            c.urToUl = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.edgeMultiply(moves[f])
                    urToUlMove[i * N_MOVE + 3 * f + p] = c.urToUl.toShort()
                }
                c.edgeMultiply(moves[f])
            }
        }
        for (i in 0 until N_UB_TO_DF) {
            c.reset()
            c.ubToDf = i
            for (f in 0 until 6) {
                for (p in 0 until 3) {
                    c.edgeMultiply(moves[f])
                    ubToDfMove[i * N_MOVE + 3 * f + p] = c.ubToDf.toShort()
                }
                c.edgeMultiply(moves[f])
            }
        }
    }

    private fun buildMergeTable() {
        val a = CubieCube()
        val b = CubieCube()
        for (i in 0 until 336) {
            for (j in 0 until 336) {
                a.urToUl = i
                b.ubToDf = j
                var ok = true
                for (k in 0 until 8) {
                    val e = a.ep[k]
                    if (e != CubieCube.INVALID) {
                        if (b.ep[k] != CubieCube.INVALID) {
                            ok = false
                            break
                        }
                        b.ep[k] = e
                    }
                }
                mergeUrToDf[336 * i + j] = if (ok) b.urToDf.toShort() else -1
            }
        }
    }

    /** Breadth-first distances over (coordinate x UD-slice position) using all 18 moves. */
    private fun buildPhase1Pruning(table: ByteArray, move: ShortArray, nCoord: Int) {
        val size = N_SLICE1 * nCoord
        table.fill(-1)
        table[0] = 0
        var done = 1
        var depth = 0
        while (done < size) {
            val next = (depth + 1).toByte()
            for (i in 0 until size) {
                if (table[i].toInt() != depth) continue
                val coordBase = (i / N_SLICE1) * N_MOVE
                val sliceBase = (i % N_SLICE1) * 24 * N_MOVE
                for (m in 0 until N_MOVE) {
                    val idx = N_SLICE1 * move[coordBase + m] + frToBrMove[sliceBase + m] / 24
                    if (table[idx].toInt() == -1) {
                        table[idx] = next
                        done++
                    }
                }
            }
            depth++
        }
    }

    /** Breadth-first distances over (coordinate x slice permutation x parity) using phase-2 moves. */
    private fun buildPhase2Pruning(table: ByteArray, move: ShortArray, nCoord: Int) {
        val size = N_SLICE2 * nCoord * N_PARITY
        table.fill(-1)
        table[0] = 0
        var done = 1
        var depth = 0
        while (done < size) {
            val next = (depth + 1).toByte()
            for (i in 0 until size) {
                if (table[i].toInt() != depth) continue
                val parity = i and 1
                val coord = (i shr 1) / N_SLICE2
                val slice = (i shr 1) % N_SLICE2
                for (m in PHASE2_MOVES) {
                    val newSlice = frToBrMove[slice * N_MOVE + m]
                    val newCoord = move[coord * N_MOVE + m]
                    val idx = (N_SLICE2 * newCoord + newSlice) * 2 + PARITY_MOVE[parity * N_MOVE + m]
                    if (table[idx].toInt() == -1) {
                        table[idx] = next
                        done++
                    }
                }
            }
            depth++
        }
    }

    companion object {
        const val N_TWIST = 2187
        const val N_FLIP = 2048
        const val N_SLICE1 = 495
        const val N_SLICE2 = 24
        const val N_PARITY = 2
        const val N_URF_TO_DLF = 20160
        const val N_FR_TO_BR = 11880
        const val N_UR_TO_UL = 1320
        const val N_UB_TO_DF = 1320
        const val N_UR_TO_DF = 20160
        const val N_MOVE = 18

        /** U, U2, U', R2, F2, D, D2, D', L2, B2 */
        val PHASE2_MOVES = intArrayOf(0, 1, 2, 4, 7, 9, 10, 11, 13, 16)

        /** Corner-permutation parity after a move: quarter turns flip it, half turns keep it. */
        val PARITY_MOVE = IntArray(2 * N_MOVE) { i ->
            val parity = i / N_MOVE
            val m = i % N_MOVE
            if (m % 3 == 1) parity else parity xor 1
        }

        fun isPhase2Move(m: Int): Boolean {
            val face = m / 3
            return face == 0 || face == 3 || m % 3 == 1
        }
    }
}
