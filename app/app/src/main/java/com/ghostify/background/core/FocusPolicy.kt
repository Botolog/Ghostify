package com.ghostify.background.core

/**
 * Android `AudioManager` focus-loss codes modelled as a pure enum so the focus
 * *policy* (T-099) is JVM-testable. The integer values mirror the platform's
 * `AudioManager` constants exactly, so the glue layer can translate without any
 * extra state:
 *
 *   GAIN                  = 1
 *   LOSS                  = -1
 *   LOSS_TRANSIENT        = -2
 *   LOSS_TRANSIENT_CAN_DUCK = -3
 */
enum class AudioFocusLoss(val code: Int) {
    GAIN(1),
    LOSS(-1),
    LOSS_TRANSIENT(-2),
    LOSS_TRANSIENT_CAN_DUCK(-3),
    UNKNOWN(0);

    companion object {
        fun fromInt(code: Int): AudioFocusLoss = values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * What the controller should do to the player in reaction to a focus change.
 * ABANDON (permanent loss) additionally tells the driver to release focus;
 * the player action is applied by the [AudioFocusController].
 */
enum class FocusAction {
    PAUSE,          // pause playback (focus lost transiently or permanently)
    DUCK,           // lower volume, keep playing (can-duck loss)
    RESUME,         // resume after a transient pause
    RESTORE_VOLUME, // un-duck after a ducking loss, on GAIN
    ABANDON,        // hand focus back to the system (permanent loss)
    NOOP,           // focus change requires no player action
    ;
}

/**
 * The audio-focus *policy*: a pure function from the focus event + the player's
 * recent history to the single reaction the controller should take.
 *
 * History flags:
 *  - [wasPlaying]         true if media was playing when the loss arrived
 *                         (drives pause/duck/resume decisions).
 *  - [isDucking]          true if we are currently ducking (volume reduced).
 *  - [pausedByTransient]  true if WE paused the player due to a transient loss
 *                         (so a later GAIN knows to resume rather than treat
 *                         a user-initiated pause as resume-worthy).
 */
class FocusPolicy {

    fun decide(
        loss: AudioFocusLoss,
        wasPlaying: Boolean,
        isDucking: Boolean,
        pausedByTransient: Boolean,
    ): FocusAction = when (loss) {
        AudioFocusLoss.GAIN -> when {
            isDucking -> FocusAction.RESTORE_VOLUME
            pausedByTransient -> FocusAction.RESUME
            else -> FocusAction.NOOP
        }
        AudioFocusLoss.LOSS -> if (wasPlaying) FocusAction.PAUSE else FocusAction.NOOP
        AudioFocusLoss.LOSS_TRANSIENT -> if (wasPlaying) FocusAction.PAUSE else FocusAction.NOOP
        AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK -> if (wasPlaying) FocusAction.DUCK else FocusAction.NOOP
        AudioFocusLoss.UNKNOWN -> FocusAction.NOOP
    }
}
