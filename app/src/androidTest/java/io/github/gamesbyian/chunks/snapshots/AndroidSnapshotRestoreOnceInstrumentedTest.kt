package io.github.gamesbyian.chunks.snapshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Real Android JNI Add and synchronous Android-private journal persistence.
 * No external peers, no user data, no network tracker, no Android GUI shortcut.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSnapshotRestoreOnceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun androidJournalBlocksDuplicateNativeSubmissionAndRestartsStopped() {
        val root = File(context.cacheDir, "snapshot-restore-jni-${System.nanoTime()}")
            .apply { mkdirs() }
        val originalDir = File(root, "source").apply { mkdirs() }
        val destination = File(root, "destination").apply { mkdirs() }
        val stateDir = File(root, "native-state").apply { mkdirs() }
        File(originalDir, "public-fixture.bin").writeBytes(ByteArray(65536) { (it and 255).toByte() })
        val torrent = TorrentBuilder().path(File(originalDir, "public-fixture.bin"))
            .pieceSize(16 * 1024).flags(TorrentBuilder.V1_ONLY)
            .generate().entry().bencode()
        val preview = TorrentMetainfoReader.preview(torrent)
        val source = SnapshotVerifiedTorrentDocumentV1.Prepared(
            preview.infoHashV1, preview.name, "chunks-public.torrent", torrent)
        val archiveId = SnapshotRestoreOnceV1.itemArchiveFingerprint(
            hash("test archive " + root.name), preview.infoHashV1)
        val targetId = hash("test phone " + root.name)
        val placeId = hash("test Place " + root.name)
        val store = AndroidSnapshotRestoreJournalStore(context)
        val engine = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0",
            stateDirectory = stateDir, allowMultipleConnectionsPerIp = true))
        try {
            engine.start()
            val journal = SnapshotRestoreJournalV1.open(store, archiveId,
                targetId, listOf(preview.infoHashV1))
            val target = SnapshotRestoreOnceV1.localEngine(engine)
            assertEquals(SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED,
                SnapshotRestoreOnceV1.execute(journal, source, destination,
                    placeId, emptyList(), true, target))
            assertTrue(requireNotNull(engine.status(preview.infoHashV1)).stopped)
            assertEquals(SnapshotRestoreJournalV1.Phase.VERIFIED,
                SnapshotRestoreJournalV1.open(store, archiveId, targetId,
                    listOf(preview.infoHashV1)).entries().single().phase)
            assertEquals(SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED,
                SnapshotRestoreOnceV1.execute(journal, source, destination,
                    placeId, emptyList(), true,
                    object : SnapshotRestoreOnceV1.LocalTarget {
                        override fun completeHashes() = emptyList<String>()
                        override fun observedStoppedPlace(infoHashV1: String) = destination
                        override fun submitStopped(
                            originalTorrent: ByteArray, chosenPlace: File
                        ): Boolean { throw AssertionError("duplicate native Add must not run") }
                    }))
            assertFalse(File(destination, "public-fixture.bin").exists())
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }
}
