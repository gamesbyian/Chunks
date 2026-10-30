package io.github.gamesbyian.chunks.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android filesystem NIO compatibility for read-only transfer diagnosis.
 * Does not start libtorrent or touch public/user files. API35 emulator evidence
 * only, not physical provider or reattachment acceptance.
 */
@RunWith(AndroidJUnit4::class)
class LocalFileDiskCheckInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun inRootSymlinkCannotMakeForeignBytesAppearHealthy() {
        val root = File(context.cacheDir, "diskcheck-symlink-" + System.nanoTime())
        check(root.mkdirs())
        try {
            val foreign = File(root, "foreign.bin")
            val bytes = ByteArray(41) { (it + 12).toByte() }
            foreign.writeBytes(bytes)
            Files.createSymbolicLink(File(root, "torrent.bin").toPath(), foreign.toPath())
            val result = LocalFileDiskCheck.check(root,
                listOf(LocalTransferFile(0, "torrent.bin", 41, 41, true)))
                .results.single()
            assertEquals(LocalFileDiskCheck.Status.UNSAFE_PATH, result.status)
            assertNull(result.observedBytes)
            assertArrayEquals(bytes, foreign.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun ordinaryAppPrivateFileStillReportsOnlyExpectedSize() {
        val root = File(context.cacheDir, "diskcheck-regular-" + System.nanoTime())
        check(root.mkdirs())
        try {
            val actual = File(root, "episode.bin")
            actual.writeBytes(ByteArray(127) { 9 })
            val result = LocalFileDiskCheck.check(root,
                listOf(LocalTransferFile(0, "episode.bin", 127, 127, true)))
                .results.single()
            assertEquals(LocalFileDiskCheck.Status.PRESENT, result.status)
            assertEquals(127L, result.observedBytes)
        } finally {
            root.deleteRecursively()
        }
    }
}
