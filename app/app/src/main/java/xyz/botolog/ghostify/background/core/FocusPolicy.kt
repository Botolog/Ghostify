package xyz.botolog.ghostify.background.core

import timber.log.Timber

/**
 * Android `AudioManager` focus-loss codes modelled as a pure enum so the focus
 * *policy* (T-099) is JVM-testable. The integer values mirror the platform's
 * `AudioManager` constants exactly, so the glue layer can translate without any
 * extra state.
 *
 * @property code the integer constant from `AudioManager`.
 */
enum class AudioFocusLoss(val code: Int) {

    /** Focus gained (or regained after a transient loss). */
    GAIN(1),

    /** Permanent focus loss — another app took exclusive control. */
    LOSS(-1),

    /** Transient focus loss — another app needs brief access. */
    LOSS_TRANSIENT(-2),

    /** Transient focus loss, but ducking is acceptable. */
    LOSS_TRANSIENT_CAN_DUCK(-3),

    /** Unrecognised focus-change code. */
    UNKNOWN(0);

    companion object {

        /**
         * Maps an integer focus-loss code to the corresponding enum constant.
         *
         * @param code the integer code from `AudioManager`.
         * @return the matching [AudioFocusLoss], or [UNKNOWN] if unrecognised.
         */
        fun fromInt(code: Int): AudioFocusLoss =
            values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * What the controller should do to the player in reaction to a focus change.
 *
 * ABANDON (permanent loss) additionally tells the driver to release focus;
 * the player action is applied by the [AudioFocusController].
 */
enum class FocusAction {

    /** Pause playback (focus lost transiently or permanently). */
    PAUSE,

    /** Lower volume, keep playing (can-duck loss). */
    DUCK,

    /** Resume after a transient pause. */
    RESUME,

    /** Un-duck after a ducking loss, on GAIN. */
    RESTORE_VOLUME,

    /** Hand focus back to the system (permanent loss). */
    ABANDON,

    /** Focus change requires no player action. */
    NOOP,
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

    /**
     * Decides the [FocusAction] for a given focus event and player state.
     *
     * @param loss the type of focus change.
     * @param wasPlaying whether the player was actively playing when the event arrived.
     * @param isDucking whether the player is currently in ducked-volume mode.
     * @param pausedByTransient whether we paused due to a transient loss (not user-initiated).
     * @return the single [FocusAction] the controller should apply.
     */
    fun decide(
        loss: AudioFocusLoss,
        wasPlaying: Boolean,
        isDucking: Boolean,
        pausedByTransient: Boolean,
    ): FocusAction {
        Timber.i("FocusPolicy.decide: START")
        val result = when (loss) {
            AudioFocusLoss.GAIN -> decideGain(isDucking, pausedByTransient)
            AudioFocusLoss.LOSS -> decideLoss(wasPlaying)
            AudioFocusLoss.LOSS_TRANSIENT -> decideTransientLoss(wasPlaying)
            AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK -> decideDuckLoss(wasPlaying)
            AudioFocusLoss.UNKNOWN -> FocusAction.NOOP
        }
        Timber.i("FocusPolicy.decide: returning $result")
        return result
    }

    /** Handles focus gain after a ducking or transient-pause event. */
    private fun decideGain(isDucking: Boolean, pausedByTransient: Boolean): FocusAction = when {
        isDucking -> FocusAction.RESTORE_VOLUME
        pausedByTransient -> FocusAction.RESUME
        else -> FocusAction.NOOP
    }

    /** Handles permanent focus loss. */
    private fun decideLoss(wasPlaying: Boolean): FocusAction =
        if (wasPlaying) FocusAction.PAUSE else FocusAction.NOOP

    /** Handles transient focus loss. */
    private fun decideTransientLoss(wasPlaying: Boolean): FocusAction =
        if (wasPlaying) FocusAction.PAUSE else FocusAction.NOOP

    /** Handles transient loss where ducking is permitted. */
    private fun decideDuckLoss(wasPlaying: Boolean): FocusAction =
        if (wasPlaying) FocusAction.DUCK else FocusAction.NOOP
}
