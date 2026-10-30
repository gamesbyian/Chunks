package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** A validated v1 Add target. Keep as a normal class: a data-class copy() bypasses private-constructor validation. */
class StoppedRemoteAddPlan private constructor(
    val source: String,
    val expectedInfoHash: String,
    val destination: String,
    val kind: Kind,
    /** True means omit d.directory.set after re-reading this server default. */
    val useServerDefault: Boolean = false
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

        /**
         * Leave the server's configured destination untouched. The observed
         * default must still be a concrete valid absolute parent for exact
         * post-Add readback; the remote adapter rechecks it before writing.
         */
        fun fromMagnetAtServerDefault(magnet: String, observedDefault: String): StoppedRemoteAddPlan {
            val validated = fromMagnet(magnet, observedDefault)
            return StoppedRemoteAddPlan(validated.source, validated.expectedInfoHash,
                validated.destination, validated.kind, useServerDefault = true)
        }

        /** The expected hash MUST come from validated fixture metainfo, never a filename. */
        fun fromArchiveTorrent(
            torrentUrl: String, verifiedInfoHash: String, destination: String
        ): StoppedRemoteAddPlan {
            val uri = URI(torrentUrl)
            // This URL is executed by rTorrent on the SERVER, not fetched by
            // Android. Keep it on archive.org's ordinary HTTPS service. A
            // nondefault port or escaped separator/dot can change the path
            // after a reverse proxy performs another decoding pass.
            val rawPath = uri.rawPath.orEmpty()
            val segments = rawPath.split('/')
            val ambiguous = Regex("(?i)%(?:25|2e|2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
            require(uri.scheme.equals("https", true) &&
                uri.host.equals("archive.org", true) && uri.port in setOf(-1, 443) &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                rawPath.startsWith("/download/") && rawPath.endsWith(".torrent") &&
                segments.size >= 4 &&
                segments.drop(1).all { it.isNotBlank() && it != "." && it != ".." } &&
                !ambiguous.containsMatchIn(rawPath)) {
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
