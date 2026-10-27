package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed Android native libtorrent smoke for startup setting readback.
 * Loopback-only listening, DHT disabled, no torrent payload or remote host.
 * Does not establish device/process lifecycle or Internet peer discovery.
 */
@RunWith(AndroidJUnit4::class)
class LocalEngineStartupSettingsInstrumentedTest {
    @Test fun savedDhtOffAndNonzeroRateLimitsReadBackBeforePublication() {
        val candidate = LocalTorrentEngine(LocalEngineConfig(
            listenInterfaces = "127.0.0.1:0", enableDht = false))
        val requested = LocalRateLimits(128 * 1024, 64 * 1024)
        var accepted: LocalTorrentEngine? = null
        try {
            accepted = LocalEngineStartupSettings.open(
                candidate = candidate,
                savedDht = false,
                savedLimits = requested,
                start = { it.start() },
                setDht = { engine, wanted -> engine.setDhtEnabled(wanted) },
                setLimits = { engine, limits -> engine.setRateLimits(limits) },
                close = { it.close() }
            )
            assertSame(candidate, accepted)
            assertFalse(accepted.isDhtEnabled())
            assertEquals(requested, accepted.rateLimits())
        } finally {
            // Safe if start verification already closed the candidate.
            candidate.close()
        }
    }
}
