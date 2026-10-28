package io.github.gamesbyian.chunks.interop

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.model.ServerToPhoneCopy
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Manual, main-only real-host probe of "Copy files to this phone" against the
 * owner's development Seedhost. Reads the inventory, picks the smallest
 * complete, active, non-private transfer under a size cap, and lets the phone
 * engine (JVM build) copy it with the server as the only named peer: no
 * tracker, no DHT. Nothing on the server is changed (it only uploads), and the
 * copy is deleted with the runner. A server peer port that cannot be reached
 * is reported as INCONCLUSIVE reachability, not as a Chunks defect.
 */
class SeedhostServerToPhoneCopyProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private var engine: LocalTorrentEngine? = null

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engine?.close() }

    @Test fun phoneEngineCopiesASmallCompletedTransferFromTheDevelopmentSeedhost() = runBlocking {
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        assumeTrue("Only in the manual Seedhost copy probe", rawUrl.isNotBlank() &&
            System.getenv("CHUNKS_COPY_PROBE") == "authorized")
        val url = URI(rawUrl)
        require(url.scheme.equals("https", true) && url.host.equals("ianseeds.wood.seedhost.eu", true) &&
            url.userInfo == null && url.query == null && url.fragment == null)
        val user = System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty()
        val password = System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()
        require(user.isNotBlank() && password.isNotEmpty())
        val maxBytes = System.getenv("CHUNKS_COPY_PROBE_MAX_BYTES")?.toLongOrNull() ?: (64L shl 20)
        val seconds = System.getenv("CHUNKS_COPY_PROBE_SECONDS")?.toLongOrNull() ?: 300L
        require(maxBytes in 1L..(1024L shl 20) && seconds in 30L..1800L)

        val target = System.getenv("CHUNKS_COPY_PROBE_HASH").orEmpty().lowercase()
        require(target.isEmpty() || Regex("[0-9a-f]{40}").matches(target))

        val remote = RTorrentRemote(RemoteCredentials(rawUrl, user, password))
        val inventory = remote.listTransfers("main")
        if (target.isNotEmpty()) {
            // An exact owner-named transfer: say why it is not eligible yet.
            val row = inventory.firstOrNull { it.hash.equals(target, true) }
            val reason = when {
                row == null -> "not in the main view"
                !row.started -> "not started"
                row.completedBytes != row.sizeBytes -> "incomplete ${row.completedBytes}/${row.sizeBytes} bytes"
                row.sizeBytes !in 1..maxBytes -> "larger than the cap ($maxBytes bytes)"
                else -> null
            }
            if (reason != null) {
                println("CHUNKS_SEEDHOST_COPY: INCONCLUSIVE target $target $reason")
                fail("INCONCLUSIVE: target $reason")
            }
        }
        val candidates = inventory
            .filter { (target.isEmpty() || it.hash.equals(target, true)) &&
                it.started && it.sizeBytes in 1..maxBytes && it.completedBytes == it.sizeBytes &&
                Regex("[0-9a-fA-F]{40}").matches(it.hash) }
            // Private transfers are common on this host; look past them.
            .sortedBy { it.sizeBytes }.take(40)
        var chosen: ServerToPhoneCopy.Ready? = null
        var chosenName = ""
        var chosenHash = ""
        var privateCount = 0
        var inactiveCount = 0
        var otherCount = 0
        for (row in candidates) {
            val source = remote.peerSource(row.hash)
            val plan = ServerToPhoneCopy.plan(row, source) { InetAddress.getAllByName(it).toList() }
            if (plan is ServerToPhoneCopy.Ready) { chosen = plan; chosenName = row.name; chosenHash = row.hash; break }
            when {
                source.isPrivate -> privateCount++
                !source.isActive -> inactiveCount++
                else -> otherCount++
            }
        }
        if (chosen == null) {
            println("CHUNKS_SEEDHOST_COPY: INCONCLUSIVE no complete, active, public transfer up to $maxBytes bytes " +
                "among ${candidates.size} candidates (private=$privateCount inactive=$inactiveCount other=$otherCount)")
            fail("INCONCLUSIVE: no eligible transfer")
        }
        val plan = requireNotNull(chosen)

        val phone = LocalTorrentEngine(LocalEngineConfig()).also { it.start(); engine = it }
        phone.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 64 * 1024))
        val added = phone.addMagnet(plan.magnet, temp.newFolder("copy"), autoStartAfterMetadata = true)
        val hash = (added as LocalAddResult.FetchingMetadata).status.infoHash
        val started = System.nanoTime()
        var maxPeers = 0
        var latest = requireNotNull(phone.status(hash))
        while (System.nanoTime() - started < seconds * 1_000_000_000L) {
            latest = requireNotNull(phone.status(hash))
            maxPeers = maxOf(maxPeers, latest.peers)
            if (latest.metadataKnown && latest.state in setOf(LocalTransferState.SEEDING, LocalTransferState.FINISHED)) break
            Thread.sleep(2_000)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000_000L
        val summary = "name=\"$chosenName\" hash=$hash peer=${plan.peer} bytes=${plan.bytes} " +
            "metadata=${latest.metadataKnown} verified=${latest.doneBytes} maxPeers=$maxPeers seconds=$elapsed"
        if (!latest.metadataKnown || latest.doneBytes != plan.bytes) {
            // Read-only diagnosis: can a plain TCP connect reach the server's
            // peer port, and is the server itself connected to any peers?
            val tcp = tcpReach(plan.peer)
            val serverPeers = runCatching { remote.readActivity(chosenHash).connectedPeers }.getOrNull()
            // The announced address may not be the one that accepts peers on a
            // multi-address host: try the same port on every address the RPC
            // hostname resolves to, and re-read the port in case it changed.
            val portNow = runCatching { remote.peerSource(chosenHash).listenPort }.getOrNull()
            val port = plan.peer.substringAfterLast(':')
            val rpcHostTcp = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrDefault(emptyList())
                .take(4).joinToString(",") { a ->
                    val literal = a.hostAddress.substringBefore('%')
                    val peer = (if (a is Inet6Address) "[$literal]" else literal) + ":" + port
                    "$peer=${tcpReach(peer)}"
                }
            println("CHUNKS_SEEDHOST_COPY: INCONCLUSIVE $summary tcp=$tcp serverPeers=$serverPeers " +
                "listenPortNow=$portNow rpcHostTcp=[$rpcHostTcp]")
            fail("INCONCLUSIVE server peer reachability or copy: $summary")
        }
        assertTrue(phone.flushCompletedForLibraryRead(hash, 30_000))
        assertEquals(plan.bytes, latest.wantedBytes)
        println("CHUNKS_SEEDHOST_COPY: VERIFIED $summary")
    }

    /** One 10 s TCP connect to "ip:port" or "[ip6]:port"; sends no bytes. */
    private fun tcpReach(peer: String): String {
        val port = peer.substringAfterLast(':').toInt()
        val host = peer.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 10_000) }
            "open"
        } catch (_: ConnectException) {
            "refused"
        } catch (_: SocketTimeoutException) {
            "timeout"
        } catch (e: java.io.IOException) {
            "error:${e.javaClass.simpleName}"
        }
    }
}
