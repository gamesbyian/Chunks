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

    /** Existing account-specific ruTorrent hooks, never a custom5 convention alone. */
    fun registeredLegacyHooks(value: Any?, principal: String): Boolean {
        if (principal.isBlank()) return false
        val hooks = value as? Map<*, *> ?: return false
        val collect = hooks["erasedata0$principal"] as? String ?: return false
        val finish = hooks["erasedata1$principal"] as? String ?: return false
        if (collect.length !in 1..8192 || finish.length !in 1..8192) return false
        // Legacy ruTorrent registers these only after successful initialization.
        // Read the existing definitions; never install hooks or submit execute.*.
        return collect.contains("d.open=") && collect.contains("custom5=") &&
            collect.contains("f.multicall=") && collect.contains("file.append=") &&
            collect.contains("frozen_path=") && collect.contains("/erasedata/") &&
            finish.contains("custom5=") && finish.contains("/erasedata/fin.sh") &&
            finish.contains("system.pid=") && finish.contains("base_path=") &&
            finish.contains("is_multi_file=")
    }

    fun legacyBrowserAdapter(script: String): Boolean = script.length <= 150_000 &&
        script.contains("rTorrentStub.prototype.removewithdata") &&
        listOf("d.set_custom5", "d.delete_tied", "d.erase").all(script::contains)

    fun acceptedLegacyResponse(value: Any?): Boolean {
        val results = value as? List<*> ?: return false
        if (results.size != 3) return false
        val values = results.map { (it as? List<*>)?.singleOrNull() }
        return values[0] in listOf("", "1", 1L, 0L, "0") &&
            values.drop(1).all { it == 0L || it == "0" }
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
    data class QueuedForCleanup(val hash: String, val sessionRemoved: Boolean = true) : RemoteDeleteDataOutcome()
    data class Partial(val hash: String) : RemoteDeleteDataOutcome()
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
    CAPABILITY_UNKNOWN("A safe data-deletion adapter could not be verified. Recheck server capabilities; session-only Remove remains available."),
    PLUGIN_METHOD_REFUSED("The selected deletion endpoint rejected this HTTP method (405). No alternative deletion was attempted."),
    PLUGIN_UNAVAILABLE("The ruTorrent erasedata endpoint is unavailable on this exact server route.")
}

enum class RemoteRemovalStage(val label: String) {
    CAPABILITY("Checking this server’s data-deletion support…"),
    TARGET("Checking the reviewed transfer…"),
    SESSION("Checking complete server session coverage…"),
    FILES("Checking payload paths and shared files…"),
    AUTHORIZATION("Checking access to this server's deletion plugin…"),
    SUBMITTING("Sending the approved removal once…"),
    VERIFYING("Checking exact server session after removal…")
}
