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

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
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
