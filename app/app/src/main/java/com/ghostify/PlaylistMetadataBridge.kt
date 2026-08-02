package com.ghostify.python

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python

/**
 * Kotlin bridge to the `ghostify_dl` Python module (Chaquopy).
 *
 * Responsibilities
 * ----------------
 * 1. Invoke `ghostify_dl.fetch_playlist(spotify_id, options)`.
 * 2. Convert the Python result dict into the typed [PlaylistMetadata] model.
 * 3. Map every Python-side failure to a [PlaylistFetchError] — never a raw
 *    crash (T-021). Python raises `GhostifyError` whose message is
 *    `"<CODE>: <human message>"`; Chaquopy prefixes the Python exception type
 *    name, so [parsePyException] locates the `CODE` token anywhere in the
 *    message rather than assuming a fixed format.
 *
 * Threading contract
 * ------------------
 * `fetchPlaylistBlocking` performs network + Python work and must never be
 * called on the main thread. Callers (ViewModels/UseCases, per PROJECT.md
 * §7) dispatch it on a background dispatcher; this class additionally
 * serializes concurrent calls on a single instance so the Chaquopy
 * interpreter is never touched concurrently (T-029).
 */
class PlaylistMetadataBridge(
    private val moduleName: String = DEFAULT_MODULE_NAME,
    private val options: Map<String, Any> = DEFAULT_OPTIONS
) {

    /**
     * Fetches playlist metadata. Never throws: every outcome is a
     * [PlaylistFetchResult].
     *
     * @param spotifyId playlist id, `open.spotify.com/playlist/...` URL or
     *   `spotify:playlist:...` URI.
     */
    fun fetchPlaylistBlocking(spotifyId: String): PlaylistFetchResult {
        synchronized(this) {
            return try {
                val python = Python.getInstance()
                val module = python.getModule(moduleName)
                val result = module.callAttr(
                    "fetch_playlist", spotifyId, toPyOptions(python, options)
                )
                parseSuccess(result)
            } catch (e: PyException) {
                parsePyException(e)
            } catch (e: RuntimeException) {
                // e.g. Python not started, interpreter unavailable, malformed
                // result. Never let a raw bridge failure escape (T-021).
                PlaylistFetchResult.Failure(PlaylistFetchError.unknown(e.message))
            }
        }
    }

    // ------------------------------------------------------------------
    // Option marshalling.
    // ------------------------------------------------------------------

    /**
     * Converts a Kotlin options [Map] into a real Python dict. Chaquopy
     * auto-converts primitives, String and arrays only — a `Map` would cross
     * the bridge as an opaque Java proxy and Python's
     * `_normalize_options` (`dict(options)`) would fail with
     * "TypeError: 'LinkedHashMap' object is not iterable". Building the dict
     * here sidesteps that (T-019/T-021).
     */
    private fun toPyOptions(python: Python, options: Map<String, Any>): PyObject {
        val dict = python.getModule("builtins").callAttr("dict")
        for ((key, value) in options) {
            dict.callAttr("__setitem__", key, value)
        }
        return dict
    }

    // ------------------------------------------------------------------
    // Success parsing.
    // ------------------------------------------------------------------

    private fun parseSuccess(result: PyObject): PlaylistFetchResult {
        val raw = result.toJava(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val map = raw as? Map<String, Any?>
            ?: return PlaylistFetchResult.Failure(
                PlaylistFetchError.unknown("The bridge returned an unexpected result.")
            )
        return PlaylistFetchResult.Success(PlaylistMetadata.fromMap(map))
    }

    // ------------------------------------------------------------------
    // Failure parsing.
    // ------------------------------------------------------------------

    private fun parsePyException(e: PyException): PlaylistFetchResult {
        val message = e.message ?: ""
        val match = CODE_PATTERN.find(message)
        val code = match?.value
            ?.let { PlaylistFetchErrorCode.fromWire(it) }
            ?: PlaylistFetchErrorCode.UNKNOWN

        val humanMessage = extractHumanMessage(message, match)
        return PlaylistFetchResult.Failure(
            PlaylistFetchError(
                code = code,
                message = humanMessage,
                retryHint = retryHintFor(code)
            )
        )
    }

    /**
     * Returns the text after `"CODE: "` when a code token was found, or the
     * full message otherwise. Never returns a blank string.
     */
    private fun extractHumanMessage(message: String, match: MatchResult?): String {
        if (match != null) {
            val start = match.range.last + 2 // skip past "CODE: "
            if (start <= message.length) {
                val after = message.substring(start).trim()
                if (after.isNotEmpty()) return after
            }
        }
        return message.ifEmpty { "The playlist could not be fetched." }
    }

    private fun retryHintFor(code: PlaylistFetchErrorCode): String? = when (code) {
        PlaylistFetchErrorCode.NO_NETWORK -> "Check your network connection and try again."
        PlaylistFetchErrorCode.RATE_LIMITED -> "Wait a moment, then try again."
        PlaylistFetchErrorCode.PRIVATE -> "Only public playlists are supported."
        PlaylistFetchErrorCode.TIMEOUT -> "The request took too long. Try again."
        PlaylistFetchErrorCode.NOT_FOUND -> "Check the link and try again."
        PlaylistFetchErrorCode.UNKNOWN -> null
    }

    companion object {
        const val DEFAULT_MODULE_NAME = "ghostify_dl"

        val DEFAULT_OPTIONS: Map<String, Any> = mapOf(
            "timeout" to 180.0,
            "resolve_yt" to true,
            "per_track_yt_timeout" to 8.0
        )

        /**
         * Stable wire codes emitted by `ghostify_dl.GhostifyError`. Matched
         * anywhere in the exception message because Chaquopy prefixes it with
         * the Python exception type name.
         */
        private val CODE_PATTERN = Regex(
            "\\b(?:NO_NETWORK|RATE_LIMITED|PRIVATE|TIMEOUT|NOT_FOUND|UNKNOWN)\\b"
        )
    }
}
