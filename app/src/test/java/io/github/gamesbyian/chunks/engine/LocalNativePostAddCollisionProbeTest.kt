package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * G1 independent diagnostic: files introduced AFTER Add but BEFORE Start can
 * collide with the torrent's still-empty destination. All bytes and peers are
 * synthetic, loopback-only, in a JUnit TemporaryFolder.
 *
 * This is NOT protection acceptance: the raw/native addStopped path is used
 * deliberately to characterize behavior underneath reviewed production Add.
 * A positive replacement witness documents a dangerous lower-level default;
 * it does not authorize any user-facing overwrite.
 */
class LocalNativePostAddCollisionProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()

    @Before fun loadNative() = DesktopLibtorrent.ensureLoaded()

    @After fun closeEngines() {
        engines.forEach { runCatching { it.close() } }
    }

    private fun engine(): LocalTorrentEngine = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true,
        enableDht = false,
        enableLsd = false,
        enablePortMapping = false
    )).also { it.start(); engines += it }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun await(label: String, timeoutMs: Long = 90_000L, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() >= end) fail("Timed out waiting for $label")
            Thread.sleep(100)
        }
    }

    @Test fun newForeignFileAfterStoppedAddIsAnUnprotectedStartTimeCollision() {
        // Construct a real, valid v1 torrent whose payload is known exactly.
        val sourceRoot = File(temp.newFolder("source"), "Timing fixture").apply {
            assertTrue(mkdirs())
        }
        val expected = ByteArray(768 * 1024).also { Random(568L).nextBytes(it) }
        File(sourceRoot, "payload.bin").writeBytes(expected)
        val metainfo = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .generate().entry().bencode()

        val seed = engine()
        val seedAdded = seed.addStopped(metainfo, sourceRoot.parentFile)
        assertTrue(seedAdded.toString(), seedAdded is LocalAddResult.AddedStopped)
        val infoHash = (seedAdded as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(seed.resume(infoHash).outcome == LocalCommandOutcome.VERIFIED)
        await("native seeder") {
            seed.status(infoHash)?.state == LocalTransferState.SEEDING
        }

        // The exact path is EMPTY when the job is registered stopped. A
        // source-safe Add preflight would observe no collision here.
        val destination = temp.newFolder("empty-at-add")
        val foreign = File(destination, "Timing fixture/payload.bin")
        assertFalse(foreign.exists())
        val receiver = engine()
        val add = receiver.addStopped(metainfo, destination,
            listOf(InetSocketAddress("127.0.0.1", seed.listenPort())))
        assertTrue(add.toString(), add is LocalAddResult.AddedStopped)
        assertTrue((add as LocalAddResult.AddedStopped).status.stopped)
        assertFalse("Add stopped must not have materialized the target", foreign.exists())

        // Another process/user can create the same filename while Chunks
        // waits for explicit Start. Equal size prevents trivially detecting
        // it by file size alone; contents have a distinct independent hash.
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val unrelated = ByteArray(expected.size).also { Random(9876L).nextBytes(it) }
        foreign.writeBytes(unrelated)
        val before = sha(foreign.readBytes())
        val sourceSha = sha(expected)
        assertFalse(before == sourceSha)
        assertArrayEquals("No mutation before Start", unrelated, foreign.readBytes())

        val start = receiver.resume(infoHash)
        println("CHUNKS_POST_ADD_COLLISION native_start_outcome=${start.outcome}")
        // The current raw Add path demonstrates the original unsafe native
        // behavior. A future checked-Start implementation MUST be allowed to
        // refuse without making this characterization test obsolete.
        assertTrue("Start must be confirmed or explicitly uncertain: $start",
            start.outcome in setOf(LocalCommandOutcome.VERIFIED, LocalCommandOutcome.UNCERTAIN))
        if (start.outcome == LocalCommandOutcome.VERIFIED) {
            await("native peer transfer completion") {
                receiver.status(infoHash)?.progressPpm == 1_000_000
            }
            assertTrue("Flush before independent backing-file readback",
                receiver.flushCompletedForLibraryRead(infoHash, 30_000L))
        } else {
            assertArrayEquals("Refused Start must preserve the foreign file",
                unrelated, foreign.readBytes())
        }

        val afterBytes = foreign.readBytes()
        val after = sha(afterBytes)
        val replaced = !afterBytes.contentEquals(unrelated)
        val expectedPresent = afterBytes.contentEquals(expected)
        println("CHUNKS_POST_ADD_COLLISION before_sha256=$before expected_sha256=$sourceSha after_sha256=$after")
        println("CHUNKS_POST_ADD_COLLISION foreign_file_replaced=$replaced")
        println("CHUNKS_POST_ADD_COLLISION expected_torrent_data_present=$expectedPresent")
        // Characterization only. A future native implementation that
        // preserves the foreign bytes is safer and should not fail this test.
        assertTrue("Only synthetic foreign or exact fixture bytes may remain",
            afterBytes.contentEquals(unrelated) || expectedPresent)
        assertTrue("The untouched source seeding file must remain intact",
            File(sourceRoot, "payload.bin").readBytes().contentEquals(expected))
    }
    @Test fun deferredMagnetAutoStartCanCollideWithFileIntroducedBeforeMetadata() {
        val sourceRoot = File(temp.newFolder("magnet-source"), "Magnet timing fixture").apply {
            assertTrue(mkdirs())
        }
        val expected = ByteArray(768 * 1024).also { Random(569L).nextBytes(it) }
        File(sourceRoot, "payload.bin").writeBytes(expected)
        val metainfo = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .generate().entry().bencode()
        val seed = engine()
        val seedAdded = seed.addStopped(metainfo, sourceRoot.parentFile)
        assertTrue(seedAdded.toString(), seedAdded is LocalAddResult.AddedStopped)
        val hash = (seedAdded as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(seed.resume(hash).outcome == LocalCommandOutcome.VERIFIED)
        await("magnet native seeder") {
            seed.status(hash)?.state == LocalTransferState.SEEDING
        }

        val destination = temp.newFolder("magnet-empty-at-add")
        val receiver = engine()
        // No initial peer or DHT: metadata cannot arrive until the test
        // deliberately connects the seeder, *after* creating a foreign file.
        val magnet = "magnet:?xt=urn:btih:$hash"
        val added = receiver.addMagnet(magnet, destination, autoStartAfterMetadata = true)
        assertTrue(added.toString(), added is LocalAddResult.FetchingMetadata)
        assertFalse(requireNotNull(receiver.status(hash)).metadataKnown)
        val foreign = File(destination, "Magnet timing fixture/payload.bin")
        assertFalse(foreign.exists())
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val unrelated = ByteArray(expected.size).also { Random(9877L).nextBytes(it) }
        foreign.writeBytes(unrelated)
        val before = sha(foreign.readBytes())
        assertFalse(before == sha(expected))

        assertTrue(receiver.connectPeer(hash,
            InetSocketAddress("127.0.0.1", seed.listenPort())))
        await("magnet metadata either safely blocked or auto-started") {
            val status = receiver.status(hash)
            status != null && status.metadataKnown &&
                ((status.stopped && status.uploadOnly) ||
                    (!status.stopped && !status.uploadOnly))
        }
        val outcome = requireNotNull(receiver.status(hash))
        println("CHUNKS_MAGNET_COLLISION observed_metadata=${outcome.metadataKnown} " +
            "stopped=${outcome.stopped} upload_only=${outcome.uploadOnly}")
        if (outcome.stopped && outcome.uploadOnly) {
            // A fail-closed implementation leaves the supplied foreign
            // bytes alone. Do not require unsafe auto-Start to pass a test.
            assertArrayEquals("Blocked metadata must preserve unrelated bytes",
                unrelated, foreign.readBytes())
        } else {
            await("magnet native peer transfer completion") {
                receiver.status(hash)?.progressPpm == 1_000_000
            }
            assertTrue("Flush before independent backing-file readback",
                receiver.flushCompletedForLibraryRead(hash, 30_000L))
        }

        val actual = foreign.readBytes()
        val replaced = !actual.contentEquals(unrelated)
        val expectedPresent = actual.contentEquals(expected)
        println("CHUNKS_MAGNET_COLLISION before_sha256=$before expected_sha256=${sha(expected)} after_sha256=${sha(actual)}")
        println("CHUNKS_MAGNET_COLLISION foreign_file_replaced=$replaced")
        println("CHUNKS_MAGNET_COLLISION expected_torrent_data_present=$expectedPresent")
        // Diagnostic only. A future safe engine may preserve foreign bytes.
        assertTrue("Only original foreign or synthetic torrent bytes may remain",
            actual.contentEquals(unrelated) || expectedPresent)
        assertArrayEquals(expected, File(sourceRoot, "payload.bin").readBytes())
    }

    @Test fun stoppedPartialJobThenForeignSkippedFileAndRestartCharacterizesNativeWrites() {
        // Independently exercise the post-Stop/re-Start window (P0 #568).
        // The receiver first downloads ONLY "initial.bin"; the second file
        // remains intentionally skipped, without any native payload authority.
        val sourceRoot = File(temp.newFolder("post-stop-source"),
            "Post-stop timing fixture").apply { assertTrue(mkdirs()) }
        val first = ByteArray(256 * 1024).also { Random(5681L).nextBytes(it) }
        val delayed = ByteArray(768 * 1024).also { Random(5682L).nextBytes(it) }
        File(sourceRoot, "initial.bin").writeBytes(first)
        File(sourceRoot, "delayed.bin").writeBytes(delayed)
        val metainfo = TorrentBuilder().path(sourceRoot).pieceSize(32 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()

        val seed = engine()
        val source = seed.addStopped(metainfo, sourceRoot.parentFile)
        assertTrue(source is LocalAddResult.AddedStopped)
        val hash = (source as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(seed.resume(hash).outcome == LocalCommandOutcome.VERIFIED)
        await("post-Stop native seed") {
            seed.status(hash)?.state == LocalTransferState.SEEDING
        }

        val destination = temp.newFolder("post-stop-destination")
        val receiver = engine()
        val registered = receiver.addStopped(metainfo, destination,
            listOf(InetSocketAddress("127.0.0.1", seed.listenPort())))
        assertTrue(registered is LocalAddResult.AddedStopped)
        val skippedIndex = receiver.files(hash).orEmpty()
            .single { it.path.endsWith("/delayed.bin") }.index
        assertTrue(receiver.setFileWanted(hash, skippedIndex, false) ==
            LocalCommandOutcome.VERIFIED)
        val firstStart = receiver.resume(hash)
        assertTrue(firstStart.outcome == LocalCommandOutcome.VERIFIED)
        await("selected first file finishes") {
            receiver.status(hash)?.let { it.progressPpm == 1_000_000 &&
                it.state in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING) } == true
        }
        assertTrue("Flush first file before independent inspection",
            receiver.flushCompletedForLibraryRead(hash, 30_000L))
        assertArrayEquals("Selected file must really arrive from the native peer",
            first, File(destination, "Post-stop timing fixture/initial.bin").readBytes())
        val stopped = receiver.pause(hash)
        assertTrue("Stop must be read back before external path substitution",
            stopped.outcome == LocalCommandOutcome.VERIFIED)
        assertTrue(requireNotNull(receiver.status(hash)).stopped)

        val foreign = File(destination, "Post-stop timing fixture/delayed.bin")
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val unrelated = ByteArray(delayed.size).also { Random(9878L).nextBytes(it) }
        foreign.writeBytes(unrelated)
        val beforeSha = sha(foreign.readBytes())
        val expectedSha = sha(delayed)
        assertFalse(beforeSha == expectedSha)

        // Toggling priorities is itself an independently observed mutation.
        // The fixture can record a write even before the second explicit Start.
        val wanted = receiver.setFileWanted(hash, skippedIndex, true)
        assertTrue("Priority transition must be observed or uncertain, never absent",
            wanted in setOf(LocalCommandOutcome.VERIFIED, LocalCommandOutcome.UNCERTAIN))
        val afterPrioritySha = sha(foreign.readBytes())
        val restart = receiver.resume(hash)
        assertTrue("A future safe native engine may refuse this Start",
            restart.outcome in setOf(LocalCommandOutcome.VERIFIED,
                LocalCommandOutcome.UNCERTAIN, LocalCommandOutcome.REFUSED))
        if (restart.outcome != LocalCommandOutcome.REFUSED) {
            receiver.connectPeer(hash, InetSocketAddress("127.0.0.1", seed.listenPort()))
            await("post-Stop restarted peer transfer") {
                receiver.status(hash)?.progressPpm == 1_000_000
            }
            assertTrue("Flush after unskipping before reading backing bytes",
                receiver.flushCompletedForLibraryRead(hash, 30_000L))
        }

        val actual = foreign.readBytes()
        println("CHUNKS_POST_STOP_COLLISION before_sha256=$beforeSha expected_sha256=$expectedSha " +
            "after_priority_sha256=$afterPrioritySha after_sha256=${sha(actual)}")
        println("CHUNKS_POST_STOP_COLLISION foreign_file_replaced=${!actual.contentEquals(unrelated)}")
        println("CHUNKS_POST_STOP_COLLISION expected_torrent_data_present=${actual.contentEquals(delayed)}")
        println("CHUNKS_POST_STOP_COLLISION first_start_outcome=${firstStart.outcome} " +
            "stop_outcome=${stopped.outcome} selection_outcome=$wanted restart_outcome=${restart.outcome}")
        // This diagnostic is expected to remain green even if future native
        // owned-descriptor protection safely refuses the replacement.
        assertTrue("Only the synthetic torrent bytes or exact planted foreign bytes may survive",
            actual.contentEquals(unrelated) || actual.contentEquals(delayed))
        assertArrayEquals("Original source payload must not be modified", delayed,
            File(sourceRoot, "delayed.bin").readBytes())
        assertArrayEquals(first, File(sourceRoot, "initial.bin").readBytes())
    }

}
