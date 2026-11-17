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
        val throwOnPause = HashSet<String>()
        val throwOnCap = HashSet<String>()
        val log = ArrayList<String>()
        override fun transfers() = facts.values.toList()
        override fun observe(infoHash: String) = observed[infoHash]
        override fun uploadLimit(infoHash: String) = limits[infoHash] ?: 0
        override fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean {
            if (infoHash in throwOnCap) throw IllegalArgumentException("engine cannot apply this cap")
            log += "cap $infoHash=${bytesPerSecond ?: 0}"
            limits[infoHash] = (bytesPerSecond ?: 0).toInt()
            return true
        }
        override fun pause(infoHash: String): Boolean {
            log += "pause $infoHash"
            if (infoHash in throwOnPause) throw IllegalStateException("lost readback")
            if (pauseConfirms) paused += infoHash
            return pauseConfirms
        }
    }
    private class MemStore : SeedingController.AssignmentStore {
        var epochMs: Long? = null
        var refuseStopAttemptCommit = false
        val map = HashMap<String, Assignment>()
        override fun epoch() = epochMs
        override fun setEpoch(ms: Long): Boolean { epochMs = ms; return true }
        override fun get(infoHash: String) = map[infoHash]
        override fun put(infoHash: String, assignment: Assignment): Boolean {
            if (refuseStopAttemptCommit && assignment.stopAttemptedReason != null) return false
            map[infoHash] = assignment
            return true
        }
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
    @Test fun unconfirmedStopIsJournaledAndNeverReissuedAfterControllerRecreation() {
        val engine = FakeEngine().apply { pauseConfirms = false }
        val store = MemStore().apply { epochMs = 0 }
        engine.facts["uncertain"] = Facts("uncertain", 10, emptyList(), false)
        engine.observed["uncertain"] = SeedingObservation(3_000, 1_000, 60, true)
        store.map["uncertain"] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 100)
        val first = store.map.getValue("uncertain")
        assertNotNull(first.stopAttemptedReason)
        assertNull(first.stoppedByRule)
        assertTrue(first.note.orEmpty().contains("will not retry"))
        assertEquals(1, engine.log.count { it == "pause uncertain" })
        // A new controller represents a restarted Android service.
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 200)
        assertEquals(1, engine.log.count { it == "pause uncertain" })
    }

    @Test fun stopJournalFailureBlocksEngineMutationAndCanTryOnLaterPoll() {
        val engine = FakeEngine()
        val store = MemStore().apply {
            epochMs = 0
            refuseStopAttemptCommit = true
        }
        engine.facts["blocked"] = Facts("blocked", 10, emptyList(), false)
        engine.observed["blocked"] = SeedingObservation(3_000, 1_000, 60, true)
        store.map["blocked"] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 100)
        assertTrue(engine.log.none { it == "pause blocked" })
        assertNull(store.map.getValue("blocked").stopAttemptedReason)
        store.refuseStopAttemptCommit = false
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 200)
        assertEquals(1, engine.log.count { it == "pause blocked" })
        assertNotNull(store.map.getValue("blocked").stoppedByRule)
    }

    @Test fun exceptionDuringPauseIsUncertainAndDoesNotRepeatOrStopNextTransfer() {
        val engine = FakeEngine().apply { throwOnPause.add("broken") }
        val store = MemStore().apply { epochMs = 0 }
        for (hash in listOf("broken", "healthy")) {
            engine.facts[hash] = Facts(hash, 10, emptyList(), false)
            engine.observed[hash] = SeedingObservation(3_000, 1_000, 60, true)
            store.map[hash] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        }
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 100)
        assertEquals(1, engine.log.count { it == "pause broken" })
        assertEquals(1, engine.log.count { it == "pause healthy" })
        assertNull(store.map.getValue("broken").stoppedByRule)
        assertNotNull(store.map.getValue("broken").stopAttemptedReason)
        assertNotNull(store.map.getValue("healthy").stoppedByRule)
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), 200)
        assertEquals(1, engine.log.count { it == "pause broken" })
        assertEquals(1, engine.log.count { it == "pause healthy" })
    }

    @Test fun uncappedRuleLeavesIndependentlyConfiguredTransferLimitUnchanged() {
        val engine = FakeEngine()
        val store = MemStore().apply { epochMs = 0 }
        engine.facts["manual"] = Facts("manual", 10, emptyList(), false)
        engine.limits["manual"] = 64 * 1024
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), nowMs = 100)
        assertEquals(SeedingResolution.Source.DEFAULT, store.map["manual"]?.source)
        assertEquals(64 * 1024, engine.limits["manual"])
        assertTrue("absence of a policy cap must not clear a manual limit", engine.log.isEmpty())
    }

    @Test fun overlargeSavedCapIsNeverNarrowedOrSentToNativeEngine() {
        val engine = FakeEngine()
        val store = MemStore().apply { epochMs = 0 }
        engine.facts["large"] = Facts("large", 10, emptyList(), false)
        engine.facts["normal"] = Facts("normal", 10, emptyList(), false)
        store.map["large"] = Assignment(
            SeedingPolicy(uploadLimit = RateLimit(Int.MAX_VALUE.toLong() + 1, RateLimit.Unit.BYTES)),
            SeedingResolution.Source.DEFAULT, null)
        store.map["normal"] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), nowMs = 100)
        assertEquals(listOf("cap normal=24576"), engine.log)
        assertTrue(store.map["large"]?.note.orEmpty().contains("outside"))
        assertEquals(0, engine.uploadLimit("large"))
    }

    @Test fun failingEngineCapSetterDoesNotAbortEnforcementOfOtherTransfers() {
        val engine = FakeEngine().apply { throwOnCap.add("failed") }
        val store = MemStore().apply { epochMs = 0 }
        engine.facts["failed"] = Facts("failed", 10, emptyList(), false)
        engine.facts["working"] = Facts("working", 10, emptyList(), false)
        store.map["failed"] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        store.map["working"] = Assignment(capped, SeedingResolution.Source.DEFAULT, null)
        SeedingController(engine, store).tick("local", emptyList(), SeedingPolicy(), nowMs = 100)
        assertTrue(store.map["failed"]?.note.orEmpty().contains("did not confirm"))
        assertEquals(listOf("cap working=24576"), engine.log)
    }

}
