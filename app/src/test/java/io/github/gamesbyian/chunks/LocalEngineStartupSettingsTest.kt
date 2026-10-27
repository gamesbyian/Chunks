package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalRateLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalEngineStartupSettingsTest {
    private val limits = LocalRateLimits(128 * 1024, 64 * 1024)

    private class FakeEngine {
        val events = mutableListOf<String>()
        var started = false
        var closed = false
        var dhtObserved = false
        var limitsObserved = LocalRateLimits()
    }

    private fun open(
        engine: FakeEngine,
        expectedDht: Boolean,
        start: (FakeEngine) -> Unit = { it.events += "start"; it.started = true },
        close: (FakeEngine) -> Unit = { it.events += "close"; it.closed = true }
    ) = LocalEngineStartupSettings.open(
        candidate = engine,
        savedDht = expectedDht,
        savedLimits = limits,
        start = start,
        setDht = { candidate, wanted ->
            candidate.events += "dht"
            candidate.dhtObserved
        },
        setLimits = { candidate, wanted ->
            candidate.events += "limits"
            candidate.limitsObserved
        },
        close = close
    )

    @Test fun bothNativeReadbacksMustSucceedBeforeEngineCanBePublished() {
        val candidate = FakeEngine().apply {
            dhtObserved = true
            limitsObserved = limits
        }
        assertSame(candidate, open(candidate, expectedDht = true))
        assertEquals(listOf("start", "dht", "limits"), candidate.events)
        assertTrue(candidate.started)
        assertFalse(candidate.closed)
    }

    @Test fun savedDhtOffMustAlsoBeConfirmedAndRejectedOnMismatch() {
        val candidate = FakeEngine().apply {
            dhtObserved = true
            limitsObserved = limits
        }
        try {
            open(candidate, expectedDht = false)
            fail("A newly started engine with DHT on must not be published as DHT off")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("DHT"))
        }
        assertEquals(listOf("start", "dht", "close"), candidate.events)
        assertTrue(candidate.closed)
    }

    @Test fun oneMismatchedSpeedDirectionBlocksStartupAndClosesCandidate() {
        val candidate = FakeEngine().apply {
            dhtObserved = false
            limitsObserved = LocalRateLimits(limits.downloadBytesPerSecond, 0)
        }
        try {
            open(candidate, expectedDht = false)
            fail("Native rate-limit mismatch must not publish a session")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("speed"))
        }
        assertEquals(listOf("start", "dht", "limits", "close"), candidate.events)
        assertTrue(candidate.closed)
    }

    @Test fun failedNativeStartupStillAttemptsToCloseCandidate() {
        val candidate = FakeEngine()
        try {
            open(candidate, expectedDht = false, start = {
                it.events += "start"
                throw IllegalStateException("No native session")
            })
            fail("Startup failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("No native session", expected.message)
        }
        assertEquals(listOf("start", "close"), candidate.events)
        assertTrue(candidate.closed)
    }

    @Test fun cleanupExceptionMustNotHideOriginalNativeFailure() {
        val candidate = FakeEngine()
        try {
            open(candidate, expectedDht = true, close = {
                it.events += "close"
                throw IllegalStateException("cleanup failed")
            })
            fail("DHT verification must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("DHT"))
        }
        assertEquals(listOf("start", "dht", "close"), candidate.events)
    }
}
