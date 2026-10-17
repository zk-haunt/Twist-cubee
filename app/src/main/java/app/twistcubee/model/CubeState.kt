package app.twistcubee.model

/**
 * Sticker ("facelet") model of an NxNxN cube.
 *
 * Faces are stored in the order U R F D L B, each as an N x N grid laid out exactly like the
 * classic cube net (the same convention Kociemba's solver uses), so a 3x3 state maps 1:1 onto
 * the solver's facelet string. Colors are the index of the face the sticker belongs to when solved.
 *
 * Every sticker also knows the integer coordinates (0..N-1) of the cubie it sits on, which makes
 * layer turns a simple geometric rotation and lets the renderer place stickers without any
 * per-frame work.
 */
class CubeState(val n: Int) {
    val faceSize = n * n
    val size = 6 * faceSize

    /** Current color (solved-face index) of every sticker. */
    val colors = ByteArray(size)

    /** Cubie coordinates of every sticker. */
    val px = ByteArray(size)
    val py = ByteArray(size)
    val pz = ByteArray(size)

    private val scratch = ByteArray(size)

    init {
        require(n in 1..31)
        val m = n - 1
        for (f in 0 until 6) for (r in 0 until n) for (c in 0 until n) {
            val i = f * faceSize + r * n + c
            val x: Int
            val y: Int
            val z: Int
            when (f) {
                U -> { x = c; y = m; z = r }
                R -> { x = m; y = m - r; z = m - c }
                F -> { x = c; y = m - r; z = m }
                D -> { x = c; y = 0; z = m - r }
                L -> { x = 0; y = m - r; z = c }
                else -> { x = m - c; y = m - r; z = 0 }
            }
            px[i] = x.toByte()
            py[i] = y.toByte()
            pz[i] = z.toByte()
        }
        reset()
    }

    fun reset() {
        for (f in 0 until 6) colors.fill(f.toByte(), f * faceSize, (f + 1) * faceSize)
    }

    fun copyFrom(other: CubeState) {
        require(other.n == n)
        other.colors.copyInto(colors)
    }

    fun faceOf(index: Int): Int = index / faceSize

    /** Sticker index of the sticker on [face] of cubie (x, y, z). */
    fun indexOf(face: Int, x: Int, y: Int, z: Int): Int {
        val m = n - 1
        return face * faceSize + when (face) {
            U -> z * n + x
            R -> (m - y) * n + (m - z)
            F -> (m - y) * n + x
            D -> (m - z) * n + x
            L -> (m - y) * n + z
            else -> (m - y) * n + (m - x)
        }
    }

    /** Applies a packed [Move]. O(stickers), allocation free. */
    fun apply(move: Int) {
        val turns = Move.turns(move)
        if (turns == 0) return
        val axis = Move.axis(move)
        val lo = Move.lo(move)
        val hi = Move.hi(move)
        val m = n - 1
        val coord = when (axis) {
            Move.X -> px
            Move.Y -> py
            else -> pz
        }
        colors.copyInto(scratch)
        for (i in 0 until size) {
            val k = coord[i].toInt()
            if (k < lo || k > hi) continue
            var x = px[i].toInt()
            var y = py[i].toInt()
            var z = pz[i].toInt()
            var f = i / faceSize
            for (t in 0 until turns) {
                // One quarter turn counter-clockwise about the positive axis.
                when (axis) {
                    Move.X -> { val ny = m - z; z = y; y = ny; f = ROT_X[f] }
                    Move.Y -> { val nx = z; z = m - x; x = nx; f = ROT_Y[f] }
                    else -> { val nx = m - y; y = x; x = nx; f = ROT_Z[f] }
                }
            }
            scratch[indexOf(f, x, y, z)] = colors[i]
        }
        scratch.copyInto(colors)
    }

    val isSolved: Boolean
        get() {
            for (f in 0 until 6) {
                val start = f * faceSize
                val c = colors[start]
                for (i in start + 1 until start + faceSize) if (colors[i] != c) return false
            }
            return true
        }

    companion object {
        const val U = 0
        const val R = 1
        const val F = 2
        const val D = 3
        const val L = 4
        const val B = 5

        /** Axis each face is perpendicular to, and the side of the axis it is on. */
        val FACE_AXIS = intArrayOf(Move.Y, Move.X, Move.Z, Move.Y, Move.X, Move.Z)
        val FACE_SIGN = intArrayOf(1, 1, 1, -1, -1, -1)

        // Where each face normal goes under a +90 degree rotation about X, Y and Z.
        private val ROT_X = intArrayOf(F, R, D, B, L, U)
        private val ROT_Y = intArrayOf(U, B, R, D, F, L)
        private val ROT_Z = intArrayOf(L, U, F, R, D, B)

        fun faceFor(axis: Int, sign: Int): Int = when (axis) {
            Move.X -> if (sign > 0) R else L
            Move.Y -> if (sign > 0) U else D
            else -> if (sign > 0) F else B
        }
    }
}
