package io.github.gamesbyian.chunks.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MediaAutomationPreferencesTest {
    private val local = MediaTransferKey("local|" + "a".repeat(40))
    private val remote = MediaTransferKey("server|" + "a".repeat(40))

    @Test fun exclusionsSurviveRestartAndRemainExactEnvironmentScoped() {
        val file = Files.createTempDirectory("media-automation").resolve("preferences.json").toFile()
        val store = FileMediaAutomationPreferences(file)
        assertFalse(store.isExcluded(local))
        store.setExcluded(local, true)
        val restarted = FileMediaAutomationPreferences(file)
        assertTrue(restarted.isExcluded(local))
        assertFalse(restarted.isExcluded(remote))
        restarted.setExcluded(local, false)
        assertFalse(FileMediaAutomationPreferences(file).isExcluded(local))
    }

    @Test fun unreadablePreferencesNeverEnableBackgroundLookups() {
        val file = Files.createTempDirectory("media-automation").resolve("preferences.json").toFile()
        file.writeText("broken preferences")
        assertTrue(FileMediaAutomationPreferences(file).isExcluded(local))
    }
    @Test fun aFailureBeforeTemporaryFileCreationStillBlocksAutomaticLookup() {
        val parent = Files.createTempDirectory("media-automation").toFile()
        val store = FileMediaAutomationPreferences(parent.resolve("preferences.json"))
        assertFalse(store.isExcluded(local))
        assertTrue(parent.delete())
        parent.writeText("a file now blocks the preference directory")
        assertTrue(runCatching { store.setExcluded(local, true) }.isFailure)
        assertTrue(store.isExcluded(local))
    }

}
