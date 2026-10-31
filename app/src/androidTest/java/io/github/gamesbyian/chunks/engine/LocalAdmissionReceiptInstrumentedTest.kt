package io.github.gamesbyian.chunks.engine

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android filesystem gate for native admission metadata. Executed on
 * API29 in the opt-in public Downloads suite. It tests the private receipt
 * filesystem, not torrent payload ownership or public-storage semantics.
 */
@RunWith(AndroidJUnit4::class)
class LocalAdmissionReceiptInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "b".repeat(40)

    private fun withPrivateRoot(label: String, test: (File) -> Unit) {
        val root = File(context.filesDir, "receipt-android-$label-" + System.nanoTime())
        assertTrue("Could not create isolated private test root", root.mkdirs())
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun exclusiveCheckedReceiptSurvivesRealAndroidFileAndDirectoryFsync() =
        withPrivateRoot("checked") { root ->
            assertTrue("Real Android CREATE_NEW and directory fsync must succeed",
                LocalAdmissionProvenance.persist(root, hash,
                    LocalAdmissionProvenance.Mode.CHECKED))
            assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
                LocalAdmissionProvenance.read(root, hash))
            assertTrue("Repeated exact mode must re-fsync before acceptance",
                LocalAdmissionProvenance.persist(root, hash,
                    LocalAdmissionProvenance.Mode.CHECKED))
            assertFalse("Different mode can never replace the receipt",
                LocalAdmissionProvenance.persist(root, hash,
                    LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        }

    @Test fun existingForeignFinalReceiptIsNotTruncated() =
        withPrivateRoot("foreign") { root ->
            val receipt = File(root, "$hash.admission")
            val original = ByteArray(4096) { (it * 13 + 7).toByte() }
            receipt.writeBytes(original)
            assertFalse(LocalAdmissionProvenance.persist(root, hash,
                LocalAdmissionProvenance.Mode.CHECKED))
            assertArrayEquals("Existing Android-private bytes must remain untouched",
                original, receipt.readBytes())
            assertNull(LocalAdmissionProvenance.read(root, hash))
        }

    @Test fun plantedFifoCannotBlockPrivateReceiptReadOrEarnAdmission() =
        withPrivateRoot("fifo") { root ->
            val receipt = File(root, "$hash.admission")
            Os.mkfifo(receipt.absolutePath, 384) // Private mode 0600.
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "chunks-fifo-negative-control").apply { isDaemon = true }
            }
            try {
                val read = pool.submit<LocalAdmissionProvenance.Mode?> {
                    LocalAdmissionProvenance.read(root, hash)
                }
                assertNull("Nonblocking pinned receipt read must refuse FIFO",
                    read.get(3, java.util.concurrent.TimeUnit.SECONDS))
                val write = pool.submit<Boolean> {
                    LocalAdmissionProvenance.persist(
                        root, hash, LocalAdmissionProvenance.Mode.CHECKED)
                }
                assertFalse("FIFO may never authorize or block native Add",
                    write.get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("Refusal must leave foreign FIFO path intact", receipt.exists())
            } finally { pool.shutdownNow() }
        }

    @Test fun interruptedDirectoryForceRefusesAndMatchingRetryCanRecover() =
        withPrivateRoot("failed-fsync") { root ->
            val denied: (java.nio.file.Path) -> Unit = {
                throw IOException("deliberately denied parent fsync")
            }
            assertFalse(LocalAdmissionProvenance.persist(root, hash,
                LocalAdmissionProvenance.Mode.REVIEWED_EXISTING, denied))
            assertFalse("An unsynced matching record is not an accepted Add",
                LocalAdmissionProvenance.persist(root, hash,
                    LocalAdmissionProvenance.Mode.REVIEWED_EXISTING, denied))
            assertTrue("A later checked file+directory sync can recover it",
                LocalAdmissionProvenance.persist(root, hash,
                    LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
            assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
                LocalAdmissionProvenance.read(root, hash))
        }
}
