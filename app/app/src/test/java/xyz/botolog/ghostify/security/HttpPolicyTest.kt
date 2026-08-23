package xyz.botolog.ghostify.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpPolicyTest {

    @Before
    fun setUp() {
        if (timber.log.Timber.treeCount == 0) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }
    }

    // --- isHttps() ---

    @Test
    fun isHttps_accepts_standard_https() {
        assertTrue(HttpPolicy.isHttps("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun isHttps_accepts_uppercase_scheme() {
        assertTrue(HttpPolicy.isHttps("HTTPS://api.example.com/v1"))
    }

    @Test
    fun isHttps_accepts_mixed_case() {
        assertTrue(HttpPolicy.isHttps("Https://api.example.com/v1"))
    }

    @Test
    fun isHttps_accepts_with_port() {
        assertTrue(HttpPolicy.isHttps("https://localhost:8443/api"))
    }

    @Test
    fun isHttps_accepts_with_path_and_query() {
        assertTrue(HttpPolicy.isHttps("https://api.example.com/v1/users?page=1"))
    }

    @Test
    fun isHttps_rejects_http() {
        assertFalse(HttpPolicy.isHttps("http://open.spotify.com/playlist/abc"))
    }

    @Test
    fun isHttps_rejects_ftp() {
        assertFalse(HttpPolicy.isHttps("ftp://files.example.com/x"))
    }

    @Test
    fun isHttps_rejects_no_scheme() {
        assertFalse(HttpPolicy.isHttps("open.spotify.com/playlist/abc"))
    }

    @Test
    fun isHttps_rejects_empty_string() {
        assertFalse(HttpPolicy.isHttps(""))
    }

    @Test
    fun isHttps_rejects_plain_text() {
        assertFalse(HttpPolicy.isHttps("not-a-url"))
    }

    @Test
    fun isHttps_rejects_file_scheme() {
        assertFalse(HttpPolicy.isHttps("file:///tmp/test.txt"))
    }

    // --- requireHttps() ---

    @Test
    fun requireHttps_returns_url_for_https() {
        assertEquals("https://api.example.com/x", HttpPolicy.requireHttps("https://api.example.com/x"))
    }

    @Test
    fun requireHttps_returns_same_url_object() {
        val url = "https://api.example.com/path?q=1&limit=10"
        assertEquals(url, HttpPolicy.requireHttps(url))
    }

    @Test(expected = HttpsRequiredException::class)
    fun requireHttps_throws_on_http() {
        HttpPolicy.requireHttps("http://open.spotify.com/track/abc")
    }

    @Test(expected = HttpsRequiredException::class)
    fun requireHttps_throws_on_ftp() {
        HttpPolicy.requireHttps("ftp://files.example.com/x")
    }

    @Test(expected = HttpsRequiredException::class)
    fun requireHttps_throws_on_empty() {
        HttpPolicy.requireHttps("")
    }

    @Test(expected = HttpsRequiredException::class)
    fun requireHttps_throws_on_no_scheme() {
        HttpPolicy.requireHttps("example.com")
    }

    @Test
    fun requireHttps_exception_contains_url() {
        try {
            HttpPolicy.requireHttps("http://evil.com")
        } catch (e: HttpsRequiredException) {
            assertEquals("http://evil.com", e.url)
            return
        }
        assertTrue("Should have thrown", false)
    }

    // --- stripUserInfo() ---

    @Test
    fun stripUserInfo_removes_user_password() {
        val cleaned = HttpPolicy.stripUserInfo("https://user:p%40ss@api.example.com/path?q=1")
        assertFalse("password leaked: $cleaned", cleaned.contains("p%40ss"))
        assertFalse("userinfo retained: $cleaned", cleaned.contains("user"))
        assertTrue(cleaned.startsWith("https://api.example.com/path?q=1"))
    }

    @Test
    fun stripUserInfo_preserves_url_without_userinfo() {
        val url = "https://api.example.com/path?q=1"
        assertEquals(url, HttpPolicy.stripUserInfo(url))
    }

    @Test
    fun stripUserInfo_handles_url_with_only_user() {
        val cleaned = HttpPolicy.stripUserInfo("https://user@api.example.com/path")
        assertFalse("userinfo retained: $cleaned", cleaned.contains("user"))
        assertTrue(cleaned.startsWith("https://api.example.com/path"))
    }

    @Test
    fun stripUserInfo_preserves_fragment() {
        val url = "https://api.example.com/path#section"
        val cleaned = HttpPolicy.stripUserInfo(url)
        assertTrue(cleaned.contains("#section"))
    }

    @Test
    fun stripUserInfo_preserves_port() {
        val url = "https://api.example.com:8080/path"
        val cleaned = HttpPolicy.stripUserInfo(url)
        assertTrue(cleaned.contains(":8080"))
    }

    @Test
    fun stripUserInfo_returns_input_for_malformed_url() {
        val input = "not-a-valid-url"
        assertEquals(input, HttpPolicy.stripUserInfo(input))
    }

    @Test
    fun stripUserInfo_returns_input_for_empty_string() {
        assertEquals("", HttpPolicy.stripUserInfo(""))
    }

    // --- Integration: FakeHttpClient pattern ---

    @Test
    fun fake_client_refuses_cleartext() {
        try {
            FakeHttpClient().get("http://cover.example/art")
            assertTrue("Should have thrown", false)
        } catch (e: HttpsRequiredException) {
            assertEquals("http://cover.example/art", e.url)
        }
    }

    @Test
    fun fake_client_passes_https() {
        assertEquals("https://cover.example/art", FakeHttpClient().get("https://cover.example/art"))
    }

    private class FakeHttpClient {
        fun get(url: String): String = HttpPolicy.requireHttps(url)
    }
}
