package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRestorePolicyTest {
    @Test fun canonicalTransfersConnectionsAndReadOnlyOptionsRestoreSafely() {
        for (safe in listOf("SERVER", "COMBINED", "MEDIA", "OPTIONS", "ADD_CHOOSE")) {
            assertEquals(safe, NavigationRestorePolicy.restorableSection(safe))
        }
    }

    @Test fun oldLocalAndRemoteListsMigrateToOneReadOnlyCanonicalCatalog() {
        for (old in listOf("LOCAL", "TRANSFERS")) {
            assertEquals("COMBINED", NavigationRestorePolicy.restorableSection(old))
        }
    }

    @Test fun oldLocalAddRestoresOnlyEmptyUniversalDestinationShell() {
        assertEquals("ADD_CHOOSE", NavigationRestorePolicy.restorableSection("LOCAL_ADD"))
    }

    @Test fun commandAndUnrecognizedRoutesNeverRestoreAsActions() {
        for (unsafe in listOf("ADD", "CREATE", "SEEDING", "NAMES", "SNAPSHOT",
            "SNAPSHOT_INSPECT", "PLACES", "ORGANIZE", "ARCHIVES",
            "", "NOT_A_ROUTE", "LOCAL;ADD", null)) {
            assertEquals("COMBINED", NavigationRestorePolicy.restorableSection(unsafe))
        }
    }
    @Test fun restoredCombinedDelegatesOnlyOneAuthorizedColdReadOnlyReconnect() {
        fun needs(section: String = "COMBINED", saved: Boolean = true,
                  remembering: Boolean = true, attempted: Boolean = false,
                  authenticated: Boolean = false) =
            NavigationRestorePolicy.requiresColdReconnectHandoff(
                section, saved, remembering, attempted, authenticated)
        assertEquals(true, needs())
        assertEquals(false, needs(section = "SERVER"))
        assertEquals(false, needs(section = "OPTIONS"))
        assertEquals(false, needs(section = "ADD_CHOOSE"))
        assertEquals(false, needs(saved = false))
        assertEquals(false, needs(remembering = false))
        assertEquals(false, needs(attempted = true))
        assertEquals(false, needs(authenticated = true))
        // The restored section alone is never an authenticated principal.
        assertEquals("COMBINED", NavigationRestorePolicy.restorableSection("TRANSFERS"))
    }

}
