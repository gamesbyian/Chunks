package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility.Decision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FLAG_SECURE for combined Transfers: any shown protected scope, including typed clients. */
class CombinedSecureWindowTest {
    private val shownProtected = Decision(mayShow = true, needsSecureWindow = true)
    private val locked = Decision(mayShow = false, needsSecureWindow = true)
    private val unprotected = Decision(mayShow = true, needsSecureWindow = false)

    private fun required(local: Decision? = unprotected, server: Decision? = null,
                         saved: List<Decision> = emptyList(), protocol: List<Decision> = emptyList()) =
        CombinedSecureWindow.required(local, server, saved, protocol)

    @Test fun nothingProtectedShownNeedsNoFlag() {
        assertFalse(required())
        assertFalse("locked scopes show nothing", required(local = locked, server = locked,
            saved = listOf(locked), protocol = listOf(locked)))
    }

    @Test fun eachShownProtectedScopeRequiresTheFlag() {
        assertTrue(required(local = shownProtected))
        assertTrue(required(server = shownProtected))
        assertTrue(required(saved = listOf(shownProtected)))
    }

    @Test fun anUnlockedProtectedTypedClientRequiresTheFlag() {
        // Regression: typed Transmission/qBittorrent/Deluge scopes were not considered, so their
        // protected rows could appear in screenshots and the app switcher.
        assertTrue(required(protocol = listOf(unprotected, shownProtected)))
        assertFalse(required(protocol = listOf(unprotected, locked)))
    }
}
