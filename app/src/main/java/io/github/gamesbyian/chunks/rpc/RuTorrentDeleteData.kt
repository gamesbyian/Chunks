package io.github.gamesbyian.chunks.rpc

import java.net.URI
import org.json.JSONArray
import org.json.JSONTokener

/** Non-force ruTorrent erasedata integration; never a recursive directory delete. */
internal object RuTorrentDeleteData {
    private val mount = Regex("/rutorrent[0-9]{0,3}/plugins/httprpc/action\\.php")
    fun endpoint(rpc: URI): URI? {
        val raw = rpc.rawPath.orEmpty()
        if (!mount.matches(raw) || rpc.rawQuery != null || rpc.userInfo != null) return null
        val path = raw.removeSuffix("httprpc/action.php") + "erasedata/action.php"
        return URI.create("${rpc.scheme}://${rpc.rawAuthority}$path")
    }

    fun safeFilePaths(paths: List<RemoteFile>): Boolean {
        if (paths.isEmpty() || paths.size > 12_000) return false
        val seen = HashSet<String>()
        return paths.all { file ->
            val p = file.path
            p.length in 1..4096 && !p.startsWith('/') && '\\' !in p &&
                p.none { it.code < 32 || it.code == 127 } &&
                p.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                seen.add(p.lowercase(java.util.Locale.ROOT))
        }
    }

    /** Plugin response is a JSON array of RPC results; never accept HTML or false. */
    fun acceptedResponse(body: String): Boolean {
        if (body.length !in 2..16384) return false
        return try {
            val tokener = JSONTokener(body)
            val parsed = tokener.nextValue()
            parsed is JSONArray && tokener.nextClean() == '\u0000' &&
                (0 until parsed.length()).all { index ->
                    val value = parsed.opt(index)
                    value is Number || value is Boolean || value is String
                }
        } catch (_: Exception) { false }
    }
}

/** Accepted queue != verified remote file deletion. */
sealed class RemoteDeleteDataOutcome {
    data object Unsupported : RemoteDeleteDataOutcome()
    data object Refused : RemoteDeleteDataOutcome()
    data class QueuedForCleanup(val hash: String) : RemoteDeleteDataOutcome()
    data class Uncertain(val hash: String) : RemoteDeleteDataOutcome()
}
