package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * A typed or pasted torrent web link, before anything is fetched
 * ([ADD-SOURCE-INTAKE]). Only HTTPS is fetched: every hop's host is resolved
 * and refused if it names this phone or a private network, and HTTPS hostname
 * verification then stops a DNS answer from silently switching to a local
 * device (it cannot present a certificate for the public name). A plain-HTTP
 * link is refused with a way forward instead of fetched unverified.
 */
internal sealed class TorrentLinkCheck {
    object NotALink : TorrentLinkCheck()
    data class Problem(val message: String) : TorrentLinkCheck()
    /** [display] is the host and last path segment only; queries (tokens, passkeys) are never shown. */
    data class Fetchable(val uri: URI, val display: String) : TorrentLinkCheck()

    companion object {
        const val PLAIN_HTTP =
            "Chunks fetches torrent links over HTTPS only. Open this link in your browser, then share or open the downloaded .torrent with Chunks."
        const val UNSUPPORTED =
            "This web link cannot be fetched. Use an https:// link to a .torrent file."
        const val CREDENTIALS =
            "This link contains a username or password. Chunks does not send those; use a link without them."

        fun inspect(raw: String): TorrentLinkCheck {
            val text = raw.trim()
            val lower = text.lowercase(Locale.ROOT)
            if (!lower.startsWith("https://") && !lower.startsWith("http://")) return NotALink
            if (text.length > 4096 || text.any { it.isISOControl() || it == ' ' }) return Problem(UNSUPPORTED)
            val uri = try { URI(text) } catch (_: Exception) { return Problem(UNSUPPORTED) }
            if (uri.scheme.equals("http", ignoreCase = true)) return Problem(PLAIN_HTTP)
            if (uri.rawUserInfo != null) return Problem(CREDENTIALS)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return Problem(UNSUPPORTED)
            if (uri.port != -1 && uri.port !in 1..65535) return Problem(UNSUPPORTED)
            return Fetchable(uri, displayOf(uri, host))
        }

        /** Host plus the last path segment, decoded and bounded; never the query. */
        fun displayOf(uri: URI, host: String = uri.host.orEmpty()): String {
            val last = uri.path.orEmpty().trimEnd('/').substringAfterLast('/')
                .filterNot { it.isISOControl() }.take(80)
            return if (last.isEmpty()) host.lowercase(Locale.ROOT) else host.lowercase(Locale.ROOT) + " · " + last
        }

        /** A clipboard link worth placing in an empty Add: HTTPS that looks like a torrent download. */
        fun plausibleTorrentLink(raw: String): Boolean {
            val check = inspect(raw) as? Fetchable ?: return false
            val path = check.uri.path.orEmpty().lowercase(Locale.ROOT)
            return path.endsWith(".torrent") || "/torrent" in path || "/download" in path ||
                check.uri.rawQuery.orEmpty().lowercase(Locale.ROOT).let { "torrent" in it }
        }
    }
}

/** Addresses a fetched link may never reach: this phone, private networks and special ranges. */
internal object TorrentLinkAddressPolicy {
    fun allowed(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 0xFF }
        return when (address) {
            is Inet4Address -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 ||
                (b[0] == 100 && b[1] in 64..127) ||            // carrier-grade NAT
                (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) || (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
                (b[0] == 198 && b[1] in 18..19) || b[0] >= 224)
            is Inet6Address -> {
                val mapped = b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF
                // Well-known DNS64/NAT64 IPv6 answers encode an IPv4
                // destination in their final 32 bits. Without unwrapping it,
                // 64:ff9b::10.0.0.1 would bypass the IPv4 private-address
                // guard. Retain IPv6-only access to *public* NAT64 peers.
                val wellKnownNat64 = b[0] == 0x00 && b[1] == 0x64 &&
                    b[2] == 0xFF && b[3] == 0x9B &&
                    b.subList(4, 12).all { it == 0 }
                // Java exposes deprecated IPv4-compatible ::a.b.c.d as an
                // Inet6Address (unlike ::ffff:a.b.c.d, which may become v4).
                // Those literal private targets must not bypass the policy.
                val ipv4Compatible = b.subList(0, 12).all { it == 0 }
                val ipv4Tail = { offset: Int ->
                    InetAddress.getByAddress(b.subList(offset, offset + 4)
                        .map { it.toByte() }.toByteArray())
                }
                when {
                    mapped || wellKnownNat64 || ipv4Compatible -> allowed(ipv4Tail(12))
                    // Local-use NAT64 (/48), 6to4 and Teredo may route through
                    // translation/tunnel infrastructure. Their private target
                    // cannot be reliably established from the visible host
                    // address, so refuse instead of treating them as public.
                    b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF &&
                        b[3] == 0x9B && b[4] == 0x00 && b[5] == 0x01 -> false
                    b[0] == 0x20 && b[1] == 0x02 -> false // 6to4 2002::/16
                    b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 &&
                        b[3] == 0x00 -> false // Teredo 2001::/32
                    else -> !((b[0] and 0xFE) == 0xFC || // unique local fc00::/7
                        (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) || // fe80::/10
                        (b[0] == 0x20 && b[1] == 0x01 &&
                            b[2] == 0x0D && b[3] == 0xB8)) // documentation
                }
            }
            else -> false
        }
    }
}

internal sealed class TorrentLinkResult {
    data class Fetched(val bytes: ByteArray, val preview: TorrentMetainfoPreview, val display: String) :
        TorrentLinkResult()
    data class Refused(val message: String) : TorrentLinkResult()

    companion object {
        const val LOCAL_NETWORK = "This link points to this phone or a private network. Chunks only fetches torrent links from public HTTPS sites."
        const val NO_HOST = "This link's site could not be found. Check the link or your connection. Nothing was added."
        const val TIMEOUT = "The site did not answer in time. Nothing was added; you can try again."
        const val OFFLINE = "The torrent could not be downloaded. Check your connection and try again. Nothing was added."
        const val DOWNGRADE = "This link redirected to an insecure http:// address, so Chunks stopped. Nothing was added."
        const val TOO_MANY_REDIRECTS = "This link redirected too many times. Nothing was added."
        const val REFUSED = "The site refused this link. It may need you to sign in, or the link has expired. Chunks does not use your browser's login."
        const val NOT_FOUND = "The site says this torrent no longer exists at that link."
        const val BUSY = "The site is busy or unavailable right now. Try again later."
        const val WEB_PAGE = "This link opened a web page, not a .torrent file. Open it in your browser and share the downloaded .torrent with Chunks."
        const val TOO_LARGE = "This link returned more than 2 MiB, which is too large for a .torrent file. Nothing was added."
        const val NOT_TORRENT = "The link did not return a supported BitTorrent v1 .torrent file. Nothing was added."
        const val UNEXPECTED = "The site returned an unexpected response. Nothing was added."
    }
}

/**
 * Bounded, read-only fetch of one torrent link, only after the person asks.
 * Redirects are followed by hand so each hop is checked; nothing is retried
 * automatically and no cookie, login or browser state is used. The returned
 * bytes go to the existing reviewed .torrent Add, which still needs the usual
 * confirmation. Messages never contain the link or its query.
 */
internal class TorrentLinkFetcher(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maxBytes: Int = MAX_BYTES,
    private val maxRedirects: Int = 5,
    private val timeoutMs: Int = 15_000
) {
    fun fetch(start: TorrentLinkCheck.Fetchable): TorrentLinkResult {
        var uri = start.uri
        repeat(maxRedirects + 1) {
            if (!uri.scheme.equals("https", ignoreCase = true))
                return TorrentLinkResult.Refused(TorrentLinkResult.DOWNGRADE)
            // A redirect is a new source chosen by a remote site, not a user
            // waiver of the original HTTPS-only/credential/length/port policy.
            // Reuse the exact admission check on EVERY hop before DNS or I/O.
            val hop = when (val check = TorrentLinkCheck.inspect(uri.toString())) {
                is TorrentLinkCheck.Fetchable -> check
                is TorrentLinkCheck.Problem -> return TorrentLinkResult.Refused(check.message)
                TorrentLinkCheck.NotALink -> return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
            }
            val host = hop.uri.host ?: return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
            val addresses = try { resolve(host) } catch (_: UnknownHostException) {
                return TorrentLinkResult.Refused(TorrentLinkResult.NO_HOST)
            } catch (_: SecurityException) {
                return TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE)
            }
            if (addresses.isEmpty()) return TorrentLinkResult.Refused(TorrentLinkResult.NO_HOST)
            if (!addresses.all(TorrentLinkAddressPolicy::allowed)) return TorrentLinkResult.Refused(TorrentLinkResult.LOCAL_NETWORK)
            val connection = try { open(uri.toURL()) } catch (_: Exception) {
                return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("Accept", "application/x-bittorrent, application/octet-stream;q=0.9, */*;q=0.1")
                connection.setRequestProperty("User-Agent", "Chunks")
                val code = connection.responseCode
                when (code) {
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
                        uri = try { uri.resolve(location.trim()) } catch (_: Exception) {
                            return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
                        }
                        return@repeat
                    }
                    401, 403 -> return TorrentLinkResult.Refused(TorrentLinkResult.REFUSED)
                    404, 410 -> return TorrentLinkResult.Refused(TorrentLinkResult.NOT_FOUND)
                    429, in 500..599 -> return TorrentLinkResult.Refused(TorrentLinkResult.BUSY)
                    200 -> return read(connection, start.display)
                    else -> return TorrentLinkResult.Refused(TorrentLinkResult.UNEXPECTED)
                }
            } catch (_: SocketTimeoutException) {
                return TorrentLinkResult.Refused(TorrentLinkResult.TIMEOUT)
            } catch (_: UnknownHostException) {
                return TorrentLinkResult.Refused(TorrentLinkResult.NO_HOST)
            } catch (_: IOException) {
                return TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE)
            } finally {
                connection.disconnect()
            }
        }
        return TorrentLinkResult.Refused(TorrentLinkResult.TOO_MANY_REDIRECTS)
    }

    private fun read(connection: HttpURLConnection, display: String): TorrentLinkResult {
        val declared = connection.contentLengthLong
        if (declared > maxBytes) return TorrentLinkResult.Refused(TorrentLinkResult.TOO_LARGE)
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            var emptyReads = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                // A broken HTTPS stream may repeatedly return zero without
                // ending. Apply the same finite no-progress bound as Android
                // content-document intake rather than spinning indefinitely.
                if (n == 0) {
                    if (++emptyReads > 16) return TorrentLinkResult.Refused(TorrentLinkResult.OFFLINE)
                    continue
                }
                emptyReads = 0
                if (out.size() + n > maxBytes) return TorrentLinkResult.Refused(TorrentLinkResult.TOO_LARGE)
                out.write(buffer, 0, n)
            }
        }
        val bytes = out.toByteArray()
        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }
        if (first == '<'.code.toByte()) return TorrentLinkResult.Refused(TorrentLinkResult.WEB_PAGE)
        val preview = try { TorrentMetainfoReader.preview(bytes) } catch (_: Exception) {
            return TorrentLinkResult.Refused(TorrentLinkResult.NOT_TORRENT)
        }
        return TorrentLinkResult.Fetched(bytes, preview, display)
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Under an Add source field holding a web link: what will be fetched (host
 * and file name only) and the one explicit action that fetches it.
 */
@androidx.compose.runtime.Composable
internal fun TorrentLinkPanel(
    check: TorrentLinkCheck,
    fetching: Boolean,
    enabled: Boolean,
    onFetch: (TorrentLinkCheck.Fetchable) -> Unit
) {
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    when (check) {
        TorrentLinkCheck.NotALink -> Unit
        is TorrentLinkCheck.Problem -> androidx.compose.material3.Text(check.message,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error, style = small,
            modifier = androidx.compose.ui.Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        is TorrentLinkCheck.Fetchable -> androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.Text("Torrent link: " + check.display,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Text(
                "Chunks downloads only the .torrent file from this site when you tap below, then shows it for review. " +
                    "Nothing is added until you confirm.", style = small)
            androidx.compose.material3.Button(
                onClick = { onFetch(check) }, enabled = enabled && !fetching,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { androidx.compose.material3.Text(if (fetching) "Getting torrent…" else "Get torrent from link") }
        }
    }
}
