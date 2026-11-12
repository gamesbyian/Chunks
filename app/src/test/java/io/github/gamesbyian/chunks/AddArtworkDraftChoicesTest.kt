package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-only draft state. No installed engine, account or provider contact. */
class AddArtworkDraftChoicesTest {
    @Test fun localAndEachAuthenticatedRouteHaveIndependentDefaults() {
        val choices = AddArtworkDraftChoices()
        val local = choices.onLocal()
        val first = choices.onServer("https://example.invalid/rutorrent/plugins/httprpc/action.php\u0000alice")
        val second = choices.onServer("https://example.invalid/rutorrent/plugins/httprpc/action.php\u0000bob")
        val numbered = choices.onServer("https://example.invalid/rutorrent2/plugins/httprpc/action.php\u0000alice")

        assertTrue(local.value)
        assertTrue(first.value)
        assertTrue(second.value)
        assertTrue(numbered.value)
        local.value = false
        assertFalse(choices.onLocal().value)
        assertTrue(first.value)
        first.value = false
        assertTrue(second.value)
        assertTrue(numbered.value)
        assertSame(first, choices.onServer("https://example.invalid/rutorrent/plugins/httprpc/action.php\u0000alice"))
        assertFalse(choices.onServer("https://example.invalid/rutorrent/plugins/httprpc/action.php\u0000alice").value)
    }

    @Test fun confirmedAddResetsOnlyItsOwnDraftChoice() {
        val choices = AddArtworkDraftChoices()
        val local = choices.onLocal()
        val remote = choices.onServer("https://seed.invalid/RPC2\u0000exact-principal")
        local.value = false
        remote.value = false
        remote.value = true // Existing Add screen resets the completed draft.
        assertFalse(local.value)
        assertTrue(remote.value)
    }

    @Test fun noServerIdentityCannotClaimArtworkChoice() {
        val choices = AddArtworkDraftChoices()
        val refused = runCatching { choices.onServer("") }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertTrue(choices.onLocal().value)
    }
}
