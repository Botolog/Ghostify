package com.ghostify.python

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The request/response codec between the app process and the `:pythond` host
 * process.
 *
 * Wire format is deliberately trivial JSON:
 *
 * ```
 * request:  {"module": "...", "method": "...", "args": [ ... ]}
 * response: {"ok": <value>, "meta": {"interpreter_id": "...", "host_uptime_ms": 0, "abi": "..."}}
 *      or:  {"error": {"kind": "...", "type": "...", "message": "...", "traceback": "..."}}
 * ```
 *
 * The `ok` payload is produced by Python's `json.dumps` (see `ghostify_dl.dispatch`),
 * so all type marshaling happens in one place and `PyObject` never touches the
 * protocol.
 *
 * Pure JVM (org.json is in the Android framework and available to JVM tests via
 * the `org.json:json` artifact) — unit-tested in `HostProtocolTest`.
 */
object HostProtocol {

    // ---------------------------------------------------------------- encode

    /** JSON text for the `args` array of a request. */
    fun encodeArray(args: List<Any?>): String = args.joinToString(",", "[", "]") { encodeValue(it) }

    fun encodeRequest(module: String, method: String, args: List<Any?>): String =
        """{"module":${quote(module)},"method":${quote(method)},"args":${encodeArray(args)}}"""

    /** `resultJson` must already be a valid JSON value (e.g. produced by `json.dumps`). */
    fun encodeOkWithMeta(resultJson: String, meta: HostMeta): String =
        """{"ok":$resultJson,"meta":${encodeValue(
            mapOf(
                "interpreter_id" to meta.interpreterId,
                "host_uptime_ms" to meta.hostUptimeMs,
                "abi" to meta.abi,
            ),
        )}}"""

    fun encodeError(kind: String, type: String, message: String, traceback: String? = null): String =
        """{"error":{"kind":${quote(kind)},"type":${quote(type)},"message":${quote(message)},"traceback":${quote(traceback ?: "")}}}"""

    /** Encode an arbitrary (JSON-safe) Kotlin value. */
    fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            if (value is Double || value is Float) {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) quote(value.toString()) else value.toString()
            } else {
                value.toString()
            }
        }
        is CharSequence -> quote(value.toString())
        is ByteArray -> quote(java.util.Base64.getEncoder().encodeToString(value))
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "${quote(keyToString(it.key))}:${encodeValue(it.value)}" }
        is Collection<*> -> value.joinToString(",", "[", "]") { encodeValue(it) }
        else -> quote(value.toString())
    }

    private fun keyToString(key: Any?): String =
        when (key) {
            null -> "null"
            is String -> key
            else -> key.toString()
        }

    // ---------------------------------------------------------------- decode

    internal fun parseRequest(json: String): Request {
        return try {
            val root = JSONObject(json)
            Request(
                module = root.getString("module"),
                method = root.getString("method"),
                argsJson = root.optJSONArray("args")?.toString() ?: "[]",
            )
        } catch (e: JSONException) {
            throw PythonError.InvalidRequest(e.message ?: "malformed request JSON", e)
        }
    }

    /**
     * Parse a host response. Throws a typed [PythonError] when the envelope
     * carries an error instead of a value.
     */
    internal fun parseResponse(json: String): HostResponse {
        return try {
            val root = JSONObject(json)
            val meta = root.optJSONObject("meta")?.let {
                HostMeta(
                    interpreterId = it.optString("interpreter_id", ""),
                    hostUptimeMs = it.optLong("host_uptime_ms", 0L),
                    abi = it.optString("abi", ""),
                )
            }
            if (root.has("error")) {
                val err = root.getJSONObject("error")
                val kind = err.optString("kind", "")
                val type = err.optString("type", "")
                val message = err.optString("message", "")
                val traceback = err.optString("traceback", "")
                throw when (kind) {
                    "PythonException" -> PythonError.PythonExceptionInfo(type, message, traceback)
                    else -> PythonError.HostErrorInfo(kind, type, message)
                }
            }
            HostResponse(ok = normalize(root.opt("ok")), meta = meta)
        } catch (e: PythonError) {
            throw e
        } catch (e: JSONException) {
            throw PythonError.InvalidRequest("malformed response JSON: ${e.message}", e)
        }
    }

    /** Convert org.json containers into plain Kotlin values ([PyValue]). */
    fun normalize(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { normalize(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
        is java.math.BigDecimal -> value.toDouble()
        is java.math.BigInteger -> if (value.bitLength() < 63) value.toLong() else value.toString()
        else -> value
    }

    // ---------------------------------------------------------------- helpers

    /** Minimal JSON string encoder (escaping only the required characters). */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
