package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferFile
import org.junit.Assert.*
import org.junit.Test

class RarVolumeCandidatesTest {
    private fun row(index: Int, path: String, done: Long = 100, wanted: Boolean = true) =
        LocalTransferFile(index, path, 100, done, wanted)

    @Test fun groupsModernAndLegacyWithinExactFolderAndOrdersNumerically() {
        val files = listOf(row(0, "A/Film.part03.rar"), row(1, "A/Film.part01.rar"),
            row(2, "A/Film.part02.rar"), row(3, "B/Film.part01.rar"),
            row(4, "A/Extra.r01"), row(5, "A/Extra.rar"), row(6, "A/Extra.r00"),
            row(7, "A/Unrelated.mkv", 50))
        val groups = requireNotNull(RarVolumeCandidates.identify(files))
        assertEquals(3, groups.size)
        val modern = groups.single { it.folder == "A" && it.stem == "Film" }
        assertEquals(RarVolumeCandidates.Form.MODERN, modern.form)
        assertEquals(listOf(1, 2, 3), modern.volumes.map { it.ordinal })
        assertEquals(listOf(1, 2, 0), modern.volumes.map { it.file.index })
        assertEquals(0, modern.missingCount)
        assertTrue(modern.allListedComplete)
        assertFalse(modern.ambiguous)
        val legacy = groups.single { it.stem == "Extra" }
        assertEquals(RarVolumeCandidates.Form.LEGACY_OR_SINGLE, legacy.form)
        assertEquals(listOf("A/Extra.rar", "A/Extra.r00", "A/Extra.r01"),
            legacy.volumes.map { it.file.path })
        assertEquals(0, legacy.missingCount)
        // Unrelated incomplete torrent file does not change the group's
        // reported counters; nothing in this model declares extraction ready.
        assertTrue(legacy.allListedComplete)
    }

    @Test fun gapsMissingFirstAndLegacyLetterRolloverStayVisible() {
        val files = listOf(row(0, "a.part02.rar"), row(1, "a.part04.rar", 12),
            row(2, "b.r99"), row(3, "b.s00", 0, false))
        val groups = requireNotNull(RarVolumeCandidates.identify(files))
        val a = groups.first()
        assertEquals(2, a.missingCount)
        assertEquals(1, a.firstMissing)
        assertEquals(1, a.listedCompleteCount)
        assertFalse(a.allListedComplete)
        val b = groups.last()
        assertEquals(listOf(101, 102), b.volumes.map { it.ordinal })
        assertEquals(100, b.missingCount)
        assertEquals(1, b.firstMissing)
        assertFalse(b.volumes.last().file.wanted)
    }

    @Test fun mixedDuplicateCaseAliasInvalidOrdinalAndInconsistentPaddingAreAmbiguous() {
        val files = listOf(row(0, "a.part1.rar"), row(1, "a.part01.rar"),
            row(2, "b.rar"), row(3, "b.part01.rar"),
            row(4, "C.part1.rar"), row(5, "c.part2.rar"),
            row(6, "d.part0.rar"), row(7, "e.part01.rar"), row(8, "e.part2.rar"),
            row(9, "f.part10001.rar"))
        val groups = requireNotNull(RarVolumeCandidates.identify(files))
        assertEquals(6, groups.size)
        assertTrue(groups.all { it.ambiguous })
        assertEquals(RarVolumeCandidates.Form.MIXED, groups.single { it.stem == "b" }.form)
    }

    @Test fun oneRarRemainsSingleOrLegacyCandidateNeverProofOfFinalVolume() {
        val candidate = requireNotNull(RarVolumeCandidates.identify(listOf(row(0, "film.RAR")))).single()
        assertEquals(RarVolumeCandidates.Form.LEGACY_OR_SINGLE, candidate.form)
        assertEquals(1, candidate.volumes.single().ordinal)
        assertEquals(0, candidate.missingCount)
        assertTrue(candidate.allListedComplete)
        assertEquals(emptyList<RarVolumeCandidates.Candidate>(),
            RarVolumeCandidates.identify(listOf(row(0, "film.zip"), row(1, "track.mp3"))))
    }

    @Test fun malformedOrUnboundedInventoriesRefuseRatherThanClaimNoArchives() {
        for (files in listOf(
            listOf(row(0, "a.rar"), row(0, "b.rar")),
            listOf(row(0, "a.rar"), row(1, "a.rar")),
            listOf(row(0, "../secret.rar")),
            listOf(row(0, "folder//secret.rar")),
            listOf(row(0, "a.rar", 101)),
            listOf(row(0, "a.rar", -1)),
            (0..RarVolumeCandidates.MAX_GROUPS).map { row(it, "archive-$it.rar") },
            (0..RarVolumeCandidates.MAX_FILES).map { row(it, "unrelated-$it.bin") }
        )) assertNull(RarVolumeCandidates.identify(files))
    }
}
