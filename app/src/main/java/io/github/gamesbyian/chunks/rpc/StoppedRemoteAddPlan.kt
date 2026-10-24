package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** A single, explicitly chosen, v1 Add test target. Not a general file mover. */
data class StoppedRemoteAddPlan private constructor(
    val source: String,
    val expectedInfoHash: String,
    val destination: String,
    val kind: Kind
) {
    enum class Kind { MAGNET, ARCHIVE_TORRENT_URL }

    companion object {
        private val hashPattern = Regex("[0-9a-fA-F]{40}")
        private fun validatePath(path: String): String {
            require(path.startsWith("/") && path.length in 2..1024) {
                "Choose a specific absolute server destination"
            }
            require(path.split('/').drop(1).all { it.isNotBlank() && it != "." && it != ".." }) {
                "Invalid server destination path"
            }
            require(path.none { it.code < 32 || it in ",;=\\\"'\u0060\$()\\\\" }) {
                "Unsupported characters in rTorrent destination"
            }
            return path
        }

        fun fromMagnet(magnet: String, destination: String): StoppedRemoteAddPlan {
            require(magnet.startsWith("magnet:?", ignoreCase = true) && magnet.length <= 8192 &&
                magnet.none { it.code < 32 }) { "Expected a bounded magnet link" }
            val xt = magnet.substringAfter('?').split('&')
                .mapNotNull { part ->
                    val raw = part.split('=', limit = 2)
                    if (raw.size != 2 || !raw[0].equals("xt", ignoreCase = true)) null
                    else URLDecoder.decode(raw[1], "UTF-8")
                }.filter { it.startsWith("urn:btih:", ignoreCase = true) }
            require(xt.size == 1) { "Use exactly one v1 magnet identity" }
            val hash = xt.single().substring(9)
            require(hashPattern.matches(hash)) { "Only 40-character v1 info hashes supported" }
            return StoppedRemoteAddPlan(magnet, hash.lowercase(Locale.ROOT),
                validatePath(destination), Kind.MAGNET)
        }

        /** The expected hash MUST come from validated fixture metainfo, never a filename. */
        fun fromArchiveTorrent(
            torrentUrl: String, verifiedInfoHash: String, destination: String
        ): StoppedRemoteAddPlan {
            val uri = URI(torrentUrl)
            require(uri.scheme == "https" && uri.host == "archive.org" &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.path.startsWith("/download/") && uri.path.endsWith(".torrent")) {
                "Archive test input must be a direct official .torrent URL"
            }
            require(hashPattern.matches(verifiedInfoHash)) { "Expected verified v1 infohash" }
            return StoppedRemoteAddPlan(torrentUrl, verifiedInfoHash.lowercase(Locale.ROOT),
                validatePath(destination), Kind.ARCHIVE_TORRENT_URL)
        }
    }
}

/** A load command acknowledgement is NOT evidence of a downloaded or moved file. */
sealed class RemoteAddOutcome {
    data class VerifiedStopped(val hash: String, val destination: String) : RemoteAddOutcome()
    data class AlreadyPresent(val hash: String) : RemoteAddOutcome()
    data class PendingVerification(val hash: String) : RemoteAddOutcome()
    data class Uncertain(val hash: String) : RemoteAddOutcome()
}
