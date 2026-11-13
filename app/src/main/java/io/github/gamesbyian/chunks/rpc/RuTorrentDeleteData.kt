package io.github.gamesbyian.chunks.rpc

import java.net.URI

/** Non-force ruTorrent erasedata integration; never a recursive directory delete. */
internal object RuTorrentDeleteData {
    // Hosted accounts commonly mount /ACCOUNT/rutorrent2/... rather than
    // /rutorrent2/... at the origin. The plugin must stay under the exact
    // authenticated tenant prefix and numbered instance; never guess another
    // mount or fall back to a primary instance for a destructive operation.
    private val mount = Regex("^/(?:[^/]+/)*rutorrent[0-9]{0,3}/plugins/httprpc/action\\.php$")
    private val encodedSeparatorOrControl =
        Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
    private val traversalSegment =
        Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")

    fun endpoint(rpc: URI): URI? {
        val raw = rpc.rawPath.orEmpty()
        if (raw.length !in 1..2048 || !mount.matches(raw) ||
            rpc.host.isNullOrBlank() || rpc.rawUserInfo != null ||
            rpc.rawQuery != null || rpc.rawFragment != null ||
            raw.contains("//") || encodedSeparatorOrControl.containsMatchIn(raw) ||
            traversalSegment.containsMatchIn(raw)) return null
        // Preserve the raw spelling, including harmless encoded tenant names.
        // Rebuilding with URI's component constructor would double-encode %20.
        val path = raw.removeSuffix("httprpc/action.php") + "erasedata/action.php"
        return URI.create("${rpc.scheme}://${rpc.rawAuthority}$path")
    }

    /**
     * A remote directory must be a canonical absolute path before its string
     * can prove filesystem non-overlap. Dot segments, duplicate separators
     * and backslashes could make an apparently disjoint path alias another.
     * Root may be examined as an OTHER job, but is never a safe selected
     * payload deletion destination.
     */
    fun safeDirectory(path: String, allowRoot: Boolean = false): Boolean {
        if (path == "/") return allowRoot
        return path.length in 2..4096 && path.startsWith("/") &&
            '\\' !in path &&
            path.none { it.code < 32 || it.code == 127 } &&
            path.substring(1).split('/').all {
                it.isNotEmpty() && it != "." && it != ".."
            }
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
    // rTorrent's setter/delete_tied/erase command results are success zeroes.
    // A three-element JSON array alone is not a successful cleanup receipt:
    // a nonzero or negative command result must not become QueuedForCleanup.
    // Legacy d.set_custom5 may return "" instead of its numeric zero.
    private val zeroThree = Regex("""\[\s*(?:0|"0")\s*,\s*(?:0|"0")\s*,\s*(?:0|"0")\s*\]""")
    private val voidThenZeroes = Regex("""\[\s*""\s*,\s*(?:0|"0")\s*,\s*(?:0|"0")\s*\]""")

    fun acceptedResponse(body: String): Boolean =
        body.length in 5..16384 &&
            (zeroThree.matches(body) || voidThenZeroes.matches(body))

}

/** Accepted queue != verified remote file deletion. */
sealed class RemoteDeleteDataOutcome {
    data object Unsupported : RemoteDeleteDataOutcome()
    data class Refused(val reason: RemoteDeleteRefusal) : RemoteDeleteDataOutcome()
    data class QueuedForCleanup(val hash: String) : RemoteDeleteDataOutcome()
    data class Uncertain(val hash: String) : RemoteDeleteDataOutcome()
}

/** Fixed, privacy-safe diagnostics. Never include server text, paths or credentials. */
enum class RemoteDeleteRefusal(val explanation: String) {
    INVALID_REVIEW("The reviewed name or directory is invalid. Refresh this transfer."),
    TARGET_CHANGED("The exact transfer's name or directory changed. Refresh and review it again."),
    INVENTORY_UNAVAILABLE("The server session inventory could not be read. Check the connection and account permissions."),
    INCOMPLETE_SESSION("Complete session coverage could not be confirmed: the view is filtered or its coverage getter is unavailable. Deletion cannot safely check for shared files."),
    FILES_UNAVAILABLE("The exact payload file list could not be read. Check this transfer in ruTorrent."),
    UNSAFE_FILES("The payload paths could not be safely validated. Inspect this transfer's files in ruTorrent."),
    SHARED_FILES("Another server transfer shares these payload files, or its file mapping could not be verified."),
    SESSION_CHANGED("The server jobs or file mappings changed during review. Refresh before a new review."),
    AUTHENTICATION("The server rejected authentication or access. Reconnect this exact profile."),
    PLUGIN_UNAVAILABLE("The ruTorrent erasedata endpoint is unavailable on this exact server route.")
}

enum class RemoteRemovalStage(val label: String) {
    TARGET("Checking the reviewed transfer…"),
    SESSION("Checking complete server session coverage…"),
    FILES("Checking payload paths and shared files…"),
    AUTHORIZATION("Checking access to this server's deletion plugin…"),
    SUBMITTING("Sending the approved removal once…"),
    VERIFYING("Checking exact server session after removal…")
}
