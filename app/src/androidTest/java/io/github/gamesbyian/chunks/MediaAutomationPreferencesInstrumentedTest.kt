package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences
import io.github.gamesbyian.chunks.media.MediaTransferKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Actual Android private-file atomic publication/reopen; no engine, provider or user files. */
@RunWith(AndroidJUnit4::class)
class MediaAutomationPreferencesInstrumentedTest {
    @Test fun exclusionPersistsOnAndroidWithoutLeakingAcrossEnvironments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "automation-preferences-test-").toFile()
        val file = root.resolve("preferences.json")
        val phone = MediaTransferKey("phone|" + "a".repeat(40))
        val server = MediaTransferKey("server|" + "a".repeat(40))
        try {
            val store = FileMediaAutomationPreferences(file)
            assertFalse(store.isExcluded(phone))
            store.setExcluded(phone, true)
            val reopened = FileMediaAutomationPreferences(file)
            assertTrue(reopened.isExcluded(phone))
            assertFalse(reopened.isExcluded(server))
            reopened.setExcluded(phone, false)
            assertFalse(FileMediaAutomationPreferences(file).isExcluded(phone))
            // A damaged preference file must fail closed, rather than silently enable lookup.
            file.writeText("interrupted or damaged preferences")
            assertTrue(FileMediaAutomationPreferences(file).isExcluded(phone))
        } finally {
            file.delete()
            root.delete()
        }
    }
}
