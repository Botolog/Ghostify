package com.ghostify.security

import java.net.URI
import timber.log.Timber

/**
 * Thrown when a caller tries to open a non-HTTPS connection (T-171).
 */
class HttpsRequiredException(val url: String) :
    IllegalArgumentException("Ghostify forbids cleartext HTTP. Refusing: $url. Use https://.")

/**
 * HTTPS enforcement policy (T-171). Every network call in Ghostify must go
 * through this object (or through a client that calls [requireHttps]) so that
 * a cleartext URL is rejected before any bytes leave the device.
 *
 * Belt-and-braces on top of the manifest: `usesCleartextTraffic="false"` (see
 * docs/HTTPS-ENFORCEMENT.md) stops the platform from allowing http:// at the
 * network stack; this policy stops the *code* from even attempting it, which
 * catches URLs built at runtime (e.g. a user-pasted playlist URL) that no
 * static check can see.
 */
object HttpPolicy {

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://""")

    /** True only for `https://` URLs (case-insensitive scheme). */
    fun isHttps(url: String): Boolean {
        Timber.i("HttpPolicy.isHttps: START")
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        val result = scheme == "https"
        Timber.i("HttpPolicy.isHttps: returning $result")
        return result
    }

    /**
     * Return [url] if it is HTTPS, otherwise throw [HttpsRequiredException].
     * The normalized URL is returned so callers log/send the exact same string.
     */
    fun requireHttps(url: String): String {
        Timber.i("HttpPolicy.requireHttps: START")
        if (!isHttps(url)) throw HttpsRequiredException(url)
        Timber.i("HttpPolicy.requireHttps: returning $url")
        return url
    }

    /**
     * Drop any `user:pass@` prefix a user may have pasted into a URL.
     * Credentials embedded in a URL must never be retained or logged.
     */
    fun stripUserInfo(url: String): String {
        Timber.i("HttpPolicy.stripUserInfo: START")
        return try {
            val u = URI(url)
            if (u.userInfo == null) {
                Timber.i("HttpPolicy.stripUserInfo: returning $url")
                url
            } else {
                val result = URI(u.scheme, null, u.host, u.port, u.path, u.query, u.fragment).toString()
                Timber.i("HttpPolicy.stripUserInfo: returning $result")
                result
            }
        } catch (t: Exception) {
            Timber.e(t, "HttpPolicy: stripUserInfo FAILED")
            url
        }
    }
}
