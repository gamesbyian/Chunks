package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAdmissionProvenanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val hash = "a".repeat(40)

    @Test fun checkedReviewIsDurablyRecognizedButNeverUpgradedToExistingData() {
        val state = temp.newFolder("checked-state")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
        assertFalse("A later raw Add must not erase checked provenance",
            LocalAdmissionProvenance.persist(state, hash,
                LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
    }

    @Test fun separatelyReviewedExistingSeedRoundTripsAndCannotBeConvertedToChecked() {
        val state = temp.newFolder("reviewed-state")
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals(LocalAdmissionProvenance.Mode.REVIEWED_EXISTING,
            LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        LocalAdmissionProvenance.retire(state, hash)
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
    }

    @Test fun corruptMissingSymlinkAndBadHashCanNeverGrantReviewedExistingAdmission() {
        val state = temp.newFolder("invalid")
        val marker = File(state, "$hash.admission")
        marker.writeText("reviewed-existing")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertTrue(marker.delete())
        val target = temp.newFile("outside-receipt").apply {
            writeText("chunks-native-admission-v1\n$hash\nreviewed-existing\n")
        }
        Files.createSymbolicLink(marker.toPath(), target.toPath())
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertEquals("unchanged unrelated target receipt",
            "chunks-native-admission-v1\n$hash\nreviewed-existing\n",
            target.readText())
        assertNull(LocalAdmissionProvenance.read(state, "../$hash"))
        assertFalse(LocalAdmissionProvenance.persist(state, "../$hash",
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
    }

    @Test fun interruptedTemporaryOrOversizedReceiptDoesNotAuthorizeUnownedData() {
        val state = temp.newFolder("interrupted")
        File(state, "$hash.admission.tmp").writeText("incomplete transaction")
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        val marker = File(state, "$hash.admission")
        marker.writeBytes(ByteArray(1000))
        assertNull(LocalAdmissionProvenance.read(state, hash))
        assertFalse(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
    }

    @Test fun stalePredictableTemporaryFileAndSymlinkAreNeverWrittenOrDeleted() {
        val state = temp.newFolder("foreign-temporary")
        val unrelated = temp.newFile("unrelated-temporary").apply {
            writeText("these bytes are not Chunks admission state")
        }
        val stale = File(state, "$hash.admission.tmp")
        Files.createSymbolicLink(stale.toPath(), unrelated.toPath())
        assertTrue(LocalAdmissionProvenance.persist(state, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        assertTrue(Files.isSymbolicLink(stale.toPath()))
        assertEquals("these bytes are not Chunks admission state", unrelated.readText())
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(state, hash))
    }

    @Test fun concurrentlyPublishedDifferentModesCannotReplaceTheFirstReceipt() {
        val state = temp.newFolder("concurrent-admission")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 24).map { index ->
                pool.submit<Pair<LocalAdmissionProvenance.Mode, Boolean>> {
                    val mode = if (index % 2 == 0)
                        LocalAdmissionProvenance.Mode.CHECKED
                    else LocalAdmissionProvenance.Mode.REVIEWED_EXISTING
                    start.await()
                    mode to LocalAdmissionProvenance.persist(state, hash, mode)
                }
            }
            start.countDown()
            val outcomes = futures.map { it.get(20, TimeUnit.SECONDS) }
            val winner = LocalAdmissionProvenance.read(state, hash)
            assertNotNull("Exactly one valid receipt must remain", winner)
            assertTrue("At least one caller must have published or confirmed the winner",
                outcomes.any { it.first == winner && it.second })
            assertTrue("No losing admission may report success",
                outcomes.none { it.first != winner && it.second })
            val expected = "chunks-native-admission-v1\n$hash\n${winner!!.token}\n"
            assertEquals(expected, File(state, "$hash.admission").readText())
            assertEquals(winner, LocalAdmissionProvenance.read(state, hash))
            assertTrue("Published receipt must resist a later contrary admission",
                !LocalAdmissionProvenance.persist(state, hash,
                    if (winner == LocalAdmissionProvenance.Mode.CHECKED)
                        LocalAdmissionProvenance.Mode.REVIEWED_EXISTING
                    else LocalAdmissionProvenance.Mode.CHECKED))
            assertEquals(expected, File(state, "$hash.admission").readText())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun receiptDirectorySymlinkCannotAuthorizeOrPublishAdmission() {
        val actualState = temp.newFolder("actual-state")
        assertTrue(LocalAdmissionProvenance.persist(actualState, hash,
            LocalAdmissionProvenance.Mode.CHECKED))
        val original = File(actualState, "$hash.admission").readBytes()
        val alias = File(temp.root, "symlink-state")
        Files.createSymbolicLink(alias.toPath(), actualState.toPath())
        assertFalse(LocalAdmissionProvenance.persist(alias, hash,
            LocalAdmissionProvenance.Mode.REVIEWED_EXISTING))
        assertNull("Even a valid receipt through a linked parent is unknown",
            LocalAdmissionProvenance.read(alias, hash))
        assertArrayEquals(original, File(actualState, "$hash.admission").readBytes())
        assertEquals(LocalAdmissionProvenance.Mode.CHECKED,
            LocalAdmissionProvenance.read(actualState, hash))
    }

}
