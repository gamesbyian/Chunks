package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRestorePolicyTest {
    @Test fun onlyPrivacyGatedPhoneAndReadOnlyCombinedCanReopenAfterProcessDeath() {
        for (safe in listOf("LOCAL", "LOCAL_ADD", "COMBINED", "MEDIA", "OPTIONS", "SERVER")) {
            assertEquals(safe, NavigationRestorePolicy.restorableSection(safe))
        }
    }

    @Test fun operationalMutationDraftRoutesNeverRestoreAsActions() {
        for (unsafe in listOf("ADD", "CREATE", "SEEDING", "NAMES", "SNAPSHOT",
            "SNAPSHOT_INSPECT", "PLACES", "ORGANIZE", "ARCHIVES",
            "TRANSFERS", "", "NOT_A_ROUTE", "LOCAL;ADD", null)) {
            assertEquals("LOCAL", NavigationRestorePolicy.restorableSection(unsafe))
        }
    }
}
