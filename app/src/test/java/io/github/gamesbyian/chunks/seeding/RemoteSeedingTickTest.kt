package io.github.gamesbyian.chunks.seeding

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSeedingFacts
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production RemoteSeeding.tickProtocol gate and its persisted per-account
 * journal, with a fake client (no native code): off reads nothing; on, the
 * activation tick leaves existing jobs alone, a new job meeting its ratio is
 * stopped once, and the journal survives a fresh store instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteSeedingTickTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeClient : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        val jobs = LinkedHashMap<String, Boolean>()   // hash -> started
        val facts = HashMap<String, ClientSeedingFacts>()
        var reads = 0
        var stops = 0
        override suspend fun session() = ClientSessionInfo(kind, "4.6", "/dl", ClientCapability.entries.toSet())
        override suspend fun torrents(): List<ClientTorrent> { reads++; return jobs.keys.mapNotNull { torrent(it) } }
        override suspend fun torrent(hash: String) = jobs[hash]?.let {
            ClientTorrent(hash, hash, if (it) ClientRunState.SEEDING else ClientRunState.STOPPED, 1, 1, 0, 0, "/dl")
        }
        override suspend fun seedingFacts(hash: String): ClientSeedingFacts? { reads++; return facts[hash] }
        override suspend fun files(hash: String) = emptyList<ClientFile>()
        override suspend fun add(request: ClientAddRequest) = Unit
        override suspend fun setStarted(hash: String, started: Boolean) { if (!started) stops++; jobs[hash] = started }
        override suspend fun remove(hash: String, deleteData: Boolean) = Unit
        override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = Unit
        override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) = Unit
        override suspend fun recheck(hash: String) = Unit
    }

    private val old = "a".repeat(40)
    private val new = "b".repeat(40)

    @Test fun offReadsNothingAndOnStopsOnlyANewJobOnceWithAPersistedJournal() {
        val client = FakeClient().apply {
            jobs[old] = true
            facts[old] = ClientSeedingFacts(old, 9_000, 1_000, 100, true, false, emptyList())
        }
        val ops = ClientOperations(client, readbackAttempts = 2, readbackDelayMillis = 1)
        val profile = "qb-exact-profile"
        val environment = RemoteSeeding.protocolEnvironment(profile)
        assertTrue(SeedingRuleStore(context).save(SeedingRuleStore.Snapshot(emptyList(),
            mapOf(environment to SeedingPolicy(ratioGoal = BigDecimal.ONE)))))

        assertEquals(RemoteSeeding.Tick.NOT_RUN, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 1_000))
        assertEquals("off: not even a read", 0, client.reads)

        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(environment, true))
        assertEquals(RemoteSeeding.Tick.NO_WRITE, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 2_000))
        assertNull("existing job unmanaged", PreferencesAssignmentStore(context, environment).get(old)?.policy)

        client.jobs[new] = true
        client.facts[new] = ClientSeedingFacts(new, 2_000, 1_000, 60, true, false,
            listOf("https://tracker.example.org/announce"))
        assertEquals(RemoteSeeding.Tick.STOP_ATTEMPTED, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 3_000))
        assertEquals(1, client.stops)
        // A fresh store instance (as after process death) still holds the journal.
        val reloaded = PreferencesAssignmentStore(context, environment)
        assertTrue(reloaded.get(new)?.stoppedByRule != null)
        assertNull(reloaded.get(old)?.stoppedByRule)

        client.jobs[new] = true // the person starts it again
        repeat(2) { assertEquals(RemoteSeeding.Tick.NO_WRITE, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 4_000)) }
        assertEquals("never stopped twice", 1, client.stops)
        assertEquals(true, client.jobs[new])
        assertFalse("another account's switch is independent",
            RemoteSeeding.EnforcementSwitch(context).enabled(RemoteSeeding.protocolEnvironment("other")))
    }

    @Test fun aPrivateOrUnprovenTransferNeverHasItsTrackersUsed() {
        val client = FakeClient()
        val port = ProtocolSeedingPort(ClientOperations(client, 2, 1), activationBaseline = false)
        client.facts[new] = ClientSeedingFacts(new, 1, 1, 1, true, isPrivate = null,
            publicTrackerUrls = listOf("https://tracker.example.org/announce?passkey=secret"))
        val described = port.details(SeedingController.Facts(new, 0, null, null))
        assertEquals("unknown privacy is treated as private", emptyList<String>(), described.trackerUrls)
        assertEquals(true, described.isPrivate)
    }
}
