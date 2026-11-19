package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.seeding.SeedingController.Assignment
import io.github.gamesbyian.chunks.seeding.SeedingController.Facts
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared controller with an rTorrent-shaped port: no seed-time counter,
 * no per-transfer cap, tracker facts read lazily, activation baseline.
 */
class RemoteSeedingControllerTest {
    private class RTorrentLikePort(var baseline: Boolean) : SeedingController.EnginePort {
        val hashes = ArrayList<String>()
        val privateHashes = HashSet<String>()
        val observed = HashMap<String, SeedingObservation>()
        val detailReads = ArrayList<String>()
        val log = ArrayList<String>()
        var confirmPause = true
        override val supportsPerTransferUploadCap get() = false
        override fun transfers() = hashes.map { Facts(it, if (baseline) 0L else 5_000L, null, null) }
        override fun details(facts: Facts): Facts {
            detailReads += facts.infoHash
            return if (facts.infoHash in privateHashes) facts.copy(trackerUrls = emptyList(), isPrivate = true)
            else facts.copy(trackerUrls = listOf("https://tracker.example.org/announce"), isPrivate = false)
        }
        override fun observe(infoHash: String) = observed[infoHash]
        override fun uploadLimit(infoHash: String): Int? { log += "cap-read"; return null }
        override fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean { log += "cap-write"; return false }
        override fun pause(infoHash: String): Boolean { log += "stop $infoHash"; return confirmPause }
    }

    private class MemStore : SeedingController.AssignmentStore {
        var epochMs: Long? = null
        val map = HashMap<String, Assignment>()
        override fun epoch() = epochMs
        override fun setEpoch(ms: Long): Boolean { epochMs = ms; return true }
        override fun get(infoHash: String) = map[infoHash]
        override fun put(infoHash: String, assignment: Assignment): Boolean { map[infoHash] = assignment; return true }
    }

    private val env = "remote:exact-account"
    private val ratioOne = SeedingPolicy(ratioGoal = BigDecimal.ONE)
    private val rule = SeedingRule("r1", "Example", false, setOf("example.org"), policy = ratioOne,
        environments = setOf(env))

    @Test fun theActivationTickLeavesEveryExistingServerTransferUnmanagedForGood() {
        val port = RTorrentLikePort(baseline = true).apply {
            hashes += "old"; observed["old"] = SeedingObservation(5_000, 1_000, null, true)
        }
        val store = MemStore()
        SeedingController(port, store).tick(env, listOf(rule), ratioOne, nowMs = 1_000)
        assertNull("pre-existing transfers get no policy", store.map.getValue("old").policy)
        port.baseline = false
        SeedingController(port, store).tick(env, listOf(rule), ratioOne, nowMs = 9_000)
        assertTrue("an old transfer is never stopped, even when it meets the goal", port.log.none { it.startsWith("stop") })
        assertEquals("no tracker read for a transfer that is never managed", emptyList<String>(), port.detailReads)
    }

    @Test fun aNewTransferMeetingItsRatioIsStoppedOnceWithAWriteAheadMarker() {
        val port = RTorrentLikePort(baseline = true)
        val store = MemStore()
        SeedingController(port, store).tick(env, listOf(rule), SeedingPolicy(), nowMs = 1_000)
        port.baseline = false
        port.hashes += "new"
        port.observed["new"] = SeedingObservation(2_000, 1_000, seedingSeconds = null, seeding = true)
        repeat(3) { SeedingController(port, store).tick(env, listOf(rule), SeedingPolicy(), nowMs = 9_000) }
        assertEquals(listOf("stop new"), port.log.filter { it.startsWith("stop") })
        val a = store.map.getValue("new")
        assertEquals("r1", a.ruleId)
        assertTrue(a.stoppedByRule!!.startsWith("Ratio 2.00 reached the goal of 1.00"))
        assertEquals("tracker facts read once, for the unassigned transfer only", listOf("new"), port.detailReads)
    }

    @Test fun anUnconfirmedRemoteStopIsNeverRetried() {
        val port = RTorrentLikePort(baseline = false).apply {
            hashes += "new"; observed["new"] = SeedingObservation(2_000, 1_000, null, true); confirmPause = false
        }
        val store = MemStore().apply { epochMs = 1_000 }
        repeat(3) { SeedingController(port, store).tick(env, listOf(rule), SeedingPolicy(), nowMs = 9_000) }
        assertEquals(1, port.log.count { it.startsWith("stop") })
        val a = store.map.getValue("new")
        assertTrue(a.stopAttemptedReason != null && a.stoppedByRule == null)
        assertTrue(a.note!!.contains("will not retry"))
    }

    @Test fun timeConditionsAndUploadCapsAreNeverJudgedOrEmulatedOnRTorrent() {
        val timed = SeedingPolicy(maxSeedSeconds = 60, uploadLimit = RateLimit(1_024, RateLimit.Unit.KIB))
        val port = RTorrentLikePort(baseline = false).apply {
            hashes += "t"; observed["t"] = SeedingObservation(9_000, 1_000, null, true)
        }
        val store = MemStore().apply { epochMs = 1_000 }
        SeedingController(port, store).tick(env, emptyList(), timed, nowMs = 9_000)
        assertFalse("unknown seeding time never stops", port.log.any { it.startsWith("stop") })
        assertFalse("no shared-throttle emulation", port.log.any { it.startsWith("cap") })
        assertTrue(store.map.getValue("t").note!!.contains("no per-transfer upload limit"))
        val decision = SeedingEnforcement.decide(timed, SeedingObservation(9_000, 1_000, null, true))
        assertTrue(decision is SeedingEnforcement.Decision.CannotJudge)
    }

    @Test fun aPrivateServerTransferTakesOnlyTheServerDefault() {
        val port = RTorrentLikePort(baseline = false).apply {
            hashes += "p"; privateHashes += "p"; observed["p"] = SeedingObservation(9_000, 1_000, null, true)
        }
        val store = MemStore().apply { epochMs = 1_000 }
        SeedingController(port, store).tick(env, listOf(rule), SeedingPolicy(), nowMs = 9_000)
        val a = store.map.getValue("p")
        assertEquals(SeedingResolution.Source.DEFAULT, a.source)
        assertFalse("no domain rule can match without reading a passkey-bearing URL", port.log.any { it.startsWith("stop") })
    }

    @Test fun ratioWithUnknownTimeStillDecidesAnyButNeverAll() {
        val obs = SeedingObservation(2_000, 1_000, null, true)
        val any = SeedingPolicy(ratioGoal = BigDecimal.ONE, maxSeedSeconds = 60, combine = SeedingPolicy.Combine.ANY)
        assertTrue(SeedingEnforcement.decide(any, obs) is SeedingEnforcement.Decision.Stop)
        val all = any.copy(combine = SeedingPolicy.Combine.ALL)
        assertTrue(SeedingEnforcement.decide(all, obs) is SeedingEnforcement.Decision.CannotJudge)
        val minimum = SeedingPolicy(ratioGoal = BigDecimal.ONE, minSeedSeconds = 60)
        assertTrue(SeedingEnforcement.decide(minimum, obs) is SeedingEnforcement.Decision.CannotJudge)
    }
}
