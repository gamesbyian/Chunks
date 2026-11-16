package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryTest {
    @Test fun descriptorsHaveUniqueStableIdsAndOneCanonicalHome() {
        val descriptors = SettingsRegistry.descriptors
        assertEquals(descriptors.size, descriptors.map { it.id }.toSet().size)
        assertTrue(descriptors.all { it.scope == SettingScope.GLOBAL })
        assertTrue(descriptors.all { it.canonicalHome == OptionsCategory.ARTWORK })
        assertNotNull(SettingsRegistry.definition("artwork.showArtwork"))
        assertNull(SettingsRegistry.definition("artwork.unknown"))
    }

    @Test fun pageComposesKnownSettingsInDeclaredOrder() {
        val page = SettingsRegistry.pages.single()
        assertEquals(OptionsCategory.ARTWORK, page.category)
        assertEquals(
            listOf("artwork.automaticLookup", "artwork.showArtwork", "artwork.downloadArtwork"),
            SettingsRegistry.pageSettings(page).map { it.id }
        )
    }

    @Test fun reusedDefinitionHasNoIndependentValueOrPersistence() {
        val descriptor = SettingsRegistry.definition("artwork.showArtwork")!!
        assertEquals(SettingControl.SWITCH, descriptor.control)
        // The descriptor has no state/store accessor: existing media controller retains ownership.
        assertEquals("Show artwork", descriptor.label)
    }
}
