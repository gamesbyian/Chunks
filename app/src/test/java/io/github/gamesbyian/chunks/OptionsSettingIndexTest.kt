package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Options index only points to existing screens; it holds no values. */
class OptionsSettingIndexTest {
    @Test fun emptySearchShowsNoShortcuts() {
        assertEquals(emptyList<OptionsShortcut>(), OptionsShortcut.search("   "))
    }

    @Test fun commonSettingWordsFindTheirExistingScreen() {
        assertEquals(listOf(OptionsShortcut.SEEDING_RULES), OptionsShortcut.search("seeding ratio"))
        assertEquals(listOf(OptionsShortcut.PHONE_SETTINGS), OptionsShortcut.search("Speed LIMIT"))
        assertEquals(listOf(OptionsShortcut.PHONE_SETTINGS), OptionsShortcut.search("dht"))
        assertEquals(listOf(OptionsShortcut.ENVIRONMENT_COLOURS), OptionsShortcut.search("color"))
        assertEquals(listOf(OptionsShortcut.NAMES), OptionsShortcut.search("rename"))
        assertEquals(listOf(OptionsShortcut.PRIVACY), OptionsShortcut.search("biometric"))
        assertEquals(listOf(OptionsShortcut.MEDIA), OptionsShortcut.search("artwork"))
        assertEquals(listOf(OptionsShortcut.MEDIA), OptionsShortcut.search("tmdb key"))
        assertTrue(OptionsShortcut.MEDIA in OptionsShortcut.search("poster"))
        assertTrue(OptionsShortcut.BACKUP in OptionsShortcut.search("export"))
        assertTrue(OptionsShortcut.INSPECT_BACKUP in OptionsShortcut.search("restore backup"))
    }

    @Test fun prefixesMatchAsPeopleType() {
        assertEquals(listOf(OptionsShortcut.PHONE_SETTINGS), OptionsShortcut.search("spe"))
        assertTrue(OptionsShortcut.PRIVACY in OptionsShortcut.search("priv"))
    }

    @Test fun everyWordMustMatchTheSameSetting() {
        assertEquals(emptyList<OptionsShortcut>(), OptionsShortcut.search("seeding colour"))
        assertEquals(emptyList<OptionsShortcut>(), OptionsShortcut.search("paywall"))
        assertEquals(emptyList<OptionsShortcut>(), OptionsShortcut.search("delete payload"))
    }
}
