package app.twistcubee.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Tactile feedback through the system haptics engine. View haptics need no VIBRATE permission and
 * follow the phone's own touch-feedback setting. Each event maps to a predefined effect (tick,
 * click, heavy click) so it feels crisp on every vibration motor.
 */
class Haptics(private val view: View) {
    private val pulse = Runnable { fire(HapticFeedbackConstants.LONG_PRESS) }

    var enabled = true

    /** A layer is picked up under the finger. */
    fun grab() = fire(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A dragged layer passes a quarter-turn position, like the click of a real cube. */
    fun detent() = fire(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CONTEXT_CLICK,
    )

    /** A turn snaps into place. */
    fun turn() = fire(HapticFeedbackConstants.VIRTUAL_KEY)

    /** An on-screen button. */
    fun tap() = fire(HapticFeedbackConstants.KEYBOARD_TAP)

    fun toggle(on: Boolean) = fire(
        when {
            Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CONTEXT_CLICK
            on -> HapticFeedbackConstants.TOGGLE_ON
            else -> HapticFeedbackConstants.TOGGLE_OFF
        },
    )

    /** An action that could not be done. */
    fun reject() = fire(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Inspection time warnings, at 8 and 12 seconds as a WCA judge would call them. */
    fun warn() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** Two heavy pulses for a solve, three for a new personal best. */
    fun solved(newBest: Boolean) {
        if (!enabled) return
        view.removeCallbacks(pulse)
        fire(HapticFeedbackConstants.LONG_PRESS)
        view.postDelayed(pulse, 140)
        if (newBest) view.postDelayed(pulse, 280)
    }

    private fun fire(constant: Int) {
        if (enabled) view.performHapticFeedback(constant)
    }
}
