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

    val focusListener: AudioFocusDriver.AudioFocusChangeListener =
        AudioFocusDriver.AudioFocusChangeListener { loss -> handleFocusChange(loss) }

    /** Call exactly once when requesting focus to begin playback. */
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

    /** Release focus (call when stopping playback / tearing down the player). */
    fun abandon() {
        Timber.i("AudioFocusController.abandon: START")
        if (hasFocus) {
            driver.abandon()
            hasFocus = false
            isDucking = false
            pausedByTransient = false
        }
    }

    private fun handleFocusChange(loss: AudioFocusLoss) {
        val action = policy.decide(loss, control.isPlaying, isDucking, pausedByTransient)
        Timber.d("AudioFocusController: state changed to $action (loss=$loss)")
        when (action) {
            FocusAction.PAUSE -> {
                val playingBefore = control.isPlaying
                control.pause()
                // Remember *our* pause so a later GAIN resumes only if we paused.
                if (loss == AudioFocusLoss.LOSS) {
                    pausedByTransient = false // permanent: no resume, not "transient"
                } else {
                    pausedByTransient = playingBefore
                }
            }
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
        // Permanent loss: hand focus back to the system regardless of the action.
        if (loss == AudioFocusLoss.LOSS) {
            driver.abandon()
            hasFocus = false
            pausedByTransient = false
            isDucking = false
        }
    }
}
