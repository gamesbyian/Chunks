package io.github.gamesbyian.chunks.media

import java.net.URI

/** Loads a recorded real provider response from src/test/resources/media-fixtures. */
fun fixture(name: String): String =
    requireNotNull(MediaTestSupport::class.java.classLoader!!.getResource("media-fixtures/$name")) { "Missing fixture $name" }.readText()

object MediaTestSupport

/**
 * Deterministic HTTP double. Routes are matched by URL substring in insertion order. Every request
 * is recorded and must pass the provider's host policy exactly as the real client enforces it.
 */
class FakeMediaHttp : MediaHttp {
    data class Route(val contains: String, val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())
    val routes = mutableListOf<Route>()
    val requests = mutableListOf<String>()
    val requestHeaders = mutableListOf<Map<String, String>>()
    val postBodies = mutableListOf<String>()

    override fun postJson(url: String, hosts: MediaHostPolicy, body: String, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
        postBodies.add(body)
        return get(url, hosts, headers, maxBytes)
    }
    var offline = false

    fun on(contains: String, status: Int = 200, body: String = "", headers: Map<String, String> = emptyMap()) = apply {
        routes.add(Route(contains, status, body.toByteArray(), headers))
    }
    fun onBytes(contains: String, body: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap()) = apply {
        routes.add(Route(contains, status, body, headers))
    }

    override fun get(url: String, hosts: MediaHostPolicy, headers: Map<String, String>, maxBytes: Int): MediaHttpResponse {
        val uri = URI(url)
        if (uri.scheme != "https") throw MediaHttpException(MediaHttpFailure.NOT_HTTPS, "Only HTTPS")
        if (!hosts.allows(uri.host)) throw MediaHttpException(MediaHttpFailure.BLOCKED_HOST, "Host not permitted for this provider")
        requests.add(url); requestHeaders.add(headers)
        if (offline) throw MediaHttpException(MediaHttpFailure.OFFLINE, "Network unavailable")
        val route = routes.firstOrNull { url.contains(it.contains) } ?: return MediaHttpResponse(404, emptyMap(), "{}".toByteArray(), url)
        if (route.body.size > maxBytes) throw MediaHttpException(MediaHttpFailure.TOO_LARGE, "Too large")
        return MediaHttpResponse(route.status, route.headers.mapKeys { it.key.lowercase() }, route.body, url)
    }
}

class FakeClock(var now: Long = 1_800_000_000_000L) {
    val sleeps = mutableListOf<Long>()
    fun sleep(ms: Long) { sleeps.add(ms); now += ms }
}
