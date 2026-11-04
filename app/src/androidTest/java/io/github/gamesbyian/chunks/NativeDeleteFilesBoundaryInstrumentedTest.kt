package io.github.gamesbyian.chunks

import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.AlertListener
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert
import org.libtorrent4j.alerts.TorrentDeletedAlert

/**
 * Explicit opt-in disposable native deletion research. Neither this probe nor
 * native acknowledgement enables production Remove transfer and data.
 *
 * The owner explicitly authorizes pre-existing matching payload files in a
 * separately reviewed destructive Remove. This probe verifies native JNI can
 * delete such a file in the default shared Downloads Place while preserving a
 * foreign canary. It does NOT prove complete torrent path admission, absence
 * of other active jobs sharing paths, or authorization for a protected server.
 */
@RunWith(AndroidJUnit4::class)
class NativeDeleteFilesBoundaryInstrumentedTest {
    @Test fun nativeDeleteAlertAndIndependentAbsenceDoNotTouchForeignBytes() {
        assumeTrue("Explicit disposable native deletion research only",
            InstrumentationRegistry.getArguments().getString("chunks_disposable_native_delete") == "authorized")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.filesDir, "native-delete-proof-" + System.nanoTime())
        assertTrue(work.mkdirs())
        var publicProbe: File? = null
        val source = File(work, "source/Disposable fixture").apply { assertTrue(mkdirs()) }
        val data = ByteArray(65_537) { ((it * 17) % 251).toByte() }
        File(source, "piece.bin").writeBytes(data)
        val metainfo = TorrentBuilder().path(source)
            .pieceSize(16 * 1024).generate().entry().bencode()
        val info = TorrentInfo(metainfo)
        // Hybrid fixtures may have different v1 and legacy/best hashes.
        // torrent_deleted_alert identifies the removed v1 torrent by v1 hash.
        assertEquals("Exact expected native path",
            "Disposable fixture/piece.bin", info.files().filePath(0))
        try {
            val privateDestination = File(work, "destination").apply { assertTrue(mkdirs()) }

            // Use the SAME real public Downloads root as the production engine,
            // with a unique child that cannot contain owner or foreign data.
            // Android Q needs the normal storage grants; Android 11+ uses
            // Chunks' existing scoped shared-Downloads access without all-files.
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                assertTrue("Android 10 legacy storage mode required",
                    Environment.isExternalStorageLegacy())
                LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS.forEach { permission ->
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .grantRuntimePermission(context.packageName, permission)
                }
            }
            val choice = LocalDownloadPlaces.choices(context)
                .single { it.place.kind == LocalPlaceKind.PUBLIC_DOWNLOADS }
            assertEquals("Public Downloads was not available for actual native Add",
                null, choice.failure)
            val root = requireNotNull(LocalDownloadPlaces.materialize(choice.place)) {
                "Could not materialize real public Downloads/Chunks Downloads"
            }
            val shared = requireNotNull(LocalOwnedAddFolder.prepare(
                root, "disposable-native-delete-" + System.nanoTime())) {
                "Could not create a unique disposable child of public Downloads"
            }
            publicProbe = shared

            // One API-level emulator session proves both private and default
            // public destinations, reusing the exact known-good native path.
            listOf(privateDestination, shared).forEachIndexed { index, destination ->
                val target = File(destination, "Disposable fixture/piece.bin")
                assertTrue(requireNotNull(target.parentFile).mkdirs())
                // Deliberately pre-existing regular payload, which explicit
                // reviewed Remove and data is authorized to delete.
                target.writeBytes(data)
                val foreignBytes = "unrelated source must survive".toByteArray()
                val foreign = File(destination, "foreign-control.txt").apply {
                    writeBytes(foreignBytes)
                }
                val done = CountDownLatch(1)
                val deleted = AtomicReference<Boolean?>(null)
                val manager = SessionManager(false)
                val listener = object : AlertListener {
                    override fun types(): IntArray = intArrayOf(
                        AlertType.TORRENT_DELETED.swig(),
                        AlertType.TORRENT_DELETE_FAILED.swig()
                    )

                    override fun alert(alert: Alert<*>) {
                        when (alert) {
                            is TorrentDeletedAlert -> {
                                if (alert.infoHashes.getV1().toHex().equals(
                                        info.infoHashes().getV1().toHex(), true)) {
                                    deleted.set(true)
                                    done.countDown()
                                }
                            }
                            is TorrentDeleteFailedAlert -> {
                                if (alert.infoHashes.getV1().toHex().equals(
                                        info.infoHashes().getV1().toHex(), true)) {
                                    deleted.set(false)
                                    done.countDown()
                                }
                            }
                        }
                    }
                }
                try {
                    manager.addListener(listener)
                    manager.start()
                    manager.download(info, destination)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    var handle = manager.find(info.infoHash())
                    while (handle == null && System.nanoTime() < deadline) {
                        Thread.sleep(100)
                        handle = manager.find(info.infoHash())
                    }
                    assertNotNull("Actual libtorrent handle never appeared", handle)
                    assertArrayEquals("Existing user-reviewable file before deletion",
                        data, target.readBytes())
                    manager.remove(requireNotNull(handle), SessionHandle.DELETE_FILES)
                    assertTrue("Missing asynchronous native delete result alert",
                        done.await(30, TimeUnit.SECONDS))
                    assertTrue("Native delete reported failure for destination $index",
                        deleted.get() == true)
                    assertFalse("Delete alert insufficient: exact target remains", target.exists())
                    assertArrayEquals("Native delete touched unrelated canary",
                        foreignBytes, foreign.readBytes())
                    assertArrayEquals("Native deletion changed fixture source",
                        data, File(source, "piece.bin").readBytes())
                } finally {
                    manager.removeListener(listener)
                    manager.stop()
                }
            }
        } finally {
            // Only the uniquely minted disposable child; do NOT erase the
            // user's default Downloads/Chunks Downloads folder.
            publicProbe?.deleteRecursively()
            work.deleteRecursively()
        }
    }
}
