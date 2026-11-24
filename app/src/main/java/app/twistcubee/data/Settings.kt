package app.twistcubee.data

import android.content.Context

/** User preferences, stored in SharedPreferences (on this device only). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var palette: Int
        get() = prefs.getInt("palette", 0).coerceIn(0, Palettes.SCHEMES.size - 1)
        set(v) = prefs.edit().putInt("palette", v).apply()

    var body: Int
        get() = prefs.getInt("body", 0).coerceIn(0, Palettes.BODIES.size - 1)
        set(v) = prefs.edit().putInt("body", v).apply()

    /** 0 = stickerless tiles, 1 = classic stickers. */
    var tiles: Int
        get() = prefs.getInt("tiles", 0).coerceIn(0, 1)
        set(v) = prefs.edit().putInt("tiles", v).apply()

    /**
     * Index into [Palettes.BACKGROUNDS]. Up to 1.1.0 the "background" key indexed a different list
     * (Midnight, Ocean, Plum, Graphite), so a new key is used and everyone upgrading starts on Dark
     * night, the old Midnight.
     */
    var background: Int
        get() = prefs.getInt("background_v2", 0).coerceIn(0, Palettes.BACKGROUNDS.size - 1)
        set(v) = prefs.edit().putInt("background_v2", v).remove("background").apply()

    /** 0 = slow, 1 = normal, 2 = fast, 3 = instant. */
    var speed: Int
        get() = prefs.getInt("speed", 1).coerceIn(0, 3)
        set(v) = prefs.edit().putInt("speed", v).apply()

    var inspection: Boolean
        get() = prefs.getBoolean("inspection", false)
        set(v) = prefs.edit().putBoolean("inspection", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    var showTimer: Boolean
        get() = prefs.getBoolean("show_timer", true)
        set(v) = prefs.edit().putBoolean("show_timer", v).apply()

    /** Whether the player has turned a layer at least once (hides the first-run tip). */
    var tipSeen: Boolean
        get() = prefs.getBoolean("tip_seen", false)
        set(v) = prefs.edit().putBoolean("tip_seen", v).apply()

    var lastSize: Int
        get() = prefs.getInt("last_size", 3).coerceIn(2, 20)
        set(v) = prefs.edit().putInt("last_size", v).apply()
}

/** Sticker color schemes (face order U R F D L B, 0xRRGGBB), cube bodies and backgrounds. */
object Palettes {
    val SCHEMES = arrayOf(
        // Classic: white top, green front, red right.
        intArrayOf(0xF5F5F5, 0xD8122A, 0x00A54F, 0xFFD400, 0xFF6C00, 0x1060D8),
        // Vivid
        intArrayOf(0xFFFFFF, 0xFF1744, 0x00E676, 0xFFEA00, 0xFF9100, 0x2979FF),
        // Pastel
        intArrayOf(0xFAFAFA, 0xF28B82, 0x81C995, 0xFDE293, 0xFCAD70, 0x8AB4F8),
        // Color-blind friendly (Okabe-Ito based)
        intArrayOf(0xFFFFFF, 0xD55E00, 0x009E73, 0xF0E442, 0xCC79A7, 0x0072B2),
    )
    val SCHEME_NAMES = arrayOf("Classic", "Vivid", "Pastel", "Color-blind")
    const val COLOR_BLIND_SCHEME = 3

    val BODIES = intArrayOf(0x121316, 0x30343C, 0xE4E6EA)
    val BODY_NAMES = arrayOf("Black", "Graphite", "White")

    /**
     * Background gradients: top, bottom, glow behind the cube. The saved setting is an index, so
     * new entries go at the end. AMOLED black and E-ink white are flat (no gradient, glow or
     * dithering), so OLED pixels stay fully off and e-ink screens get clean white.
     */
    val BACKGROUNDS = arrayOf(
        intArrayOf(0x1A2238, 0x070A12, 0x0F2229),
        intArrayOf(0x000000, 0x000000, 0x000000),
        intArrayOf(0xF2F4F8, 0xD9DEE6, 0x0A0A0A),
        intArrayOf(0xFFFFFF, 0xFFFFFF, 0x000000),
    )
    val BACKGROUND_NAMES = arrayOf("Dark night", "AMOLED black", "Light", "E-ink white")

    /** How the app's own UI (text, panels, toolbar) is drawn over the background [colors]. */
    fun tone(colors: IntArray): Tone = when {
        luminance(colors[0]) + luminance(colors[1]) > 1f -> Tone.LIGHT
        colors.all { it == 0 } -> Tone.BLACK
        else -> Tone.DEFAULT
    }

    private fun luminance(rgb: Int): Float =
        (0.2126f * (rgb shr 16 and 0xFF) + 0.7152f * (rgb shr 8 and 0xFF) + 0.0722f * (rgb and 0xFF)) / 255f
}

/**
 * DEFAULT: navy panels matching Dark night. BLACK: the same light-on-dark UI on pure black
 * surfaces (AMOLED black). LIGHT: dark text on white surfaces (Light and E-ink white).
 */
enum class Tone { DEFAULT, BLACK, LIGHT }
