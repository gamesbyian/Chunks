package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaNameInterpretationTest {
    private fun parse(s: String) = MediaNameInterpreter.interpret(s)

    @Test fun preservesRawUtf16SpansAcrossUnicodeAndPaths() {
        val original = "some/私の資料/Übersetzung_東京_2024.720p.mkv"
        val result = parse(original)
        assertEquals(original, result.raw)
        assertEquals("Übersetzung 東京", result.titleCandidate)
        assertEquals(2024, result.year!!.year)
        assertTrue(result.spans.all { original.substring(it.start, it.endExclusive) == it.literal })
        assertEquals(MediaNameCategory.VIDEO, result.category)
        assertEquals(1, result.parserVersion)
    }

    @Test fun babylon5EpisodeAndReleaseTagsAreIndependent() {
        val p = parse("Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv")
        assertEquals("Babylon 5", p.titleCandidate)
        assertEquals(MediaNameCategory.TV_EPISODE, p.category)
        assertEquals(2, p.episode!!.season)
        assertEquals(7, p.episode!!.firstEpisode)
        assertEquals(null, p.episode!!.lastEpisode)
        assertTrue(p.spans.any { it.kind == MediaNameSpanKind.RELEASE_TECHNICAL })
    }

    @Test fun episodeRangesAreHintsNeverExpandedIntoFiles() {
        val p = parse("Show.S01E02-E03.1080p.mkv")
        assertEquals(2, p.episode!!.firstEpisode)
        assertEquals(3, p.episode!!.lastEpisode)
        assertTrue(p.episode!!.isRange)
        val huge = parse("Show.S01E02-E99.mkv")
        assertEquals(99, huge.episode!!.lastEpisode)
        assertTrue("episode-range-requires-file-inventory" in huge.ambiguities)
    }

    @Test fun boundedAlternateEpisodeGrammar() {
        assertEquals(7, parse("Show.2x07.1080p.mkv").episode!!.firstEpisode)
        assertEquals(null, parse("Movie.720x480.mkv").episode)
        assertEquals(null, parse("B5S02E07.mkv").episode)
        assertEquals(null, parse("Show.S02E07A.mkv").episode)
    }

    @Test fun comicRangeIsNotInventedIssueCoverageOrFilmYear() {
        val p = parse("Hulk.1987-1998.Issue_331.cbz")
        assertEquals(MediaNameCategory.COMIC, p.category)
        assertEquals(331, p.issueNumber)
        assertEquals(null, p.year)
        assertTrue(p.spans.any { it.kind == MediaNameSpanKind.YEAR_RANGE && it.literal == "1987-1998" })
        assertEquals(null, parse("Hulk.331.mkv").issueNumber)
    }

    @Test fun explicitComicNumberAndNoncomicNumbersRemainSeparate() {
        assertEquals(331, parse("Hulk #331.cbz").issueNumber)
        assertEquals(null, parse("Back.to.the.Future.2.1989.mkv").issueNumber)
        assertEquals(null, parse("BTTF2.cbz").issueNumber)
    }

    @Test fun bookFilmAndMusicExtensionsAreOnlyCategoryHints() {
        assertEquals(MediaNameCategory.BOOK, parse("Carrie.1974.epub").category)
        assertEquals(MediaNameCategory.VIDEO, parse("Carrie.2002.mkv").category)
        assertEquals(MediaNameCategory.AUDIO, parse("Johnny.Cash.At.Folsom.Prison.1968.FLAC").category)
        assertEquals(MediaNameCategory.ARCHIVE, parse("archive.tar.gz").category)
        assertEquals(MediaNameCategory.SOFTWARE, parse("app.apk").category)
    }

    @Test fun numberedSequelsAndRepeatedMovieTitlesDoNotCollapse() {
        val b2 = parse("BTTF2.1989.1080p.mkv")
        val b3 = parse("BTTF3.1990.1080p.mkv")
        assertEquals("BTTF2", b2.titleCandidate)
        assertEquals("BTTF3", b3.titleCandidate)
        assertFalse(b2.titleCandidate == b3.titleCandidate)
        assertEquals(1989, b2.year!!.year)
        assertEquals(1990, b3.year!!.year)
    }

    @Test fun unknownExtensionsAbstainFromAssumingTechnicalTokensAreFileSuffix() {
        val p = parse("Her.2023.unknownformat")
        assertEquals(MediaNameCategory.UNKNOWN, p.category)
        assertEquals("Her", p.titleCandidate)
        assertTrue(p.spans.none { it.kind == MediaNameSpanKind.EXTENSION })
    }

    @Test fun ambiguousYearsCannotPretendToBeVerifiedReleaseYear() {
        val p = parse("Movie.1999.2025.mkv")
        assertEquals(null, p.year)
        assertTrue("multiple-release-years" in p.ambiguities)
    }

    @Test fun multipleEpisodesAndIssuesAbstainRatherThanInventOneMember() {
        val episodes = parse("Show.S01E02.S01E03.mkv")
        assertEquals(null, episodes.episode)
        assertTrue("multiple-episode-markers" in episodes.ambiguities)
        val mixed = parse("Show.S01E02.1x03.mkv")
        assertEquals(null, mixed.episode)
        assertTrue("multiple-episode-markers" in mixed.ambiguities)
        val issues = parse("Hulk.Issue_331.Issue_332.cbz")
        assertEquals(null, issues.issueNumber)
        assertTrue("multiple-issue-markers" in issues.ambiguities)
    }

    @Test fun reversedEpisodeRangeIsRejected() {
        val reversed = parse("Show.S02E09-E03.mkv")
        assertEquals(null, reversed.episode)
        assertTrue("invalid-episode-range" in reversed.ambiguities)
    }

    @Test fun emptyOrMetadataFreeInputDoesNotInventIdentity() {
        val p = parse("")
        assertEquals(null, p.titleCandidate)
        assertEquals(null, p.episode)
        assertTrue(p.spans.isEmpty())
        assertEquals(MediaNameCategory.UNKNOWN, p.category)
    }
}
