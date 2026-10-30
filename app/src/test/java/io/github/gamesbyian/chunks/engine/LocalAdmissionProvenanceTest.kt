package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
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
}
