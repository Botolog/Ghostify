package com.ghostify.background.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoisyPolicyTest {

    @Test
    fun `becoming noisy action should pause`() {
        assertTrue(NoisyPolicy.shouldPause(NoisyPolicy.ACTION_AUDIO_BECOMING_NOISY))
    }

    @Test
    fun `other actions should not pause`() {
        assertEquals(false, NoisyPolicy.shouldPause("android.intent.action.BOOT_COMPLETED"))
        assertEquals(false, NoisyPolicy.shouldPause(null))
        assertEquals(false, NoisyPolicy.shouldPause(""))
    }

    @Test
    fun `the noisy action string matches the platform constant`() {
        assertEquals("android.media.AUDIO_BECOMING_NOISY", NoisyPolicy.ACTION_AUDIO_BECOMING_NOISY)
    }
}
