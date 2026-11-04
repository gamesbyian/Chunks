package io.github.gamesbyian.chunks.rpc

import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One genuine Add -> files/priorities -> Start/Stop -> Remove keep bytes ->
 * same-hash re-Add journey using the production RTorrentRemote adapter.
 *
 * Intentionally uses the EXISTING disposable real rTorrent process and test
 * directory. It never targets an owner's seedbox or claims that a peerless
 * fixture proves actual download ordering, Android UI or physical handset.
 */
class RTorrentDisposableFullJourneyTest {
    private fun rig(): Pair<RTorrentRemote, File> {
        val address = System.getenv("CHUNKS_DISPOSABLE_RT_URL").orEmpty()
        assumeTrue("Real integration only inside disposable rig", address.isNotBlank())
        val uri = URI(address)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" &&
            uri.port == 18080 && uri.path == "/RPC2" &&
            uri.userInfo == null && uri.rawQuery == null)
        val root = File(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR").orEmpty())
        require(root.isAbsolute && root.canonicalFile.isDirectory &&
            root.canonicalPath.endsWith("/chunks-data"))
        return RTorrentRemote(RemoteCredentials(address, "", "")) to root
    }

    private fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    private fun fixture(bundle: String, a: ByteArray, b: ByteArray): ByteArray {
        val first = "01-first.bin"
        val second = "02-second.bin"
        val prefix = (
            "d4:info" +
            "d5:filesl" +
            "d6:lengthi${a.size}e4:pathl${first.length}:$first" + "ee" +
            "d6:lengthi${b.size}e4:pathl${second.length}:$second" + "ee" +
            "e" +
            "4:name${bundle.length}:$bundle" +
            "12:piece lengthi16384e6:pieces20:"
        ).toByteArray(Charsets.ISO_8859_1)
        return prefix + sha1(a + b) + "ee".toByteArray(Charsets.ISO_8859_1)
    }

    private suspend fun requireTransfer(remote: RTorrentRemote, hash: String): RemoteTransfer {
        repeat(30) {
            remote.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.let { return it }
            Thread.sleep(200)
        }
        fail("Real rTorrent did not expose the exact fixture session")
        error("Unreachable")
    }

    private suspend fun requireAbsent(remote: RTorrentRemote, hash: String) {
        repeat(30) {
            if (remote.lookupName(hash) == null) return
            Thread.sleep(200)
        }
        fail("Real rTorrent still reports exact hash after session-only Remove")
    }

    private suspend fun requireState(remote: RTorrentRemote, hash: String, started: Boolean) {
        repeat(30) {
            if (remote.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }?.started == started)
                return
            Thread.sleep(200)
        }
        fail("Real rTorrent did not read back started=$started for exact hash")
    }

    private suspend fun requireFiles(remote: RTorrentRemote, hash: String): List<RemoteFile> {
        repeat(30) {
            val files = remote.listFiles(hash)
            if (files.size == 2) return files
            Thread.sleep(200)
        }
        fail("Real rTorrent never exposed both file identities")
        error("Unreachable")
    }

    @Test fun oneRealTransferSurvivesPriorityStopRemoveKeepAndSameHashReadd() = runBlocking {
        val (remote, root) = rig()
        val selectedParent = File(root, "journey-${System.nanoTime()}").apply {
            assertTrue(mkdirs())
        }
        val name = "Journey Bundle ${System.nanoTime()}"
        val firstBytes = "episode-one-daily-use".toByteArray(Charsets.UTF_8)
        val secondBytes = "episode-two-daily-use".toByteArray(Charsets.UTF_8)
        val torrent = fixture(name, firstBytes, secondBytes)
        val hash = TorrentMetainfoReader.preview(torrent).infoHashV1
        val payloadRoot = File(selectedParent, name).apply { assertTrue(mkdirs()) }
        val first = File(payloadRoot, "01-first.bin").apply { writeBytes(firstBytes) }
        val second = File(payloadRoot, "02-second.bin").apply { writeBytes(secondBytes) }
        val canary = File(selectedParent, "foreign-canary.txt").apply { writeText("leave me intact") }
        val canaryHash = sha1(canary.readBytes())
        assertNull("Fixture must start absent from real rTorrent", remote.lookupName(hash))

        // Stage 1: one explicit Add to a chosen parent, not a default location.
        val added = remote.loadStoppedTorrentBytes(torrent, selectedParent.absolutePath)
        assertTrue("Uncertain or rejected Add: $added",
            added is RemoteAddOutcome.VerifiedStopped ||
                added is RemoteAddOutcome.PendingVerification)
        val observed = requireTransfer(remote, hash)
        assertEquals(name, observed.name)
        assertEquals(payloadRoot.canonicalPath, File(observed.directory).canonicalPath)
        assertFalse(observed.started)
        assertEquals(RemoteAddOutcome.AlreadyPresent(hash),
            remote.loadStoppedTorrentBytes(torrent, selectedParent.absolutePath))

        // Stage 2: two independently addressed files with different priorities.
        val files = requireFiles(remote, hash)
        assertEquals(setOf("01-first.bin", "02-second.bin"), files.map { it.path }.toSet())
        val desired = listOf(2, 0)
        for (index in files.indices) {
            val before = files[index]
            val outcome = remote.setFilePriority(hash, index, before.path, before.sizeBytes,
                before.priority, desired[index])
            assertTrue("Unexpected priority result for file $index: $outcome",
                outcome is FilePriorityOutcome.Verified ||
                    outcome is FilePriorityOutcome.PendingVerification ||
                    outcome is FilePriorityOutcome.Unchanged)
        }
        repeat(30) {
            if (remote.listFiles(hash).map { it.priority } == desired) return@repeat
            Thread.sleep(200)
        }
        assertEquals("Real per-file priority readback must match selected file IDs",
            desired, remote.listFiles(hash).map { it.priority })

        // Stage 3: real engine Start then Stop and exact state readback.
        val start = remote.setStarted(hash, true)
        assertTrue("$start", start is LifecycleOutcome.Verified || start is LifecycleOutcome.Pending)
        requireState(remote, hash, true)
        val stop = remote.setStarted(hash, false)
        assertTrue("$stop", stop is LifecycleOutcome.Verified || stop is LifecycleOutcome.Pending)
        requireState(remote, hash, false)

        // Stage 4: Remove session only; the original payload and foreign file survive.
        val erased = remote.eraseFromSession(hash, name, observed.directory)
        assertTrue("$erased",
            erased is SessionEraseOutcome.VerifiedRemoved || erased is SessionEraseOutcome.PendingRemoval)
        requireAbsent(remote, hash)
        assertArrayEquals(firstBytes, first.readBytes())
        assertArrayEquals(secondBytes, second.readBytes())
        assertArrayEquals(canaryHash, sha1(canary.readBytes()))

        // Stage 5: a same-hash new session must register independently of the
        // prior absent receipt; no blind replay or stale exact-target mutation.
        val readd = remote.loadStoppedTorrentBytes(torrent, selectedParent.absolutePath)
        assertTrue("New session was blocked by a stale removal state: $readd",
            readd is RemoteAddOutcome.VerifiedStopped || readd is RemoteAddOutcome.PendingVerification)
        val secondSession = requireTransfer(remote, hash)
        assertEquals(name, secondSession.name)
        assertEquals(observed.directory, secondSession.directory)
        assertEquals(2, requireFiles(remote, hash).size)
        try {
            remote.eraseFromSession(hash, "stale-reviewed-display-name", observed.directory)
            fail("A stale reviewed name must not erase the re-added session")
        } catch (_: IllegalArgumentException) { }
        assertNotNull("Stale review must leave re-added exact session intact", remote.lookupName(hash))
        val finalErase = remote.eraseFromSession(hash, secondSession.name, secondSession.directory)
        assertTrue("$finalErase",
            finalErase is SessionEraseOutcome.VerifiedRemoved ||
                finalErase is SessionEraseOutcome.PendingRemoval)
        requireAbsent(remote, hash)
        assertArrayEquals(firstBytes, first.readBytes())
        assertArrayEquals(secondBytes, second.readBytes())
        assertArrayEquals(canaryHash, sha1(canary.readBytes()))

        println("CHUNKS_P0_FULL_JOURNEY: disposable real rTorrent, selected destination, " +
            "two file priorities, start/stop, keep-data Remove, same-hash re-add, " +
            "stale-target refusal, second Remove, independent payload/canary bytes")
    }
}
