package app.twistcubee.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Procedural meshes, generated once per cube size (level of detail depends on the size). */
internal object Meshes {

    class Mesh(val vertices: FloatArray, val indices: ShortArray)

    // Per-face normal, tangent and bitangent (tangent x bitangent = normal), face order U R F D L B.
    private val FN = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, -1f)
    private val FT = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, -1f, 0f, 0f)
    private val FB = floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 0f)

    /**
     * Cube of half-size [h] with edges rounded by radius [r], [seg] segments per 45 degrees of
     * bevel. Vertex layout: position xyz, normal xyz. Each face is a grid whose lines are spaced so
     * that the projected bevel samples are evenly spaced in angle.
     */
    fun roundedCube(h: Float, r: Float, seg: Int): Mesh {
        val g = 2 * seg + 2
        val coords = FloatArray(g)
        for (i in 0..seg) {
            val a = (PI / 4 * i / seg).toFloat()
            coords[seg - i] = -(h - r) - r * tan(a)
            coords[seg + 1 + i] = (h - r) + r * tan(a)
        }
        val verts = FloatArray(6 * g * g * 6)
        val idx = ShortArray(6 * (g - 1) * (g - 1) * 6)
        var vi = 0
        var ii = 0
        val inner = h - r
        for (f in 0 until 6) {
            val base = f * g * g
            for (j in 0 until g) for (i in 0 until g) {
                val u = coords[i]
                val v = coords[j]
                val px = FN[3 * f] * h + FT[3 * f] * u + FB[3 * f] * v
                val py = FN[3 * f + 1] * h + FT[3 * f + 1] * u + FB[3 * f + 1] * v
                val pz = FN[3 * f + 2] * h + FT[3 * f + 2] * u + FB[3 * f + 2] * v
                val ix = px.coerceIn(-inner, inner)
                val iy = py.coerceIn(-inner, inner)
                val iz = pz.coerceIn(-inner, inner)
                var nx = px - ix
                var ny = py - iy
                var nz = pz - iz
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len
                verts[vi++] = ix + nx * r
                verts[vi++] = iy + ny * r
                verts[vi++] = iz + nz * r
                verts[vi++] = nx
                verts[vi++] = ny
                verts[vi++] = nz
            }
            for (j in 0 until g - 1) for (i in 0 until g - 1) {
                val a = base + j * g + i
                val b = a + 1
                val c = a + g + 1
                val d = a + g
                idx[ii++] = a.toShort(); idx[ii++] = b.toShort(); idx[ii++] = c.toShort()
                idx[ii++] = a.toShort(); idx[ii++] = c.toShort(); idx[ii++] = d.toShort()
            }
        }
        return Mesh(verts, idx)
    }

    /** Flat rounded square of half-size [a] and corner radius [rc] as a triangle fan (xy). */
    fun roundedSquare(a: Float, rc: Float, seg: Int): Mesh {
        val per = 4 * (seg + 1)
        val verts = FloatArray((per + 1) * 2)
        var k = 2
        val sx = floatArrayOf(1f, -1f, -1f, 1f)
        val sy = floatArrayOf(1f, 1f, -1f, -1f)
        for (c in 0 until 4) {
            val cx = sx[c] * (a - rc)
            val cy = sy[c] * (a - rc)
            for (s in 0..seg) {
                val ang = (PI / 2 * c + PI / 2 * s / seg).toFloat()
                verts[k++] = cx + rc * cos(ang)
                verts[k++] = cy + rc * sin(ang)
            }
        }
        val idx = ShortArray(per * 3)
        for (i in 0 until per) {
            idx[3 * i] = 0
            idx[3 * i + 1] = (1 + i).toShort()
            idx[3 * i + 2] = (1 + (i + 1) % per).toShort()
        }
        return Mesh(verts, idx)
    }

    /** Unit box [-1, 1]^3, positions only, outward counter-clockwise winding. */
    fun box(): Mesh {
        val v = floatArrayOf(
            -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f,
            -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f,
        )
        val i = shortArrayOf(
            4, 5, 6, 4, 6, 7, // +Z
            1, 0, 3, 1, 3, 2, // -Z
            5, 1, 2, 5, 2, 6, // +X
            0, 4, 7, 0, 7, 3, // -X
            7, 6, 2, 7, 2, 3, // +Y
            0, 1, 5, 0, 5, 4, // -Y
        )
        return Mesh(v, i)
    }
}
