package io.github.gamesbyian.chunks.seeding

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.FinishInOrderScopes
import io.github.gamesbyian.chunks.FinishInOrderStore
import io.github.gamesbyian.chunks.ProtocolOperationKind
import io.github.gamesbyian.chunks.ProtocolOperationReceiptOwner
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.tickProtocolRemotePolicies
import kotlinx.coroutines.runBlocking
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

    private fun orderRecord() = FinishInOrderRecord(FinishInOrderPlanner.Policy(
        FinishInOrderPlanner.Mode.BALANCED,
        listOf(FinishInOrderPlanner.OrderedFile(0, "series/01.mkv", 100L),
            FinishInOrderPlanner.OrderedFile(1, "series/02.mkv", 100L)), 1))

    @Test fun inactiveOrRetiredProfileNeverCreatesASessionAndActivePoliciesShareOneInventory() = runBlocking {
        val profile = "first"
        val other = "other"
        val hash = "a".repeat(40)
        val store = FinishInOrderStore(context, FinishInOrderScopes.protocol(profile))
        assertTrue(store.save(hash, orderRecord()))
        val client = FakeClient()
        val operations = ClientOperations(client)
        var factories = 0
        suspend fun tick(id: String, ownerCurrent: () -> Boolean) {
            tickProtocolRemotePolicies(context, id, FinishInOrderStore.scheduledPolicies(context), ownerCurrent) {
                factories++
                operations
            }
        }
        tick(other) { true }
        tick(profile) { false }
        assertEquals(0, factories)
        assertEquals(0, client.reads)
        assertEquals(0, client.sessions)

        val environment = RemoteSeeding.protocolEnvironment(profile)
        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(environment, true))
        tick(profile) { true }
        assertEquals(1, factories)
        assertEquals(1, client.sessions)
        assertEquals(1, client.inventories)
    }

    @Test fun legacyPolicyOwnerDiscoveryPreservesExactJournalAndUsesScopedScheduling() {
        val scope = FinishInOrderScopes.protocol("legacy")
        val otherScope = FinishInOrderScopes.protocol("other")
        val hash = "B".repeat(40)
        val prefs = context.getSharedPreferences("chunks_finish_in_order_v1", android.content.Context.MODE_PRIVATE)
        val key = "policy_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest((scope + "\u0000" + hash).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val record = orderRecord().copy(finished = true, pending = listOf(
            FinishInOrderPlanner.Change(0, FinishInOrderPlanner.NORMAL, FinishInOrderPlanner.HIGH)))
        val old = record.encode()
        assertTrue(prefs.edit().putString(key, old).commit())
        assertTrue(FinishInOrderStore.scheduledPolicies(context).mayHaveWork(otherScope))
        val store = FinishInOrderStore(context, scope)
        assertEquals(record, store.get(hash))
        store.rememberOwner(hash)
        assertEquals(old, prefs.getString(key, null))
        assertTrue(FinishInOrderStore.scheduledPolicies(context).mayHaveWork(scope))
        assertFalse(FinishInOrderStore.scheduledPolicies(context).mayHaveWork(otherScope))
        assertTrue(store.update(hash, record.copy(suspendedReason = "Waiting for review")))
        assertEquals(record.pending, FinishInOrderStore(context, scope).get(hash)?.pending)
        assertFalse(FinishInOrderStore.scheduledPolicies(context).mayHaveWork(scope))
        assertTrue(store.remove(hash))
        assertNull(FinishInOrderStore(context, scope).get(hash))
    }

    private class FakeClient : TorrentClient {
        override val kind = TorrentClientKind.QBITTORRENT
        val jobs = LinkedHashMap<String, Boolean>()   // hash -> started
        val facts = HashMap<String, ClientSeedingFacts>()
        var reads = 0
        var inventories = 0
        var sessions = 0
        var stops = 0
        var fileReads = 0
        var afterFacts: () -> Unit = {}
        override suspend fun session(): ClientSessionInfo {
            sessions++
            return ClientSessionInfo(kind, "4.6", "/dl", ClientCapability.entries.toSet())
        }
        override suspend fun torrents(): List<ClientTorrent> {
            reads++
            inventories++
            return jobs.keys.mapNotNull { torrent(it) }
        }
        override suspend fun torrent(hash: String) = jobs[hash]?.let {
            ClientTorrent(hash, hash, if (it) ClientRunState.SEEDING else ClientRunState.STOPPED, 1, 1, 0, 0, "/dl")
        }
        override suspend fun seedingFacts(hash: String): ClientSeedingFacts? {
            reads++
            return facts[hash].also { afterFacts() }
        }
        override suspend fun files(hash: String): List<ClientFile> {
            fileReads++
            return emptyList()
        }
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

    @Test fun aBusyOrUnresolvedManualCommandDefersBothPoliciesWithoutWritingAJournal() = runBlocking {
        val profile = "manual-command"
        val environment = RemoteSeeding.protocolEnvironment(profile)
        val client = FakeClient()
        val ops = ClientOperations(client, 2, 1)
        val owner = ProtocolOperationReceiptOwner()
        val key = ProtocolOperationReceiptOwner.Key(profile, new)
        assertTrue(SeedingRuleStore(context).save(SeedingRuleStore.Snapshot(emptyList(),
            mapOf(environment to SeedingPolicy(ratioGoal = BigDecimal.ONE)))))
        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(environment, true))
        assertEquals(RemoteSeeding.Tick.NO_WRITE,
            RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 2_000, receipts = owner))

        client.jobs[new] = true
        client.facts[new] = ClientSeedingFacts(new, 2_000, 1_000, 60, true, false, emptyList())
        val policy = orderRecord()
        val orders = FinishInOrderStore(context, FinishInOrderScopes.protocol(profile))
        assertTrue(orders.save(new, policy))
        val command = requireNotNull(owner.begin(key, "g1", ProtocolOperationKind.REMOVE))
        assertTrue(owner.possibleWrite(command))
        owner.cancel(command)

        tickProtocolRemotePolicies(context, profile, FinishInOrderStore.scheduledPolicies(context),
            ownerCurrent = { true }, receipts = owner) { ops }
        assertEquals(0, client.stops)
        assertEquals(0, client.fileReads)
        assertEquals(policy, orders.get(new))
        assertNull(PreferencesAssignmentStore(context, environment).get(new)?.stopAttemptedReason)

        assertTrue(owner.reconcile(command, present = true))
        tickProtocolRemotePolicies(context, profile, FinishInOrderStore.scheduledPolicies(context),
            ownerCurrent = { true }, receipts = owner) { ops }
        assertEquals(1, client.stops)
        assertTrue(PreferencesAssignmentStore(context, environment).get(new)?.stoppedByRule != null)
        assertEquals(0, client.fileReads) // Stop retired the active inventory.
    }

    @Test fun disablingEnforcementDuringObservationRevokesThePendingStop() {
        val profile = "revoked-opt-in"
        val environment = RemoteSeeding.protocolEnvironment(profile)
        val client = FakeClient()
        val ops = ClientOperations(client, 2, 1)
        val setting = RemoteSeeding.EnforcementSwitch(context)
        assertTrue(SeedingRuleStore(context).save(SeedingRuleStore.Snapshot(emptyList(),
            mapOf(environment to SeedingPolicy(ratioGoal = BigDecimal.ONE)))))
        assertTrue(setting.set(environment, true))
        RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 2_000)
        client.jobs[new] = true
        client.facts[new] = ClientSeedingFacts(new, 0, 1_000, 60, true, false, emptyList())
        RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 3_000)
        client.facts[new] = ClientSeedingFacts(new, 2_000, 1_000, 60, true, false, emptyList())
        client.afterFacts = { assertTrue(setting.set(environment, false)) }

        assertEquals(RemoteSeeding.Tick.NO_WRITE, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 4_000))
        assertFalse(setting.enabled(environment))
        assertEquals(0, client.stops)
        assertNull(PreferencesAssignmentStore(context, environment).get(new)?.stopAttemptedReason)
    }

    /** The installed switch journey's exact production path: a saved default cap, applied once. */
    @Test fun theSavedDefaultCapIsWrittenOnceOnANewJobThroughTheProductionTick() {
        val client = object : TorrentClient by FakeClient() {
            val caps = HashMap<String, Long>()
            var capWrites = 0
            val inner = FakeClient()
            override val kind = TorrentClientKind.TRANSMISSION
            override suspend fun torrents() = inner.torrents()
            override suspend fun torrent(hash: String) = inner.torrent(hash)
            override suspend fun seedingFacts(hash: String) = inner.seedingFacts(hash)
            override suspend fun uploadCapStepBytes(): Long = 1000L
            override suspend fun uploadCapBytesPerSecond(hash: String): Long = caps[hash] ?: 0L
            override suspend fun setUploadCapBytesPerSecond(hash: String, bytesPerSecond: Long) {
                capWrites++; caps[hash] = bytesPerSecond
            }
        }
        val ops = ClientOperations(client, 2, 1)
        val profile = "tr-cap-profile"
        val environment = RemoteSeeding.protocolEnvironment(profile)
        val capped = "c".repeat(40)
        assertTrue(SeedingRuleStore(context).save(SeedingRuleStore.Snapshot(emptyList(),
            mapOf(environment to SeedingPolicy(uploadLimit = io.github.gamesbyian.chunks.seeding.RateLimit(
                128_000, io.github.gamesbyian.chunks.seeding.RateLimit.Unit.KB))))))
        assertTrue(RemoteSeeding.EnforcementSwitch(context).set(environment, true))
        assertEquals("activation", RemoteSeeding.Tick.NO_WRITE, RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 2_000))
        client.inner.jobs[capped] = false
        client.inner.facts[capped] = ClientSeedingFacts(capped, 0, 0, 0, false, false,
            listOf("http://127.0.0.1:1/announce"))
        RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 3_000)
        assertEquals(128_000L, client.caps[capped])
        RemoteSeeding.tickProtocol(context, profile, ops, nowMs = 4_000)
        assertEquals("never rewritten when it already matches", 1, client.capWrites)
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
