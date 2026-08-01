package com.ghostify.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-168 — No secrets/credentials in source, logs, or stored data.
 *
 * Exercises [SecretRedactor], the runtime's single source of truth for "what
 * counts as a secret": it must catch real credential-shaped values (in logs
 * and in strings about to be stored) and must not false-positive on
 * placeholders. The repo-wide half of T-168 is `SecurityScanTest` (static
 * scan of the source tree + stored-data model).
 */
class SecretRedactorTest {

    @Test
    fun `detects client_secret assignment`() {
        val matches = SecretRedactor.find("val config = mapOf(\"client_secret\" to \"abc123XYZsecret\")")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun `detects api key and bearer token`() {
        val text = """
            apiKey = "AIzaSyFakeKey0123456789"
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token.value.here
        """.trimIndent()
        val keys = SecretRedactor.find(text).map { it.key }
        assertTrue("api_key" in keys, "expected api_key in $keys")
        assertTrue("auth" in keys, "expected auth (bearer) in $keys")
    }

    @Test
    fun `detects secret in query string`() {
        val redacted = SecretRedactor.redact("https://api.example/x?access_token=ABC123secret&q=hi")
        assertTrue(redacted.contains("[REDACTED]"), "query secret not masked: $redacted")
        assertTrue(!redacted.contains("ABC123secret"))
    }

    @Test
    fun `redacts every credential-shaped value from a stored string`() {
        val payload = """
            {"access_token": "gho_supersecrettokennope", "refresh_token": "r_anothersecret123"}
        """.trimIndent()
        val redacted = SecretRedactor.redact(payload)
        assertTrue(!redacted.contains("gho_supersecrettokennope"))
        assertTrue(!redacted.contains("r_anothersecret123"))
        assertTrue(redacted.contains("<REDACTED:"))
    }

    @Test
    fun `ignores placeholders and empty values`() {
        for (text in listOf(
            "client_id = None",
            "client_secret = \"\"",
            "api_key = \"your-api-key\"",
            "secret = test",
        )) {
            assertTrue(SecretRedactor.find(text).isEmpty(), "false positive on: $text")
        }
    }

    @Test
    fun `ignores ordinary words`() {
        assertTrue(SecretRedactor.find("token = unknown_identifier").isEmpty())
        assertTrue(SecretRedactor.find("the token for the session").isEmpty())
    }
}
