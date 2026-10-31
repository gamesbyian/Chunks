package io.github.gamesbyian.chunks

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalAdmissionProvenance
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Capability acceptance for the shared Downloads/Chunks Downloads Place:
 * the Android native engine writes a payload there over loopback, the bytes
 * read back exactly, a restarted engine resumes from those files without any
 * peer, and then seeds them to a fresh peer. Runs on Android 10 (after the
 * storage grant) and Android 11+. Emulator evidence only: not a
 * physical handset, SD card, reinstall/ownership or uninterrupted-seeding proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalPublicDownloadsPlaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private var publicTestFolder: File? = null
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun setUp() {
        work = File(context.filesDir, "public-place-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        work.deleteRecursively()
        // Only the uniquely named folder this test created; never the shared root.
        publicTestFolder?.deleteRecursively()
    }

    private fun engine(state: File? = null) = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true,
        stateDirectory = state)).also { it.start(); engines += it }

    @Test fun privateAdmissionReceiptPublicationReportsTheFailingFilesystemStage() {
        val root = File(work, "receipt-stages").apply { assertTrue(mkdir()) }.toPath()
        var stage = "inspect private root"
        try {
            assertTrue(java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            stage = "exclusive temporary creation"
            val temporary = java.nio.file.Files.createTempFile(root, "receipt.", ".tmp")
            stage = "temporary write and fsync"
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap("stage probe".toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            stage = "non-replacing hardlink publication"
            java.nio.file.Files.createLink(root.resolve("receipt"), temporary)
            stage = "directory fsync"
            java.nio.channels.FileChannel.open(root, java.nio.file.StandardOpenOption.READ)
                .use { it.force(true) }
            stage = "production admission persistence"
            assertTrue("Production admission persistence refused after filesystem stages passed",
                LocalAdmissionProvenance.persist(root.toFile(), "a".repeat(40),
                LocalAdmissionProvenance.Mode.CHECKED))
        } catch (failure: Exception) {
            throw AssertionError("Admission receipt stage failed: $stage", failure)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 90_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun waitForExact(path: File, expected: ByteArray, phase: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val actual = try { path.takeIf { it.isFile }?.readBytes() } catch (_: Exception) { null }
            if (actual != null && actual.contentEquals(expected)) return
            if (System.currentTimeMillis() > deadline)
                fail("$phase: exact bytes did not read back from $path (size=${actual?.size ?: -1})")
            Thread.sleep(150)
        }
    }

    /**
     * Android 10 needs legacy storage (from the manifest flag, fixed at install)
     * plus the user's storage permissions; the test grants exactly what the
     * "Allow storage access" button requests. Android 11+ needs nothing.
     */
    private fun allowSharedDownloads() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) return
        @Suppress("DEPRECATION")
        assertTrue("requestLegacyExternalStorage was not honored on Android 10",
            Environment.isExternalStorageLegacy())
        val before = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        if (before.failure != null) assertTrue("Unexpected Android 10 refusal: ${before.failure}",
            before.needsStoragePermission)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach {
            automation.grantRuntimePermission(context.packageName, it)
        }
    }

    @Test fun publicDownloadsIsTheVerifiedDefaultWhereAndroidAllowsIt() {
        allowSharedDownloads()
        val choices = LocalDownloadPlaces.choices(context)
        val public = choices.single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        assertNull("Public Downloads write probe failed: ${public.failure}", public.failure)
        assertEquals(LocalPlaceKind.PUBLIC_DOWNLOADS,
            LocalDownloadPlacePolicy.resolve(null, choices).selected?.kind)
        val app = choices.single { it.place.kind == LocalPlaceKind.APP_STORAGE }
        assertNull(app.failure)
    }

    @Test fun checkedAddAcceptsCleanPublicDownloadsOnActualAndroidRoot() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Public Downloads could not be verified"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "clean-checked-" + System.nanoTime()))
        publicTestFolder = destination

        val source = File(work, "checked-source/Clean checked fixture").apply { mkdirs() }
        File(source, "payload.bin").writeBytes(ByteArray(16_384) { (it % 251).toByte() })
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .generate().entry().bencode()

        // Uses the production checked Add against the actual Android path,
        // catching indirect /storage ancestor compatibility regressions.
        val native = engine()
        val result = native.addStoppedChecked(torrent, destination)
        assertTrue("Fresh Android public Downloads path was rejected: " + result,
            result is LocalAddResult.AddedStopped)
        val added = (result as LocalAddResult.AddedStopped).status
        assertTrue("Checked Add must be stopped", added.stopped)
        assertEquals(1, native.list().size)
        assertTrue("Stopped Add should not create any payload bytes",
            !File(destination, "Clean checked fixture/payload.bin").exists())
    }

    @Test fun checkedAddRejectsUnrelatedCollisionInSharedDownloadsWithoutMutation() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Public Downloads could not be verified"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "collision-" + System.nanoTime()))
        publicTestFolder = destination

        val source = File(work, "foreign-source/Collision fixture").apply { mkdirs() }
        val expected = ByteArray(96_000).also { Random(568).nextBytes(it) }
        File(source, "payload.bin").writeBytes(expected)
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .generate().entry().bencode()

        val foreign = File(destination, "Collision fixture/payload.bin")
        assertTrue(foreign.parentFile.mkdirs())
        val original = ByteArray(expected.size).also { Random(9876).nextBytes(it) }
        foreign.writeBytes(original)

        val native = engine()
        val result = native.addStoppedChecked(torrent, destination)
        assertTrue("Existing foreign file must be refused: " + result,
            result is LocalAddResult.Refused)
        assertTrue("Refusal must not register a native transfer", native.list().isEmpty())
        assertArrayEquals("Refusal must preserve exact public Downloads bytes",
            original, foreign.readBytes())
    }

    @Test fun twoCheckedHashesCannotReserveOneEmptyPublicDownloadsBackingPath() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Public Downloads could not be verified"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "checked-double-hash-" + System.nanoTime()))
        publicTestFolder = destination

        fun torrent(parent: String, seed: Long): ByteArray {
            val source = File(work, "$parent/Shared destination fixture")
                .apply { mkdirs() }
            val payload = ByteArray(96_000).also { Random(seed).nextBytes(it) }
            File(source, "payload.bin").writeBytes(payload)
            return TorrentBuilder().path(source).pieceSize(16 * 1024)
                .generate().entry().bencode()
        }
        val first = torrent("shared-source-one", 568L)
        val second = torrent("shared-source-two", 569L)
        val native = engine()
        val accepted = native.addStoppedChecked(first, destination)
        assertTrue("The first stopped checked job should be admitted: $accepted",
            accepted is LocalAddResult.AddedStopped)
        val competing = native.addStoppedChecked(second, destination)
        assertTrue("A still-empty directory must not authorize two native writers: $competing",
            competing is LocalAddResult.Refused)
        assertTrue((competing as LocalAddResult.Refused).reason.contains("reserves"))
        assertEquals(1, native.list().size)
        val path = File(destination, "Shared destination fixture/payload.bin")
        assertTrue("Neither stopped job may create public Downloads backing bytes",
            !path.exists())

        // The libtorrent Remove operation is asynchronous; a later same-path
        // checked Add must not claim the first handle's outstanding disk I/O.
        val hash = (accepted as LocalAddResult.AddedStopped).status.infoHash
        assertTrue(native.removeKeepingData(hash))
        assertTrue(native.addStoppedChecked(second, destination) is LocalAddResult.Refused)
        assertTrue("No payload created after admission refusal", !path.exists())
    }

    @Test fun checkedFirstStartRefusesLateForeignFileInSharedDownloads() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Public Downloads could not be verified"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "checked-start-race-" + System.nanoTime()))
        publicTestFolder = destination

        val source = File(work, "checked-race-source/Late collision fixture")
            .apply { mkdirs() }
        val payload = ByteArray(96_000).also { Random(568).nextBytes(it) }
        File(source, "payload.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .generate().entry().bencode()

        val native = engine()
        val added = native.addStoppedChecked(torrent, destination)
        assertTrue("The checked Add itself must succeed: $added",
            added is LocalAddResult.AddedStopped)
        val transfer = (added as LocalAddResult.AddedStopped).status
        val foreign = File(destination, "Late collision fixture/payload.bin")
        assertTrue(foreign.parentFile.isDirectory || foreign.parentFile.mkdirs())
        val original = ByteArray(payload.size).also { Random(9876).nextBytes(it) }
        foreign.writeBytes(original)

        assertEquals(LocalCommandOutcome.REFUSED,
            native.resume(transfer.infoHash).outcome)
        val stopped = requireNotNull(native.status(transfer.infoHash))
        assertTrue("First Start collision must remain stopped", stopped.stopped)
        assertTrue("User sees the refusal", stopped.error.orEmpty().contains("since Add"))
        assertArrayEquals("Android shared Downloads collision must retain exact foreign bytes",
            original, foreign.readBytes())
    }

    @Test fun restoredCheckedStartRefusesPlantedForeignPublicDownloadsBytes() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Actual Android Downloads root could not be verified"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "restored-checked-collision-" + System.nanoTime()))
        publicTestFolder = destination

        val source = File(work, "restore-checked-source/Restored checked Android fixture")
            .apply { mkdirs() }
        val payload = ByteArray(96_000).also { Random(5687).nextBytes(it) }
        File(source, "payload.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .generate().entry().bencode()
        val state = File(work, "restore-checked-state").apply { mkdirs() }
        val beforeDeath = engine(state)
        val added = beforeDeath.addStoppedChecked(torrent, destination)
        assertTrue("An initially clean public backing path must admit checked Add: $added",
            added is LocalAddResult.AddedStopped)
        val hash = (added as LocalAddResult.AddedStopped).status.infoHash
        assertTrue("A stopped native Add must persist its resume source",
            File(state, "$hash.resume").isFile)
        beforeDeath.close()

        // The fixture actor is outside the engine and owns only this uniquely
        // created test folder. No real user Downloads content may be touched.
        val foreignPath = File(destination, "Restored checked Android fixture/payload.bin")
        assertTrue(foreignPath.parentFile.mkdirs() || foreignPath.parentFile.isDirectory)
        val foreign = ByteArray(payload.size).also { Random(5688).nextBytes(it) }
        assertTrue(!payload.contentEquals(foreign))
        foreignPath.writeBytes(foreign)

        val restored = engine(state)
        assertTrue("Native resume should restore with no malformed records",
            restored.restoreProblems.isEmpty())
        val preStart = requireNotNull(restored.status(hash))
        assertTrue("Restored job must remain stopped", preStart.stopped)
        assertEquals("No payload should download while stopped", 0L, preStart.doneBytes)
        assertEquals("A foreign zero-progress file must refuse first restored native Start",
            LocalCommandOutcome.REFUSED, restored.resume(hash).outcome)
        val blocked = requireNotNull(restored.status(hash))
        assertTrue(blocked.stopped)
        assertTrue(blocked.error.orEmpty().contains("no verified native bytes"))
        assertArrayEquals("The actual Android Downloads foreign file must survive byte for byte",
            foreign, foreignPath.readBytes())
        // No direct filesystem adoption or implicit cleanup: the user/test
        // must remove only data it independently recognizes as its own.
    }

    @Test fun magnetMetadataCollisionPreservesPublicDownloadsAcrossStartAndRestart() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Public Downloads could not be verified: ${public.failure}"
        }
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "magnet-collision-" + System.nanoTime()))
        publicTestFolder = destination

        val source = File(work, "magnet-source/Magnet collision fixture").apply { mkdirs() }
        val payload = ByteArray(192_000).also { Random(568).nextBytes(it) }
        File(source, "payload.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val seeder = engine()
        val seed = (seeder.addStopped(torrent, requireNotNull(source.parentFile))
            as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor("magnet collision fixture seeder") {
            seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING
        }

        // The same path and size contain independently generated foreign bytes.
        // Only this test's unique folder is touched; no existing user file is used.
        val foreign = File(destination, "Magnet collision fixture/payload.bin")
        assertTrue(foreign.parentFile.mkdirs())
        val original = ByteArray(payload.size).also { Random(9876).nextBytes(it) }
        assertTrue("Negative control must differ from the torrent payload", !original.contentEquals(payload))
        foreign.writeBytes(original)

        val state = File(work, "magnet-collision-state").apply { mkdirs() }
        val leecher = engine(state)
        val added = leecher.addMagnet("magnet:?xt=urn:btih:" + seed.infoHash, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort())),
            autoStartAfterMetadata = true)
        assertTrue("The magnet must fetch real metadata, rather than be refused at intake: $added",
            added is LocalAddResult.FetchingMetadata)
        waitFor("metadata-ready collision to remain stopped") {
            leecher.status(seed.infoHash)?.let { it.metadataKnown && it.stopped } == true
        }
        val blocked = requireNotNull(leecher.status(seed.infoHash))
        assertTrue("Early Start consent must retain the native upload-only gate", blocked.uploadOnly)
        assertTrue("The actual colliding path must produce a visible refusal",
            blocked.error.orEmpty().contains("Existing files"))
        assertEquals("Metadata exchange must not download payload into foreign files",
            0L, blocked.payloadDownloadedBytes)
        assertArrayEquals("Metadata arrival must preserve exact public Downloads bytes",
            original, foreign.readBytes())

        assertEquals("Explicit Start must refuse the newly known payload collision",
            LocalCommandOutcome.REFUSED, leecher.resume(seed.infoHash).outcome)
        val afterStart = requireNotNull(leecher.status(seed.infoHash))
        assertTrue(afterStart.stopped)
        assertTrue(afterStart.uploadOnly)
        assertArrayEquals("Refused Start must preserve exact public Downloads bytes",
            original, foreign.readBytes())

        // Metadata's own resume save must persist the native gate, so restarting
        // cannot turn rejected early consent into permission to overwrite files.
        waitFor("collision-gated native resume data", timeoutMs = 15_000) {
            File(state, seed.infoHash + ".resume").let { it.isFile && it.length() > 0L }
        }
        leecher.close()
        assertArrayEquals(original, foreign.readBytes())
        val restarted = engine(state)
        assertTrue("Blocked magnet resume must restore cleanly", restarted.restoreProblems.isEmpty())
        val restored = requireNotNull(restarted.status(seed.infoHash))
        assertTrue(restored.metadataKnown)
        assertTrue("Blocked metadata-ready magnet must restore stopped", restored.stopped)
        assertTrue("Native upload-only protection must survive restart", restored.uploadOnly)
        assertEquals(destination.absolutePath, restored.savePath)
        assertEquals(LocalCommandOutcome.REFUSED, restarted.resume(seed.infoHash).outcome)
        val afterRestartStart = requireNotNull(restarted.status(seed.infoHash))
        assertTrue(afterRestartStart.stopped)
        assertTrue(afterRestartStart.uploadOnly)
        assertEquals(0L, afterRestartStart.payloadDownloadedBytes)
        restarted.close()
        assertArrayEquals("Refused Start after restart must preserve foreign public Downloads bytes",
            original, foreign.readBytes())
    }

    @Test fun nativeEngineWritesResumesAndSeedsFromPublicChunksDownloads() {
        allowSharedDownloads()
        val public = LocalDownloadPlaces.choices(context)
            .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
        val root = requireNotNull(LocalDownloadPlaces.materialize(public.place)) {
            "Downloads/Chunks Downloads could not be created and verified: ${public.failure}"
        }
        assertTrue(root.absolutePath.endsWith("/Download/" + LocalDownloadPlaces.PUBLIC_FOLDER_NAME))
        val destination = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "instrumented-" + System.nanoTime())) { "Unique test subfolder was refused" }
        publicTestFolder = destination

        val seedRoot = File(work, "seed/Public place fixture").apply { mkdirs() }
        val payload = ByteArray(700_000).also { Random(23).nextBytes(it) }
        File(seedRoot, "payload.bin").writeBytes(payload)
        File(seedRoot, "notes.txt").writeText("Generated on device for the Chunks Downloads Place test")
        val torrent = TorrentBuilder().path(seedRoot).pieceSize(32 * 1024).generate().entry().bencode()

        val seeder = engine()
        val seed = (seeder.addStopped(torrent, requireNotNull(seedRoot.parentFile))
            as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor("fixture seeder") { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }

        // 1. Download into the public folder and read the bytes back independently.
        val state = File(work, "leecher-state").apply { mkdirs() }
        val leecher = engine(state)
        val leech = (leecher.addStopped(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(leech.infoHash).outcome)
        waitFor("download into public Downloads") {
            val s = leecher.status(leech.infoHash)
            s?.error?.let { fail("Native engine reported an error in public Downloads: $it") }
            s?.state == LocalTransferState.SEEDING || s?.state == LocalTransferState.FINISHED
        }
        assertTrue(leecher.flushCompletedForLibraryRead(leech.infoHash, 20_000L))
        val written = File(destination, "Public place fixture/payload.bin")
        waitForExact(written, payload, "after download")
        assertTrue(leecher.saveResumeData(10_000) >= 1)
        leecher.close()
        seeder.close()

        // 2. Restart with no peer at all: completion can only come from the public files.
        val restarted = engine(state)
        assertTrue(restarted.restoreProblems.isEmpty())
        val restored = requireNotNull(restarted.status(leech.infoHash))
        assertTrue(restored.stopped)
        assertEquals(LocalCommandOutcome.VERIFIED, restarted.resume(leech.infoHash).outcome)
        waitFor("restarted engine to seed from public Downloads") {
            restarted.status(leech.infoHash)?.state == LocalTransferState.SEEDING
        }
        assertEquals(1_000_000, restarted.status(leech.infoHash)?.progressPpm)

        // 3. A fresh peer downloads the exact payload from the public-folder seeder.
        val freshRoot = File(work, "fresh").apply { mkdirs() }
        val fresh = engine()
        val again = (fresh.addStopped(torrent, freshRoot,
            listOf(InetSocketAddress("127.0.0.1", restarted.listenPort()))) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, fresh.resume(again.infoHash).outcome)
        waitFor("fresh peer to download from public Downloads") {
            val s = fresh.status(again.infoHash)?.state
            s == LocalTransferState.SEEDING || s == LocalTransferState.FINISHED
        }
        assertTrue(fresh.flushCompletedForLibraryRead(again.infoHash, 20_000L))
        waitForExact(File(freshRoot, "Public place fixture/payload.bin"), payload, "fresh peer")
        waitFor("public-folder seeder upload counter") {
            (restarted.status(leech.infoHash)?.payloadUploadedBytes ?: 0) >= payload.size
        }
        assertArrayEquals(payload, written.readBytes())
    }
}
