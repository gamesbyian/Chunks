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

    @Test fun urlsDuplicateIdsAndBadValuesNeverLoad() {
        val good = SeedingRuleStore.encode(SeedingRuleStore.Snapshot(listOf(rule), emptyMap()))
        val withUrl = good.replace("\"iptorrents.com\"", "\"https://iptorrents.com/announce?passkey=x\"")
        val dup = SeedingRuleStore.encode(SeedingRuleStore.Snapshot(listOf(rule, rule), emptyMap()))
        for (bad in listOf(withUrl, dup, good.replace("\"version\":1", "\"version\":2"), "not json"))
            try { SeedingRuleStore.decode(bad); fail(bad.take(60)) } catch (_: Exception) { }
        assertFalse("no passkey can be stored", good.contains("passkey"))
    }
}
