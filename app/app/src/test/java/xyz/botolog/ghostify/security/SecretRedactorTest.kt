package xyz.botolog.ghostify.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecretRedactorTest {

    @Before
    fun setUp() {
        if (timber.log.Timber.treeCount == 0) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }
    }

    // --- find() - assignment patterns ---

    @Test
    fun find_detects_client_secret_double_quoted() {
        val matches = SecretRedactor.find("client_secret = \"abc123XYZsecret\"")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
        assertEquals("abc123XYZsecret", matches[0].value)
    }

    @Test
    fun find_detects_client_secret_single_quoted() {
        val matches = SecretRedactor.find("client_secret = 'abc123XYZsecret'")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun find_detects_client_secret_colon_separator() {
        val matches = SecretRedactor.find("\"client_secret\": \"abc123XYZsecret\"")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun find_detects_client_secret_to_separator() {
        val matches = SecretRedactor.find("\"client_secret\" to \"abc123XYZsecret\"")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun find_detects_api_key_assignment() {
        val matches = SecretRedactor.find("api_key = \"AIzaSyFakeKey0123456789\"")
        assertEquals(1, matches.size)
        assertEquals("api_key", matches[0].key)
    }

    @Test
    fun find_detects_apiKey_camel_case() {
        val matches = SecretRedactor.find("apiKey = \"AIzaSyFakeKey0123456789\"")
        assertEquals(1, matches.size)
        assertEquals("api_key", matches[0].key)
    }

    @Test
    fun find_detects_apikey_single_word() {
        val matches = SecretRedactor.find("apikey = \"AIzaSyFakeKey0123456789\"")
        assertEquals(1, matches.size)
        assertEquals("apikey", matches[0].key)
    }

    @Test
    fun find_detects_access_token() {
        val matches = SecretRedactor.find("access_token = \"gho_abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("access_token", matches[0].key)
    }

    @Test
    fun find_detects_refresh_token() {
        val matches = SecretRedactor.find("refresh_token = \"r_abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("refresh_token", matches[0].key)
    }

    @Test
    fun find_detects_password_assignment() {
        val matches = SecretRedactor.find("password = \"superSecret1234\"")
        assertEquals(1, matches.size)
        assertEquals("password", matches[0].key)
    }

    @Test
    fun find_detects_token_assignment() {
        val matches = SecretRedactor.find("token = \"ghp_abcXYZ1234567890abcdefghij\"")
        assertEquals(1, matches.size)
        assertEquals("token", matches[0].key)
    }

    @Test
    fun find_detects_private_key_assignment() {
        val matches = SecretRedactor.find("private_key = \"MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\"")
        assertEquals(1, matches.size)
        assertEquals("private_key", matches[0].key)
    }

    @Test
    fun find_detects_auth_key_assignment() {
        val matches = SecretRedactor.find("auth_key = \"abcXYZ1234567890abcdef\"")
        assertEquals(1, matches.size)
        assertEquals("auth_key", matches[0].key)
    }

    @Test
    fun find_detects_passphrase_assignment() {
        val matches = SecretRedactor.find("passphrase = \"mySecretPassphrase123\"")
        assertEquals(1, matches.size)
        assertEquals("passphrase", matches[0].key)
    }

    @Test
    fun find_detects_passwd_assignment() {
        val matches = SecretRedactor.find("passwd = \"superSecret123456\"")
        assertEquals(1, matches.size)
        assertEquals("passwd", matches[0].key)
    }

    @Test
    fun find_detects_access_key_assignment() {
        val matches = SecretRedactor.find("access_key = \"AKIAIOSFODNN7EXAMPLE\"")
        assertEquals(1, matches.size)
        assertEquals("access_key", matches[0].key)
    }

    @Test
    fun find_detects_client_id_assignment() {
        val matches = SecretRedactor.find("client_id = \"abcXYZ1234567890abcdef\"")
        assertEquals(1, matches.size)
        assertEquals("client_id", matches[0].key)
    }

    @Test
    fun find_detects_hyphenated_client_secret() {
        val matches = SecretRedactor.find("client-secret = \"abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun find_detects_hyphenated_api_key() {
        val matches = SecretRedactor.find("api-key = \"abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("api_key", matches[0].key)
    }

    @Test
    fun find_detects_bare_token_with_digit() {
        val matches = SecretRedactor.find("token = abcXYZ1234567890")
        assertEquals(1, matches.size)
        assertEquals("token", matches[0].key)
        assertEquals("abcXYZ1234567890", matches[0].value)
    }

    @Test
    fun find_multiple_assignments_in_text() {
        val text = """
            val config = mapOf(
                "client_secret" to "abc123XYZsecret",
                "api_key" to "AIzaSyFakeKey0123456789"
            )
        """.trimIndent()
        val keys = SecretRedactor.find(text).map { it.key }
        assertTrue("client_secret" in keys)
        assertTrue("api_key" in keys)
        assertEquals(2, keys.size)
    }

    // --- find() - bearer / basic tokens ---

    @Test
    fun find_detects_bearer_token() {
        val text = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token.value.here"
        val matches = SecretRedactor.find(text).filter { it.key == "auth" }
        assertEquals(1, matches.size)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.token.value.here", matches[0].value)
    }

    @Test
    fun find_detects_basic_auth() {
        val text = "Authorization: Basic dXNlcjpwYXNzd29yZA=="
        val matches = SecretRedactor.find(text)
        assertTrue(matches.any { it.key == "auth" })
    }

    @Test
    fun find_detects_bearer_case_insensitive() {
        val text = "authorization: bearer abcXYZ1234567890"
        val matches = SecretRedactor.find(text)
        assertTrue(matches.any { it.key == "auth" })
    }

    @Test
    fun find_ignores_bearer_with_empty_token() {
        val text = "Authorization: Bearer "
        val matches = SecretRedactor.find(text)
        assertTrue(matches.none { it.key == "auth" })
    }

    @Test
    fun find_detects_bearer_in_longer_text() {
        val text = "Header: Bearer abcXYZ1234567890abcXYZ1234567890, other stuff"
        val matches = SecretRedactor.find(text)
        assertTrue(matches.any { it.key == "auth" })
    }

    // --- find() - PEM blocks ---

    @Test
    fun find_detects_pem_rsa_private_key() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----"
        val matches = SecretRedactor.find(pem)
        assertEquals(1, matches.size)
        assertEquals("private_key", matches[0].key)
        assertTrue(matches[0].value.contains("BEGIN RSA PRIVATE KEY"))
    }

    @Test
    fun find_detects_pem_ec_private_key() {
        val pem = "-----BEGIN EC PRIVATE KEY-----\nMHQCAQEE...\n-----END EC PRIVATE KEY-----"
        val matches = SecretRedactor.find(pem)
        assertEquals(1, matches.size)
        assertEquals("private_key", matches[0].key)
    }

    @Test
    fun find_detects_pem_private_key_without_type() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBg...\n-----END PRIVATE KEY-----"
        val matches = SecretRedactor.find(pem)
        assertEquals(1, matches.size)
        assertEquals("private_key", matches[0].key)
    }

    @Test
    fun find_detects_pem_multiline() {
        val pem = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASC
            BSK7ZggHIBdrMhT0kHWmJYU1E0ZQ==
            -----END PRIVATE KEY-----
        """.trimIndent()
        val matches = SecretRedactor.find(pem)
        assertEquals(1, matches.size)
    }

    // --- find() - ignores safe content ---

    @Test
    fun find_ignores_all_placeholders() {
        val placeholders = listOf(
            "client_id = None",
            "client_secret = \"\"",
            "api_key = \"your-api-key\"",
            "secret = test",
            "client_secret = null",
            "client_secret = false",
            "client_secret = true",
            "client_secret = \"changeme\"",
            "client_secret = \"none\"",
            "token = \"xxx\"",
            "api_key = \"lorem-ipsum\"",
            "client_secret = \"password\"",
            "client_secret = \"spotify\"",
            "api_key = \"example\"",
            "api_key = \"your-api-key-here\"",
        )
        for (text in placeholders) {
            assertTrue("false positive on: $text", SecretRedactor.find(text).isEmpty())
        }
    }

    @Test
    fun find_ignores_snake_case_identifiers() {
        assertTrue(SecretRedactor.find("token = unknown_identifier").isEmpty())
        assertTrue(SecretRedactor.find("secret = some_var_name_here").isEmpty())
    }

    @Test
    fun find_ignores_ordinary_text() {
        assertTrue(SecretRedactor.find("the token for the session is valid").isEmpty())
        assertTrue(SecretRedactor.find("no secrets here").isEmpty())
    }

    @Test
    fun find_ignores_bare_token_without_digit() {
        assertTrue(SecretRedactor.find("token = abcdefghijklmnop").isEmpty())
    }

    @Test
    fun find_ignores_bare_token_with_dot() {
        assertTrue(SecretRedactor.find("token = m.groupValues[2]").isEmpty())
    }

    @Test
    fun find_ignores_short_bare_token() {
        assertTrue(SecretRedactor.find("token = abc123").isEmpty())
    }

    @Test
    fun find_ignores_keyword_only_without_value() {
        assertTrue(SecretRedactor.find("api_key").isEmpty())
        assertTrue(SecretRedactor.find("client_secret:").isEmpty())
    }

    @Test
    fun find_ignores_empty_string() {
        assertTrue(SecretRedactor.find("").isEmpty())
    }

    // --- redact() ---

    @Test
    fun redact_client_secret_replaced() {
        val redacted = SecretRedactor.redact("client_secret = \"abc123XYZsecret\"")
        assertFalse(redacted.contains("abc123XYZsecret"))
        assertTrue(redacted.contains("<REDACTED:client_secret>"))
    }

    @Test
    fun redact_api_key_replaced() {
        val redacted = SecretRedactor.redact("api_key = \"AIzaSyFakeKey0123456789\"")
        assertFalse(redacted.contains("AIzaSyFakeKey0123456789"))
        assertTrue(redacted.contains("<REDACTED:api_key>"))
    }

    @Test
    fun redact_bearer_token_replaced() {
        val redacted = SecretRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.token.value.here")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9.token.value.here"))
        assertTrue(redacted.contains("<REDACTED:auth>"))
    }

    @Test
    fun redact_preserves_surrounding_text() {
        val redacted = SecretRedactor.redact("Failed to authenticate: client_secret = \"abc123XYZsecret\"")
        assertTrue(redacted.contains("Failed to authenticate:"))
        assertTrue(redacted.contains("<REDACTED:client_secret>"))
        assertFalse(redacted.contains("abc123XYZsecret"))
    }

    @Test
    fun redact_multiple_secrets() {
        val text = "api_key = \"AIzaSyFakeKey0123456789\" and token = \"ghp_abcXYZ1234567890abcdefghij\""
        val redacted = SecretRedactor.redact(text)
        assertFalse(redacted.contains("AIzaSyFakeKey0123456789"))
        assertFalse(redacted.contains("ghp_abcXYZ1234567890abcdefghij"))
        assertTrue(redacted.contains("<REDACTED:"))
    }

    @Test
    fun redact_empty_string_returns_empty() {
        assertEquals("", SecretRedactor.redact(""))
    }

    @Test
    fun redact_no_secrets_returns_unchanged() {
        val text = "This is a normal log message with no secrets."
        assertEquals(text, SecretRedactor.redact(text))
    }

    @Test
    fun redact_json_payload_removes_all_secrets() {
        val payload = """{"access_token": "gho_supersecrettokennope", "refresh_token": "r_anothersecret123"}"""
        val redacted = SecretRedactor.redact(payload)
        assertFalse(redacted.contains("gho_supersecrettokennope"))
        assertFalse(redacted.contains("r_anothersecret123"))
        assertTrue(redacted.contains("<REDACTED:"))
    }

    @Test
    fun redact_is_idempotent() {
        val text = "client_secret = \"abc123XYZsecret\""
        val once = SecretRedactor.redact(text)
        val twice = SecretRedactor.redact(once)
        assertEquals(once, twice)
    }

    @Test
    fun redact_query_secret_access_token() {
        val redacted = SecretRedactor.redact("https://api.example.com/x?access_token=ABC123secret&q=hi")
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(redacted.contains("ABC123secret"))
    }

    @Test
    fun redact_query_secret_refresh_token() {
        val redacted = SecretRedactor.redact("https://api.example.com/x?refresh_token=r_abcXYZsecret")
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(redacted.contains("r_abcXYZsecret"))
    }

    @Test
    fun redact_query_secret_api_key() {
        val redacted = SecretRedactor.redact("https://api.example.com/x?api_key=abcXYZ123456")
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(redacted.contains("abcXYZ123456"))
    }

    @Test
    fun redact_query_secret_code() {
        val redacted = SecretRedactor.redact("https://api.example.com/x?code=abcXYZ123456")
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(redacted.contains("abcXYZ123456"))
    }

    @Test
    fun redact_query_secret_preserves_normal_params() {
        val redacted = SecretRedactor.redact("https://api.example.com/x?page=2&limit=10")
        assertTrue(redacted.contains("page=2"))
        assertTrue(redacted.contains("limit=10"))
        assertFalse(redacted.contains("[REDACTED]"))
    }

    @Test
    fun redact_pem_block_in_text() {
        val text = "Found key: -----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY----- in file"
        val redacted = SecretRedactor.redact(text)
        assertTrue(redacted.contains("Found key:"))
        assertTrue(redacted.contains("<REDACTED:private_key>"))
        assertFalse(redacted.contains("MIIEowIBAAKCAQEA"))
    }

    // --- looksLikeSecret() ---

    @Test
    fun looksLikeSecret_false_for_empty() {
        assertFalse(SecretRedactor.looksLikeSecret(""))
    }

    @Test
    fun looksLikeSecret_false_for_none() {
        assertFalse(SecretRedactor.looksLikeSecret("none"))
    }

    @Test
    fun looksLikeSecret_false_for_null() {
        assertFalse(SecretRedactor.looksLikeSecret("null"))
    }

    @Test
    fun looksLikeSecret_false_for_true() {
        assertFalse(SecretRedactor.looksLikeSecret("true"))
    }

    @Test
    fun looksLikeSecret_false_for_false() {
        assertFalse(SecretRedactor.looksLikeSecret("false"))
    }

    @Test
    fun looksLikeSecret_false_for_zero() {
        assertFalse(SecretRedactor.looksLikeSecret("0"))
    }

    @Test
    fun looksLikeSecret_false_for_short_value() {
        assertFalse(SecretRedactor.looksLikeSecret("abc"))
    }

    @Test
    fun looksLikeSecret_false_for_changeme() {
        assertFalse(SecretRedactor.looksLikeSecret("changeme"))
    }

    @Test
    fun looksLikeSecret_true_for_long_random_string() {
        assertTrue(SecretRedactor.looksLikeSecret("abcXYZ1234567890"))
    }

    @Test
    fun looksLikeSecret_true_for_jwt_style_token() {
        assertTrue(SecretRedactor.looksLikeSecret("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"))
    }

    // --- Key normalization ---

    @Test
    fun key_is_normalized_api_key() {
        val matches = SecretRedactor.find("API-KEY = \"abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("api_key", matches[0].key)
    }

    @Test
    fun key_is_normalized_clientSecret_camel() {
        val matches = SecretRedactor.find("clientSecret = \"abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("client_secret", matches[0].key)
    }

    @Test
    fun key_is_normalized_clientId_camel() {
        val matches = SecretRedactor.find("clientId = \"abcXYZ1234567890\"")
        assertEquals(1, matches.size)
        assertEquals("client_id", matches[0].key)
    }
}
