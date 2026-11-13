package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDownloadPlacePickerInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    private fun choice(kind: LocalPlaceKind, title: String, failure: String? = null) = LocalPlaceChoice(
        LocalDownloadPlace(kind, File("/storage/x/" + kind.key), title, "$title detail"), failure)

    @Test fun defaultIsVisibleChangeIsOptionalAndOnlyVerifiedPlacesAreSelectable() {
        val public = choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "Downloads/Chunks Downloads")
        val app = choice(LocalPlaceKind.APP_STORAGE, "Chunks app folder")
        val sd = choice(LocalPlaceKind.REMOVABLE_APP_STORAGE, "SD card (Chunks app folder)", "card is read-only")
        val chosen = ArrayList<LocalPlaceKind>()
        rule.setContent {
            MaterialTheme {
                LocalDownloadPlacePicker(
                    LocalDownloadPlacePolicy.resolve(null, listOf(public, app, sd)),
                    enabled = true, onChoose = { chosen += it.place.kind })
            }
        }
        rule.onNodeWithText("Saves to: Downloads/Chunks Downloads").assertExists()
        rule.onNodeWithText("Chunks app folder").assertDoesNotExist()
        rule.onNodeWithText("Change download location").performClick()
        rule.onNodeWithText("Unavailable: card is read-only").assertExists()
        rule.onNodeWithText("SD card (Chunks app folder)").performClick()
        assertEquals(emptyList<LocalPlaceKind>(), chosen)
        rule.onNodeWithText("Chunks app folder").performClick()
        assertEquals(listOf(LocalPlaceKind.APP_STORAGE), chosen)
    }

    @Test fun unavailablePublicDownloadsFallbackIsStated() {
        rule.setContent {
            MaterialTheme {
                LocalDownloadPlacePicker(LocalDownloadPlacePolicy.resolve(null, listOf(
                    choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "Downloads/Chunks Downloads", "not mounted"),
                    choice(LocalPlaceKind.APP_STORAGE, "Chunks app folder"))),
                    enabled = false, onChoose = {})
            }
        }
        rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
        rule.onNodeWithText("not mounted", substring = true).assertExists()
        rule.onNodeWithText("Change download location").assertIsNotEnabled()
    }

    @Test fun android10PermissionIsOnlyRequestedFromAnExplicitButton() {
        var requests = 0
        val waiting = LocalPlaceChoice(LocalDownloadPlace(LocalPlaceKind.PUBLIC_DOWNLOADS,
            File("/storage/x/public"), "Downloads/Chunks Downloads", "detail"),
            LocalPublicDownloadsAccess.NEEDS_PERMISSION, needsStoragePermission = true)
        rule.setContent {
            MaterialTheme {
                LocalDownloadPlacePicker(LocalDownloadPlacePolicy.resolve(null, listOf(
                    waiting, choice(LocalPlaceKind.APP_STORAGE, "Chunks app folder"))),
                    enabled = true, onChoose = {}, onRequestStoragePermission = { requests++ })
            }
        }
        rule.onNodeWithText("Saves to: Chunks app folder").assertExists()
        rule.onNodeWithText(LocalPublicDownloadsAccess.NEEDS_PERMISSION, substring = true).assertExists()
        assertEquals("Never requested on its own", 0, requests)
        rule.onNodeWithText("Allow storage access for Downloads/Chunks Downloads").performClick()
        assertEquals(1, requests)
    }

    @Test fun noPermissionButtonWhenNothingNeedsIt() {
        rule.setContent {
            MaterialTheme {
                LocalDownloadPlacePicker(LocalDownloadPlacePolicy.resolve(null, listOf(
                    choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "Downloads/Chunks Downloads"),
                    choice(LocalPlaceKind.APP_STORAGE, "Chunks app folder"))),
                    enabled = true, onChoose = {})
            }
        }
        rule.onNodeWithText("Allow storage access", substring = true).assertDoesNotExist()
    }
    @Test fun oneTimeChoiceAndDefaultSaveAreSeparateAccessibleActions() {
        val public = choice(LocalPlaceKind.PUBLIC_DOWNLOADS, "Downloads/Chunks Downloads")
        val app = choice(LocalPlaceKind.APP_STORAGE, "Chunks app folder")
        val defaults = LocalDownloadPlacePolicy.resolve(null, listOf(public, app))
        var saved = 0
        var resets = 0
        rule.setContent {
            val current = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(
                LocalAddPlacePolicy.identity(app.place)) }
            MaterialTheme {
                LocalDownloadPlacePicker(LocalAddPlacePolicy.resolve(defaults, current.value), true,
                    onChoose = { current.value = LocalAddPlacePolicy.identity(it.place) },
                    currentAdd = true, defaultResolution = defaults,
                    onUseDefault = { current.value = null; resets++ },
                    onSaveDefault = { saved++ })
            }
        }
        rule.onNodeWithText("For this Add only. Saving a local default is a separate action.").assertExists()
        assertEquals(0, saved)
        rule.onNodeWithText("Save this location as local default").performClick()
        assertEquals(1, saved)
        rule.onNodeWithText("Use local default for this Add").performClick()
        assertEquals(1, resets)
        rule.onNodeWithText("Saves to: Downloads/Chunks Downloads").assertExists()
    }

    @Test fun realOneTimeSelectionNeverChangesPersistedDefaultOrMeasuredRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("local-download-place", android.content.Context.MODE_PRIVATE)
        val oldKind = prefs.getString("default-kind", null)
        val oldRoot = prefs.getString("resolved-root", null)
        try {
            val actual = LocalDownloadPlaces.choices(context).first { it.verified }
            org.junit.Assert.assertTrue(LocalDownloadPlaces.remember(context, actual))
            val other = LocalDownloadPlaces.choices(context).firstOrNull {
                it.verified && it.place.kind != actual.place.kind
            } ?: actual
            val default = LocalDownloadPlaces.resolve(context)
            val id = LocalAddPlacePolicy.identity(other.place)
            assertEquals(other.place, LocalAddPlacePolicy.resolve(default, id).selected)
            assertEquals(actual.place.kind.key, prefs.getString("default-kind", null))
            assertEquals(actual.place.root.absolutePath, prefs.getString("resolved-root", null))
            val unavailable = default.copy(choices = default.choices.map {
                if (LocalAddPlacePolicy.identity(it.place) == id) it.copy(failure = "permission revoked") else it
            })
            org.junit.Assert.assertNull(LocalAddPlacePolicy.resolve(unavailable, id).selected)
            assertEquals(actual.place.kind.key, prefs.getString("default-kind", null))
            assertEquals(other.place, LocalAddPlacePolicy.resolve(LocalDownloadPlaces.resolve(context), id).selected)
        } finally {
            prefs.edit().apply {
                if (oldKind == null) remove("default-kind") else putString("default-kind", oldKind)
                if (oldRoot == null) remove("resolved-root") else putString("resolved-root", oldRoot)
            }.commit()
        }
    }

}
