package app.twistcubee.data

import android.content.Context

/** Per-size solve statistics, stored in SharedPreferences (on this device only). */
class Stats(context: Context) {
    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    class Summary(
        val n: Int,
        val solves: Int,
        val bestMs: Long,
        val averageMs: Long,
        val ao5Ms: Long,
        val fewestMoves: Int,
    )

    /** Records a timed, unassisted solve. Returns true if it is a new best time. */
    fun record(n: Int, timeMs: Long, moves: Int): Boolean {
        val solves = prefs.getInt("solves_$n", 0)
        val best = prefs.getLong("best_$n", Long.MAX_VALUE)
        val fewest = prefs.getInt("fewest_$n", Int.MAX_VALUE)
        val total = prefs.getLong("total_$n", 0L)
        val recent = recentTimes(n).toMutableList()
        recent.add(timeMs)
        while (recent.size > RECENT) recent.removeAt(0)
        prefs.edit()
            .putInt("solves_$n", solves + 1)
            .putLong("best_$n", minOf(best, timeMs))
            .putInt("fewest_$n", minOf(fewest, moves))
            .putLong("total_$n", total + timeMs)
            .putString("recent_$n", recent.joinToString(","))
            .apply()
        return timeMs < best
    }

    fun bestMs(n: Int): Long = prefs.getLong("best_$n", -1L).let { if (it == Long.MAX_VALUE) -1L else it }

    fun solves(n: Int): Int = prefs.getInt("solves_$n", 0)

    fun summary(n: Int): Summary? {
        val solves = solves(n)
        if (solves == 0) return null
        val recent = recentTimes(n)
        val ao5 = if (recent.size >= 5) {
            // WCA average of 5: drop best and worst of the last five.
            val last5 = recent.takeLast(5).sorted()
            (last5[1] + last5[2] + last5[3]) / 3
        } else {
            -1L
        }
        return Summary(
            n = n,
            solves = solves,
            bestMs = bestMs(n),
            averageMs = prefs.getLong("total_$n", 0L) / solves,
            ao5Ms = ao5,
            fewestMoves = prefs.getInt("fewest_$n", -1),
        )
    }

    /** Most recent solve times, newest first (up to 12). */
    fun recent(n: Int): List<Long> = recentTimes(n).asReversed()

    fun reset() {
        prefs.edit().clear().apply()
    }

    private fun recentTimes(n: Int): List<Long> {
        val s = prefs.getString("recent_$n", null) ?: return emptyList()
        if (s.isEmpty()) return emptyList()
        return s.split(',').mapNotNull { it.toLongOrNull() }
    }

    private companion object {
        const val RECENT = 12
    }
}
