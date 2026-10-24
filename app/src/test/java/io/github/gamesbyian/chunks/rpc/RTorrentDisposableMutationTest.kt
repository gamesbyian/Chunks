package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real rTorrent 0.9.x integration test running ONLY against localhost ephemeral
 * rTorrent + SCGI/nginx. Never point at a user seedbox or non-loopback server.
 *
 * Test data need not exist for a stopped transfer; the ephemeral process and
 * data root are destroyed with its isolated CI runner.
 */
class RTorrentDisposableMutationTest {
    private fun temporaryRemote(): RTorrentRemote {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Real integration only when disposable test rig is enabled", address.isNotBlank())
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 &&
            uri.path == "/RPC2" && uri.rawQuery == null && uri.userInfo == null) {
            "Refusing to run mutation test outside the disposable loopback rig"
        }
        return RTorrentRemote(RemoteCredentials(address, "", ""))
    }

    private val bytes = (
        "d4:info" +
        "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789e" +
        "e"
    ).toByteArray(Charsets.UTF_8)

    @Test fun stoppedBinaryAddAndHashBoundedLifecycleWorkOnRealEngine() = runBlocking {
        val rtorrent = temporaryRemote()
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val target = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(target.startsWith("/") && target.contains("chunks-data")) {
            "No disposable data root was configured"
        }

        assertTrue("No duplicate fixture should exist in this clean real engine",
            rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })

        // One real binary metainfo Add. No retry if it is uncertain or pending.
        val first = rtorrent.loadStoppedTorrentBytes(bytes, target)
        assertTrue("RPC must not leave a divergent location or uncertain mutation: $first",
            first is RemoteAddOutcome.VerifiedStopped ||
                first is RemoteAddOutcome.PendingVerification)

        // The rTorrent event loop can complete the load on a later tick.
        var observed: RemoteTransfer? = null
        for (index in 1..15) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (observed != null) break
            Thread.sleep(400)
        }
        assertNotNull("Actual rTorrent never listed loaded test hash", observed)
        val added = requireNotNull(observed)
        assertFalse("New test torrent must not auto-start", added.started)
        assertTrue("Existing data must not be sent to a default directory",
            added.directory.startsWith(target))

        val second = rtorrent.loadStoppedTorrentBytes(bytes, target)
        assertEquals("Duplicate Add must be refused without mutation",
            RemoteAddOutcome.AlreadyPresent(hash), second)

        // A real engine may perform this transition asynchronously.
        val start = rtorrent.setStarted(hash, true)
        assertTrue("Start should be acknowledged with sane state", start is LifecycleOutcome.Verified ||
            start is LifecycleOutcome.Pending)
        var running = false
        for (index in 1..15) {
            running = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.started == true
            if (running) break
            Thread.sleep(400)
        }
        assertTrue("Real rTorrent did not enter started state", running)

        val stop = rtorrent.setStarted(hash, false)
        assertTrue("Stop should not have uncertain transport outcome", stop is LifecycleOutcome.Verified ||
            stop is LifecycleOutcome.Pending)
        var stopped = false
        for (index in 1..15) {
            stopped = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.started == false
            if (stopped) break
            Thread.sleep(400)
        }
        assertTrue("Real rTorrent did not return to stopped state", stopped)
    }
    @Test fun multifileStoppedAddPreservesItsNamedRootUnderChosenParent() = runBlocking {
        val rtorrent = temporaryRemote()
        val target = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(target.startsWith("/") && target.contains("chunks-data")) {
            "No disposable data root was configured"
        }
        val multiBytes = (
            "d4:infod5:filesld6:lengthi5e4:pathl8:clip.txteee" +
            "4:name13:Public Bundle12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)
        val hash = TorrentMetainfoReader.preview(multiBytes).infoHashV1
        assertTrue(rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })
        val outcome = rtorrent.loadStoppedTorrentBytes(multiBytes, target)
        assertTrue("Multi-file Add returned uncertain/error state: $outcome",
            outcome is RemoteAddOutcome.VerifiedStopped ||
                outcome is RemoteAddOutcome.PendingVerification)
        val expectedRoot = target + "/Public Bundle"
        var observed: RemoteTransfer? = null
        for (index in 1..15) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (observed != null) break
            Thread.sleep(400)
        }
        val added = requireNotNull(observed) { "Multi-file torrent hash not visible on real engine" }
        assertFalse("Multi-file Add must remain stopped", added.started)
        assertEquals("The user-selected folder is a PARENT, not a replacement torrent root",
            expectedRoot, added.directory)
        if (outcome is RemoteAddOutcome.VerifiedStopped) {
            assertEquals(expectedRoot, outcome.destination)
        }
    }

    @Test fun exactHashSessionEraseWorksOnDisposableRealEngineWithoutDeletingPayload() = runBlocking {
        val rtorrent = temporaryRemote()
        val target = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(target.startsWith("/") && target.contains("chunks-data")) {
            "Removal test must use the disposable local data root"
        }
        val removeBytes = (
            "d4:infod6:lengthi5e4:name9:erase.txt12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)
        val hash = TorrentMetainfoReader.preview(removeBytes).infoHashV1
        val payload = Paths.get(target, "erase.txt")
        val original = "hello".toByteArray(Charsets.UTF_8)
        Files.createDirectories(payload.parent)
        Files.write(payload, original)
        assertTrue("Fresh disposable engine should not contain this exact fixture hash",
            rtorrent.listTransfers("main").none { it.hash.equals(hash, true) })

        val loaded = rtorrent.loadStoppedTorrentBytes(removeBytes, target)
        assertTrue("Real Add must not have an ambiguous outcome before destructive testing: $loaded",
            loaded is RemoteAddOutcome.VerifiedStopped ||
                loaded is RemoteAddOutcome.PendingVerification)
        var observed: RemoteTransfer? = null
        for (attempt in 1..15) {
            observed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
            if (observed != null) break
            Thread.sleep(400)
        }
        val present = requireNotNull(observed) { "Disposable test torrent failed to appear" }
        assertEquals("erase.txt", present.name)
        assertEquals(target, present.directory)
        assertFalse("Removal acceptance requires a stopped transfer", present.started)

        // Prove optimistic name/path preflight refuses stale UI information.
        try {
            rtorrent.eraseFromSession(hash, "stale-display-name", present.directory)
            fail("A stale name must refuse erase without changing session identity")
        } catch (_: IllegalArgumentException) { }
        assertTrue(rtorrent.listTransfers("main").any { it.hash.equals(hash, true) })

        val result = rtorrent.eraseFromSession(hash, present.name, present.directory)
        assertTrue("Real erase must not return ambiguous result: $result",
            result is SessionEraseOutcome.VerifiedRemoved ||
                result is SessionEraseOutcome.PendingRemoval)

        var stillListed = true
        for (attempt in 1..15) {
            stillListed = rtorrent.listTransfers("main").any { it.hash.equals(hash, true) }
            if (!stillListed) break
            Thread.sleep(400)
        }
        assertFalse("d.erase must remove this hash from the real main view", stillListed)
        assertEquals("After verified disappearance, duplicate erase must issue no write",
            SessionEraseOutcome.AlreadyAbsent(hash),
            rtorrent.eraseFromSession(hash, present.name, present.directory))
        assertArrayEquals("Session removal must not delete or change the payload bytes",
            original, Files.readAllBytes(payload))
    }

    @Test fun exactFilePriorityChangeOnRealDisposableEngine() = runBlocking {
        val remote = temporaryRemote()
        val destination = System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty()
        require(destination.startsWith("/") && destination.contains("chunks-data"))
        val metainfo = (
            "d4:infod5:filesld6:lengthi5e4:pathl10:sample.oggeee" +
            "4:name15:Priority Bundle12:piece lengthi16384e6:pieces20:" +
            "01234567890123456789ee"
        ).toByteArray(Charsets.UTF_8)
        val hash = TorrentMetainfoReader.preview(metainfo).infoHashV1
        assertTrue(remote.listTransfers("main").none { it.hash.equals(hash, true) })
        val added = remote.loadStoppedTorrentBytes(metainfo, destination)
        assertTrue("Real Add result uncertain: $added",
            added is RemoteAddOutcome.VerifiedStopped ||
                added is RemoteAddOutcome.PendingVerification)

        var files: List<RemoteFile> = emptyList()
        for (attempt in 1..15) {
            files = remote.listFiles(hash)
            if (files.isNotEmpty()) break
            Thread.sleep(400)
        }
        assertEquals("Disposable metainfo must expose exactly one stable file", 1, files.size)
        val first = files.single()
        assertEquals("sample.ogg", first.path)
        assertTrue("Expected a known valid initial priority", first.priority in 0..2)
        val requested = if (first.priority == 2) 0 else 2
        val outcome = remote.setFilePriority(
            hash, 0, first.path, first.sizeBytes, first.priority, requested)
        val observedAfterWrite = remote.listFiles(hash).single().priority
        assertTrue("Real priority update returned $outcome; observed priority " +
            observedAfterWrite + " (previous " + first.priority + ", requested " + requested + ")",
            outcome is FilePriorityOutcome.Verified ||
                outcome is FilePriorityOutcome.PendingVerification)

        var observed = false
        for (attempt in 1..15) {
            observed = remote.listFiles(hash)[0].priority == requested
            if (observed) break
            Thread.sleep(400)
        }
        assertTrue("Real rTorrent did not apply file priority", observed)
        assertEquals("Already requested priority must issue no further writes",
            FilePriorityOutcome.Unchanged(hash, 0, requested),
            remote.setFilePriority(hash, 0, first.path, first.sizeBytes,
                requested, requested))
    }

}
