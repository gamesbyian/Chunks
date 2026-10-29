package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Finish in Order against the disposable loopback rTorrent rig only. */
class RTorrentDisposableFinishInOrderTest {
    private fun remote(): Pair<RTorrentRemote, File> {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Runs only inside the disposable rTorrent rig", address.isNotBlank())
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.path == "/RPC2" && uri.rawQuery == null && uri.userInfo == null) {
            "Refusing to mutate outside the disposable loopback rig"
        }
        val target = File(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(target.isAbsolute && target.path.contains("chunks-data") && target.isDirectory)
        return RTorrentRemote(RemoteCredentials(address, "", "")) to target
    }

    /** Multi-file v1 torrent with one 16 KiB piece per file (piece-aligned). */
    private fun torrent(folder: String, contents: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("d8:announce27:http://127.0.0.1:9/announce4:infod5:filesl")
        contents.forEach { (name, bytes) -> w("d6:lengthi${bytes.size}e4:pathl${name.length}:${name}ee") }
        w("e4:name${folder.length}:${folder}12:piece lengthi16384e6:pieces${20 * contents.size}:")
        contents.forEach { out.write(MessageDigest.getInstance("SHA-1").digest(it.second)) }
        w("ee")
        return out.toByteArray()
    }

    @Test fun realRtorrentRaisesTheEarliestUnverifiedEpisodeAndLeavesVerifiedOnesAlone() = runBlocking {
        val (rtorrent, root) = remote()
        val folder = "fio-${System.nanoTime()}"
        val episodes = (1..3).map { n -> "Show S01E0$n.mkv" to ByteArray(16384) { (n * 31 + it).toByte() } }
        val bytes = torrent(folder, episodes)
        // Only episode 2's bytes exist on the server before the check.
        File(root, folder).mkdirs()
        File(File(root, folder), episodes[1].first).writeBytes(episodes[1].second)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val added = rtorrent.loadStoppedTorrentBytes(bytes, root.absolutePath)
        assertTrue("$added", added is RemoteAddOutcome.VerifiedStopped || added is RemoteAddOutcome.PendingVerification)
        assertTrue(rtorrent.setStarted(hash, true) !is LifecycleOutcome.Uncertain)
        var files = rtorrent.readFileCompletion(hash)
        repeat(60) { if (!files[1].verifiedComplete) { Thread.sleep(500); files = rtorrent.readFileCompletion(hash) } }
        assertTrue("rTorrent must hash-verify episode 2's existing bytes", files[1].verifiedComplete)
        assertTrue(!files[0].verifiedComplete && !files[2].verifiedComplete)

        var saved: FinishInOrderRecord? = null
        val port = RemoteFinishInOrderPort(rtorrent) { _, r -> saved = r; true }
        val observed = requireNotNull(port.observe(hash)).map { it.file }
        val record = FinishInOrderRecord(FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED,
            observed, requireNotNull(FinishInOrderPlanner.suggest(observed)).order))
        val step = FinishInOrderRunner(port).step(hash, record)
        assertTrue("$step", step is FinishInOrderRunner.Step.Applied)
        val after = rtorrent.readFileCompletion(hash)
        assertEquals(listOf(2, 1, 1), after.map { it.priority })
        assertTrue(requireNotNull(saved).pending.isEmpty())
        // Session-only cleanup of this disposable fixture; payload stays on disk.
        rtorrent.setStarted(hash, false)
        Unit
    }
}
