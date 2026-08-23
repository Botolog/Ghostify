package xyz.botolog.ghostify.player.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.background.core.AudioFocusLoss
import xyz.botolog.ghostify.background.core.FocusAction
import xyz.botolog.ghostify.background.core.FocusPolicy

class FocusPolicyTest {

    private lateinit var policy: FocusPolicy

    @Before
    fun setUp() {
        policy = FocusPolicy()
    }

    // ── GAIN ──────────────────────────────────────────────

    @Test
    fun `GAIN while ducking returns RESTORE_VOLUME`() {
        assertEquals(FocusAction.RESTORE_VOLUME, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = true, pausedByTransient = false))
    }

    @Test
    fun `GAIN after transient pause returns RESUME`() {
        assertEquals(FocusAction.RESUME, policy.decide(AudioFocusLoss.GAIN, wasPlaying = false, isDucking = false, pausedByTransient = true))
    }

    @Test
    fun `GAIN with no ducking and no transient pause returns NOOP`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `GAIN while ducking and pausedByTransient prioritises RESTORE_VOLUME`() {
        assertEquals(FocusAction.RESTORE_VOLUME, policy.decide(AudioFocusLoss.GAIN, wasPlaying = true, isDucking = true, pausedByTransient = true))
    }

    // ── LOSS ──────────────────────────────────────────────

    @Test
    fun `LOSS when wasPlaying returns PAUSE`() {
        assertEquals(FocusAction.PAUSE, policy.decide(AudioFocusLoss.LOSS, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `LOSS when not playing returns NOOP`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.LOSS, wasPlaying = false, isDucking = false, pausedByTransient = false))
    }

    // ── LOSS_TRANSIENT ────────────────────────────────────

    @Test
    fun `LOSS_TRANSIENT when wasPlaying returns PAUSE`() {
        assertEquals(FocusAction.PAUSE, policy.decide(AudioFocusLoss.LOSS_TRANSIENT, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `LOSS_TRANSIENT when not playing returns NOOP`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.LOSS_TRANSIENT, wasPlaying = false, isDucking = false, pausedByTransient = false))
    }

    // ── LOSS_TRANSIENT_CAN_DUCK ───────────────────────────

    @Test
    fun `LOSS_TRANSIENT_CAN_DUCK when wasPlaying returns DUCK`() {
        assertEquals(FocusAction.DUCK, policy.decide(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK, wasPlaying = true, isDucking = false, pausedByTransient = false))
    }

    @Test
    fun `LOSS_TRANSIENT_CAN_DUCK when not playing returns NOOP`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK, wasPlaying = false, isDucking = false, pausedByTransient = false))
    }

    // ── UNKNOWN ───────────────────────────────────────────

    @Test
    fun `UNKNOWN returns NOOP`() {
        assertEquals(FocusAction.NOOP, policy.decide(AudioFocusLoss.UNKNOWN, wasPlaying = true, isDucking = true, pausedByTransient = true))
    }

    // ── AudioFocusLoss.fromInt ────────────────────────────

    @Test
    fun `fromInt(1) returns GAIN`() {
        assertEquals(AudioFocusLoss.GAIN, AudioFocusLoss.fromInt(1))
    }

    @Test
    fun `fromInt(-1) returns LOSS`() {
        assertEquals(AudioFocusLoss.LOSS, AudioFocusLoss.fromInt(-1))
    }

    @Test
    fun `fromInt(-2) returns LOSS_TRANSIENT`() {
        assertEquals(AudioFocusLoss.LOSS_TRANSIENT, AudioFocusLoss.fromInt(-2))
    }

    @Test
    fun `fromInt(-3) returns LOSS_TRANSIENT_CAN_DUCK`() {
        assertEquals(AudioFocusLoss.LOSS_TRANSIENT_CAN_DUCK, AudioFocusLoss.fromInt(-3))
    }

    @Test
    fun `fromInt(0) returns UNKNOWN`() {
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(0))
    }

    @Test
    fun `fromInt(999) returns UNKNOWN for unrecognised code`() {
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(999))
    }

    @Test
    fun `fromInt(-99) returns UNKNOWN for unrecognised code`() {
        assertEquals(AudioFocusLoss.UNKNOWN, AudioFocusLoss.fromInt(-99))
    }
}
