package io.github.gamesbyian.chunks.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MediaAutomationPreferencesTest {
    private val phone = MediaTransferKey("phone|" + "a".repeat(40))
    private val remote = MediaTransferKey("server|" + "a".repeat(40))

    @Test fun exclusionsSurviveRestartAndRemainExactEnvironmentScoped() {
        val file = Files.createTempDirectory("media-automation").resolve("preferences.json").toFile()
        val store = FileMediaAutomationPreferences(file)
        assertFalse(store.isExcluded(phone))
        store.setExcluded(phone, true)
        val restarted = FileMediaAutomationPreferences(file)
        assertTrue(restarted.isExcluded(phone))
        assertFalse(restarted.isExcluded(remote))
        restarted.setExcluded(phone, false)
        assertFalse(FileMediaAutomationPreferences(file).isExcluded(phone))
    }

    @Test fun unreadablePreferencesNeverEnableBackgroundLookups() {
        val file = Files.createTempDirectory("media-automation").resolve("preferences.json").toFile()
        file.writeText("broken preferences")
        assertTrue(FileMediaAutomationPreferences(file).isExcluded(phone))
    }
}
