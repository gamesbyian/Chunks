package io.github.gamesbyian.chunks.discovery

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscoveryGateTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun gate() = DiscoveryGate(DiscoveryGate.PreferencesStore(context))

    @Test fun offUntilTheUserEnablesItAndThatSurvivesRestart() {
        val fresh = gate()
        assertFalse(fresh.enabled)
        assertNull("no discovery work while off", fresh.begin())
        assertTrue(fresh.setEnabled(true))
        assertTrue(gate().enabled)
        assertTrue(gate().setEnabled(false))
        assertFalse(gate().enabled)
    }

    @Test fun enablingSearchNeverEnablesAutomaticIntakeOrAutoStart() {
        val g = gate()
        assertTrue(g.setEnabled(true))
        assertFalse(g.automaticIntakeAllowed)
        assertFalse(g.autoStartAllowed)
        assertTrue(g.setAutoStart(true))
        assertFalse("auto-start needs intake first", g.autoStartAllowed)
        assertTrue(g.setAutomaticIntake(true)); assertTrue(g.setAutoStart(true))
        assertTrue(g.autoStartAllowed)
        // Off prevails over every saved sub-setting, which is kept for later.
        assertTrue(g.setEnabled(false))
        assertFalse(g.automaticIntakeAllowed); assertFalse(g.autoStartAllowed)
        assertTrue(g.settings.automaticIntake)
        assertTrue(g.setEnabled(true))
        assertTrue(g.autoStartAllowed)
        // Turning intake off also turns auto-start off.
        assertTrue(g.setAutomaticIntake(false))
        assertFalse(g.settings.autoStartAfterIntake)
    }

    @Test fun disablingInvalidatesInFlightWorkEvenAfterReEnabling() {
        val g = gate().apply { setEnabled(true) }
        val ticket = requireNotNull(g.begin())
        assertTrue(g.stillAllowed(ticket))
        g.setEnabled(false)
        assertFalse(g.stillAllowed(ticket))
        g.setEnabled(true)
        assertFalse("an old ticket never comes back to life", g.stillAllowed(ticket))
        assertTrue(g.stillAllowed(requireNotNull(g.begin())))
    }

    @Test fun aFailedSaveKeepsTheOldStateButStillStopsInFlightWork() {
        var allowWrites = true
        val store = object : DiscoveryGate.Store {
            override fun read() = DiscoveryGate.Settings(enabled = true)
            override fun write(settings: DiscoveryGate.Settings) = allowWrites
        }
        val g = DiscoveryGate(store)
        val ticket = requireNotNull(g.begin())
        allowWrites = false
        assertFalse(g.setEnabled(false))
        assertFalse("work in flight still stops", g.stillAllowed(ticket))
        assertTrue(g.enabled)
    }

    @Test fun noDispatchSlipsThroughAfterDisableUnderConcurrency() {
        val g = gate().apply { setEnabled(true) }
        val pool = Executors.newFixedThreadPool(8)
        val afterDisable = AtomicInteger()
        val disabled = CountDownLatch(1)
        val tickets = (1..200).map { requireNotNull(g.begin()) }
        repeat(200) { i ->
            pool.execute {
                if (i == 50) { g.setEnabled(false); disabled.countDown() }
                if (disabled.count == 0L && g.stillAllowed(tickets[i])) afterDisable.incrementAndGet()
            }
        }
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(0, afterDisable.get())
    }
}
