package io.github.gamesbyian.chunks.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

data class MediaHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray, val finalUrl: String) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    val text: String get() = body.toString(Charsets.UTF_8)
}

enum class MediaHttpFailure { OFFLINE, TIMEOUT, TOO_LARGE, BLOCKED_HOST, NOT_HTTPS, TOO_MANY_REDIRECTS, IO }

class MediaHttpException(val failure: MediaHttpFailure, message: String) : IOException(message)

/** Host policy for one request: every hop (including redirects) must be HTTPS and allowed. */
fun interface MediaHostPolicy { fun allows(host: String): Boolean }

/** Hosts listed as ".example.org" also allow subdomains; others are exact. */
fun mediaHostPolicy(hosts: Collection<String>): MediaHostPolicy = MediaHostPolicy { raw ->
    val host = raw.lowercase(Locale.ROOT).trimEnd('.')
    hosts.any { h -> if (h.startsWith('.')) host.endsWith(h) || host == h.substring(1) else host == h }
}

/** Blocking, bounded GET. Callers run it off the main thread. */
interface MediaHttp {
    fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String> = emptyMap(), maxBytes: Int = 1_000_000): MediaHttpResponse
}

object MediaUserAgent {
    /** Identifies the app and its public project page as MusicBrainz/Open Library request. No user data. */
    const val VALUE = "Chunks/0.1 ( https://github.com/gamesbyian/Chunks )"
}

/**
 * [HttpURLConnection] implementation: manual redirects (max 3) with per-hop HTTPS + host checks,
 * no cookies, no credentials, connect/read timeouts and a hard body cap.
 */
class UrlConnectionMediaHttp(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
    private val userAgent: String = MediaUserAgent.VALUE
) : MediaHttp {
    override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
        var current = url
        repeat(4) {
            val uri = try { URI(current) } catch (e: Exception) { throw MediaHttpException(MediaHttpFailure.IO, "Malformed URL") }
            if (!"https".equals(uri.scheme, ignoreCase = true)) throw MediaHttpException(MediaHttpFailure.NOT_HTTPS, "Only HTTPS is allowed")
            val host = uri.host ?: throw MediaHttpException(MediaHttpFailure.IO, "Missing host")
            if (!hosts.allows(host)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider")
            if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Unexpected port or credentials")
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val status = try { conn.responseCode } catch (e: UnknownHostException) {
                    throw MediaHttpException(MediaHttpFailure.OFFLINE, "Network unavailable")
                } catch (e: SocketTimeoutException) { throw MediaHttpException(MediaHttpFailure.TIMEOUT, "Timed out") }
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw MediaHttpException(MediaHttpFailure.IO, "Redirect without location")
                    current = uri.resolve(location).toString()
                    return@repeat
                }
                val length = conn.contentLengthLong
                if (length > maxBytes) throw MediaHttpException(MediaHttpFailure.TOO_LARGE, "Response too large")
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val body = stream?.use { input ->
                    val out = ByteArrayOutputStream(minOf(maxOf(length, 0L), maxBytes.toLong()).toInt().coerceAtLeast(1024))
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = try { input.read(buf) } catch (e: SocketTimeoutException) { throw MediaHttpException(MediaHttpFailure.TIMEOUT, "Timed out") }
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw MediaHttpException(MediaHttpFailure.TOO_LARGE, "Response too large")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                val hdrs = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase(Locale.ROOT) }
                    .mapValues { it.value.firstOrNull() ?: "" }
                return MediaHttpResponse(status, hdrs, body, current)
            } catch (e: MediaHttpException) { throw e
            } catch (e: SocketTimeoutException) { throw MediaHttpException(MediaHttpFailure.TIMEOUT, "Timed out")
            } catch (e: UnknownHostException) { throw MediaHttpException(MediaHttpFailure.OFFLINE, "Network unavailable")
            } catch (e: IOException) { throw MediaHttpException(MediaHttpFailure.IO, "Network error")
            } finally { conn.disconnect() }
        }
        throw MediaHttpException(MediaHttpFailure.TOO_MANY_REDIRECTS, "Too many redirects")
    }
}
