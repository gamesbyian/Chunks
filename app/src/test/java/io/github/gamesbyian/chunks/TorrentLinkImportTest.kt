package io.github.gamesbyian.chunks

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link classification, address policy and the bounded fetch, without a network. */
class TorrentLinkImportTest {
    private val torrent = TorrentLinkFixtures.singleFileV1("linux.iso", 1_048_576)

    private class FakeConnection(url: URL, private val code: Int,
        private val headers: Map<String, String> = emptyMap(), private val body: ByteArray = ByteArray(0),
        private val timeout: Boolean = false) : HttpURLConnection(url) {
        var requested = false
        override fun connect() { requested = true }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { requested = true; if (timeout) throw SocketTimeoutException(); return code }
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getContentLengthLong(): Long = headers["Content-Length"]?.toLong() ?: -1L
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private val publicIp = InetAddress.getByName("207.241.224.2")
    private val opened = ArrayList<String>()

    private fun fetcher(routes: Map<String, (URL) -> HttpURLConnection>,
        dns: Map<String, List<InetAddress>> = emptyMap(), max: Int = TorrentLinkFetcher.MAX_BYTES) =
        TorrentLinkFetcher(
            resolve = { host -> dns[host] ?: listOf(publicIp) },
            open = { url -> opened += url.toString(); (routes[url.toString()] ?: error("unexpected $url"))(url) },
            maxBytes = max)

    private fun link(text: String) = TorrentLinkCheck.inspect(text) as TorrentLinkCheck.Fetchable

    @Test fun classificationAndRedactedDisplay() {
        assertEquals(TorrentLinkCheck.NotALink, TorrentLinkCheck.inspect("magnet:?xt=urn:btih:" + "a".repeat(40)))
        assertEquals(TorrentLinkCheck.Problem(TorrentLinkCheck.PLAIN_HTTP), TorrentLinkCheck.inspect("http://example.com/a.torrent"))
        assertEquals(TorrentLinkCheck.Problem(TorrentLinkCheck.CREDENTIALS), TorrentLinkCheck.inspect("https://u:p@example.com/a.torrent"))
        val tokened = link("https://Tracker.Example/download.php?id=7&passkey=SECRET123")
        assertEquals("tracker.example · download.php", tokened.display)
        assertFalse("a passkey never reaches the display", "SECRET" in tokened.display)
        assertEquals("linuxtracker.org · download",
            link("https://linuxtracker.org/torrents/f6a86eee64d65f3f970f22f64e584dfbf3a76d84/download/").display)
    }

    @Test fun plausibleClipboardLinksAreTorrentShapedHttpsOnly() {
        assertTrue(TorrentLinkCheck.plausibleTorrentLink("https://archive.org/download/x/x_archive.torrent"))
        assertTrue(TorrentLinkCheck.plausibleTorrentLink("https://linuxtracker.org/torrents/abc/download/"))
        assertTrue(TorrentLinkCheck.plausibleTorrentLink("https://t.example/get.php?torrent=5&passkey=x"))
        assertFalse(TorrentLinkCheck.plausibleTorrentLink("https://news.example/story/today"))
        assertFalse(TorrentLinkCheck.plausibleTorrentLink("http://archive.org/x.torrent"))
    }

    @Test fun addressPolicyRefusesThisPhoneAndPrivateNetworks() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.10", "169.254.1.1", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:192.168.1.1", "198.18.0.1"))
            assertFalse(ip, TorrentLinkAddressPolicy.allowed(InetAddress.getByName(ip)))
        for (ip in listOf("207.241.224.2", "104.21.33.39", "2606:4700::6810:84e5"))
            assertTrue(ip, TorrentLinkAddressPolicy.allowed(InetAddress.getByName(ip)))
    }

    @Test fun extensionlessLinkReturnsTheTorrentForReview() {
        val url = "https://linuxtracker.org/torrents/abc/download/"
        val result = fetcher(mapOf(url to { u -> FakeConnection(u, 200, mapOf("Content-Type" to "application/x-bittorrent"), torrent) }))
            .fetch(link(url))
        result as TorrentLinkResult.Fetched
        assertEquals("linux.iso", result.preview.name)
        assertEquals("linuxtracker.org · download", result.display)
    }

    @Test fun redirectsAreCheckedHopByHop() {
        val a = "https://archive.org/download/x/x_archive.torrent"
        val b = "https://dn1.ca.archive.org/0/items/x/x_archive.torrent"
        val ok = fetcher(mapOf(
            a to { u -> FakeConnection(u, 302, mapOf("Location" to b)) },
            b to { u -> FakeConnection(u, 200, body = torrent) })).fetch(link(a))
        assertTrue(ok is TorrentLinkResult.Fetched)

        val toLocal = fetcher(mapOf(a to { u -> FakeConnection(u, 302, mapOf("Location" to "https://router.lan/admin")) }),
            dns = mapOf("router.lan" to listOf(InetAddress.getByName("192.168.1.1")))).fetch(link(a))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.LOCAL_NETWORK), toLocal)

        val downgrade = fetcher(mapOf(a to { u -> FakeConnection(u, 301, mapOf("Location" to "http://archive.org/x.torrent")) }))
            .fetch(link(a))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.DOWNGRADE), downgrade)

        val loop = fetcher(mapOf(a to { u -> FakeConnection(u, 302, mapOf("Location" to a)) })).fetch(link(a))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.TOO_MANY_REDIRECTS), loop)
    }

    @Test fun aHostThatResolvesPrivatelyIsNeverOpened() {
        val url = "https://intranet.example/a.torrent"
        val result = fetcher(emptyMap(), dns = mapOf("intranet.example" to
            listOf(publicIp, InetAddress.getByName("10.0.0.5")))).fetch(link(url))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.LOCAL_NETWORK), result)
        assertTrue("no connection is opened", opened.isEmpty())
    }

    @Test fun specificRecoverableErrors() {
        val url = "https://t.example/a.torrent"
        fun with(c: (URL) -> HttpURLConnection) = fetcher(mapOf(url to c)).fetch(link(url))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.REFUSED), with { FakeConnection(it, 403) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.REFUSED), with { FakeConnection(it, 401) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.NOT_FOUND), with { FakeConnection(it, 404) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.BUSY), with { FakeConnection(it, 503) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.TIMEOUT), with { FakeConnection(it, 200, timeout = true) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.WEB_PAGE),
            with { FakeConnection(it, 200, body = "  <!doctype html><title>Login</title>".toByteArray()) })
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.NOT_TORRENT),
            with { FakeConnection(it, 200, body = "d3:fooi1ee".toByteArray()) })
        val noDns = TorrentLinkFetcher(resolve = { throw UnknownHostException() }, open = { error("no") }).fetch(link(url))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.NO_HOST), noDns)
    }

    @Test fun sizeIsCappedWhetherOrNotTheServerDeclaresIt() {
        val url = "https://t.example/a.torrent"
        val declared = fetcher(mapOf(url to { u -> FakeConnection(u, 200, mapOf("Content-Length" to "999999999")) }), max = 1024)
            .fetch(link(url))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.TOO_LARGE), declared)
        val streamed = fetcher(mapOf(url to { u -> FakeConnection(u, 200, body = ByteArray(5000)) }), max = 1024).fetch(link(url))
        assertEquals(TorrentLinkResult.Refused(TorrentLinkResult.TOO_LARGE), streamed)
    }

    @Test fun messagesNeverCarryTheLinkOrItsToken() {
        val url = "https://t.example/a.torrent?passkey=SECRET123"
        val r = fetcher(mapOf(url to { u -> FakeConnection(u, 403) })).fetch(link(url)) as TorrentLinkResult.Refused
        assertFalse("SECRET123" in r.message || "t.example" in r.message)
    }
}

/** Minimal valid single-file v1 metainfo, built here so the test needs no fixture file. */
internal object TorrentLinkFixtures {
    fun singleFileV1(name: String, length: Long): ByteArray {
        val pieceLength = 262_144L
        val pieces = ((length + pieceLength - 1) / pieceLength).toInt()
        val out = java.io.ByteArrayOutputStream()
        fun s(v: String) { val b = v.toByteArray(); out.write("${b.size}:".toByteArray()); out.write(b) }
        out.write("d8:announce".toByteArray()); s("https://tracker.example/announce")
        out.write("4:infod6:lengthi${length}e4:name".toByteArray()); s(name)
        out.write("12:piece lengthi${pieceLength}e6:pieces${pieces * 20}:".toByteArray())
        out.write(ByteArray(pieces * 20))
        out.write("ee".toByteArray())
        return out.toByteArray()
    }
}
