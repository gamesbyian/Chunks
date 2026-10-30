package io.github.gamesbyian.chunks.create

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable actual Android JNI acceptance of CREATE_NEW copy and seeding. */
@RunWith(AndroidJUnit4::class)
class CreateSeedCopyOwnershipInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun singleFileCopyIsExclusivelyWrittenAndSeededOnAndroid() {
        val root = File(context.cacheDir, "seed-copy-" + System.nanoTime())
        assertTrue(root.mkdirs())
        val payload = ByteArray(24_181) { (it % 251).toByte() }
        val existing = File(root, "episode.bin.chunks-part")
        val unrelated = byteArrayOf(10, 20, 30, 40)
        existing.writeBytes(unrelated)
        val source = TorrentMetainfoBuilder.Request("episode.bin", listOf(
            TorrentMetainfoBuilder.Entry(listOf("episode.bin"), payload.size.toLong()) {
                ByteArrayInputStream(payload)
            }
        ))
        val expected = TorrentMetainfoBuilder.build(source)
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        try {
            engine.start()
            val outcome = CreateSeedCopy.copyVerifyAndSeed(source, expected, root, engine)
            assertTrue("Actual Android JNI copy must seed: $outcome",
                outcome is CreateSeedCopy.Outcome.Seeding)
            assertArrayEquals(payload, File(root, "episode.bin").readBytes())
            assertArrayEquals(unrelated, existing.readBytes())
            assertFalse(Files.isSymbolicLink(File(root, "episode.bin").toPath()))
        } finally {
            engine.close()
            root.deleteRecursively() // own disposable test root only
        }
    }

    @Test fun cancellationKeepsPartialCopyForReviewWithoutTouchingOtherFiles() {
        val root = File(context.cacheDir, "seed-copy-cancel-" + System.nanoTime())
        assertTrue(root.mkdirs())
        val keep = File(root, "foreign.bin")
        val unrelated = byteArrayOf(9, 3, 7)
        keep.writeBytes(unrelated)
        val payload = ByteArray(160_000) { (it % 251).toByte() }
        val source = TorrentMetainfoBuilder.Request("partial.bin", listOf(
            TorrentMetainfoBuilder.Entry(listOf("partial.bin"), payload.size.toLong()) {
                ByteArrayInputStream(payload)
            }))
        val expected = TorrentMetainfoBuilder.build(source)
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        var cancelled = false
        try {
            engine.start()
            val result = CreateSeedCopy.copyVerifyAndSeed(source, expected, root, engine,
                onProgress = { cancelled = true }, isCancelled = { cancelled })
            assertTrue(result is CreateSeedCopy.Outcome.Refused)
            assertTrue((result as CreateSeedCopy.Outcome.Refused).reason.contains("manual review"))
            val partial = File(root, "partial.bin")
            assertTrue(partial.isFile)
            assertTrue(partial.length() in 1 until payload.size.toLong())
            assertArrayEquals(payload.copyOf(partial.length().toInt()), partial.readBytes())
            assertArrayEquals(unrelated, keep.readBytes())
            assertTrue("A cancelled copy must never register a seed", engine.list().isEmpty())
        } finally {
            engine.close()
            root.deleteRecursively() // Only this test's disposable fixture.
        }
    }

}
