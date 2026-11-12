package io.github.gamesbyian.chunks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real temporary filesystem and preference callback. No engine write.
 * Exact Add authority is still checked by installed local journey tests.
 */
class LocalAddArtworkPreflightTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rejectedPlaceNeverPersistsAnOptOut() {
        val root = temp.newFolder("downloads")
        val obstructed = File(root, "not-a-folder")
        assertTrue(obstructed.writeText("unrelated owned bytes").let { true })
        var writes = 0
        val result = prepareLocalAddPlaceAndArtwork(root, "not-a-folder", false) {
            writes++
            true
        }
        assertEquals(LocalAddArtworkPreflight.InvalidPlace, result)
        assertEquals(0, writes)
        assertEquals("unrelated owned bytes", obstructed.readText())
    }

    @Test fun invalidOrMissingRootNeverTouchesPreferenceStore() {
        var writes = 0
        val result = prepareLocalAddPlaceAndArtwork(null, "Season 01", false) {
            writes++
            true
        }
        assertEquals(LocalAddArtworkPreflight.InvalidPlace, result)
        assertEquals(0, writes)
        val root = temp.newFolder("downloads")
        assertEquals(LocalAddArtworkPreflight.InvalidPlace,
            prepareLocalAddPlaceAndArtwork(root, "../outside", false) {
                writes++
                true
            })
        assertEquals(0, writes)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun optedOutJobPersistsExactlyOnceAfterVerifiedPlace() {
        val root = temp.newFolder("downloads")
        var writes = 0
        val result = prepareLocalAddPlaceAndArtwork(root, "Season 01", false) {
            assertTrue("Selected Place must exist before preference commit",
                File(root, "Season 01").isDirectory)
            writes++
            true
        }
        assertTrue(result is LocalAddArtworkPreflight.Ready)
        assertEquals(File(root, "Season 01").canonicalFile,
            (result as LocalAddArtworkPreflight.Ready).folder.canonicalFile)
        assertEquals(1, writes)
        assertFalse("Diagnostics must not include a private download path",
            result.toString().contains(root.absolutePath))
    }

    @Test fun failedPreferenceCommitNeverReportsReadyForNativeAdd() {
        val root = temp.newFolder("downloads")
        var writes = 0
        val result = prepareLocalAddPlaceAndArtwork(root, "", false) {
            writes++
            false
        }
        assertEquals(LocalAddArtworkPreflight.PreferenceNotSaved, result)
        assertEquals(1, writes)
    }

    @Test fun defaultCheckedChoiceDoesNotWritePreferences() {
        val root = temp.newFolder("downloads")
        val result = prepareLocalAddPlaceAndArtwork(root, "", true) {
            fail("Default-on Add should not remove or write any preexisting exclusion")
            false
        }
        assertTrue(result is LocalAddArtworkPreflight.Ready)
        assertEquals(root.canonicalFile, (result as LocalAddArtworkPreflight.Ready).folder.canonicalFile)
    }
}
