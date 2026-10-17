package app.twistcubee

import app.twistcubee.model.CubeState
import app.twistcubee.model.Move
import app.twistcubee.model.MoveList
import app.twistcubee.model.Scrambler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CubeStateTest {

    @Test
    fun indexMappingRoundTrips() {
        for (n in 1..7) {
            val s = CubeState(n)
            for (i in 0 until s.size) {
                val back = s.indexOf(s.faceOf(i), s.px[i].toInt(), s.py[i].toInt(), s.pz[i].toInt())
                assertEquals("n=$n i=$i", i, back)
            }
        }
    }

    @Test
    fun stickersSitOnTheirFace() {
        val n = 4
        val s = CubeState(n)
        for (i in 0 until s.size) {
            val coord = when (CubeState.FACE_AXIS[s.faceOf(i)]) {
                Move.X -> s.px[i]
                Move.Y -> s.py[i]
                else -> s.pz[i]
            }.toInt()
            val expected = if (CubeState.FACE_SIGN[s.faceOf(i)] > 0) n - 1 else 0
            assertEquals(expected, coord)
        }
    }

    @Test
    fun fourQuarterTurnsAreIdentity() {
        for (n in 2..6) for (axis in 0..2) for (layer in 0 until n) {
            val s = CubeState(n)
            scramble(s, Random(n * 100L + axis * 10 + layer))
            val before = s.colors.copyOf()
            repeat(4) { s.apply(Move.of(axis, layer, layer, 1)) }
            assertArrayEquals(before, s.colors)
        }
    }

    @Test
    fun moveThenInverseIsIdentity() {
        val rnd = Random(7)
        for (n in 2..8) {
            val s = CubeState(n)
            scramble(s, rnd)
            val before = s.colors.copyOf()
            repeat(50) {
                val axis = rnd.nextInt(3)
                val lo = rnd.nextInt(n)
                val hi = lo + rnd.nextInt(n - lo)
                val m = Move.of(axis, lo, hi, 1 + rnd.nextInt(3))
                s.apply(m)
                s.apply(Move.inverse(m))
            }
            assertArrayEquals(before, s.colors)
        }
    }

    @Test
    fun sexyMoveHasOrderSix() {
        // (R U R' U') repeated 6 times is the identity on a 3x3.
        val s = CubeState(3)
        val r = Move.face(CubeState.R, 1, 3)
        val u = Move.face(CubeState.U, 1, 3)
        repeat(6) {
            s.apply(r); s.apply(u); s.apply(Move.inverse(r)); s.apply(Move.inverse(u))
            if (it < 5) assertFalse(s.isSolved)
        }
        assertTrue(s.isSolved)
    }

    @Test
    fun clockwiseUMovesFrontToLeft() {
        // After U (clockwise seen from above) the front face's top row shows the right face's color.
        val s = CubeState(3)
        s.apply(Move.face(CubeState.U, 1, 3))
        for (c in 0 until 3) {
            assertEquals(CubeState.R.toByte(), s.colors[CubeState.F * 9 + c])
            assertEquals(CubeState.B.toByte(), s.colors[CubeState.R * 9 + c])
            assertEquals(CubeState.F.toByte(), s.colors[CubeState.L * 9 + c])
        }
        // ...and the R move (clockwise seen from the right) carries the front column up to U.
        val t = CubeState(3)
        t.apply(Move.face(CubeState.R, 1, 3))
        for (r in 0 until 3) assertEquals(CubeState.F.toByte(), t.colors[CubeState.U * 9 + r * 3 + 2])
    }

    @Test
    fun wholeCubeRotationKeepsSolved() {
        for (n in 2..5) for (axis in 0..2) {
            val s = CubeState(n)
            s.apply(Move.of(axis, 0, n - 1, 1))
            assertTrue(s.isSolved)
        }
    }

    @Test
    fun scramblesAreLongAndUnsolved() {
        val rnd = Random(3)
        for (n in 2..10) {
            val list = MoveList()
            Scrambler.generate(n, rnd, list)
            assertEquals(Scrambler.length(n), list.size)
            val s = CubeState(n)
            for (i in 0 until list.size) s.apply(list[i])
            assertFalse(s.isSolved)
            for (i in 1 until list.size) {
                assertFalse("redundant move pair", Move.sameSlice(list[i], list[i - 1]))
            }
        }
    }

    @Test
    fun notation() {
        assertEquals("R", Move.notation(Move.face(CubeState.R, 1, 3), 3))
        assertEquals("U'", Move.notation(Move.face(CubeState.U, 3, 3), 3))
        assertEquals("F2", Move.notation(Move.face(CubeState.F, 2, 3), 3))
        assertEquals("L", Move.notation(Move.face(CubeState.L, 1, 3), 3))
        assertEquals("D'", Move.notation(Move.face(CubeState.D, 3, 3), 3))
        assertEquals("B2", Move.notation(Move.face(CubeState.B, 2, 3), 3))
        assertEquals("M", Move.notation(Move.of(Move.X, 1, 1, 1), 3))
        assertEquals("2R", Move.notation(Move.of(Move.X, 2, 2, 3), 4))
        assertEquals("Rw'", Move.notation(Move.of(Move.X, 2, 3, 1), 4))
        assertEquals("x", Move.notation(Move.of(Move.X, 0, 2, 3), 3))
    }

    private fun scramble(s: CubeState, rnd: Random) {
        val list = MoveList()
        Scrambler.generate(s.n, rnd, list)
        for (i in 0 until list.size) s.apply(list[i])
    }
}
