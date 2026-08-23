package xyz.botolog.ghostify.background.core

/**
 * Thin abstraction over `AudioManager` so [AudioFocusController] is fully
 * JVM-testable. The production implementation lives in the Android glue layer.
 */
interface AudioFocusDriver {

    /**
     * Requests audio focus.
     *
     * @param listener callback invoked when the system grants, loses or changes focus.
     * @return whether focus was granted.
     */
    fun requestFocus(listener: AudioFocusChangeListener): AudioFocusRequestResult

    /** Abandons any held audio focus. */
    fun abandon()

    /**
     * Callback interface for audio-focus changes.
     */
    fun interface AudioFocusChangeListener {

        /**
         * Called when the system changes the audio-focus state.
         *
         * @param loss the type of focus loss (or gain).
         */
        fun onChange(loss: AudioFocusLoss)
    }
}

/** Result of an audio-focus request. */
enum class AudioFocusRequestResult {

    /** Focus was granted. */
    GRANTED,

    /** Focus was denied. */
    DENIED,
    ;

    /** Convenience property: true when this result is [GRANTED]. */
    val isGranted: Boolean get() = this == GRANTED
}

/** Decision returned by [AudioFocusController.requestFocus]. */
enum class PlayDecision {

    /** Focus acquired — safe to play. */
    GRANTED,

    /** Focus denied — do not play. */
    DENIED,
}
