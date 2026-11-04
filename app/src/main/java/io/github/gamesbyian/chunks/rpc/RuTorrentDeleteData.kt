package io.github.gamesbyian.chunks.rpc

import java.net.URI

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

    /**
     * For one hash, erasedata returns three XML-RPC command results as
     * JSON, sometimes with a leading void (empty-string) setter result and
     * decimal-string integer results. Its failure sentinel is JSON false.
     * Empty arrays and the wrong scalar count are refused. Keep this parser deliberately limited to the
     * actual accepted response shape: no Android JSON stub in host JVM tests,
     * no HTML, objects, trailers, nested arrays or permissive prefix parsing.
     */
    private val scalarArray = Regex("""\[\s*(?:(?:-?[0-9]+|"-?[0-9]+")
        (?:\s*,\s*(?:-?[0-9]+|"-?[0-9]+"))*)?\s*\]""".replace("\n", "").replace(" ", ""))
    // Real legacy rTorrent: d.set_custom5 -> "", then d.delete_tied/d.erase
    // -> decimal zeroes, so upstream PHP sends ["","0","0"].
    private val voidFirstThree = Regex("""\[\s*""\s*,\s*(?:-?[0-9]+|"-?[0-9]+")\s*,\s*(?:-?[0-9]+|"-?[0-9]+")\s*\]""")
    fun acceptedResponse(body: String): Boolean =
        body.length in 5..16384 &&
            (voidFirstThree.matches(body) ||
                (scalarArray.matches(body) && body.count { it == ',' } == 2))

}

/** Accepted queue != verified remote file deletion. */
sealed class RemoteDeleteDataOutcome {
    data object Unsupported : RemoteDeleteDataOutcome()
    data object Refused : RemoteDeleteDataOutcome()
    data class QueuedForCleanup(val hash: String) : RemoteDeleteDataOutcome()
    data class Uncertain(val hash: String) : RemoteDeleteDataOutcome()
}
