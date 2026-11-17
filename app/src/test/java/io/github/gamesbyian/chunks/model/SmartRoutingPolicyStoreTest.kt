package io.github.gamesbyian.chunks.model

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Persistence and offline plan only. No authentication, network, engine,
 * provider artwork, Add/Move, or payload mutation is permitted by this API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartRoutingPolicyStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences(
        "chunks-smart-routing-policy", Context.MODE_PRIVATE)
    private val local = SmartRoutingPreview.Destination("local", true, true, true)
    private val aliceId = EnvironmentSnapshots.serverId("https://host.invalid/RPC2", "alice").value
    private val spacedAliceId = EnvironmentSnapshots.serverId("https://host.invalid/RPC2", " alice ").value
    private val alice get() = SmartRoutingPreview.Destination(aliceId, true, true, true)
    private val inaccessible get() = SmartRoutingPreview.Destination(spacedAliceId, false, true, true)
    private val episode = SmartRoutingPreview.Evidence("A series", listOf("Show.S02E04.mkv"))

    private fun rule(id: String, target: String, priority: Int = 50) =
        SmartRoutingPreview.Rule(id, priority, SmartRoutingPreview.Kind.TV, 90, target)

    @Before fun clearOnlyRoutingPolicyFixture() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun absentPolicyIsDisabledAndNeverChoosesAClientAutomatically() {
        val store = SmartRoutingPolicyStore(app)
        assertEquals(SmartRoutingPolicyStore.Read.Ready(
            0L, SmartRoutingPolicyStore.Policy()), store.read())
        val result = store.preview(episode, listOf(local, alice))
        assertEquals(SmartRoutingPreview.Outcome.ASK, result.outcome)
        assertNull(result.proposedEnvironmentId)
        assertEquals("smart-routing-disabled", result.reason)
        assertEquals(SmartRoutingPreview.Outcome.PROPOSED,
            store.preview(episode, listOf(local), explicitEnvironmentId = "local").outcome)
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun enabledRulesSurviveRecreationAndPreserveExactHashedAccount() {
        val first = SmartRoutingPolicyStore(app)
        val policy = SmartRoutingPolicyStore.Policy(enabled = true,
            rules = listOf(rule("tv", aliceId)))
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, first.save(0L, policy))
        val reopened = SmartRoutingPolicyStore(app)
        assertEquals(SmartRoutingPolicyStore.Read.Ready(1L, policy), reopened.read())
        val chosen = reopened.preview(episode, listOf(local, alice))
        assertEquals(SmartRoutingPreview.Outcome.PROPOSED, chosen.outcome)
        assertEquals(aliceId, chosen.proposedEnvironmentId)
        assertEquals("tv", chosen.ruleId)
        val stored = preferences.all.values.single().toString()
        assertFalse(stored.contains("https://host.invalid"))
        assertFalse(stored.contains("alice"))
        assertTrue(stored.contains(aliceId))
    }

    @Test fun stableSavedOrderDefinesEqualPriorityWinnerAfterExplicitReordering() {
        val store = SmartRoutingPolicyStore(app)
        val first = rule("zzz", aliceId)
        val second = rule("aaa", "local")
        val policy = SmartRoutingPolicyStore.Policy(enabled = true,
            rules = listOf(first, second))
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(0L, policy))
        assertEquals(aliceId, store.preview(episode, listOf(alice, local))
            .proposedEnvironmentId)
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(1L,
            policy.copy(rules = listOf(second, first))))
        assertEquals("local", SmartRoutingPolicyStore(app)
            .preview(episode, listOf(alice, local)).proposedEnvironmentId)
    }

    @Test fun changedSavedRevisionCannotSilentlyOverwriteOtherEditors() {
        val first = SmartRoutingPolicyStore(app)
        val second = SmartRoutingPolicyStore(app)
        val alicePolicy = SmartRoutingPolicyStore.Policy(true,
            rules = listOf(rule("a", aliceId)))
        val localPolicy = SmartRoutingPolicyStore.Policy(true,
            rules = listOf(rule("local", "local")))
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, first.save(0L, alicePolicy))
        assertEquals(SmartRoutingPolicyStore.Save.STALE_REVISION,
            second.save(0L, localPolicy))
        assertEquals(SmartRoutingPolicyStore.Read.Ready(1L, alicePolicy), first.read())
        assertEquals(SmartRoutingPolicyStore.Save.SAVED,
            second.save(1L, localPolicy))
        assertEquals(SmartRoutingPolicyStore.Read.Ready(2L, localPolicy), first.read())
    }

    @Test fun unavailableAccountDoesNotFallBackToUnrelatedClient() {
        val store = SmartRoutingPolicyStore(app)
        assertEquals(SmartRoutingPolicyStore.Save.SAVED, store.save(0L,
            SmartRoutingPolicyStore.Policy(true,
                rules = listOf(rule("exact", spacedAliceId),
                    rule("lower", "local", priority = 1)))))
        val automatic = store.preview(episode, listOf(alice, inaccessible, local))
        assertEquals(SmartRoutingPreview.Outcome.TARGET_UNAVAILABLE, automatic.outcome)
        assertEquals("exact", automatic.ruleId)
        assertNull(automatic.proposedEnvironmentId)
        assertEquals(SmartRoutingPreview.Outcome.EXPLICIT_TARGET_UNAVAILABLE,
            store.preview(episode, listOf(alice, inaccessible, local),
                explicitEnvironmentId = spacedAliceId).outcome)
        assertNotEquals(aliceId, spacedAliceId)
    }

    @Test fun malformedOrFuturePolicyIsQuarantinedWithoutRewritingIt() {
        val badCases = listOf(
            "{oops",
            """{"schema":2,"revision":10,"enabled":true,"fallback":"ASK","rules":[]}""",
            """{"schema":1,"revision":0,"enabled":true,"fallback":"AUTO_UNKNOWN","rules":[]}""",
            """{"schema":1,"revision":0,"enabled":true,"fallback":"ASK","rules":[{"id":"a","priority":1,"kind":"TV","minimumConfidence":90,"targetEnvironmentId":"https://private.example/RPC2"}]}"""
        )
        for (raw in badCases) {
            assertTrue(preferences.edit().putString("routing-policy-v1", raw).commit())
            val store = SmartRoutingPolicyStore(app)
            assertSame(SmartRoutingPolicyStore.Read.Unavailable, store.read())
            assertEquals(SmartRoutingPolicyStore.Save.UNAVAILABLE,
                store.save(0L, SmartRoutingPolicyStore.Policy(true)))
            assertEquals(raw, preferences.getString("routing-policy-v1", null))
            assertEquals(SmartRoutingPreview.Outcome.ASK,
                store.preview(episode, listOf(local)).outcome)
        }
        // Do not erase a value written by a future or incompatible version.
        assertSame(SmartRoutingPolicyStore.Read.Unavailable,
            SmartRoutingPolicyStore(app).read())
    }

    @Test fun invalidIdsDuplicatesAndUnboundedRulesNeverReachPreferences() {
        val store = SmartRoutingPolicyStore(app)
        assertEquals(SmartRoutingPolicyStore.Save.INVALID, store.save(0L,
            SmartRoutingPolicyStore.Policy(true,
                rules = listOf(rule("duplicate", "local"), rule("duplicate", aliceId)))))
        assertEquals(SmartRoutingPolicyStore.Save.INVALID, store.save(0L,
            SmartRoutingPolicyStore.Policy(true,
                rules = listOf(rule("unsafe", "https://host.invalid/RPC2")))))
        assertEquals(SmartRoutingPolicyStore.Save.INVALID, store.save(0L,
            SmartRoutingPolicyStore.Policy(true,
                rules = listOf(rule("name with spaces", "local")))))
        assertEquals(SmartRoutingPolicyStore.Save.INVALID, store.save(0L,
            SmartRoutingPolicyStore.Policy(true,
                rules = List(501) { rule("r$it", "local") })))
        assertEquals(SmartRoutingPolicyStore.Read.Ready(
            0L, SmartRoutingPolicyStore.Policy()), store.read())
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun wrongPreferenceTypeCannotSilentlyRestoreDefaultOrAuthorizeRouting() {
        assertTrue(preferences.edit().putInt("routing-policy-v1", 9).commit())
        val store = SmartRoutingPolicyStore(app)
        assertSame(SmartRoutingPolicyStore.Read.Unavailable, store.read())
        assertEquals(SmartRoutingPolicyStore.Save.UNAVAILABLE,
            store.save(0L, SmartRoutingPolicyStore.Policy()))
        assertEquals(9, preferences.getInt("routing-policy-v1", -1))
        assertEquals(SmartRoutingPreview.Outcome.ASK,
            store.preview(episode, listOf(local)).outcome)
    }
}
