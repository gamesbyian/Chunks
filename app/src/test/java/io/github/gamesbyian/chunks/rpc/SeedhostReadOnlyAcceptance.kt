package io.github.gamesbyian.chunks.rpc

/**
 * Shared by the actual trusted-main probe and real localhost counterexamples.
 * Public version/inventory reads prove reachability, not supplied credentials.
 * An accepted challenge is bounded HTTP evidence, not human account ownership
 * or authorization to mutate a torrent.
 */
internal suspend fun verifySeedhostReadOnlyAcceptance(remote: RTorrentRemote): Int {
    val version = remote.version()
    check(version.isNotBlank() && version.length <= 120) { "Invalid remote version response" }
    val rows = remote.listTransfers("main")
    check(rows.size <= 12000) { "Main view unexpected size" }
    val hashes = rows.map { it.hash.lowercase() }
    check(hashes.size == hashes.distinct().size) { "Main view has duplicate hash identities" }
    for (row in rows) {
        check(Regex("(?i)[a-f0-9]{40}([a-f0-9]{24})?").matches(row.hash)) { "Invalid transfer identity" }
        check(row.sizeBytes >= 0 && row.completedBytes >= 0) { "Invalid transfer byte counts" }
        check(row.name.isNotBlank()) { "Missing remote torrent name" }
    }
    check(remote.readAuthenticationEvidence() == RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED) {
        "HTTP read authentication is unverified; public RPC success cannot verify supplied credentials"
    }
    return rows.size
}
