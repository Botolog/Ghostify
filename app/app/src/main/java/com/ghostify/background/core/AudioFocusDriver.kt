package com.ghostify.background.core

/**
 * Thin abstraction over `AudioManager` so [AudioFocusController] is fully
 * JVM-testable. The production implementation lives in the Android glue layer.
 */
interface AudioFocusDriver {
    fun requestFocus(listener: AudioFocusChangeListener): AudioFocusRequestResult
    fun abandon()
    fun interface AudioFocusChangeListener {
        fun onChange(loss: AudioFocusLoss)
    }
}

enum class AudioFocusRequestResult {
    GRANTED,
    DENIED,
    ;

    val isGranted: Boolean get() = this == GRANTED
}

enum class PlayDecision {
    GRANTED,   // focus acquired -> play
    DENIED,    // focus denied -> do not play
}
