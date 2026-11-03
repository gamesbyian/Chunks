package io.github.gamesbyian.chunks

import android.content.ContentUris
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feasibility probe, NOT a shipping delete path. Creates ONLY two unique,
 * disposable files under Downloads/Chunks Downloads using direct POSIX File
 * APIs (the class of APIs libtorrent4j uses), then asks MediaStore whether
 * an exact indexed download and unlaunched delete request are available.
 *
 * Never launches a deletion intent, never erases a torrent, never recursively
 * cleans the selected Downloads folder and never touches preexisting files.
 * A successful row/URI is NOT torrent ownership or cross-torrent safety proof.
 */
@RunWith(AndroidJUnit4::class)
class NativeDownloadsMediaStoreIdentityInstrumentedTest {
    @Test fun directCreatedNonMediaDownloadHasExactMediaStoreUriAndUnlaunchedDeleteRequest() {
        // Never create/clean research files on a physical owner handset or in
        // the normal installed Add suite. This explicit argument is supplied
        // only by the dedicated disposable CI emulator workflow.
        assumeTrue("Disposable emulator research only",
            InstrumentationRegistry.getArguments()
                .getString("chunks_disposable_media_store") == "authorized")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        assertTrue("Public Downloads directory unavailable", downloads.isDirectory)
        val root = File(downloads, "Chunks Downloads")
        if (!root.exists()) assertTrue("Could not create Chunks Downloads", root.mkdir())
        assertTrue("Chunks Downloads must be a direct regular directory",
            Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(root.toPath()))
        val nonce = java.lang.Long.toUnsignedString(System.nanoTime(), 36)
        val payload = File(root, "chunks-p0-owner-probe-$nonce.bin")
        val canary = File(root, "chunks-p0-canary-$nonce.bin")
        val bytes = ByteArray(4096) { ((it * 31) % 251).toByte() }
        val canaryBytes = "other file never part of deletion request".toByteArray()
        var payloadCreated = false
        var canaryCreated = false
        try {
            Files.newOutputStream(payload.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE).use { it.write(bytes) }
            payloadCreated = true
            Files.newOutputStream(canary.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE).use { it.write(canaryBytes) }
            canaryCreated = true
            val scanned = CountDownLatch(1)
            MediaScannerConnection.scanFile(ctx, arrayOf(payload.absolutePath),
                arrayOf("application/octet-stream")) { _, _ -> scanned.countDown() }
            assertTrue("Media scan did not finish", scanned.await(20, TimeUnit.SECONDS))
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.SIZE)
            var matching: Pair<Long, String?>? = null
            for (attempt in 0 until 12) {
                resolver.query(collection, projection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(payload.name), null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val ownerColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (cursor.getLong(sizeColumn) == bytes.size.toLong()) {
                            val id = cursor.getLong(idColumn)
                            val uri = ContentUris.withAppendedId(collection, id)
                            val readback = resolver.openInputStream(uri)?.use { it.readBytes() }
                            if (readback?.contentEquals(bytes) == true) {
                                matching = id to cursor.getString(ownerColumn)
                                break
                            }
                        }
                    }
                }
                if (matching != null) break
                Thread.sleep(200)
            }
            assertTrue("POSIX-written nonmedia Downloads payload lacks a readable exact MediaStore row",
                matching != null)
            val (id, owner) = requireNotNull(matching)
            val selected = ContentUris.withAppendedId(collection, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Only constructs a PendingIntent. NEVER invoke its IntentSender.
                val pending = MediaStore.createDeleteRequest(resolver, listOf(selected))
                assertTrue("OS did not create an exact-item deletion request", pending.intentSender != null)
            }
            assertArrayEquals("Preparing a request must preserve the selected payload",
                bytes, payload.readBytes())
            assertArrayEquals("An unrelated file must be untouched", canaryBytes, canary.readBytes())
            println("CHUNKS_MEDIASTORE_POSIX_PROBE indexed=true ownerIsApp=${owner == ctx.packageName} " +
                "ownerUnknown=${owner == null} deleteRequestPrepared=${Build.VERSION.SDK_INT >= 30}")
        } finally {
            // These paths were created with CREATE_NEW in this test invocation.
            // Never delete the folder, wildcard-match names or touch any other file.
            if (payloadCreated) Files.deleteIfExists(payload.toPath())
            if (canaryCreated) Files.deleteIfExists(canary.toPath())
        }
    }
}
