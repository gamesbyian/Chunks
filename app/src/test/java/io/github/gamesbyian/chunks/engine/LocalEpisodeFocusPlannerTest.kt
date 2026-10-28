package io.github.gamesbyian.chunks.engine

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

class LocalEpisodeFocusPlannerTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = mutableListOf<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }
    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }

    private fun row(index: Int, name: String, priority: Int = 4,
        wanted: Boolean = true, done: Long = 0) =
        LocalTransferFile(index, "Season 1/$name", 4096, done, wanted, priority)

    @Test fun resolvesSeasonEpisodesNaturallyRegardlessOfNativeFileIndices() {
        val input = listOf(row(8, "Series S01E26.mkv"), row(2, "Series S01E02.mkv"),
            row(4, "Series S01E01.mkv"), row(7, "Series S01E10.mkv"),
            row(1, "Series S01E03.mkv"), row(0, "Series S01E02.srt"),
            row(9, "Trailer S01E00.mkv"), row(10, "Series S01E04.mkv", wanted = false))
        val plan = requireNotNull(LocalEpisodeFocusPlanner.plan(input))
        assertEquals(listOf(1, 2, 3, 10, 26), plan.orderedEpisodes.map {
            LocalEpisodeFocusPlanner.episodeKey(it.path)?.episode
        })
        assertEquals(4, plan.firstEpisode.index)
        assertEquals(mapOf(4 to 7, 1 to 1, 7 to 1, 8 to 1),
            plan.changes.associate { it.reviewed.index to it.requestedPriority })
        assertTrue(plan.changes.none { it.reviewed.index in setOf(0, 9, 10) })
    }

    @Test fun rejectsAmbiguousPatternsAndUnknownSeasonOrdering() {
        assertNull(LocalEpisodeFocusPlanner.episodeKey("Movie.mkv"))
        assertNull(LocalEpisodeFocusPlanner.episodeKey("Show S01E01 S02E02.mkv"))
        assertNull(LocalEpisodeFocusPlanner.episodeKey("Season 1/S01E03.sample.mkv"))
        assertNull(LocalEpisodeFocusPlanner.episodeKey("S01E01.srt"))
        assertEquals(LocalEpisodeFocusPlanner.EpisodeKey(2, 10),
            LocalEpisodeFocusPlanner.episodeKey("Show.2x10.mkv"))
        assertNull(LocalEpisodeFocusPlanner.plan(listOf(row(0, "Movie.mkv"),
            row(1, "Movie2.mkv"))))
    }

    @Test fun realNativeBatchPrioritizesNextEpisodeAndRefusesStaleRepeat() {
        val parent = temp.newFolder("episode-source")
        val show = File(parent, "Season 1").apply { mkdirs() }
        for (episode in 1..6) {
            File(show, "Public S01E${episode.toString().padStart(2, '0')}.mkv")
                .writeBytes(ByteArray(30_000) { (episode * 11 + it).toByte() })
        }
        File(show, "Season 1 notes.txt").writeText("No actual media attached")
        val bytes = TorrentBuilder().path(show).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()
        val target = temp.newFolder("episode-target")
        val engine = engine()
        val added = engine.addStopped(bytes, target) as LocalAddResult.AddedStopped
        val before = requireNotNull(engine.files(added.status.infoHash))
        val plan = requireNotNull(LocalEpisodeFocusPlanner.plan(before))
        assertTrue(plan.changes.size >= 3)
        assertEquals(1,
            LocalEpisodeFocusPlanner.episodeKey(plan.firstEpisode.path)?.episode)
        assertEquals(LocalFileBatchOutcome.VERIFIED,
            engine.focusEpisodesOnce(added.status.infoHash, plan))
        val updated = requireNotNull(engine.files(added.status.infoHash))
        val first = requireNotNull(updated.find { it.index == plan.firstEpisode.index })
        assertEquals(7, first.priority)
        assertTrue(first.wanted)
        assertEquals(LocalFileBatchOutcome.STALE_REVIEW,
            engine.focusEpisodesOnce(added.status.infoHash, plan))
        assertTrue(requireNotNull(engine.status(added.status.infoHash)).stopped)
        assertEquals(0L, requireNotNull(engine.status(added.status.infoHash))
            .payloadDownloadedBytes)
    }
}
