package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class RememberedProfileAddressTest {
    @Test fun addressKeepsHostPortAndInstancePathButNeverScheme() {
        assertEquals("box.example/rutorrent2/plugins/httprpc/action.php",
            RememberedRemoteProfileRoster.addressOf("https://box.example/rutorrent2/plugins/httprpc/action.php"))
        assertEquals("box.example:9443/RPC2", RememberedRemoteProfileRoster.addressOf(" https://box.example:9443/RPC2 "))
        assertEquals("rTorrent server", RememberedRemoteProfileRoster.addressOf("https://bad host/RPC2"))
    }
}
