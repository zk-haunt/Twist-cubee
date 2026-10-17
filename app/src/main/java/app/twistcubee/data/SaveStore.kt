package app.twistcubee.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * One small binary file per cube size (a few KB even for 20x20). Writes go to a temp file that is
 * renamed into place, so a crash mid-write can never corrupt a save.
 */
class SaveStore(private val dir: File) {

    fun save(s: GameSave) {
        val tmp = File(dir, "cube_${s.n}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(s.n)
                out.writeInt(s.colors.size)
                out.write(s.colors)
                out.writeInt(s.status)
                out.writeBoolean(s.assisted)
                out.writeLong(s.elapsedMs)
                out.writeInt(s.solvedMark)
                writeInts(out, s.scramble)
                writeInts(out, s.user)
                writeInts(out, s.redo)
            }
            if (!tmp.renameTo(File(dir, "cube_${s.n}.sav"))) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    fun load(n: Int): GameSave? {
        val f = File(dir, "cube_$n.sav")
        if (!f.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION) return null
                val size = inp.readInt()
                val count = inp.readInt()
                if (size != n || count != 6 * n * n) return null
                val colors = ByteArray(count)
                inp.readFully(colors)
                if (colors.any { it < 0 || it > 5 }) return null
                val status = inp.readInt()
                val assisted = inp.readBoolean()
                val elapsed = inp.readLong()
                val solvedMark = inp.readInt()
                val scramble = readInts(inp)
                val user = readInts(inp)
                val redo = readInts(inp)
                GameSave(n, colors, scramble, user, redo, status, assisted, elapsed, solvedMark)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(n: Int) {
        File(dir, "cube_$n.sav").delete()
    }

    private fun writeInts(out: DataOutputStream, a: IntArray) {
        out.writeInt(a.size)
        for (v in a) out.writeInt(v)
    }

    private fun readInts(inp: DataInputStream): IntArray {
        val size = inp.readInt()
        require(size in 0..1_000_000)
        return IntArray(size) { inp.readInt() }
    }

    private companion object {
        const val MAGIC = 0x43334D44 // "C3MD"
        const val VERSION = 1
    }
}
