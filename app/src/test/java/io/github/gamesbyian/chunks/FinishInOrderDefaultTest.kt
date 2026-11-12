package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in Finish in Order default for new Adds (#415): off, per scope, no policy side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishInOrderDefaultTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test fun offUntilChosenAndScopedToOneEnvironment() {
        val local = FinishInOrderStore(context)
        val server = FinishInOrderStore(context, "https://host.example/RPC2\u0000user")
        assertFalse(local.defaultForNewAdds())
        assertTrue(local.setDefaultForNewAdds(true))
        assertTrue(FinishInOrderStore(context).defaultForNewAdds())
        assertFalse(server.defaultForNewAdds())
        assertTrue(local.setDefaultForNewAdds(false))
        assertFalse(FinishInOrderStore(context).defaultForNewAdds())
    }

    @Test fun choosingTheDefaultCreatesNoPolicyOrRequest() {
        val local = FinishInOrderStore(context)
        assertTrue(local.setDefaultForNewAdds(true))
        assertNull(local.get(hash))
        assertTrue(local.afterMetadataRequests().isEmpty())
    }
}
