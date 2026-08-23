package xyz.botolog.ghostify.background.core

import timber.log.Timber

/**
 * Explicit, testable audio-focus controller (T-099).
 *
 * Drives [PlaybackControl] in reaction to Android focus changes:
 *  - permanent LOSS  -> pause + abandon
 *  - transient LOSS  -> pause (resumes on GAIN)
 *  - can-duck LOSS   -> lower volume (restores on GAIN)
 *  - GAIN            -> resume after a transient pause, or restore volume after
 *                       ducking
 *
 * Focus is requested explicitly before the player starts (handleAudioFocus=false
 * on ExoPlayer so the player does not fight us). The controller never re-requests
 * focus on its own after the initial request; it only reacts to loss/gain.
 *
 * All decisions are delegated to [FocusPolicy]; this class owns the history
 * flags and applies the reactions.
 *
 * @param driver the platform-specific focus driver (production or test fake).
 * @param control the playback surface driven by focus decisions.
 * @param policy pure focus-change policy that produces [FocusAction] values.
 */
class AudioFocusController(
    private val driver: AudioFocusDriver,
    private val control: PlaybackControl,
    private val policy: FocusPolicy = FocusPolicy(),
) {

    /** True once a focus listener has been registered and not abandoned. */
    var hasFocus: Boolean = false
        private set

    /** True when we ducked the volume and have not yet restored it. */
    private var isDucking = false

    /** True when *we* paused because of a transient focus loss (not the user). */
    private var pausedByTransient = false

    /** Callback registered with the driver that routes focus changes to [handleFocusChange]. */
    val focusListener: AudioFocusDriver.AudioFocusChangeListener =
        AudioFocusDriver.AudioFocusChangeListener { loss -> handleFocusChange(loss) }

    /**
     * Requests audio focus and reports whether playback may begin.
     *
     * Call exactly once when starting playback.
     *
     * @return [PlayDecision.GRANTED] if focus was acquired, [PlayDecision.DENIED] otherwise.
     */
    fun requestFocus(): PlayDecision {
        Timber.i("AudioFocusController.requestFocus: START")
        val result = driver.requestFocus(focusListener)
        val decision = if (result.isGranted) {
            hasFocus = true
            PlayDecision.GRANTED
        } else {
            PlayDecision.DENIED
        }
        Timber.i("AudioFocusController.requestFocus: returning $decision")
        return decision
    }

    /**
     * Releases audio focus and resets internal state.
     *
     * Call when stopping playback or tearing down the player.
     */
    fun abandon() {
        Timber.i("AudioFocusController.abandon: START")
        if (hasFocus) {
            driver.abandon()
            hasFocus = false
            isDucking = false
            pausedByTransient = false
        }
    }

    /** Routes a focus-loss event through the policy and applies the resulting action. */
    private fun handleFocusChange(loss: AudioFocusLoss) {
        val action = policy.decide(loss, control.isPlaying, isDucking, pausedByTransient)
        Timber.d("AudioFocusController: state changed to $action (loss=$loss)")
        applyFocusAction(action, loss)
        cleanupAfterPermanentLoss(loss)
    }

    /**
     * Applies the [FocusAction] produced by the policy.
     *
     * @param action the action to apply.
     * @param loss the original focus-loss event (used to decide transient vs permanent).
     */
    private fun applyFocusAction(action: FocusAction, loss: AudioFocusLoss) {
        when (action) {
            FocusAction.PAUSE -> applyPause(loss)
            FocusAction.DUCK -> {
                control.setDucking(true)
                isDucking = true
            }
            FocusAction.RESUME -> {
                control.play()
                pausedByTransient = false
            }
            FocusAction.RESTORE_VOLUME -> {
                control.setDucking(false)
                isDucking = false
            }
            FocusAction.ABANDON -> {
                driver.abandon()
                hasFocus = false
            }
            FocusAction.NOOP -> Unit
        }
    }

    /**
     * Pauses playback and records whether the pause was transient.
     *
     * @param loss the focus-loss event (permanent vs transient determines resume behaviour).
     */
    private fun applyPause(loss: AudioFocusLoss) {
        val playingBefore = control.isPlaying
        control.pause()
        if (loss == AudioFocusLoss.LOSS) {
            pausedByTransient = false
        } else {
            pausedByTransient = playingBefore
        }
    }

    /**
     * On permanent loss, hands focus back to the system regardless of the action taken.
     *
     * @param loss the focus-loss event.
     */
    private fun cleanupAfterPermanentLoss(loss: AudioFocusLoss) {
        if (loss != AudioFocusLoss.LOSS) return
        driver.abandon()
        hasFocus = false
        pausedByTransient = false
        isDucking = false
    }
}
