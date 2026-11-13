package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Authorization is bound to one actual authenticated route + principal, not a saved label. */
class UniversalAddDestinationPickerTest {
    @Test fun onlyExactAuthenticatedAccountCanBeOfferedForRemoteAdd() {
        val authenticated = "https://seed.example/instances/2/RPC2\u0000alice"
        assertTrue(eligibleUniversalAddRemote(authenticated, authenticated))
        assertFalse(eligibleUniversalAddRemote(null, authenticated))
        assertFalse(eligibleUniversalAddRemote("", authenticated))
        assertFalse(eligibleUniversalAddRemote(authenticated, null))
        assertFalse(eligibleUniversalAddRemote(authenticated, ""))
        assertFalse(eligibleUniversalAddRemote(authenticated,
            "https://seed.example/instances/3/RPC2\u0000alice"))
        assertFalse(eligibleUniversalAddRemote(authenticated,
            "https://seed.example/instances/2/RPC2\u0000bob"))
    }
    @Test fun duplicateSavedNamesAreDisambiguatedWithoutChangingTheirIdentity() {
        val (active, saved) = compactUniversalAddTargetLabels("Wood", listOf(
            UniversalAddSavedDestination("server-2", "wood"),
            UniversalAddSavedDestination("server-3", "Other"),
            UniversalAddSavedDestination("server-4", "Wood")
        ))
        assertEquals("Wood (1)", active)
        assertEquals(listOf("wood (2)", "Other", "Wood (3)"), saved.map { it.label })
        assertEquals(listOf("server-2", "server-3", "server-4"), saved.map { it.id })
    }

    @Test fun missingCustomNameUsesShortGenericLabelWithoutExposingEndpoint() {
        val (active, saved) = compactUniversalAddTargetLabels("   ", listOf(
            UniversalAddSavedDestination("server-2", " "),
            UniversalAddSavedDestination("server-3", "Seedbox")
        ))
        assertEquals("rTorrent (1)", active)
        assertEquals(listOf("rTorrent (2)", "Seedbox"), saved.map { it.label })
        assertFalse(saved.any { it.label.contains("https:") })
        val (none, onlySaved) = compactUniversalAddTargetLabels(null,
            listOf(UniversalAddSavedDestination("remote-a", "Wood")))
        assertEquals(null, none)
        assertEquals("Wood", onlySaved.single().label)
    }

}
