package io.github.gamesbyian.chunks

import androidx.test.core.app.ApplicationProvider
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import io.github.gamesbyian.chunks.seeding.SeedingPolicy
import io.github.gamesbyian.chunks.seeding.SeedingResolution
import io.github.gamesbyian.chunks.seeding.SeedingRule
import io.github.gamesbyian.chunks.seeding.SeedingRuleStore
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneSeedingPreviewTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun torrent(private: Boolean) = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request("a.bin",
        listOf(TorrentMetainfoBuilder.Entry(listOf("a.bin"), 20_000) { ByteArrayInputStream(ByteArray(20_000)) }),
        trackerTiers = listOf(listOf("https://Tracker.Example:8443/announce.php?passkey=SECRET"), listOf("udp://open.example:6969/a")),
        private = private)).torrent

    @Test fun previewKeepsHostsAndPrivateFlagButNoPasskey() {
        val preview = TorrentMetainfoReader.preview(torrent(private = true))
        assertEquals(listOf("tracker.example", "open.example"), preview.trackerHosts)
        assertTrue(preview.isPrivate)
        assertFalse(preview.toString().contains("SECRET"))
        assertFalse(TorrentMetainfoReader.preview(torrent(private = false)).isPrivate)
    }

    @Test fun addReviewShowsTheRuleTheControllerWillSnapshot() {
        val rule = SeedingRule("r", "Tracker · Ratio 1.00", false, setOf("tracker.example"), privateOnly = true,
            policy = SeedingPolicy(ratioGoal = BigDecimal("1.00")), environments = setOf("phone"))
        assertTrue(SeedingRuleStore(context).save(SeedingRuleStore.Snapshot(listOf(rule), emptyMap())))
        val resolved = requireNotNull(phoneSeedingPreview(context, TorrentMetainfoReader.preview(torrent(private = true))))
        assertEquals(SeedingResolution.Source.RULE, resolved.source)
        assertEquals("r", resolved.ruleId)
        val public = requireNotNull(phoneSeedingPreview(context, TorrentMetainfoReader.preview(torrent(private = false))))
        assertEquals("private-only rule skips a public torrent", SeedingResolution.Source.DEFAULT, public.source)
    }
}
