package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

/** User-pasted URL parser failures must never echo embedded secrets. */
class RemoteEndpointParseRedactionTest {
    @Test fun malformedUriDoesNotExposeCredentialsInDiscoveryOrExactEndpointParsing() {
        val secret = "sensitive-token-123"
        val malformed = listOf(
            "https://alice:$secret@seedbox.example/rutorrent bad",
            "https://seedbox.example/rutorrent bad?access_token=$secret",
            "https://seedbox.example/[bad/$secret",
            "https://seedbox.example/path\u0000$secret"
        )
        for (raw in malformed) {
            for (parse in listOf<(String) -> Any>(
                { RemoteSmartConnect.candidates(it, "alice") },
                { RemoteEndpointHints.rpcAddress(it) }
            )) {
                try {
                    parse(raw)
                    fail("Malformed URL accepted")
                } catch (failure: IllegalArgumentException) {
                    assertFalse(failure.message.orEmpty().contains(secret))
                    assertFalse(failure.message.orEmpty().contains("alice:"))
                    assertFalse(failure.message.orEmpty().contains(raw))
                }
            }
        }
    }

    @Test fun validHostedAccountsAndNumberedInstancesRemainDiscoverable() {
        val address = "https://box.example/account%20one/rutorrent2/"
        val expected = "https://box.example/account%20one/rutorrent2/plugins/httprpc/action.php"
        assertEquals(expected, RemoteEndpointHints.rpcAddress(address))
        assertEquals(listOf(expected),
            RemoteSmartConnect.candidates(address, "alice").map { it.endpoint })
    }
}
