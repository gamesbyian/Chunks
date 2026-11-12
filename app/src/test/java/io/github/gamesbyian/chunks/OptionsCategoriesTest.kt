package io.github.gamesbyian.chunks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsCategoriesTest {
    @Test fun ownerApprovedTaxonomyAndOrderArePreserved() {
        assertEquals(listOf("Connections", "Downloads & storage", "Seeding & limits", "Artwork & metadata",
            "Notifications", "Appearance & accessibility", "Privacy & network", "About Chunks"),
            OptionsCategory.entries.map { it.title })
        assertEquals(8, OptionsCategory.entries.map { it.tag }.toSet().size)
    }

    @Test fun filledCardsHaveReadableWhiteTextAndDiscsKeepDarkIconOutlinesVisible() {
        for (category in OptionsCategory.entries) {
            assertTrue("${category.title} text must meet AA", 1.05 / (category.fill.luminance() + 0.05) >= 4.5)
            val disc = Color.White.copy(alpha = 0.8f).compositeOver(category.fill)
            assertTrue("${category.title} icon must remain distinguishable",
                (disc.luminance() + 0.05) / (ChunksColors.OnAccent.luminance() + 0.05) >= 3.0)
        }
    }

    @Test fun legacySettingWordsFindTheirNewSingleOwner() {
        for ((query, category) in listOf("clients" to OptionsCategory.CONNECTIONS,
            "archive" to OptionsCategory.DOWNLOADS, "ratio" to OptionsCategory.SEEDING,
            "posters" to OptionsCategory.ARTWORK, "alerts" to OptionsCategory.NOTIFICATIONS,
            "widget" to OptionsCategory.APPEARANCE, "clipboard" to OptionsCategory.PRIVACY,
            "diagnostics" to OptionsCategory.ABOUT)) {
            assertTrue("$query must find ${category.title}", category.matches(query))
        }
        assertEquals(OptionsCategory.PRIVACY, LocalOptionsShortcut.DHT.category)
        assertEquals(OptionsCategory.SEEDING, LocalOptionsShortcut.SPEED_LIMITS.category)
        assertEquals(OptionsCategory.DOWNLOADS, LocalOptionsShortcut.SNAPSHOT_RESTORE.category)
    }
}
