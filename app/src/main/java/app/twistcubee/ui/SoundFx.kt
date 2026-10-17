package app.twistcubee.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.twistcubee.R

/** Low-latency sound effects through SoundPool; decoded once, played from memory. */
class SoundFx(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val turnId = pool.load(context, R.raw.turn, 1)
    private val solvedId = pool.load(context, R.raw.solved, 1)
    private var lastTurnNs = 0L
    private var seed = 0x2545F491

    var enabled = true

    fun turn(user: Boolean) {
        if (!enabled) return
        val now = System.nanoTime()
        // Rapid automatic moves (scramble, rewind) would otherwise turn into a buzz.
        if (!user && now - lastTurnNs < 55_000_000L) return
        lastTurnNs = now
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        val rate = 0.92f + ((seed ushr 8) and 0xFF) / 255f * 0.16f
        val volume = if (user) 0.85f else 0.45f
        pool.play(turnId, volume, volume, 1, 0, rate)
    }

    fun solved() {
        if (enabled) pool.play(solvedId, 0.9f, 0.9f, 2, 0, 1f)
    }

    fun release() = pool.release()
}
