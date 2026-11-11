package io.github.gamesbyian.chunks

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
}
