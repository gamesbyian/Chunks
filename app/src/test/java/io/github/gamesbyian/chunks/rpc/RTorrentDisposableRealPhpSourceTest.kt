package io.github.gamesbyian.chunks.rpc

import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Pinned upstream ruTorrent Source and a real, disposable rTorrent session. */
class RTorrentDisposableRealPhpSourceTest {
    @Test fun readsTheUppercaseSessionFileWithoutATiedTorrent() = runBlocking {
        val rpc = System.getenv("CHUNKS_DISPOSABLE_PHP_SOURCE_RPC").orEmpty()
        assumeTrue("Requires the authenticated disposable PHP Source rig", rpc.isNotBlank())
        require(rpc == "http://127.0.0.1:18080/rutorrent1/plugins/httprpc/action.php")
        val session = Paths.get(requireNotNull(System.getenv("CHUNKS_DISPOSABLE_RT_SESSION")))
        val destination = Paths.get(requireNotNull(System.getenv("CHUNKS_DISPOSABLE_TARGET_DIR")))
        require(session.isAbsolute && session.fileName.toString() == "chunks-session")
        require(destination.isAbsolute && destination.fileName.toString() == "chunks-data" &&
            session.parent == destination.parent)
        val source = RTorrentRemote(RemoteCredentials(rpc,
            requireNotNull(System.getenv("CHUNKS_DISPOSABLE_AUTH_USER")),
            requireNotNull(System.getenv("CHUNKS_DISPOSABLE_AUTH_PASS"))))
        val daemon = RTorrentRemote(RemoteCredentials("http://127.0.0.1:18080/RPC2", "", ""))

        val name = "chunks-php-source-from-session.bin"
        val tracker = "http://127.0.0.1:9/announce"
        val info = ("d6:lengthi5e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:").toByteArray() +
            MessageDigest.getInstance("SHA-1").digest("hello".toByteArray()) + "e".toByteArray()
        val bytes = "d8:announce${tracker.length}:$tracker".toByteArray() +
            "4:info".toByteArray() + info + "e".toByteArray()
        val original = TorrentMetainfoReader.preview(bytes)
        val hash = original.infoHashV1
        val uppercaseHash = hash.uppercase(Locale.ROOT)
        assertNotEquals(hash, uppercaseHash)
        assertNull(daemon.lookupName(hash))
        assertTrue(source.version().isNotBlank())
        assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED, source.readAuthenticationEvidence())

        try {
            assertEquals(RemoteAddOutcome.VerifiedStopped(hash, destination.toString()),
                daemon.loadStoppedTorrentBytes(bytes, destination.toString()))
            assertEquals("", daemon.call("d.tied_to_file", listOf(hash)))
            daemon.call("session.save")
            assertTrue(Files.isRegularFile(session.resolve("$uppercaseHash.torrent")))
            assertFalse(Files.exists(session.resolve("$hash.torrent")))

            val recovered = requireNotNull(source.originalMetainfoForSnapshot(hash)) {
                "The authenticated upstream Source plugin must return the saved definition"
            }
            assertEquals(original, TorrentMetainfoReader.preview(recovered))
            assertTrue(recovered.toString(Charsets.ISO_8859_1).contains(tracker))
            assertFalse(daemon.listTransfers("main").single { it.hash.equals(hash, true) }.started)
            println("CHUNKS_RUTORRENT_SOURCE: authenticated real PHP Source read an uppercase session filename with no tied file")
        } finally {
            if (daemon.lookupName(hash) != null)
                assertEquals(SessionEraseOutcome.VerifiedRemoved(hash),
                    daemon.eraseFromSession(hash, name, destination.toString()))
        }
    }
}
