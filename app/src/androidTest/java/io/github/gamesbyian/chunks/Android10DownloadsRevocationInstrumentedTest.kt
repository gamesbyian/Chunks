package io.github.gamesbyian.chunks

import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Opt-in API29 three-process capability witness. The harness, NEVER the app,
 * revokes and restores Android's storage grant between instrumented phases.
 *
 * Only a uniquely generated disposable-emulator folder is written; no
 * production cleanup, no user downloads and no broad all-files permission.
 * API35 scoped-storage, SD card and physical/OEM evidence remain separate.
 */
@RunWith(AndroidJUnit4::class)
class Android10DownloadsRevocationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val work get() = File(context.filesDir, "chunks-grant-revocation")
    private val receipt get() = File(work, "receipt.properties")
    private val publicRoot get() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        LocalDownloadPlaces.PUBLIC_FOLDER_NAME
    )

    private fun requirePhase(phase: String) {
        assumeTrue("Only tools/android10_downloads_revocation.sh may run this test",
            args.getString("chunksGrantPhase") == phase && Build.VERSION.SDK_INT == 29)
        assertTrue("This destructive permission lifecycle test is emulator-only",
            Build.HARDWARE in setOf("ranchu", "goldfish") ||
                Build.FINGERPRINT.startsWith("generic"))
    }

    private fun hasStorageGrants() = LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun digest(path: File): String = MessageDigest.getInstance("SHA-256")
        .digest(path.readBytes()).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun marker(): Properties = Properties().apply {
        assertTrue("Phase A durable receipt missing", receipt.isFile)
        receipt.inputStream().use { load(it) }
    }

    private fun await(what: String, seconds: Long = 60, condition: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= until) fail("Timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    @Test fun phaseA_completeNativeSeedInSharedDownloadsThenStop() {
        requirePhase("a")
        assertNull("Phase A must be an independent process", LocalEngineHolder.current())
        assertTrue("A stale prior fixture must never be adopted", work.mkdir())
        if (!hasStorageGrants()) LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        assertTrue("Android 10 storage grant absent", hasStorageGrants())
        val choice = LocalDownloadPlaces.choices(context).single {
            it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS
        }
        assertNull("Public root unavailable after grant: ${choice.failure}", choice.failure)
        val root = requireNotNull(LocalDownloadPlaces.materialize(choice.place))
        val folder = requireNotNull(LocalOwnedAddFolder.prepare(root,
            "grant-revocation-" + System.nanoTime().toString(16)))
        val name = "owned-payload.bin"
        val original = File(work, name).apply {
            writeBytes(ByteArray(262_144).also { Random(10569).nextBytes(it) })
        }
        val torrent = TorrentBuilder().path(original).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val target = File(folder, name).apply { writeBytes(original.readBytes()) }
        val engine = LocalEngineHolder.get(context)
        assertEquals("Other phone sessions must not contaminate this capability test",
            emptyList<String>(), engine.completeIdentityInventory())
        val added = engine.addStopped(torrent, folder)
        assertTrue("Native Add must be real and stopped: $added", added is LocalAddResult.AddedStopped)
        val hash = (added as LocalAddResult.AddedStopped).status.infoHash
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(hash).outcome)
        await("real libtorrent piece verification") {
            engine.status(hash)?.state == LocalTransferState.SEEDING
        }
        assertEquals(digest(original), digest(target))
        assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(hash).outcome)
        assertTrue("Stop was not read back", requireNotNull(engine.status(hash)).stopped)
        assertTrue("Synthetic fixture receipt directory missing", work.isDirectory)
        Properties().apply {
            setProperty("hash", hash)
            setProperty("name", name)
            setProperty("folder", folder.absolutePath)
            setProperty("sha256", digest(target))
        }.also { data ->
            FileOutputStream(receipt).use { output ->
                data.store(output, "Disposable API29 permission-revocation fixture only")
                output.fd.sync()
            }
        }
        android.util.Log.i("ChunksGrant", "CHUNKS_GRANT phase=a persisted=true stopped=true " +
            "bytes=262144 hash=$hash sha256=${digest(target)}")
        // Do not engine.close() or remove: harness forces a process boundary.
    }

    @Test fun phaseB_storageRevokedRefusesStartWithoutResumingJob() {
        requirePhase("b")
        assertNull("Phase B must have a new process", LocalEngineHolder.current())
        assertFalse("Harness did not revoke Android 10 storage permissions", hasStorageGrants())
        val data = marker()
        val hash = requireNotNull(data.getProperty("hash"))
        val folder = requireNotNull(data.getProperty("folder"))
        assertTrue("Only a dedicated test folder may be referenced",
            File(folder).name.startsWith("grant-revocation-"))
        val choice = LocalDownloadPlaces.choices(context).single {
            it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS
        }
        assertEquals(LocalPublicDownloadsAccess.NEEDS_PERMISSION, choice.failure)
        val resolution = LocalDownloadPlacePolicy.resolve(LocalPlaceKind.PUBLIC_DOWNLOADS,
            LocalDownloadPlaces.choices(context))
        assertEquals("A revoked grant cannot remain the default for new Adds",
            LocalPlaceKind.APP_STORAGE, resolution.selected?.kind)
        val restored = requireNotNull(LocalEngineHolder.restoreIfSaved(context))
        assertTrue("Native metadata restoration failed: ${restored.restoreProblems}",
            restored.restoreProblems.isEmpty())
        assertEquals(listOf(hash), restored.completeIdentityInventory())
        assertTrue("Revoked-grant restored job auto-started",
            requireNotNull(restored.status(hash)).stopped)
        val outcome = restored.resume(hash).outcome
        assertEquals("Native Start must refuse before storage permission is restored",
            LocalCommandOutcome.REFUSED, outcome)
        assertTrue("Refused Start must remain stopped", requireNotNull(restored.status(hash)).stopped)
        android.util.Log.i("ChunksGrant",
            "CHUNKS_GRANT phase=b refused=true stopped=true permission_revoked=true")
    }

    @Test fun phaseC_grantRestoredRechecksSeedsAndRemovesWithoutDeletingFiles() {
        requirePhase("c")
        assertNull("Phase C must have a new process", LocalEngineHolder.current())
        assertTrue("Android 10 storage permissions were not regranted", hasStorageGrants())
        val data = marker()
        val hash = requireNotNull(data.getProperty("hash"))
        val folder = File(requireNotNull(data.getProperty("folder")))
        assertTrue(folder.name.startsWith("grant-revocation-"))
        val target = File(folder, requireNotNull(data.getProperty("name")))
        val expected = requireNotNull(data.getProperty("sha256"))
        assertEquals(expected, digest(target))
        val restored = requireNotNull(LocalEngineHolder.restoreIfSaved(context))
        assertTrue("Cold restore errors: ${restored.restoreProblems}",
            restored.restoreProblems.isEmpty())
        assertEquals(listOf(hash), restored.completeIdentityInventory())
        assertTrue("Grant restoration may not auto-start", requireNotNull(restored.status(hash)).stopped)
        assertEquals("Manual Start after grant must be accepted",
            LocalCommandOutcome.VERIFIED, restored.resume(hash).outcome)
        await("rechecked verified seeding after grant restoration") {
            restored.status(hash)?.state == LocalTransferState.SEEDING
        }
        assertEquals(expected, digest(target))
        assertEquals(LocalCommandOutcome.VERIFIED, restored.pause(hash).outcome)
        assertTrue("Session-only Remove must be confirmed", restored.removeKeepingData(hash))
        assertNull("Removed identity remains in local engine", restored.status(hash))
        assertEquals("Remove must preserve original public bytes", expected, digest(target))
        android.util.Log.i("ChunksGrant", "CHUNKS_GRANT phase=c restored=true " +
            "seeded=true removed_keep_files=true sha256=$expected")
        // Leave the owner-created fixture present for forensic inspection;
        // the disposable emulator is discarded by CI.
    }
}
