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
