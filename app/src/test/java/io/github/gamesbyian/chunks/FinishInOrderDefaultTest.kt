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
        val phone = FinishInOrderStore(context)
        val server = FinishInOrderStore(context, "https://host.example/RPC2\u0000user")
        assertFalse(phone.defaultForNewAdds())
        assertTrue(phone.setDefaultForNewAdds(true))
        assertTrue(FinishInOrderStore(context).defaultForNewAdds())
        assertFalse(server.defaultForNewAdds())
        assertTrue(phone.setDefaultForNewAdds(false))
        assertFalse(FinishInOrderStore(context).defaultForNewAdds())
    }

    @Test fun choosingTheDefaultCreatesNoPolicyOrRequest() {
        val phone = FinishInOrderStore(context)
        assertTrue(phone.setDefaultForNewAdds(true))
        assertNull(phone.get(hash))
        assertTrue(phone.afterMetadataRequests().isEmpty())
    }
}
