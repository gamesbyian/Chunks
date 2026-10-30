package io.github.gamesbyian.chunks.rpc

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/**
 * DNS labels and seedbox account-path hints are protocol identifiers.
 * They must not change when the Android device uses Turkish casing rules.
 * No network connections or credentials are involved.
 */
class RemoteProviderLocaleSafetyTest {
    @Test fun uppercaseDnsNamesUseLocaleInvariantProviderIdentification() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("feral", RemoteProviderCatalog.match("APOLLO.FERALHOSTING.COM")?.id)
            assertEquals("rapidseedbox", RemoteProviderCatalog.match("RT-EXAMPLE.A.SEEDBOX.VIP")?.id)
            assertNull(RemoteProviderCatalog.match("FERALHOSTING.COM.EVIL.EXAMPLE"))
            val hint = RemoteProviderCatalog.hint("https://APOLLO.FERALHOSTING.COM/")
            assertTrue(hint.orEmpty().contains("Feral Hosting"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun hostAccountPrefixPlanningDoesNotChangeWithPhoneLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val actual = RemoteProviderCatalog.plan("IANSEEDS.WOOD.SEEDHOST.EU", "IANSEEDS")
                .map { it.first }
            assertEquals(listOf(
                "/rutorrent/plugins/httprpc/action.php",
                "/RPC1",
                "/IANSEEDS/rutorrent/plugins/httprpc/action.php",
                "/IANSEEDS/RPC1",
                "/rutorrent/plugins/httprpc/action.php",
                "/RPC1"
            ), actual)
            // None of these paths grants authority. Smart Connect must still
            // authenticate and verify the exact candidate's actual torrent view.
            assertTrue(actual.all { it.startsWith("/") && ".." !in it })
        } finally {
            Locale.setDefault(previous)
        }
    }
}
