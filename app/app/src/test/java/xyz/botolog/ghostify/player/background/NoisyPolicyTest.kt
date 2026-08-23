package xyz.botolog.ghostify.player.background

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.background.core.NoisyPolicy

class NoisyPolicyTest {

    @Test
    fun `shouldPause true for ACTION_AUDIO_BECOMING_NOISY`() {
        assertEquals(true, NoisyPolicy.shouldPause(NoisyPolicy.ACTION_AUDIO_BECOMING_NOISY))
    }

    @Test
    fun `shouldPause false for unrelated action`() {
        assertEquals(false, NoisyPolicy.shouldPause("android.intent.action.SOME_OTHER_ACTION"))
    }

    @Test
    fun `shouldPause false for empty string`() {
        assertEquals(false, NoisyPolicy.shouldPause(""))
    }

    @Test
    fun `shouldPause false for null`() {
        assertEquals(false, NoisyPolicy.shouldPause(null))
    }

    @Test
    fun `shouldPause false for partial match`() {
        assertEquals(false, NoisyPolicy.shouldPause("android.media.AUDIO_BECOMING"))
    }

    @Test
    fun `ACTION_AUDIO_BECOMING_NOISY constant matches expected value`() {
        assertEquals("android.media.AUDIO_BECOMING_NOISY", NoisyPolicy.ACTION_AUDIO_BECOMING_NOISY)
    }
}
