package app.twistcubee.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mutable unit quaternion; every operation works in place so nothing allocates per frame. */
class Quat {
    var x = 0f
    var y = 0f
    var z = 0f
    var w = 1f

    fun set(o: Quat): Quat {
        x = o.x; y = o.y; z = o.z; w = o.w
        return this
    }

    fun set(x: Float, y: Float, z: Float, w: Float): Quat {
        this.x = x; this.y = y; this.z = z; this.w = w
        return this
    }

    fun identity(): Quat = set(0f, 0f, 0f, 1f)

    /** Rotation of [angle] radians about the unit axis (ax, ay, az). */
    fun setAxisAngle(ax: Float, ay: Float, az: Float, angle: Float): Quat {
        val h = angle * 0.5f
        val s = sin(h)
        x = ax * s; y = ay * s; z = az * s; w = cos(h)
        return this
    }

    /** this = o * this, i.e. applies [o] after the current rotation. */
    fun preMultiply(o: Quat): Quat {
        val nx = o.w * x + o.x * w + o.y * z - o.z * y
        val ny = o.w * y - o.x * z + o.y * w + o.z * x
        val nz = o.w * z + o.x * y - o.y * x + o.z * w
        val nw = o.w * w - o.x * x - o.y * y - o.z * z
        x = nx; y = ny; z = nz; w = nw
        return this
    }

    fun normalize(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        if (l > 1e-9f) {
            val inv = 1f / l
            x *= inv; y *= inv; z *= inv; w *= inv
        } else {
            identity()
        }
        return this
    }

    /** Rotates vector v by this quaternion, writing the result to out[0..2]. */
    fun rotate(vx: Float, vy: Float, vz: Float, out: FloatArray) {
        // t = 2 * cross(q, v); v' = v + w * t + cross(q, t)
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        out[0] = vx + w * tx + (y * tz - z * ty)
        out[1] = vy + w * ty + (z * tx - x * tz)
        out[2] = vz + w * tz + (x * ty - y * tx)
    }

    /** Rotates v by the inverse of this quaternion. */
    fun rotateInverse(vx: Float, vy: Float, vz: Float, out: FloatArray) {
        val tx = 2f * (-y * vz + z * vy)
        val ty = 2f * (-z * vx + x * vz)
        val tz = 2f * (-x * vy + y * vx)
        out[0] = vx + w * tx + (-y * tz + z * ty)
        out[1] = vy + w * ty + (-z * tx + x * tz)
        out[2] = vz + w * tz + (-x * ty + y * tx)
    }

    /** Column-major 4x4 rotation matrix, as expected by OpenGL. */
    fun toMatrix(m: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        m[0] = 1f - 2f * (yy + zz); m[1] = 2f * (xy + wz); m[2] = 2f * (xz - wy); m[3] = 0f
        m[4] = 2f * (xy - wz); m[5] = 1f - 2f * (xx + zz); m[6] = 2f * (yz + wx); m[7] = 0f
        m[8] = 2f * (xz + wy); m[9] = 2f * (yz - wx); m[10] = 1f - 2f * (xx + yy); m[11] = 0f
        m[12] = 0f; m[13] = 0f; m[14] = 0f; m[15] = 1f
    }

    /** Spherical interpolation from [a] to [b]; stores the result in this. */
    fun slerp(a: Quat, b: Quat, t: Float): Quat {
        var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
        var d = a.x * bx + a.y * by + a.z * bz + a.w * bw
        if (d < 0f) {
            d = -d; bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        val wa: Float
        val wb: Float
        if (d > 0.9995f) {
            wa = 1f - t
            wb = t
        } else {
            val theta = acos(d)
            val s = sin(theta)
            wa = sin((1f - t) * theta) / s
            wb = sin(t * theta) / s
        }
        x = wa * a.x + wb * bx
        y = wa * a.y + wb * by
        z = wa * a.z + wb * bz
        w = wa * a.w + wb * bw
        return normalize()
    }
}
