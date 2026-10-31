package io.github.gamesbyian.chunks

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
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

    @Test fun checkedAddAcceptsExistingRegularPayloadInChosenSharedDownloads() {
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

        val sibling = File(destination, "not-in-torrent.txt")
        sibling.writeText("unrelated sibling")
        val native = engine()
        val result = native.addStoppedChecked(torrent, destination)
        assertTrue("A chosen destination permits existing regular torrent payloads: " + result,
            result is LocalAddResult.AddedStopped)
        assertTrue((result as LocalAddResult.AddedStopped).status.stopped)
        assertEquals(1, native.list().size)
        assertArrayEquals("Stopped Add must not download yet", original, foreign.readBytes())
        assertEquals("unrelated sibling", sibling.readText())
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

    @Test fun checkedFirstStartAcceptsLateRegularPayloadAndPreservesSibling() {
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

        val sibling = File(destination, "outside-the-torrent.txt")
        sibling.writeText("keep sibling")
        assertEquals(LocalCommandOutcome.VERIFIED,
            native.resume(transfer.infoHash).outcome)
        assertTrue("Start must be permitted in the chosen destination",
            requireNotNull(native.status(transfer.infoHash)).stopped == false)
        // No peer is connected. This validates permission to start, not a
        // completed download. The separate positive test supplies real peers.
        assertEquals("keep sibling", sibling.readText())
    }

    @Test fun magnetAutoStartsOverExistingRegularPayloadInPublicDownloadsAndRestores() {
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
        val sibling = File(destination, "never-a-torrent-path.txt")
        sibling.writeText("untouched sibling")
        waitFor("magnet metadata and authorized peer download") {
            val current = leecher.status(seed.infoHash)
            current?.metadataKnown == true && current.progressPpm == 1_000_000
        }
        assertTrue("Actual libtorrent payload must reach a cache-flushed state",
            leecher.flushCompletedForLibraryRead(seed.infoHash, timeoutMs = 30_000L))
        waitForExact(foreign, payload, "magnet writes selected path")
        assertEquals("untouched sibling", sibling.readText())
        assertTrue("Completed magnet must persist resume state", leecher.saveResumeData() >= 1)
        leecher.close()

        val restarted = engine(state)
        assertTrue("Completed magnet must restore without errors",
            restarted.restoreProblems.isEmpty())
        val restored = requireNotNull(restarted.status(seed.infoHash))
        assertTrue(restored.metadataKnown)
        assertTrue("Restore starts stopped", restored.stopped)
        assertEquals(destination.absolutePath, restored.savePath)
        assertEquals(LocalCommandOutcome.VERIFIED, restarted.resume(seed.infoHash).outcome)
        waitFor("restored public Downloads magnet to seed") {
            restarted.status(seed.infoHash)?.state == LocalTransferState.SEEDING
        }
        assertArrayEquals(payload, foreign.readBytes())
        assertEquals("untouched sibling", sibling.readText())
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
