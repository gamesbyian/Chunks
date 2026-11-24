package io.github.gamesbyian.chunks

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal interface TorrentLinkHop : AutoCloseable {
    val status: Int
    val contentLength: Long
    fun header(name: String): String?
    fun body(): InputStream
}

internal fun interface TorrentLinkTransport {
    fun get(uri: URI, addresses: List<InetAddress>, timeoutMs: Int): TorrentLinkHop
}

internal class TorrentLinkTransportRefusal(val reason: String) : IOException(reason)

/**
 * Only this user-authorized public-source transport uses OkHttp. DNS is pinned
 * per hop, TCP has an independent pre-connect guard, and default TLS validates
 * the original URL authority (never an IP substituted into the URL).
 * No proxy fallback, global cookie handler, redirects, response cache or
 * connection reuse across independent hops. Never log URI/exception details.
 */
internal class PinnedTorrentLinkTransport(
    private val proxies: (URI) -> List<Proxy> = { uri ->
        ProxySelector.getDefault()?.select(uri) ?: listOf(Proxy.NO_PROXY)
    },
    private val beforeConnect: (InetSocketAddress) -> Unit = {}
) : TorrentLinkTransport {
    private val base by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // OkHttp may independently follow a 503 with Retry-After: 0.
            // The source-fetch contract reports Busy without a second GET.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build()
                else response
            }
            .build()
    }

    override fun get(uri: URI, addresses: List<InetAddress>, timeoutMs: Int): TorrentLinkHop {
        val pins = addresses.toSet()
        if (pins.isEmpty() || !pins.all(TorrentLinkAddressPolicy::allowed))
            throw TorrentLinkTransportRefusal(TorrentLinkResult.LOCAL_NETWORK)
        val routes = try { proxies(uri) } catch (_: Exception) {
            throw TorrentLinkTransportRefusal(TorrentLinkResult.PROXY)
        }
        if (routes.isEmpty() || routes.any { it.type() != Proxy.Type.DIRECT })
            throw TorrentLinkTransportRefusal(TorrentLinkResult.PROXY)
        val ascii = URI(uri.toASCIIString())
        val url = ascii.toString().toHttpUrlOrNull()
            ?: throw TorrentLinkTransportRefusal(TorrentLinkResult.UNEXPECTED)
        // Refuse ambiguous URL parsing or path/query normalization rather than
        // corrupt a signed request. IPv6 literals may have canonical spelling
        // changes, but their numeric socket address is independently pinned.
        val host = ascii.host?.removePrefix("[")?.removeSuffix("]")
            ?: throw TorrentLinkTransportRefusal(TorrentLinkResult.UNEXPECTED)
        if (!url.isHttps || ascii.rawUserInfo != null ||
            (!host.contains(':') && !url.host.equals(host, ignoreCase = true)) ||
            url.port != (if (ascii.port == -1) 443 else ascii.port) ||
            url.encodedPath != ascii.rawPath.orEmpty().ifEmpty { "/" } ||
            url.encodedQuery != ascii.rawQuery)
            throw TorrentLinkTransportRefusal(TorrentLinkResult.UNEXPECTED)

        val pool = ConnectionPool(0, 1, TimeUnit.SECONDS)
        val client = base.newBuilder()
            .connectionPool(pool)
            .dns(Dns { requested ->
                if (!requested.equals(url.host, ignoreCase = true))
                    throw UnknownHostException("Unexpected source host")
                pins.toList()
            })
            .socketFactory(PinnedTorrentSocketFactory(pins, url.port, beforeConnect))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong() * 2, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/x-bittorrent, application/octet-stream;q=0.9, */*;q=0.1")
            .header("User-Agent", "Chunks")
            .get().build()
        val response = try { client.newCall(request).execute() } catch (error: Exception) {
            pool.evictAll()
            throw error
        }
        return object : TorrentLinkHop {
            override val status = response.code
            override val contentLength get() = response.body.contentLength()
            override fun header(name: String) = response.header(name)
            override fun body() = response.body.byteStream()
            override fun close() {
                try { response.close() } finally { pool.evictAll() }
            }
        }
    }
}

/**
 * OkHttp uses the unconnected socket factory path. Guard the resolved address
 * and port BEFORE any TCP or TLS traffic. Explicit NO_PROXY also prevents a
 * platform SOCKS fallback. Any other factory overload is refused, not delegated.
 */
internal class PinnedTorrentSocketFactory(
    addresses: Set<InetAddress>,
    private val expectedPort: Int,
    private val beforeConnect: (InetSocketAddress) -> Unit = {}
) : SocketFactory() {
    private val pins = addresses.toSet()
    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress
            val peer = target?.address
            // Socket.port is zero before connect; compare the frozen route port,
            // never the inherited Java Socket.getPort() property.
            if (target == null || peer == null || target.port != expectedPort ||
                peer !in pins || !TorrentLinkAddressPolicy.allowed(peer))
                throw TorrentLinkTransportRefusal(TorrentLinkResult.LOCAL_NETWORK)
            beforeConnect(target)
            super.connect(target, timeout)
        }
        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    }
    private fun refused(): Nothing = throw TorrentLinkTransportRefusal(TorrentLinkResult.LOCAL_NETWORK)
    override fun createSocket(host: String?, port: Int): Socket = refused()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = refused()
    override fun createSocket(host: InetAddress?, port: Int): Socket = refused()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = refused()
}
