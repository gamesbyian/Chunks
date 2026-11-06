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
fun interface MediaHostPolicy {
    fun allows(host: String): Boolean
    /** Checked against the actually connected peer address; restrictive policies override it. */
    fun allowsPeer(address: java.net.InetAddress): Boolean = true
    val restrictsPeers: Boolean get() = false
}

/** Hosts listed as ".example.org" also allow subdomains; others are exact. */
fun mediaHostPolicy(hosts: Collection<String>): MediaHostPolicy = MediaHostPolicy { raw ->
    val host = raw.lowercase(Locale.ROOT).trimEnd('.')
    hosts.any { h -> if (h.startsWith('.')) host.endsWith(h) || host == h.substring(1) else host == h }
}

/** Blocking, bounded GET. Callers run it off the main thread. */
interface MediaHttp {
    fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String> = emptyMap(), maxBytes: Int = 1_000_000): MediaHttpResponse

    /** Bounded JSON POST (GraphQL providers). No redirects are followed for POST. */
    fun postJson(url: String, hosts: MediaHostPolicy, body: String, headers: Map<String, String> = emptyMap(),
                 maxBytes: Int = 1_000_000): MediaHttpResponse =
        throw MediaHttpException(MediaHttpFailure.IO, "POST not supported")
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
    override fun postJson(url: String, hosts: MediaHostPolicy, body: String, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse =
        request(url, hosts, headers, maxBytes, body.toByteArray(Charsets.UTF_8))

    override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse =
        request(url, hosts, headers, maxBytes, null)

    private fun request(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int, post: ByteArray?): MediaHttpResponse {
        if (post != null && post.size > 64 * 1024) throw MediaHttpException(MediaHttpFailure.TOO_LARGE, "Request too large")
        var current = url
        repeat(4) {
            val uri = try { URI(current) } catch (e: Exception) { throw MediaHttpException(MediaHttpFailure.IO, "Malformed URL") }
            if (!"https".equals(uri.scheme, ignoreCase = true)) throw MediaHttpException(MediaHttpFailure.NOT_HTTPS, "Only HTTPS is allowed")
            val host = uri.host ?: throw MediaHttpException(MediaHttpFailure.IO, "Missing host")
            if (!hosts.allows(host)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider")
            if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Unexpected port or credentials")
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                // Through an HTTP proxy the TCP peer is the proxy, which resolves the name itself; then only
                // the name-level resolution check applies. Direct connections also check the real peer.
                val direct = runCatching { java.net.ProxySelector.getDefault()?.select(uri)?.all { it.type() == java.net.Proxy.Type.DIRECT } }
                    .getOrNull() != false
                if (hosts.restrictsPeers && direct && this is javax.net.ssl.HttpsURLConnection) {
                    sslSocketFactory = PeerCheckingSocketFactory(sslSocketFactory, hosts)
                }
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (post != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(post.size)
                }
            }
            try {
                if (post != null) conn.outputStream.use { it.write(post) }
                val status = try { conn.responseCode } catch (e: UnknownHostException) {
                    throw MediaHttpException(MediaHttpFailure.OFFLINE, "Network unavailable")
                } catch (e: SocketTimeoutException) { throw MediaHttpException(MediaHttpFailure.TIMEOUT, "Timed out") }
                if (status in 300..399 && post != null) throw MediaHttpException(MediaHttpFailure.IO, "Unexpected redirect")
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

/**
 * Verifies the TCP peer the TLS layer is about to run over. HttpsURLConnection resolves the name
 * itself after the host policy did, so a rebinding resolver could otherwise swap in a LAN address.
 */
internal class PeerCheckingSocketFactory(private val delegate: javax.net.ssl.SSLSocketFactory,
                                         private val policy: MediaHostPolicy) : javax.net.ssl.SSLSocketFactory() {
    private fun <T : java.net.Socket> checked(s: T): T {
        val peer = s.inetAddress
        if (peer != null && !policy.allowsPeer(peer)) { runCatching { s.close() }; throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider") }
        return s
    }
    private fun checkLayered(plain: java.net.Socket) {
        val peer = plain.inetAddress
        if (peer != null && !policy.allowsPeer(peer)) { runCatching { plain.close() }; throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider") }
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(s: java.net.Socket, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
        checkLayered(s); return delegate.createSocket(s, host, port, autoClose)
    }
    override fun createSocket(): java.net.Socket = PeerCheckingSocket(delegate.createSocket() as javax.net.ssl.SSLSocket, policy)
    override fun createSocket(host: String?, port: Int): java.net.Socket = checked(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, local: java.net.InetAddress?, localPort: Int): java.net.Socket =
        checked(delegate.createSocket(host, port, local, localPort))
    override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = checked(delegate.createSocket(host, port))
    override fun createSocket(address: java.net.InetAddress?, port: Int, local: java.net.InetAddress?, localPort: Int): java.net.Socket =
        checked(delegate.createSocket(address, port, local, localPort))
}

/** Unconnected-socket path: verify the endpoint at connect time, before any TLS bytes are sent. */
internal class PeerCheckingSocket(private val inner: javax.net.ssl.SSLSocket, private val policy: MediaHostPolicy) : javax.net.ssl.SSLSocket() {
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
        val a = (endpoint as? java.net.InetSocketAddress)?.address
        if (a == null || !policy.allowsPeer(a)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider")
        inner.connect(endpoint, timeout)
    }
    override fun connect(endpoint: java.net.SocketAddress?) = connect(endpoint, 0)
    override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = inner.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>?) { inner.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = inner.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = inner.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>?) { inner.enabledProtocols = protocols }
    override fun getSession(): javax.net.ssl.SSLSession = inner.session
    override fun addHandshakeCompletedListener(l: javax.net.ssl.HandshakeCompletedListener?) = inner.addHandshakeCompletedListener(l)
    override fun removeHandshakeCompletedListener(l: javax.net.ssl.HandshakeCompletedListener?) = inner.removeHandshakeCompletedListener(l)
    override fun startHandshake() = inner.startHandshake()
    override fun setUseClientMode(mode: Boolean) { inner.useClientMode = mode }
    override fun getUseClientMode(): Boolean = inner.useClientMode
    override fun setNeedClientAuth(need: Boolean) { inner.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = inner.needClientAuth
    override fun setWantClientAuth(want: Boolean) { inner.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = inner.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) { inner.enableSessionCreation = flag }
    override fun getEnableSessionCreation(): Boolean = inner.enableSessionCreation
    override fun getSSLParameters(): javax.net.ssl.SSLParameters = inner.sslParameters
    override fun setSSLParameters(p: javax.net.ssl.SSLParameters?) { inner.sslParameters = p }
    override fun getInputStream(): java.io.InputStream = inner.inputStream
    override fun getOutputStream(): java.io.OutputStream = inner.outputStream
    override fun close() = inner.close()
    override fun isConnected(): Boolean = inner.isConnected
    override fun isClosed(): Boolean = inner.isClosed
    override fun getInetAddress(): java.net.InetAddress? = inner.inetAddress
    override fun getPort(): Int = inner.port
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
    override fun getKeepAlive(): Boolean = inner.keepAlive
    override fun shutdownInput() = inner.shutdownInput()
    override fun shutdownOutput() = inner.shutdownOutput()
    override fun isInputShutdown(): Boolean = inner.isInputShutdown
    override fun isOutputShutdown(): Boolean = inner.isOutputShutdown
    override fun getLocalAddress(): java.net.InetAddress? = inner.localAddress
    override fun getLocalPort(): Int = inner.localPort
    override fun getRemoteSocketAddress(): java.net.SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): java.net.SocketAddress? = inner.localSocketAddress
}

/** Rechecked on every redirect/peer validation, so revoked work cannot authorize a later hop. */
internal fun MediaHostPolicy.whileAllowed(allowed: () -> Boolean): MediaHostPolicy =
    object : MediaHostPolicy by this {
        override fun allows(host: String) = allowed() && this@whileAllowed.allows(host)
        override fun allowsPeer(address: java.net.InetAddress) = allowed() && this@whileAllowed.allowsPeer(address)
    }
