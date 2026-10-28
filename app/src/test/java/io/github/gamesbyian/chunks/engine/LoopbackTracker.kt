package io.github.gamesbyian.chunks.engine

import java.net.InetSocketAddress

/**
 * Minimal BEP 3/23 HTTP tracker bound to loopback for one test: records each
 * announcer's loopback address and port and returns the other peers in
 * compact form. Never reachable off the runner; holds no real swarm data.
 */
internal class LoopbackTracker : AutoCloseable {
    private val peers = java.util.concurrent.ConcurrentHashMap<String, MutableSet<InetSocketAddress>>()
    private val server = com.sun.net.httpserver.HttpServer.create(
        InetSocketAddress("127.0.0.1", 0), 8).apply {
        createContext("/announce") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty().split('&')
                .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (key, value) -> key to value }
            val infoHash = query["info_hash"]?.let(::percentDecodedHex)
            val port = query["port"]?.toIntOrNull()
            val body = if (infoHash == null || port == null || port !in 1..65535) {
                bencodedFailure("bad announce")
            } else {
                val self = InetSocketAddress(exchange.remoteAddress.address, port)
                val swarm = peers.computeIfAbsent(infoHash) {
                    java.util.Collections.synchronizedSet(LinkedHashSet())
                }
                if (query["event"] == "stopped") swarm.remove(self) else swarm.add(self)
                announces.incrementAndGet()
                val compact = java.io.ByteArrayOutputStream()
                synchronized(swarm) {
                    swarm.filter { it != self }.forEach { peer ->
                        compact.write(peer.address.address)
                        compact.write(peer.port shr 8)
                        compact.write(peer.port and 0xff)
                    }
                }
                val bytes = compact.toByteArray()
                "d8:intervali5e12:min intervali1e5:peers${bytes.size}:".toByteArray(Charsets.ISO_8859_1) +
                    bytes + "e".toByteArray()
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    /** Every well-formed announce received, from any client. */
    val announces = java.util.concurrent.atomic.AtomicInteger()

    val announceUrl = "http://127.0.0.1:${server.address.port}/announce"

    private fun bencodedFailure(reason: String) =
        "d14:failure reason${reason.length}:${reason}e".toByteArray()

    private fun percentDecodedHex(raw: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '%' && index + 2 < raw.length) {
                out.write(raw.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                out.write(c.code)
                index++
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.size == 20) bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } else null
    }

    override fun close() = server.stop(0)
}
