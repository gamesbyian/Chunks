package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class LocalRestoreDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingStateDirectoryDoesNotStartEngine() {
        assertFalse(LocalRestoreDiscovery.shouldAttemptRestore(File(temp.root, "not-created")))
    }

    @Test fun genuinelyEmptyStateDirectoryDoesNotStartEngine() {
        assertFalse(LocalRestoreDiscovery.shouldAttemptRestore(temp.newFolder("empty")))
    }

    @Test fun existingResumeFileTriggersRestore() {
        val state = temp.newFolder("existing")
        File(state, "a".repeat(40) + ".resume").writeText("fixture")
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(state))
    }

    @Test fun conflictingFileMustReachEnginePreflight() {
        val file = File(temp.root, "collision").apply { writeText("preserved") }
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(file))
        assertEquals("preserved", file.readText())
    }

    @Test fun failedStateEnumerationMustNotMeanNoSavedTorrents() {
        val state = temp.newFolder("denied")
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(state) {
            if (it == state) null else it.listFiles()
        })
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(state) {
            if (it == state) throw IOException("access lost") else it.listFiles()
        })
    }

    @Test fun failedParentEnumerationMustNotMeanStateAbsent() {
        val state = File(temp.root, "may-have-files")
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(state) { null })
        assertTrue(LocalRestoreDiscovery.shouldAttemptRestore(state) { throw IOException("no parent") })
    }

    @Test fun stagingOnlyDoesNotCountAsRestorableTorrent() {
        val state = temp.newFolder("staging")
        File(state, "a".repeat(40) + ".resume.tmp").writeText("incomplete")
        assertFalse(LocalRestoreDiscovery.shouldAttemptRestore(state))
    }
}
