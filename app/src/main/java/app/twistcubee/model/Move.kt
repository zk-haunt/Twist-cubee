package app.twistcubee.model

/**
 * A move packed into a single Int, so move histories, animation queues and solver output never
 * allocate objects.
 *
 *  bits 0-1   axis (0 = X, 1 = Y, 2 = Z)
 *  bits 2-6   first layer (0 = the layer at the negative end of the axis)
 *  bits 7-11  last layer (inclusive)
 *  bits 12-13 quarter turns counter-clockwise about the positive axis: 1, 2 or 3 (3 = one clockwise turn)
 */
object Move {
    const val X = 0
    const val Y = 1
    const val Z = 2

    fun of(axis: Int, lo: Int, hi: Int, turns: Int): Int =
        axis or (lo shl 2) or (hi shl 7) or ((turns and 3) shl 12)

    fun axis(m: Int): Int = m and 3
    fun lo(m: Int): Int = (m ushr 2) and 31
    fun hi(m: Int): Int = (m ushr 7) and 31
    fun turns(m: Int): Int = (m ushr 12) and 3
    fun withTurns(m: Int, turns: Int): Int = (m and 0xFFF) or ((turns and 3) shl 12)
    fun inverse(m: Int): Int = withTurns(m, 4 - turns(m))

    /** True when both moves turn exactly the same block of layers. */
    fun sameSlice(a: Int, b: Int): Boolean = (a and 0xFFF) == (b and 0xFFF)

    /**
     * Outer face turn in solver terms: face 0..5 = U R F D L B, power 1..3 = clockwise quarter turns
     * as seen when looking at that face.
     */
    fun face(face: Int, power: Int, n: Int): Int {
        val axis = FACE_AXIS[face]
        val positive = face < 3
        val layer = if (positive) n - 1 else 0
        // A clockwise turn of a positive face (U, R, F) is a negative rotation about its axis.
        val turns = if (positive) (4 - power) and 3 else power
        return of(axis, layer, layer, turns)
    }

    private val FACE_AXIS = intArrayOf(Y, X, Z, Y, X, Z)
    private val POS_FACE = charArrayOf('R', 'U', 'F')
    private val NEG_FACE = charArrayOf('L', 'D', 'B')
    private val ROTATION = charArrayOf('x', 'y', 'z')
    private val SLICE = charArrayOf('M', 'E', 'S')

    /** Standard notation: R, U', F2, M, 2R, Rw, 3Lw2, x... */
    fun notation(m: Int, n: Int): String {
        val axis = axis(m)
        val lo = lo(m)
        val hi = hi(m)
        val sb = StringBuilder(8)
        val clockwiseTurns: Int
        if (lo == 0 && hi == n - 1) {
            sb.append(ROTATION[axis])
            clockwiseTurns = 3
        } else if (n == 3 && lo == 1 && hi == 1) {
            sb.append(SLICE[axis])
            // M follows L, E follows D, S follows F.
            clockwiseTurns = if (axis == Z) 3 else 1
        } else {
            val fromPos = hi == n - 1 || (lo != 0 && n - 1 - hi < lo)
            val face = if (fromPos) POS_FACE[axis] else NEG_FACE[axis]
            val depth = if (fromPos) n - lo else hi + 1
            val width = hi - lo + 1
            val touchesFace = if (fromPos) hi == n - 1 else lo == 0
            when {
                touchesFace && width > 1 -> {
                    if (depth > 2) sb.append(depth)
                    sb.append(face).append('w')
                }
                touchesFace -> sb.append(face)
                width == 1 -> sb.append(depth).append(face)
                else -> {
                    val near = if (fromPos) n - hi else lo + 1
                    sb.append(near).append('-').append(depth).append(face)
                }
            }
            clockwiseTurns = if (fromPos) 3 else 1
        }
        when (turns(m)) {
            2 -> sb.append('2')
            clockwiseTurns -> {}
            else -> sb.append('\'')
        }
        return sb.toString()
    }
}
