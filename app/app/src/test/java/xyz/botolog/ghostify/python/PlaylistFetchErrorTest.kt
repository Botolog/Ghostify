package xyz.botolog.ghostify.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFetchErrorTest {

    // ── PlaylistFetchErrorCode.fromWire ───────────────────────────────────

    @Test
    fun fromWireReturnsNoNetwork() {
        assertEquals(PlaylistFetchErrorCode.NO_NETWORK, PlaylistFetchErrorCode.fromWire("NO_NETWORK"))
    }

    @Test
    fun fromWireReturnsRateLimited() {
        assertEquals(PlaylistFetchErrorCode.RATE_LIMITED, PlaylistFetchErrorCode.fromWire("RATE_LIMITED"))
    }

    @Test
    fun fromWireReturnsPrivate() {
        assertEquals(PlaylistFetchErrorCode.PRIVATE, PlaylistFetchErrorCode.fromWire("PRIVATE"))
    }

    @Test
    fun fromWireReturnsTimeout() {
        assertEquals(PlaylistFetchErrorCode.TIMEOUT, PlaylistFetchErrorCode.fromWire("TIMEOUT"))
    }

    @Test
    fun fromWireReturnsNotFound() {
        assertEquals(PlaylistFetchErrorCode.NOT_FOUND, PlaylistFetchErrorCode.fromWire("NOT_FOUND"))
    }

    @Test
    fun fromWireReturnsUnknown() {
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire("UNKNOWN"))
    }

    @Test
    fun fromWireReturnsUnknownForUnrecognisedToken() {
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire("SOMETHING_ELSE"))
    }

    @Test
    fun fromWireReturnsUnknownForNull() {
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire(null))
    }

    @Test
    fun fromWireReturnsUnknownForEmptyString() {
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire(""))
    }

    @Test
    fun fromWireIsCaseSensitive() {
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire("no_network"))
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, PlaylistFetchErrorCode.fromWire("No_Network"))
    }

    // ── PlaylistFetchErrorCode enum values ────────────────────────────────

    @Test
    fun allExpectedEnumValuesExist() {
        val expected = setOf(
            "NO_NETWORK", "RATE_LIMITED", "PRIVATE", "TIMEOUT", "NOT_FOUND", "UNKNOWN"
        )
        assertEquals(expected, PlaylistFetchErrorCode.entries.map { it.name }.toSet())
    }

    @Test
    fun enumValuesCountIsSix() {
        assertEquals(6, PlaylistFetchErrorCode.entries.size)
    }

    // ── PlaylistFetchError data class ─────────────────────────────────────

    @Test
    fun errorDataClassCreation() {
        val error = PlaylistFetchError(
            code = PlaylistFetchErrorCode.TIMEOUT,
            message = "Request timed out",
            retryHint = "Try again later"
        )
        assertEquals(PlaylistFetchErrorCode.TIMEOUT, error.code)
        assertEquals("Request timed out", error.message)
        assertEquals("Try again later", error.retryHint)
    }

    @Test
    fun errorDataClassDefaultsRetryHintToNull() {
        val error = PlaylistFetchError(
            code = PlaylistFetchErrorCode.NOT_FOUND,
            message = "Not found"
        )
        assertNull(error.retryHint)
    }

    @Test
    fun errorDataClassEquality() {
        val a = PlaylistFetchError(PlaylistFetchErrorCode.NO_NETWORK, "No connection")
        val b = PlaylistFetchError(PlaylistFetchErrorCode.NO_NETWORK, "No connection")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun errorDataClassCopy() {
        val original = PlaylistFetchError(
            PlaylistFetchErrorCode.PRIVATE, "Private playlist"
        )
        val copied = original.copy(message = "Updated message")
        assertEquals("Updated message", copied.message)
        assertEquals(PlaylistFetchErrorCode.PRIVATE, copied.code)
    }

    // ── PlaylistFetchError.unknown ────────────────────────────────────────

    @Test
    fun unknownReturnsErrorWithUnknownCode() {
        val error = PlaylistFetchError.unknown("Something broke")
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, error.code)
        assertEquals("Something broke", error.message)
    }

    @Test
    fun unknownUsesFallbackForNull() {
        val error = PlaylistFetchError.unknown(null)
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, error.code)
        assertEquals("The playlist could not be fetched.", error.message)
    }

    @Test
    fun unknownUsesFallbackForBlank() {
        val error = PlaylistFetchError.unknown("   ")
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, error.code)
        assertEquals("The playlist could not be fetched.", error.message)
    }

    @Test
    fun unknownUsesFallbackForEmptyString() {
        val error = PlaylistFetchError.unknown("")
        assertEquals(PlaylistFetchErrorCode.UNKNOWN, error.code)
        assertEquals("The playlist could not be fetched.", error.message)
    }

    @Test
    fun unknownPreservesNonBlankMessage() {
        val error = PlaylistFetchError.unknown("  Something went wrong  ")
        assertEquals("  Something went wrong  ", error.message)
    }

    // ── PlaylistFetchResult sealed class ──────────────────────────────────

    @Test
    fun successHoldsMetadata() {
        val metadata = PlaylistMetadata(
            name = "My Playlist",
            owner = "user1",
            coverUrl = null,
            description = null,
            trackCount = 0,
            tracks = emptyList(),
        )
        val result = PlaylistFetchResult.Success(metadata)
        assertTrue(result is PlaylistFetchResult.Success)
        assertEquals("My Playlist", result.metadata.name)
    }

    @Test
    fun failureHoldsError() {
        val error = PlaylistFetchError(PlaylistFetchErrorCode.NO_NETWORK, "No net")
        val result = PlaylistFetchResult.Failure(error)
        assertTrue(result is PlaylistFetchResult.Failure)
        assertEquals(PlaylistFetchErrorCode.NO_NETWORK, result.error.code)
    }

    @Test
    fun successAndFailureAreDistinct() {
        val metadata = PlaylistMetadata("P", "O", null, null, 0, emptyList())
        val error = PlaylistFetchError(PlaylistFetchErrorCode.UNKNOWN, "e")
        val success = PlaylistFetchResult.Success(metadata)
        val failure = PlaylistFetchResult.Failure(error)
        assertTrue(success != failure)
    }
}
