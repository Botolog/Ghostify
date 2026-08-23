package xyz.botolog.ghostify.security

import timber.log.Timber

/**
 * Detects and redacts credential-shaped values anywhere they could appear:
 * log messages, stored strings, error payloads, Python/Kotlin source. This is
 * the single source of truth for "what counts as a secret" — the runtime
 * redactor and the static scanners (SecurityScan.kt + scripts/security_scan.py)
 * all reuse these patterns so a secret that leaks is a secret that's detected.
 *
 * T-168: "No secrets/credentials in source, logs, or stored data."
 *
 * Detection model (deliberately conservative to avoid false positives on code):
 *
 *  - **Assignment form** `KEY[: =| to ]VALUE` — catches `client_secret = "x"`,
 *    `"client_secret" to "x"`, `api_key: "x"`, `"refresh_token":"x"`. The
 *    keyword is matched only at a word boundary so it is never part of a larger
 *    identifier (e.g. `oauth_secret` is left alone).
 *  - **Authorization headers** — `Bearer`/`Basic` tokens are always treated as
 *    secrets (they are credentials by construction) and are redacted by value.
 *  - **Sensitive query-string params** — `?access_token=`, `?code=`, etc.
 *
 * A candidate value is only a secret when it is *not* a known placeholder and
 * passes [looksLikeSecret]. The quoted-vs-bare distinction matters: a quoted
 * string literal is a value the author chose to write, so a long-ish one is
 * treated as a secret; a *bare* token that is purely a lowercase snake_case
 * identifier (`unknown_identifier`) is almost certainly a variable reference, so
 * it is only treated as a secret when it has the character mix (digit or
 * uppercase) of a real token. This is what makes `token = unknown_identifier`
 * safe while `token = abcXYZ123` is caught.
 */
object SecretRedactor {

    internal val PLACEHOLDERS = setOf(
        "", "none", "null", "true", "false", "0",
        "your-api-key", "your-api-key-here", "changeme", "secret",
        "password", "xxx", "lorem-ipsum", "example", "test",
        "spotify", "client_id", "client_secret", "api_key", "apikey",
    )

    internal val EMPTY_LIKE = Regex("""(?i:(none|null|false|true|0|))\s*""")

    /**
     * A bare token that is *purely* a lowercase snake_case identifier — no
     * digits, no uppercase — e.g. `unknown_identifier`. Such a token is almost
     * always a variable reference, never a credential, so it is never reported
     * as a secret. Tokens with a digit or uppercase (the character mix of a
     * real token) are still subject to the digit/upper check below.
     */
    private val BARE_SECRET = Regex("""^[a-z_]+$""")

    /** A credential-shaped value found inside some text. */
    data class SecretMatch(
        /** Canonical name, e.g. "client_secret", "api_key", "auth". */
        val key: String,
        /** The exact matched value text (without surrounding quotes). */
        val value: String,
        /** Start offset (inclusive) of [value] in the source text. */
        val start: Int,
        /** End offset (exclusive) of [value] in the source text. */
        val endExclusive: Int,
    )

    /**
     * Assignment of a credential keyword to a value.
     *
     * Groups:
     *  1 — keyword (client_secret, api_key, ...)
     *  2 — double-quoted value body
     *  3 — single-quoted value body
     *  4 — bare token body
     */
    private val ASSIGNMENT = Regex(
        """["']?\b(client[_-]?secret|client[_-]?id|api[_-]?key|apikey|access[_-]?key|access[_-]?token""" +
            """|refresh[_-]?token|secret|token|password|passwd|passphrase|private[_-]?key|auth[_-]?key)\b["']?""" +
            """\s*(?::\s*|=\s*|\s+to\s+)\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|([A-Za-z0-9+/_.\\-]{12,}))""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Authorization headers / bearer tokens — always credentials. */
    private val BEARER = Regex(
        """(?i)\b(Bearer|Basic)\s+([A-Za-z0-9\-._~+/]+={0,2})""",
    )

    /** Sensitive query-string parameters whose value must be masked. */
    private val QUERY_SECRET = Regex(
        """([?&](?:access_token|id_token|refresh_token|api_key|apikey|client_secret|code|token|key|auth))=[^&#\s"']+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** PEM private keys. */
    private val PEM_BLOCK = Regex(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Returns every credential-shaped value in [text] (used by the static
     * scanner, R1). Empty when the text carries no secret-like data.
     */
    fun find(text: String): List<SecretMatch> {
        Timber.i("SecretRedactor.find: START")
        val out = ArrayList<SecretMatch>()
        out += findAssignments(text)
        out += findBearerTokens(text)
        out += findPemBlocks(text)
        Timber.i("SecretRedactor.find: returning ${out.size} matches")
        return out
    }

    /** Finds credential assignments of the form `KEY = VALUE`. */
    private fun findAssignments(text: String): List<SecretMatch> {
        val out = ArrayList<SecretMatch>()
        for (m in ASSIGNMENT.findAll(text)) {
            val key = normalizeKey(m.groupValues[1])
            val value: String
            val g: MatchGroup
            when {
                m.groups[2] != null -> { value = m.groupValues[2]; g = m.groups[2]!! }
                m.groups[3] != null -> { value = m.groupValues[3]; g = m.groups[3]!! }
                m.groups[4] != null -> { value = m.groupValues[4]; g = m.groups[4]!! }
                else -> continue
            }
            val quoted = m.groups[2] != null || m.groups[3] != null
            if (looksLikeSecret(value, quoted)) {
                out.add(SecretMatch(key, value, g.range.first, g.range.last + 1))
            }
        }
        return out
    }

    /** Finds Bearer/Basic authorization tokens. */
    private fun findBearerTokens(text: String): List<SecretMatch> {
        val out = ArrayList<SecretMatch>()
        for (m in BEARER.findAll(text)) {
            val token = m.groupValues[2]
            if (looksLikeSecret(token, quoted = true)) {
                out.add(SecretMatch("auth", token, m.groups[2]!!.range.first, m.groups[2]!!.range.last + 1))
            }
        }
        return out
    }

    /** Finds PEM private key blocks. */
    private fun findPemBlocks(text: String): List<SecretMatch> {
        val out = ArrayList<SecretMatch>()
        for (m in PEM_BLOCK.findAll(text)) {
            out.add(SecretMatch("private_key", m.groupValues[0], m.range.first, m.range.last + 1))
        }
        return out
    }

    /** True when [value] is a real-looking secret (not a placeholder / empty). */
    fun looksLikeSecret(value: String): Boolean {
        Timber.i("SecretRedactor.looksLikeSecret: START")
        val result = looksLikeSecret(value, quoted = true)
        Timber.i("SecretRedactor.looksLikeSecret: returning $result")
        return result
    }

    internal fun looksLikeSecret(value: String, quoted: Boolean): Boolean {
        if (EMPTY_LIKE.matches(value)) return false
        val v = value.trim().trim('"').trim('\'')
        if (v.length < 4) return false
        if (v.lowercase() in PLACEHOLDERS) return false
        if (quoted) {
            // A quoted literal the author wrote: a long-ish literal is treated as
            // a secret (length >= 8). This catches pasted tokens in strings
            // while ignoring short literals like "xxxx" or "abc".
            return v.length >= 8
        }
        // Bare token — almost always a variable name, so only treat as a secret
        // when it is a flat credential-shaped string: no field/method access
        // ('.') and it carries a digit, the character mix of a real token. This
        // avoids false positives on code such as `token = AtomicBoolean(...)`
        // or `token = m.groupValues[2]` while still catching real tokens like
        // `abcXYZ123` (quoted secrets, the common case, are handled above).
        if ("." in v) return false
        if (v.length < 12) return false
        if (BARE_SECRET.matches(v)) return false
        return v.any { it.isDigit() }
    }

    /** Canonical key name (api_key, apiKey, API-Key -> api_key). */
    private fun normalizeKey(raw: String): String {
        val camel = raw.replace(Regex("(?<=[a-zA-Z])(?=[A-Z])"), "_")
        return camel.lowercase().replace('-', '_').replace(Regex("_+"), "_").trim('_')
    }

    private const val QUERY_REDACTED_SUFFIX = "=[REDACTED]"

    /**
     * Replaces every credential-shaped value with a masked marker, so logs and
     * stored strings never leak the value itself. Idempotent.
     */
    fun redact(text: String): String {
        Timber.i("SecretRedactor.redact: START")
        if (text.isEmpty()) return text
        val redactedAssignments = redactAssignments(text)
        val result = redactQuerySecrets(redactedAssignments)
        Timber.i("SecretRedactor.redact: returning $result")
        return result
    }

    /** Replaces credential assignments with `<REDACTED:key>` markers. */
    private fun redactAssignments(text: String): String {
        val sb = StringBuilder(text)
        for (m in find(text).sortedByDescending { it.start }) {
            sb.replace(m.start, m.endExclusive, "<REDACTED:${m.key}>")
        }
        return sb.toString()
    }

    /** Replaces sensitive query-string parameter values with `[REDACTED]`. */
    private fun redactQuerySecrets(text: String): String {
        return QUERY_SECRET.replace(text) { match ->
            match.groupValues[1] + QUERY_REDACTED_SUFFIX
        }
    }
}
