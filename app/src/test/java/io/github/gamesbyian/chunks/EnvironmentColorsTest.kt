package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class EnvironmentColorsTest {
    @get:Rule val rule = createComposeRule()

    private class MapPrefs : EnvironmentColorStore.Prefs {
        val map = LinkedHashMap<String, String>()
        override fun all(): Map<String, String> = map.toMap()
        override fun put(key: String, value: String): Boolean { map[key] = value; return true }
        override fun remove(key: String): Boolean { map.remove(key); return true }
    }

    @Test fun automaticColoursAvoidRepeatsStayPutAndNeverIncludeBlackOrWhite() {
        val store = EnvironmentColorStore(MapPrefs())
        val ids = listOf("phone") + (1..10).map { "remote:$it" }
        val first = ids.associateWith { store.colorFor(it) }
        assertEquals(EnvironmentColor.entries.size, first.values.take(EnvironmentColor.entries.size).toSet().size)
        assertEquals("orange and red are chosen last", EnvironmentColor.RED, first.getValue("remote:7"))
        // Stable: asking again (for example after a rename or restart) never shifts a colour.
        ids.forEach { assertEquals(first.getValue(it), store.colorFor(it)) }
        EnvironmentColor.entries.forEach { assertTrue(it.color != Color.Black && it.color != Color.White) }
    }

    @Test fun userMayRepeatAColourAndRestoreTheAutomaticOne() {
        val store = EnvironmentColorStore(MapPrefs())
        assertEquals(EnvironmentColor.CYAN, store.colorFor("phone"))
        assertEquals(EnvironmentColor.BLUE, store.colorFor("remote:a"))
        assertTrue(store.choose("remote:a", EnvironmentColor.CYAN))
        assertEquals(EnvironmentColor.CYAN, store.colorFor("remote:a"))
        assertTrue(store.saved("remote:a")!!.manual)
        assertTrue(store.restoreAutomatic("remote:a"))
        assertEquals(EnvironmentColor.BLUE, store.colorFor("remote:a"))
        assertFalse(store.saved("remote:a")!!.manual)
    }

    @Test fun namesAreAlwaysReadableInTheirColour() {
        listOf(ChunksColors.SurfaceRaised, ChunksColors.Background, ChunksColors.Surface).forEach { bg ->
            EnvironmentColor.entries.forEach { assertTrue(it.label, contrastRatio(textSafe(it.color, bg), bg) >= 4.5) }
        }
    }

    @Test fun serverIdMatchesTheSeedingEnvironmentId() {
        val endpoint = "https://box.example/rutorrent2/plugins/httprpc/action.php"
        assertEquals(SeedingEnvironment.server(endpoint, "ian").id, EnvironmentIds.server(endpoint + "\u0000" + "ian"))
    }

    @Test fun badgePickerChangesTheColourAndOffersRestore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.setContent {
            ChunksTheme { Surface(color = ChunksColors.Background) {
                EnvironmentBadge(PHONE_ENVIRONMENT_LABEL, server = false, environmentId = EnvironmentIds.PHONE, editable = true)
            } }
        }
        rule.onNodeWithText(PHONE_ENVIRONMENT_LABEL).performClick()
        rule.onNodeWithText("Colour for $PHONE_ENVIRONMENT_LABEL").assertExists()
        rule.onNodeWithText("Cyan (current)").assertExists()
        rule.onNodeWithText("Pink").performClick()
        rule.waitForIdle()
        assertEquals(EnvironmentColor.PINK, EnvironmentColorStore.of(context).colorFor(EnvironmentIds.PHONE))
        rule.onNodeWithText(PHONE_ENVIRONMENT_LABEL).performClick()
        rule.onNodeWithText("Pink (current)").assertExists()
        rule.onNodeWithText("Restore automatic colour").performClick()
        rule.waitForIdle()
        assertEquals(EnvironmentColor.CYAN, EnvironmentColorStore.of(context).colorFor(EnvironmentIds.PHONE))
    }
}
