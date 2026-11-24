package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.media.Json
import io.github.gamesbyian.chunks.media.MediaJsonException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * A successful exact-filter reply can be empty or contain the requested
 * identity exactly once. Any other reply is inconclusive, never absence.
 * Validate the entire reply before selecting its row.
 */
internal fun <T> exactProtocolRow(rows: List<T>, expectedHash: String, hashOf: (T) -> String): T? {
    if (rows.isEmpty()) return null
    if (rows.size != 1 || !hashOf(rows[0]).equals(expectedHash, ignoreCase = true)) {
        throw IOException("Unexpected or ambiguous exact client identity")
    }
    return rows[0]
}

/** Use documented positional order only when the entire roster omits explicit indices. */
internal fun exactIndexedFiles(rows: JSONArray): List<Pair<Int, JSONObject>> {
    val seen = BooleanArray(rows.length())
    val explicit = rows.optJSONObject(0)?.has("index") == true
    return List(rows.length()) { position ->
        val row = rows.optJSONObject(position) ?: throw IOException("Malformed client file row")
        if (row.has("index") != explicit) throw IOException("Mixed client file index modes")
        val index = if (explicit) row.exactFileInteger("index")
            ?.takeIf { it in 0L until rows.length().toLong() }?.toInt()
            ?: throw IOException("Invalid client file index") else position
        if (seen[index]) throw IOException("Duplicate client file index")
        seen[index] = true
        index to row
    }
}

internal fun JSONObject.exactFileInteger(field: String): Long? = exactWireInteger(opt(field))

internal fun exactWireInteger(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal fun JSONObject.exactFilePath(field: String): String =
    (opt(field) as? String)?.takeIf { it.isNotEmpty() } ?: throw IOException("Invalid client file path")

internal fun exactProgressFraction(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 }

/**
 * Android's JSON object reader can discard earlier duplicate keys before
 * identity checks. Parse exact responses with unique decoded keys, including
 * escaped spellings of the same key. The wire already bounds response bytes.
 */
internal object ExactRpcJson {
    fun objectBody(body: String): JSONObject = value(body) as? JSONObject
        ?: throw IOException("Expected an exact client object")

    fun arrayBody(body: String): JSONArray = value(body) as? JSONArray
        ?: throw IOException("Expected an exact client array")

    private fun value(body: String): Any {
        val parsed = try {
            Json.parseUnique(body, maxChars = 4_194_304, maxMembers = 220_000)
        } catch (_: MediaJsonException) {
            throw IOException("Malformed or ambiguous exact client JSON")
        }
        return convert(parsed)
    }

    private fun convert(value: Json): Any = when (value) {
        Json.Null -> JSONObject.NULL
        is Json.Bool -> value.value
        is Json.Num -> value.asLong ?: value.asDouble?.takeIf { it.isFinite() }
            ?: throw IOException("Invalid exact client number")
        is Json.Str -> value.value
        is Json.Arr -> JSONArray().apply { value.items.forEach { put(convert(it)) } }
        is Json.Obj -> JSONObject().apply { value.members.forEach { (key, row) -> put(key, convert(row)) } }
    }
}
