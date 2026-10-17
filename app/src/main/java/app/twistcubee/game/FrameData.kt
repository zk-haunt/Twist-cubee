package app.twistcubee.game

/**
 * Everything the renderer needs for one frame, copied from the game under its lock so the GL
 * thread can draw without holding it. Preallocated and reused every frame.
 */
class FrameData {
    var n = 3
    var geometryVersion = -1

    val viewProj = FloatArray(16)
    /** Cube orientation * uniform scale (cube units -> world). */
    val model = FloatArray(16)
    val eye = FloatArray(3)
    var shiftX = 0f
    var shiftY = 0f
    var aspect = 1f

    /** Layer being animated: axis (-1 = none), layer range and angle in radians. */
    var animAxis = -1
    var animLo = 0
    var animHi = 0
    var animAngle = 0f

    /** Hint highlight: axis (-1 = none), layer range and strength 0..1. */
    var hintAxis = -1
    var hintLo = 0
    var hintHi = 0
    var hintAmount = 0f

    var colorsVersion = -1
    var colors = ByteArray(0)

    /** Sticker palette (6 RGB triples, face order U R F D L B) and body color. */
    var paletteVersion = 0
    val palette = FloatArray(18)
    val body = FloatArray(3)

    /** Background gradient top, bottom and glow (3 RGB triples). */
    val background = FloatArray(9)

    /** 0 = stickerless tiles, 1 = classic stickers. */
    var tileStyle = 0
}
