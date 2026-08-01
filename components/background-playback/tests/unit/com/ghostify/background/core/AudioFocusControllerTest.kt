package com.ghostify.background.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusControllerTest {

    private class FakeDriver : AudioFocusDriver {
        val calls = mutableListOf<String>()
        var granted = true
        lateinit var listener: AudioFocusDriver.AudioFocusChangeListener
        override fun requestFocus(listener: AudioFocusDriver.AudioFocusChangeListener): AudioFocusRequestResult {
            this.listener = listener
            calls += "requestFocus"
            return if (granted) AudioFocusRequestResult.GRANTED else AudioFocusRequestResult.DENIED
        }
        override fun abandon() { calls += "abandon" }
        fun deliver(loss: AudioFocusLoss) = listener.onChange(loss)
    }

    private class FakeControl : PlaybackControl {
        val calls = mutableListOf<String>()
        var playing = false
        var ducked = false
        override val currentPositionMs = 0L
        override val durationMs = 0L
        override val speed = 1f
        override val isLoading = false
        override val isPlaying: Boolean get() = playing
        override fun play() { calls += "play"; playing = true }
        override fun pause() { calls += "pause"; playing = false }
        override fun stop() { calls += "stop"; playing = false }
        override fun setDucking(duck: Boolean) { ducked = duck; calls += if (duck) "duck" else "unduck" }
    }

    @Test
    fun `requestFocus registers listener and reports granted`() {
        val driver = FakeDriver()
        val control = FakeControl()
        val controller = AudioFocusController(driver, control)
        assertEquals(PlayDecision.GRANTED, controller.requestFocus())
        assertEquals("requestFocus", driver.calls.single())
        assertTrue(controller.hasFocus)
    }

    @Test
    fun `requestFocus denied reports denied`() {
        val driver = FakeDriver().apply { granted = false }
        val control = FakeControl()
        val controller = AudioFocusController(driver, control)
        assertEquals(PlayDecision.DENIED, controller.requestFocus())
        assertFalse(controller.hasFocus)
    }

    @Test
    fun `transient loss pauses a playing player so it can resume`() {
        val driver = FakeDriver()
        val control = FakeControl().apply { playing = true }
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        driver.deliver(AudioFocusLoss.LOSS_TRANSIENT)
        // playing -> pause; focus kept (transient, no abandon)
        assertEquals(listOf("pause"), control.calls.filter { it == "pause" || it == "play" })
        assertFalse(control.playing)
        assertFalse(driver.calls.contains("abandon"))
    }

    @Test
    fun `gain after transient loss resumes`() {
        val driver = FakeDriver()
        val control = FakeControl().apply { playing = true }
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        driver.deliver(AudioFocusLoss.LOSS_TRANSIENT) // pauses
        control.calls.clear()
        driver.deliver(AudioFocusLoss.GAIN)           // resumes
        assertEquals(listOf("play"), control.calls)
        assertTrue(control.playing)
    }

    @Test
    fun `can-duck loss ducks and gain restores volume`() {
        val driver = FakeDriver()
        val control = FakeControl().apply { playing = true }
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        driver.deliver(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(control.ducked)
        driver.deliver(AudioFocusLoss.GAIN)
        assertFalse(control.ducked)
    }

    @Test
    fun `gain while ducking restores volume without playing`() {
        val driver = FakeDriver()
        val control = FakeControl().apply { playing = true }
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        driver.deliver(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK)
        control.calls.clear()
        driver.deliver(AudioFocusLoss.GAIN)
        assertFalse(control.calls.contains("play"), "should not call play() when only un-ducking")
        assertFalse(control.calls.contains("pause"))
        assertFalse(control.ducked)
    }

    @Test
    fun `permanent loss abandons focus`() {
        val driver = FakeDriver()
        val control = FakeControl().apply { playing = true }
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        assertTrue(controller.hasFocus)
        driver.deliver(AudioFocusLoss.LOSS)
        assertTrue(driver.calls.contains("abandon"))
        assertFalse(controller.hasFocus)
    }

    @Test
    fun `abandon clears state and is idempotent`() {
        val driver = FakeDriver()
        val control = FakeControl()
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        controller.abandon()
        assertTrue(driver.calls.contains("abandon"))
        driver.calls.clear()
        controller.abandon() // second call should be a no-op (no double abandon)
        assertFalse(driver.calls.contains("abandon"))
    }

    @Test
    fun `transient loss when not playing is a no-op`() {
        val driver = FakeDriver()
        val control = FakeControl() // not playing
        val controller = AudioFocusController(driver, control)
        controller.requestFocus()
        driver.deliver(AudioFocusLoss.LOSS_TRANSIENT)
        assertTrue(control.calls.isEmpty())
    }
}
