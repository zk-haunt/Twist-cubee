package app.twistcubee

import app.twistcubee.model.CubeState
import app.twistcubee.model.Move
import app.twistcubee.solver.CubieCube
import app.twistcubee.solver.SolverService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SolverTest {

    @Test
    fun derivedMoveCubesMatchKociemba() {
        // U: URF <- UBR, UFL <- URF, ULB <- UFL, UBR <- ULB (Kociemba's cpU)
        val u = CubieCube.moveCube[0]
        assertArrayEquals(intArrayOf(3, 0, 1, 2, 4, 5, 6, 7), u.cp)
        assertArrayEquals(IntArray(8), u.co)
        assertArrayEquals(intArrayOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11), u.ep)
        // R: cp = DFR UFL ULB URF DRB DLF DBL UBR, co = 2 0 0 1 1 0 0 2
        val r = CubieCube.moveCube[1]
        assertArrayEquals(intArrayOf(4, 1, 2, 0, 7, 5, 6, 3), r.cp)
        assertArrayEquals(intArrayOf(2, 0, 0, 1, 1, 0, 0, 2), r.co)
        assertArrayEquals(intArrayOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0), r.ep)
        // F: eo = 0 1 0 0 0 1 0 0 1 1 0 0
        val f = CubieCube.moveCube[2]
        assertArrayEquals(intArrayOf(1, 5, 2, 3, 0, 4, 6, 7), f.cp)
        assertArrayEquals(intArrayOf(1, 2, 0, 0, 2, 1, 0, 0), f.co)
        assertArrayEquals(intArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0), f.eo)
    }

    @Test
    fun coordinatesRoundTrip() {
        val c = CubieCube()
        for (i in 0 until 2187 step 7) { c.twist = i; assertEquals(i, c.twist) }
        for (i in 0 until 2048 step 5) { c.flip = i; assertEquals(i, c.flip) }
        for (i in 0 until 11880 step 13) { c.frToBr = i; assertEquals(i, c.frToBr) }
        for (i in 0 until 20160 step 17) { c.urfToDlf = i; assertEquals(i, c.urfToDlf) }
        for (i in 0 until 20160 step 19) { c.urToDf = i; assertEquals(i, c.urToDf) }
        for (i in 0 until 1320 step 3) { c.urToUl = i; assertEquals(i, c.urToUl) }
        for (i in 0 until 1320 step 3) { c.ubToDf = i; assertEquals(i, c.ubToDf) }
        c.reset()
        assertEquals(0, c.urToDf)
        assertEquals(114, c.ubToDf)
    }

    @Test
    fun solves3x3RandomStates() {
        val rnd = Random(42)
        val t0 = System.nanoTime()
        SolverService.solveNow(3, CubeState(3).colors, 10) // builds the tables
        println("3x3 tables built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        var total = 0
        var worst = 0
        val runs = 60
        val s0 = System.nanoTime()
        repeat(runs) {
            val s = CubeState(3)
            repeat(30) { s.apply(Move.face(rnd.nextInt(6), 1 + rnd.nextInt(3), 3)) }
            val sol = SolverService.solveNow(3, s.colors, 60)
            assertNotNull(sol)
            for (m in sol!!) s.apply(m)
            assertTrue("not solved", s.isSolved)
            total += sol.size
            worst = maxOf(worst, sol.size)
        }
        println("3x3: avg ${total.toFloat() / runs} moves, worst $worst, ${(System.nanoTime() - s0) / 1_000_000 / runs} ms/solve")
        assertTrue(worst <= 24)
    }

    @Test
    fun solves3x3WithMovedCenters() {
        // Slice moves move the centers; the solver must solve relative to the current centers.
        val rnd = Random(9)
        repeat(20) {
            val s = CubeState(3)
            repeat(25) {
                val axis = rnd.nextInt(3)
                val layer = rnd.nextInt(3)
                s.apply(Move.of(axis, layer, layer, 1 + rnd.nextInt(3)))
            }
            val sol = SolverService.solveNow(3, s.colors, 50)
            assertNotNull(sol)
            for (m in sol!!) s.apply(m)
            assertTrue(s.isSolved)
        }
    }

    @Test
    fun solvedCubeNeedsNoMoves() {
        assertEquals(0, SolverService.solveNow(3, CubeState(3).colors, 50)!!.size)
        assertEquals(0, SolverService.solveNow(2, CubeState(2).colors, 50)!!.size)
    }

    @Test
    fun solves2x2Optimally() {
        val t0 = System.nanoTime()
        SolverService.solveNow(2, CubeState(2).colors, 10)
        println("2x2 table built in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val rnd = Random(5)
        var worst = 0
        repeat(200) {
            val s = CubeState(2)
            // Any layer (including the DBL ones) so the solver has to re-anchor the frame.
            repeat(20) {
                val axis = rnd.nextInt(3)
                val layer = rnd.nextInt(2)
                s.apply(Move.of(axis, layer, layer, 1 + rnd.nextInt(3)))
            }
            val sol = SolverService.solveNow(2, s.colors, 10)
            assertNotNull(sol)
            for (m in sol!!) s.apply(m)
            assertTrue(s.isSolved)
            worst = maxOf(worst, sol.size)
        }
        assertTrue("2x2 God's number is 11 (HTM), got $worst", worst <= 11)
        // A single quarter turn must be undone in exactly one move.
        val s = CubeState(2)
        s.apply(Move.of(Move.X, 1, 1, 1))
        assertEquals(1, SolverService.solveNow(2, s.colors, 10)!!.size)
    }
}
