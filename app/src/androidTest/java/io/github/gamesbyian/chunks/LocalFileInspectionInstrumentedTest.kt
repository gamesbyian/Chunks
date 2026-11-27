package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

@RunWith(AndroidJUnit4::class)
class LocalFileInspectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fixtureRoot() = File(context.cacheDir, "inspection-${System.nanoTime()}").apply {
        assertTrue(mkdir())
    }

    @Test fun streamsLargerThanLibraryCopyLimitAndRetainsOnlyRequestedPrefix() {
        val root = fixtureRoot()
        try {
            val bytes = ByteArray(9 * 1024 * 1024 + 31).also { Random(77).nextBytes(it) }
            val file = File(root, "large.bin").apply { writeBytes(bytes) }
            val progress = mutableListOf<Long>()
            val result = LocalPinnedPayloadRead.read(root, file, bytes.size.toLong(),
                captureLimit = 64, cancelled = { false }, onProgress = { progress += it })
            assertEquals(sha256(bytes), result.sha256)
            assertArrayEquals(bytes.copyOf(64), result.captured)
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(2L * bytes.size, progress.last())
            assertArrayEquals(bytes, file.readBytes())
            val empty = File(root, "empty.txt").apply { writeBytes(byteArrayOf()) }
            val zero = LocalPinnedPayloadRead.read(root, empty, 0, 64, { false })
            assertEquals(sha256(byteArrayOf()), zero.sha256)
            assertEquals(0, zero.captured.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyChecksumsAreOptInAndMatchIndependentKnownVectors() {
        val root = fixtureRoot()
        try {
            val bytes = "123456789".toByteArray(Charsets.US_ASCII)
            val file = File(root, "vector.bin").apply { writeBytes(bytes) }
            val ordinary = LocalPinnedPayloadRead.read(root, file, bytes.size.toLong(), 0, { false })
            assertTrue("Default read must not calculate legacy checksums", ordinary.legacy.isEmpty())
            val legacy = LocalPinnedPayloadRead.read(root, file, bytes.size.toLong(), 0,
                cancelled = { false }, includeLegacy = true)
            assertEquals(ordinary.sha256, legacy.sha256)
            assertEquals("f7c3bc1d808e04732adf679965ccc34ca7ae3441",
                legacy.legacy[LocalChecksumComparison.Algorithm.SHA1])
            assertEquals("25f9e794323b453885f5181f1b624d0b",
                legacy.legacy[LocalChecksumComparison.Algorithm.MD5])
            assertEquals("cbf43926", legacy.legacy[LocalChecksumComparison.Algorithm.CRC32])
            assertEquals(0, legacy.captured.size)
            assertArrayEquals(bytes, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun pinnedReadRejectsSubstitutionCancellationAndLeafSymlinkWithoutChangingEitherFile() {
        val root = fixtureRoot()
        try {
            val old = ByteArray(200_000).also { Random(5).nextBytes(it) }
            val replacement = ByteArray(old.size).also { Random(6).nextBytes(it) }
            val file = File(root, "reviewed.bin").apply { writeBytes(old) }
            val retired = File(root, "retired.bin")
            var swapped = false
            val failure = runCatching {
                LocalPinnedPayloadRead.read(root, file, old.size.toLong(), 0, { false }) {
                    if (!swapped) {
                        assertTrue(file.renameTo(retired))
                        file.writeBytes(replacement)
                        swapped = true
                    }
                }
            }.exceptionOrNull()
            assertTrue(swapped)
            assertTrue(failure is IOException)
            assertArrayEquals(old, retired.readBytes())
            assertArrayEquals(replacement, file.readBytes())
            assertTrue(runCatching {
                LocalPinnedPayloadRead.read(root, file, replacement.size.toLong(), 0, { true })
            }.exceptionOrNull() is IOException)
            val link = File(root, "link.bin")
            android.system.Os.symlink(retired.absolutePath, link.absolutePath)
            assertNotNull(runCatching {
                LocalPinnedPayloadRead.read(root, link, old.size.toLong(), 0, { false })
            }.exceptionOrNull())
            assertArrayEquals(old, retired.readBytes())
            assertArrayEquals(replacement, file.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun canonicalLocalDetailsInspectsRealCompletedNativeFileWithoutChangingSeedingBytes() {
        val root = fixtureRoot()
        val source = File(root, "Source").apply { assertTrue(mkdir()) }
        val bytes = "<script>inert NFO text</script>\n".toByteArray()
        val file = File(source, "release.nfo").apply { writeBytes(bytes) }
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        try {
            local.start()
            val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024).generate().entry().bencode()
            val hash = (local.addStopped(torrent, root) as LocalAddResult.AddedStopped).status.infoHash
            local.resume(hash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (local.status(hash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() > deadline) fail("Real native inspection fixture did not seed")
                Thread.sleep(100)
            }
            val review = requireNotNull(LocalFileInspection.review(local, hash))
            val row = review.files.single()
            var refusedProgress = 0
            val stale = LocalFileInspection.inspect(local,
                review.copy(nativeToken = review.nativeToken + "-obsolete"), row,
                onProgress = { refusedProgress++ })
            assertTrue(stale is LocalFileInspection.Outcome.Refused)
            assertEquals(0, refusedProgress)
            val wrongRow = LocalFileInspection.inspect(local, review, row.copy(index = row.index + 1),
                onProgress = { refusedProgress++ })
            assertTrue(wrongRow is LocalFileInspection.Outcome.Refused)
            assertEquals(0, refusedProgress)

            // Exercise the production Details entrypoint, not an isolated mock inspection.
            val fence = LocalMutationFence.revision
            compose.waitUntil(5_000) {
                LocalDetailsRuns.running.isEmpty() &&
                    fence.acceptObservation(fence.snapshot(), emptyList<Any>())
            }
            val port = EngineLocalDetailsPort(local)
            compose.setContent {
                ChunksTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalDetailsOperations(hash, current = true, stale = false, engine = { port })
                    }
                }
            }
            compose.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).performScrollTo().performClick()
            compose.onNodeWithText("Inspect checksums…").performScrollTo().performClick()
            val label = "Inspect " + row.path + " (" + row.sizeBytes + " bytes)"
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label).performScrollTo().performClick()
            val expected = "SHA-256: " + sha256(bytes)
            compose.waitUntil(45_000) { compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(expected).assertExists()
            compose.onNodeWithText("UTF-8 text preview").assertExists()
            compose.onNodeWithText("<script>inert NFO text</script>", substring = true).assertExists()
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(LocalTransferState.SEEDING, local.status(hash)?.state)
        } finally {
            local.close()
            root.deleteRecursively()
        }
    }
}
