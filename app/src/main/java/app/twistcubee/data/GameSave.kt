package app.twistcubee.data

/** Persisted state of one cube size. */
class GameSave(
    val n: Int,
    val colors: ByteArray,
    val scramble: IntArray,
    val user: IntArray,
    val redo: IntArray,
    val status: Int,
    val assisted: Boolean,
    val elapsedMs: Long,
    /** Index into [user] at which the cube was last solved, or -1 (use the whole history). */
    val solvedMark: Int,
)
