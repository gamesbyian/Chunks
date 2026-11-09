package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationRestorePolicyTest {
    @Test fun onlyPrivacyGatedPhoneAndReadOnlyCombinedCanReopenAfterProcessDeath() {
        for (safe in listOf("LOCAL", "LOCAL_ADD", "COMBINED", "MEDIA", "OPTIONS")) {
            assertEquals(safe, NavigationRestorePolicy.restorableSection(safe))
        }
    }

    @Test fun connectionAndMutationDraftRoutesNeverRestoreAsActions() {
        for (unsafe in listOf("ADD", "CREATE", "SEEDING", "NAMES", "SNAPSHOT",
            "SNAPSHOT_INSPECT", "PLACES", "ORGANIZE", "SERVER", "ARCHIVES",
            "TRANSFERS", "", "NOT_A_ROUTE", "LOCAL;ADD", null)) {
            assertEquals("LOCAL", NavigationRestorePolicy.restorableSection(unsafe))
        }
    }
}
