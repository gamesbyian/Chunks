package io.github.gamesbyian.chunks.seeding

import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedingRuleStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val capped = SeedingPolicy(ratioGoal = BigDecimal("1.00"), maxSeedSeconds = 7 * 86_400,
        uploadLimit = RateLimit.parse("24 kbps").getOrThrow(), combine = SeedingPolicy.Combine.ALL)
    private val rule = SeedingRule("r-1", "Iptorrents · Seed forever", false, setOf("iptorrents.com"),
        privateOnly = true, policy = SeedingPolicy(), environments = setOf("phone", "rtorrent:7"),
        includeFutureEnvironments = false, knownEnvironments = setOf("phone", "rtorrent:7", "rtorrent:8"))

    @Test fun rulesAndDefaultsRoundTripExactly() {
        val store = SeedingRuleStore(context)
        val snapshot = SeedingRuleStore.Snapshot(listOf(rule), mapOf("phone" to capped))
        assertTrue(store.save(snapshot))
        val loaded = SeedingRuleStore(context).load()
        assertEquals(snapshot, loaded)
        assertEquals(RateLimit.Unit.KBIT, loaded.defaults.getValue("phone").uploadLimit!!.unit)
        assertEquals(3_000L, loaded.defaults.getValue("phone").uploadLimit!!.bytesPerSecond)
    }

    @Test fun nothingSavedMeansNoRulesNotAnError() {
        val loaded = SeedingRuleStore(context).load()
        assertTrue(loaded.rules.isEmpty()); assertFalse(loaded.unreadable)
    }

    @Test fun anUnreadableRecordIsReportedAndNeverSilentlyOverwritten() {
        context.getSharedPreferences("chunks_seeding_rules", 0).edit().putString("rules", "{\"version\":99}").commit()
        val store = SeedingRuleStore(context)
        assertTrue(store.load().unreadable)
        assertFalse(store.save(SeedingRuleStore.Snapshot(emptyList(), emptyMap())))
        assertTrue("explicit replacement only", store.save(SeedingRuleStore.Snapshot(listOf(rule), emptyMap()), replaceUnreadable = true))
        assertEquals(listOf(rule), store.load().rules)
    }

    @Test fun occupiedWrongTypePreferenceCannotBeMistakenForEmptyWritableRules() {
        val prefs = context.getSharedPreferences("chunks_seeding_rules", 0)
        assertTrue(prefs.edit().putInt("rules", 42).commit())
        val store = SeedingRuleStore(context)
        assertTrue(store.load().unreadable)
        assertFalse(store.save(SeedingRuleStore.Snapshot(listOf(rule), emptyMap())))
        assertTrue(prefs.contains("rules"))
        assertEquals(42, prefs.getInt("rules", -1))
        // Only an explicitly authorized replacement may replace the record.
        assertTrue(store.save(SeedingRuleStore.Snapshot(listOf(rule), emptyMap()),
            replaceUnreadable = true))
        assertEquals(listOf(rule), SeedingRuleStore(context).load().rules)
    }

    @Test fun legacyTrimmedServerIdsAreQuarantinedEvenWhenTheyMatchAnotherExactAccount() {
        val endpoint = "https://box.invalid/RPC2"
        val oldHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((endpoint + "\u0000" + "alice").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        // This is the old ID for authenticated username " alice " AND the
        // current exact ID of a DIFFERENT username "alice".
        val collision = "remote:" + oldHash
        val before = rule.copy(environments = setOf("phone", collision),
            knownEnvironments = setOf("phone", collision),
            includeFutureEnvironments = true)
        val policy = SeedingPolicy(ratioGoal = BigDecimal("2.00"))
        val encoded = org.json.JSONObject(SeedingRuleStore.encode(
            SeedingRuleStore.Snapshot(listOf(before),
                mapOf("phone" to capped, collision to policy))))
        // Reproduce bytes stored before the exact-account identity marker.
        encoded.remove("identityScheme")
        encoded.remove("quarantinedRemoteRules")
        encoded.remove("quarantinedRemoteDefaults")
        val legacy = encoded.toString()
        val safe = SeedingRuleStore.decode(legacy)
        val safeRule = safe.rules.single()
        assertEquals(setOf("phone"), safeRule.environments)
        assertEquals(setOf("phone"), safeRule.knownEnvironments)
        assertFalse(safeRule.includeFutureEnvironments)
        assertFalse(safeRule.appliesTo(collision))
        assertEquals(mapOf("phone" to capped), safe.defaults)
        assertEquals(policy, safe.quarantinedRemoteDefaults.getValue(collision))
        assertEquals(before.environments, safe.quarantinedRemoteRules.single().environments)
        assertTrue(safe.quarantinedRemoteRules.single().includeFutureEnvironments)
        val stored = SeedingRuleStore.encode(safe)
        assertTrue(stored.contains("remote-principal-exact-v2"))
        assertEquals(safe, SeedingRuleStore.decode(stored))
        // Only an explicit v2 edit can authorize a current exact server.
        val reviewed = safe.copy(rules = listOf(safeRule.copy(
            environments = setOf("phone", collision))))
        assertEquals(setOf("phone", collision),
            SeedingRuleStore.decode(SeedingRuleStore.encode(reviewed)).rules.single().environments)
    }

    @Test fun legacyUnknownScopeAndFutureWildcardCannotAutomaticallyReachAnyServer() {
        val before = rule.copy(environments = setOf("remote:unknown"),
            knownEnvironments = emptySet(), includeFutureEnvironments = true)
        val root = org.json.JSONObject(SeedingRuleStore.encode(
            SeedingRuleStore.Snapshot(listOf(before), emptyMap())))
        root.remove("identityScheme")
        root.remove("quarantinedRemoteRules")
        root.remove("quarantinedRemoteDefaults")
        val safe = SeedingRuleStore.decode(root.toString())
        assertFalse(safe.rules.single().appliesTo("remote:another-server"))
        assertFalse(safe.rules.single().appliesTo("remote:unknown"))
        assertEquals(emptySet<String>(), safe.rules.single().environments)
        assertEquals(before.environments, safe.quarantinedRemoteRules.single().environments)
        assertEquals(safe, SeedingRuleStore.decode(SeedingRuleStore.encode(safe)))
    }

    @Test fun urlsDuplicateIdsAndBadValuesNeverLoad() {
        val good = SeedingRuleStore.encode(SeedingRuleStore.Snapshot(listOf(rule), emptyMap()))
        val withUrl = good.replace("\"iptorrents.com\"", "\"https://iptorrents.com/announce?passkey=x\"")
        val dup = SeedingRuleStore.encode(SeedingRuleStore.Snapshot(listOf(rule, rule), emptyMap()))
        for (bad in listOf(withUrl, dup, good.replace("\"version\":1", "\"version\":2"), "not json"))
            try { SeedingRuleStore.decode(bad); fail(bad.take(60)) } catch (_: Exception) { }
        assertFalse("no passkey can be stored", good.contains("passkey"))
    }
}
