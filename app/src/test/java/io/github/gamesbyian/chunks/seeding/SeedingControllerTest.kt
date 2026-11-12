package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.seeding.SeedingController.Assignment
import io.github.gamesbyian.chunks.seeding.SeedingController.Facts
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class SeedingControllerTest {
    private class FakeEngine : SeedingController.EnginePort {
        val facts = LinkedHashMap<String, Facts>()
        val observed = HashMap<String, SeedingObservation>()
        val limits = HashMap<String, Int>()
        val paused = HashSet<String>()
        var pauseConfirms = true
        val log = ArrayList<String>()
        override fun transfers() = facts.values.toList()
        override fun observe(infoHash: String) = observed[infoHash]
        override fun uploadLimit(infoHash: String) = limits[infoHash] ?: 0
        override fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean {
            log += "cap $infoHash=${bytesPerSecond ?: 0}"; limits[infoHash] = (bytesPerSecond ?: 0).toInt(); return true
        }
        override fun pause(infoHash: String): Boolean { log += "pause $infoHash"; if (pauseConfirms) paused += infoHash; return pauseConfirms }
    }
    private class MemStore : SeedingController.AssignmentStore {
        var epochMs: Long? = null
        val map = HashMap<String, Assignment>()
        override fun epoch() = epochMs
        override fun setEpoch(ms: Long): Boolean { epochMs = ms; return true }
        override fun get(infoHash: String) = map[infoHash]
        override fun put(infoHash: String, assignment: Assignment): Boolean { map[infoHash] = assignment; return true }
    }

    private val capped = SeedingPolicy(ratioGoal = BigDecimal("1.00"), uploadLimit = RateLimit.parse("24 KiB/s").getOrThrow())
    private val rule = SeedingRule("r", "Example · Ratio 1.00", false, setOf("tracker.example"), policy = capped,
        environments = setOf("local"))

    @Test fun onlyTransfersAddedAfterEnablingAreManagedAndRuleEditsNeverRewriteThem() {
        val engine = FakeEngine(); val store = MemStore()
        engine.facts["old"] = Facts("old", 500, listOf("https://tracker.example/a"), false)
        engine.facts["new"] = Facts("new", 2_000, listOf("https://tracker.example/a"), false)
        store.epochMs = 1_000
        val controller = SeedingController(engine, store)
        controller.tick("local", listOf(rule), SeedingPolicy(), nowMs = 3_000)
        assertNull("existing transfers are never managed", store.map.getValue("old").policy)
        assertEquals(capped, store.map.getValue("new").policy)
        assertEquals(listOf("cap new=24576"), engine.log)
        // Editing the rule later does not change the snapshot.
        controller.tick("local", listOf(rule.copy(policy = SeedingPolicy())), SeedingPolicy(), nowMs = 4_000)
        assertEquals(capped, store.map.getValue("new").policy)
    }

    @Test fun firstRunSetsTheEpochSoEverythingAlreadyThereIsLeftAlone() {
        val engine = FakeEngine(); val store = MemStore()
        engine.facts["a"] = Facts("a", 9_000, listOf("https://tracker.example/a"), false)
        SeedingController(engine, store).tick("local", listOf(rule), SeedingPolicy(), nowMs = 10_000)
        assertEquals(10_000L, store.epochMs)
        assertNull(store.map.getValue("a").policy)
        assertTrue(engine.log.isEmpty())
    }

    @Test fun ratioStopPausesOnceAndAManualResumeIsRespected() {
        val engine = FakeEngine(); val store = MemStore().apply { epochMs = 0 }
        engine.facts["t"] = Facts("t", 10, listOf("https://tracker.example/a"), false)
        engine.observed["t"] = SeedingObservation(uploadedBytes = 1_000, downloadedBytes = 1_000, seedingSeconds = 60, seeding = true)
        val controller = SeedingController(engine, store)
        controller.tick("local", listOf(rule), SeedingPolicy(), nowMs = 100)
        assertTrue("t" in engine.paused)
        assertNotNull(store.map.getValue("t").stoppedByRule)
        // The user resumes it: the rule never pauses it again.
        engine.paused.clear(); engine.log.clear()
        controller.tick("local", listOf(rule), SeedingPolicy(), nowMs = 200)
        assertTrue(engine.log.none { it.startsWith("pause") })
    }

    @Test fun trackerBeforePrivateFlagCannotPermanentlyFreezeWrongPolicy() {
        val engine = FakeEngine()
        val store = MemStore().apply { epochMs = 0 }
        val controller = SeedingController(engine, store)
        val privateRule = rule.copy(privateOnly = true)
        val facts = Facts("same-hash", 10, listOf("https://tracker.example/a"), null)
        engine.facts[facts.infoHash] = facts
        controller.tick("local", listOf(privateRule), SeedingPolicy(), nowMs = 100)
        assertNull("Private-only rule must await flag evidence", store.get(facts.infoHash))
        assertTrue("No cap, pause or other engine write while private status is unknown", engine.log.isEmpty())
        // The same observed tracker now comes with a verified private flag.
        engine.facts[facts.infoHash] = facts.copy(isPrivate = true)
        controller.tick("local", listOf(privateRule), SeedingPolicy(), nowMs = 200)
        assertEquals(SeedingResolution.Source.RULE, store.get(facts.infoHash)?.source)
        assertEquals(capped, store.get(facts.infoHash)?.policy)
        assertEquals(listOf("cap same-hash=24576"), engine.log)
        // Existing policy snapshots still remain fixed across later edits.
        controller.tick("local", listOf(privateRule.copy(policy = SeedingPolicy())),
            SeedingPolicy(), nowMs = 300)
        assertEquals(capped, store.get(facts.infoHash)?.policy)
    }

    @Test fun verifiedPublicFlagAllowsDefaultAfterPrivateRuleDoesNotMatch() {
        val engine = FakeEngine()
        val store = MemStore().apply { epochMs = 0 }
        val privateRule = rule.copy(privateOnly = true)
        val facts = Facts("public-hash", 10, listOf("https://tracker.example/a"), null)
        engine.facts[facts.infoHash] = facts
        val controller = SeedingController(engine, store)
        controller.tick("local", listOf(privateRule), capped, nowMs = 100)
        assertNull(store.get(facts.infoHash))
        engine.facts[facts.infoHash] = facts.copy(isPrivate = false)
        controller.tick("local", listOf(privateRule), capped, nowMs = 200)
        assertEquals(SeedingResolution.Source.DEFAULT, store.get(facts.infoHash)?.source)
        assertEquals(capped, store.get(facts.infoHash)?.policy)
    }

    @Test fun unconfirmedPauseIsNotedAndMagnetsWaitForMetadata() {
        val engine = FakeEngine().apply { pauseConfirms = false }; val store = MemStore().apply { epochMs = 0 }
        engine.facts["m"] = Facts("m", 10, null, null)
        engine.facts["t"] = Facts("t", 10, listOf("https://tracker.example/a"), false)
        engine.observed["t"] = SeedingObservation(2_000, 1_000, 60, true)
        SeedingController(engine, store).tick("local", listOf(rule), SeedingPolicy(), nowMs = 100)
        assertNull("no snapshot before metadata", store.map["m"])
        assertNull(store.map.getValue("t").stoppedByRule)
        assertTrue(store.map.getValue("t").note!!.contains("did not confirm"))
    }
}
