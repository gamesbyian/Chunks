package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.seeding.SeedingResolution.Source
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class SeedingRulesTest {
    private val phone = "phone"
    private val box = "rtorrent:profile-7"

    private val forever = SeedingRule("r-ipt", SeedingRule.suggestedName(setOf("iptorrents.com"), SeedingPolicy()), false,
        setOf("iptorrents.com"), policy = SeedingPolicy(), environments = setOf(phone, box))
    private val everythingElse = SeedingPolicy(ratioGoal = BigDecimal("1.00"),
        uploadLimit = RateLimit.parse("24 KiB/s").getOrThrow())

    /** Acceptance 1: IPTorrents seeds forever; everything else stops at 1.00 and uploads ≤ 24 KiB/s. */
    @Test fun ipTorrentsSeedsForeverAndEverythingElseUsesTheDefault() {
        val ipt = SeedingRuleEngine.resolve(TransferFacts(box,
            listOf("https://ssl.empirehost.me/announce", "https://routing.iptorrents.com/announce.php?passkey=SECRET"),
            isPrivate = true), listOf(forever), everythingElse)
        assertEquals(Source.RULE, ipt.source)
        assertTrue(ipt.policy.seedsForever)
        assertEquals("Iptorrents · Seed forever", forever.name)
        assertTrue(ipt.reasons.joinToString().contains("routing.iptorrents.com"))
        assertFalse("no passkeys in reasons", ipt.reasons.joinToString().contains("SECRET"))

        val other = SeedingRuleEngine.resolve(TransferFacts(box, listOf("udp://tracker.opentrackr.org:1337/announce"), false),
            listOf(forever), everythingElse)
        assertEquals(Source.DEFAULT, other.source)
        assertEquals(24 * 1024L, other.policy.uploadLimit!!.bytesPerSecond)
        assertEquals("Stops seeding at ratio 1.00; uploads at most 24 KiB/s (196.6 kbit/s) per transfer. " +
            "Stopping never removes the transfer or deletes files.", other.policy.describe())
    }

    @Test fun kilobitsAndKibibytesAreNeverConfusedOrRounded() {
        assertEquals(3_000L, RateLimit.parse("24 kbps").getOrThrow().bytesPerSecond)
        assertEquals(24_576L, RateLimit.parse("24 KiB/s").getOrThrow().bytesPerSecond)
        assertEquals(24_000L, RateLimit.parse("24 kB/s").getOrThrow().bytesPerSecond)
        assertEquals(1_572_864L, RateLimit.parse("1.5 MiB/s").getOrThrow().bytesPerSecond)
        assertEquals("24 kbit/s (2.9 KiB/s)", RateLimit.parse("24 kbps").getOrThrow().describe())
        for (bad in listOf("24", "24k", "24 bps", "0 KiB/s", "1.001 kbit/s", "fast")) assertTrue(bad, RateLimit.parse(bad).isFailure)
    }

    @Test fun hostsMatchWholeLabelsNeverLookalikes() {
        val rule = forever
        for (url in listOf("https://iptorrents.com.evil.example/announce", "https://notiptorrents.com/a",
                "https://user:pw@iptorrents.com/a", "ftp://iptorrents.com/a")) {
            val r = SeedingRuleEngine.resolve(TransferFacts(phone, listOf(url), null), listOf(rule), everythingElse)
            assertEquals(url, Source.DEFAULT, r.source)
        }
    }

    @Test fun overrideBeatsRulesAndMissingMetadataIsPending() {
        val capped = SeedingRule("r-cap", "Slow", true, setOf("example.org"), policy = everythingElse, environments = setOf(phone))
        val facts = TransferFacts(phone, listOf("https://iptorrents.com/a"), true, overrideRuleId = "r-cap")
        assertEquals(Source.OVERRIDE, SeedingRuleEngine.resolve(facts, listOf(forever, capped), SeedingPolicy()).source)
        val pending = SeedingRuleEngine.resolve(TransferFacts(phone, null, null), listOf(forever), everythingElse)
        assertEquals(Source.PENDING, pending.source)
        assertEquals(everythingElse, pending.policy)
    }

    @Test fun savedOrderDecidesAndDifferingMatchesAreFlagged() {
        val second = SeedingRule("r-2", "Also IPT", true, setOf("iptorrents.com"),
            policy = everythingElse, environments = setOf(phone))
        val r = SeedingRuleEngine.resolve(TransferFacts(phone, listOf("https://iptorrents.com/a"), true),
            listOf(forever, second), SeedingPolicy())
        assertEquals("r-ipt", r.ruleId)
        assertEquals(listOf("Also IPT"), r.conflicts)
    }

    @Test fun environmentScopeIsExplicitAndFutureProfilesOptIn() {
        val phoneOnly = forever.copy(environments = setOf(phone), knownEnvironments = setOf(phone, box))
        assertFalse(phoneOnly.appliesTo(box))
        assertFalse("a new profile is not included by default", phoneOnly.appliesTo("rtorrent:new"))
        assertTrue(phoneOnly.copy(includeFutureEnvironments = true).appliesTo("rtorrent:new"))
        assertFalse("known but unselected stays excluded", phoneOnly.copy(includeFutureEnvironments = true).appliesTo(box))
        val r = SeedingRuleEngine.resolve(TransferFacts(box, listOf("https://iptorrents.com/a"), true),
            listOf(phoneOnly), everythingElse)
        assertEquals(Source.DEFAULT, r.source)
    }

    @Test fun generatedNamesFollowConditionsUntilTheUserRenames() {
        val renamed = forever.withConditions(setOf("iptorrents.com"), everythingElse)
        assertEquals("Iptorrents · Ratio 1.00 · 24 KiB/s", renamed.name)
        val custom = renamed.copy(name = "My tracker", nameIsCustom = true)
        assertEquals("My tracker", custom.withConditions(setOf("other.org"), SeedingPolicy()).name)
        assertEquals("r-ipt", custom.id)
    }

    @Test fun contradictionsAreReportedNotFixed() {
        val p = SeedingPolicy(minSeedSeconds = 7 * 86_400, maxSeedSeconds = 86_400)
        assertTrue(p.warnings().single().contains("minimum"))
        assertEquals("Stops seeding at ratio 1.00 or 7 days, whichever comes first; no upload limit. " +
            "Stopping never removes the transfer or deletes files.",
            SeedingPolicy(ratioGoal = BigDecimal("1"), maxSeedSeconds = 7 * 86_400).describe())
    }
}
