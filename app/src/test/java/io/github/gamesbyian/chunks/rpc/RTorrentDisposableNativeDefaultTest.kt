package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Behavioral/data-integrity: authentic challenged rTorrent, real payload; never a protected host. */
class RTorrentDisposableNativeDefaultTest {
    @Test fun unknownNativeDefaultIsObservedAndStoppedStartedRecheckedRemovedWithoutLosingBytes() = runBlocking {
        val url = System.getenv("CHUNKS_DISPOSABLE_AUTH_URL").orEmpty()
        assumeTrue("Requires the authenticated disposable rig", url.isNotBlank())
        val uri = URI(url)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18080 && uri.path == "/AUTHRPC")
        val remote = RTorrentRemote(RemoteCredentials(url,
            System.getenv("CHUNKS_DISPOSABLE_AUTH_USER"), System.getenv("CHUNKS_DISPOSABLE_AUTH_PASS")))
        val parent = Paths.get(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR"))
        require(parent.isAbsolute && parent.fileName.toString() == "chunks-data")
        val name = "chunks-native-default-real.bin"
        val data = "Chunks authentic native-default payload\n".repeat(100).toByteArray()
        val pieces = MessageDigest.getInstance("SHA-1").digest(data)
        val bytes = ("d4:infod6:lengthi${data.size}e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:").toByteArray() + pieces + "ee".toByteArray()
        val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
        val payload = parent.resolve(name)
        check(!Files.exists(payload) && remote.lookupName(hash) == null)
        Files.write(payload, data)
        try {
            // Empty observed path models a denied/empty default getter. No setter is sent.
            val result = remote.loadStoppedTorrentBytes(bytes, "", useServerDefault = true)
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, parent.toString()), result)
            val row = remote.listTransfers().single { it.hash.equals(hash, true) }
            assertFalse(row.started)
            remote.setStarted(hash, true)
            var verified = 0L
            for (attempt in 0..30) {
                verified = remote.readTrafficSample(hash).verifiedBytes ?: 0
                if (verified == data.size.toLong()) break
                kotlinx.coroutines.delay(100)
            }
            assertEquals("Real engine must hash-verify actual payload", data.size.toLong(), verified)
            assertEquals(LifecycleOutcome.Verified(false), remote.setStarted(hash, false))
            assertEquals(SessionEraseOutcome.VerifiedRemoved(hash),
                remote.eraseFromSession(hash, row.name, row.directory))
            assertArrayEquals("Session-only Remove preserves exact data", data, Files.readAllBytes(payload))
            assertNull(remote.lookupName(hash))
        } finally {
            // No cleanup mutation on failure: preserve the disposable session for diagnosis.
            if (remote.lookupName(hash) == null) Files.deleteIfExists(payload)
        }
    }
}
