package io.github.gamesbyian.chunks.storage

import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ExclusiveFsyncJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "a".repeat(32)

    @Test fun createsAndRecoversOnlyDurableCompleteRecords() {
        val dir = temp.newFolder("private").toPath()
        val log = ExclusiveFsyncJournal.create(dir, id)
        log.append("BEGIN\t" + id)
        log.append("PUBLISH_INTENT\tName")
        log.close()
        assertEquals(listOf("BEGIN\t" + id, "PUBLISH_INTENT\tName"),
            ExclusiveFsyncJournal.readCompleteLines(log.path))
        Files.write(log.path, "INCOMPLETE".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        assertEquals(listOf("BEGIN\t" + id, "PUBLISH_INTENT\tName"),
            ExclusiveFsyncJournal.readCompleteLines(log.path))
    }

    @Test fun existingFileAndSymlinkAreNeverTruncated() {
        val dir = temp.newFolder("private").toPath()
        val foreign = temp.newFile("foreign").toPath()
        Files.write(foreign, "keep me".toByteArray())
        val log = dir.resolve(id + ".log")
        Files.createSymbolicLink(log, foreign)
        try {
            ExclusiveFsyncJournal.create(dir, id).close()
            throw AssertionError("Existing link was accepted")
        } catch (_: FileAlreadyExistsException) { }
        assertArrayEquals("keep me".toByteArray(), Files.readAllBytes(foreign))
        Files.delete(log)
        Files.createLink(log, foreign)
        try {
            ExclusiveFsyncJournal.create(dir, id).close()
            throw AssertionError("Existing hardlink was accepted")
        } catch (_: FileAlreadyExistsException) { }
        assertArrayEquals("keep me".toByteArray(), Files.readAllBytes(foreign))
    }

    @Test fun retainedDescriptorIgnoresSubstitutedJournalPath() {
        val dir = temp.newFolder("private").toPath()
        val foreign = temp.newFile("foreign").toPath()
        Files.write(foreign, "FOREIGN".toByteArray())
        val journal = ExclusiveFsyncJournal.create(dir, id)
        journal.append("BEGIN")
        Files.move(journal.path, dir.resolve("orphan"))
        Files.createLink(journal.path, foreign)
        journal.append("AFTER_SUBSTITUTION")
        journal.close()
        assertArrayEquals("FOREIGN".toByteArray(), Files.readAllBytes(foreign))
        assertEquals(listOf("BEGIN", "AFTER_SUBSTITUTION"),
            ExclusiveFsyncJournal.readCompleteLines(dir.resolve("orphan")))
        assertEquals(emptyList<String>(), ExclusiveFsyncJournal.readCompleteLines(journal.path))
    }

    @Test fun unknownJournalIsNeverTreatedAsAnOwnedDeletionTarget() {
        val dir = temp.newFolder("private").toPath()
        val missing = dir.resolve("missing.log")
        assertNull(ExclusiveFsyncJournal.readCompleteLines(missing))
        assertTrue(Files.notExists(missing))
    }
}
