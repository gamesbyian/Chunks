package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Behavioral/data-integrity: real challenged rTorrent and owned disposable data; never a protected server. */
class RTorrentDisposableAddRecoveryTest {
    private fun client(): Pair<RTorrentRemote, Path> {
        val url = System.getenv("CHUNKS_DISPOSABLE_AUTH_URL").orEmpty()
        assumeTrue("Requires authenticated disposable rTorrent", url.isNotBlank())
        val uri = URI(url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 && uri.path == "/AUTHRPC")
        val parent = Paths.get(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR"))
        require(parent.isAbsolute && parent.fileName.toString() == "chunks-data")
        return RTorrentRemote(RemoteCredentials(url,
            System.getenv("CHUNKS_DISPOSABLE_AUTH_USER").orEmpty(),
            System.getenv("CHUNKS_DISPOSABLE_AUTH_PASS").orEmpty())) to parent
    }

    private fun metainfo(name: String, resume: Boolean = false): ByteArray {
        val data = "hello".toByteArray()
        val info = ("d6:lengthi5e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:").toByteArray() +
            MessageDigest.getInstance("SHA-1").digest(data) + "e".toByteArray()
        return "d4:info".toByteArray() + info +
            (if (resume) "17:libtorrent_resumed5:filesld8:priorityi0eeee" else "").toByteArray() + "e".toByteArray()
    }

    @Test fun foreignResumeCannotSilentlyDisableEveryFileInANewRemoteJob() = runBlocking {
        val (remote, parent) = client()
        val bytes = metainfo("chunks-clean-new-add.bin", resume = true)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        check(remote.lookupName(hash) == null)
        assertEquals(RemoteAddOutcome.VerifiedStopped(hash, parent.toString()),
            remote.loadStoppedTorrentBytes(bytes, parent.toString()))
        assertEquals("New Add follows ruTorrent: external session OFF priorities are not imported",
            listOf(1), remote.listFiles(hash).map { it.priority })
        assertEquals(SessionEraseOutcome.VerifiedRemoved(hash),
            remote.eraseFromSession(hash, "chunks-clean-new-add.bin", parent.toString()))
    }

    @Test fun unavailableStorageIsTruthfulAndTheSessionCanRecoverAfterExplicitRepair() = runBlocking {
        val (remote, parent) = client()
        val owned = Files.createTempDirectory(parent, "chunks-refused-storage-")
        val destination = owned.resolve("download-place")
        // A regular file where the directory must be is unusable even when
        // modern rTorrent runs as root inside its disposable container.
        // chmod alone only refuses the unprivileged legacy fixture.
        Files.write(destination, "Owned storage refusal fixture".toByteArray(),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val name = "chunks-storage-recovery.bin"
        val bytes = metainfo(name)
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        try {
            check(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
            assertTrue("Unusable storage cannot be called Started",
                remote.loadStartedTorrentBytes(bytes, destination.toString()) is RemoteAddAndStart.StartNotVerified)
            val stalled = RemoteStallInspector.inspect(remote, hash)
            assertTrue(stalled.started)
            assertFalse(stalled.active)
            assertFalse(stalled.open)
            assertEquals(true, stalled.hashCheckFailed)
            assertTrue(stalled.explanation().contains("permissions"))
            assertEquals(LifecycleOutcome.Verified(false), remote.setStarted(hash, false))
            remote.requestHashRecheck(hash, name, destination.toString())
            assertFalse("Recheck does not repair unusable storage", remote.readActivity(hash).active == true)

            // Test-rig storage repair, not a Chunks remote-shell capability.
            // Only a fresh, explicitly requested Start follows this independently observed repair.
            Files.delete(destination) // exact owned fixture, never user payload
            Files.createDirectory(destination)
            assertTrue(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS))
            remote.setStarted(hash, true)
            var active = false
            for (attempt in 0..30) {
                active = remote.readActivity(hash).active == true
                if (active) break
                delay(100)
            }
            assertTrue("Ordinary lifecycle must recover after actual storage is repaired", active)
            assertEquals(SessionEraseOutcome.VerifiedRemoved(hash),
                remote.eraseFromSession(hash, name, destination.toString()))
        } finally {
            if (remote.lookupName(hash) == null) {
                if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS))
                    Files.deleteIfExists(destination.resolve(name))
                Files.deleteIfExists(destination)
                Files.deleteIfExists(owned)
            }
        }
    }
}
