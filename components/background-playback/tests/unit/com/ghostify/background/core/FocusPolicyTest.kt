package com.ghostify.background.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FocusPolicyTest {

    private val policy = FocusPolicy()

    @Test
    fun `permanent loss while playing pauses`() {
        assertEquals(FocusAction.PAUSE, policy.decide(AudioFocusLoss.LOSS, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `permanent loss while not playing is a no-op`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.LOSS, wasPlaying = false, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `transient loss while playing pauses for resume later`() {
        assertEquals(FocusAction.PAUSE, policy.decide(AudioFocusLoss.LOSS_TRANSIENT, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `transient loss while not playing is a no-op`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.LOSS_TRANSIENT, wasPlaying = false, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `can-duck loss while playing ducks`() {
        assertEquals(FocusAction.DUCK, policy.decide(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `gain after ducking restores volume`() {
        assertEquals(FocusAction.RESTORE_VOLUME, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = true, pausedByTransient = false))
    }

    @Test
    fun `gain after transient pause resumes`() {
        assertEquals(FocusAction.RESUME, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = false, pausedByTransient = true))
    }

    @Test
    fun `gain when not ducked and not paused-by-focus is a no-op`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `unknown loss code is a no-op`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.UNKNOWN, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }
}

class AudioFocusLossTest {

    @Test
    fun `fromInt maps platform codes`() {
        assertEquals(AudioFocusLoss.GAIN, AudioFocusLoss.fromInt(1))
        assertEquals(AudioFocusLoss.LOSS, AudioFocusLoss.fromInt(-1))
        assertEquals(AudioFocusLoss.LOSS_TRANSIENT, AudioFocusLoss.fromInt(-2))
        assertEquals(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK, AudioFocusLoss.fromInt(-3))
    }

    @Test
    fun `fromInt maps unknown to UNKNOWN`() {
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(0))
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(42))
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(-99))
    }
}
