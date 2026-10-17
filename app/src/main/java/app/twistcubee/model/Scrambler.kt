package app.twistcubee.model

import java.util.Random

/** Random-move scrambles that never contain a move cancelling (part of) the previous one. */
object Scrambler {

    fun length(n: Int): Int = when (n) {
        2 -> 11
        3 -> 25
        else -> 20 * (n - 2) // 4x4: 40, 5x5: 60, 7x7: 100 (WCA lengths), 20x20: 360
    }

    fun generate(n: Int, rnd: Random, out: MoveList) {
        out.clear()
        val len = length(n)
        var prevAxis = -1
        var prevLayer = -1
        var sameAxisRun = 0
        while (out.size < len) {
            val axis = rnd.nextInt(3)
            val layer = when (n) {
                2 -> 1 // R, U, F only: keeps the scramble free of whole-cube rotations
                3 -> if (rnd.nextBoolean()) 0 else 2 // outer faces only, centers stay put
                else -> {
                    var l: Int
                    // Leave the central slice of odd cubes alone so the color scheme stays oriented.
                    do l = rnd.nextInt(n) while (n % 2 == 1 && l == n / 2)
                    l
                }
            }
            if (axis == prevAxis) {
                if (layer == prevLayer) continue
                if (n <= 3 && sameAxisRun >= 1) continue // avoid R L R style redundancy
            }
            sameAxisRun = if (axis == prevAxis) sameAxisRun + 1 else 0
            prevAxis = axis
            prevLayer = layer
            out.add(Move.of(axis, layer, layer, 1 + rnd.nextInt(3)))
        }
    }
}
