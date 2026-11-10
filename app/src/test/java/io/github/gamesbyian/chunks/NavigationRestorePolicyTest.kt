package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRestorePolicyTest {
    @Test fun canonicalTransfersAndConnectionsAreSafeRootsToRestore() {
        for (safe in listOf("SERVER", "COMBINED", "MEDIA", "OPTIONS", "ADD_CHOOSE")) {
            assertEquals(safe, NavigationRestorePolicy.restorableSection(safe))
        }
    }

    @Test fun oldOwningListsMigrateToCanonicalTransfersAndPhoneAddToUniversalShell() {
        assertEquals("COMBINED", NavigationRestorePolicy.restorableSection("LOCAL"))
        assertEquals("COMBINED", NavigationRestorePolicy.restorableSection("TRANSFERS"))
        assertEquals("ADD_CHOOSE", NavigationRestorePolicy.restorableSection("LOCAL_ADD"))
    }

    @Test fun commandAndUnrecognizedRoutesNeverRestoreAuthority() {
        for (unsafe in listOf("ADD", "CREATE", "SEEDING", "NAMES", "SNAPSHOT",
            "SNAPSHOT_INSPECT", "PLACES", "ORGANIZE", "ARCHIVES",
            "", "NOT_A_ROUTE", "LOCAL;ADD", null)) {
            assertEquals("COMBINED", NavigationRestorePolicy.restorableSection(unsafe))
        }
    }
}
