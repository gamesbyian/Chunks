package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRecognizerTest {
    private fun r(name: String, vararg files: Pair<String, Long?>) =
        MediaRecognizer.recognize(name, files.map { MediaFileHint(it.first, it.second) })

    @Test fun sceneMovieWithYearAndTechnicalTokens() {
        val x = r("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv")
        assertEquals(MediaKind.MOVIE, x.kind); assertEquals("The Matrix", x.title); assertEquals(1999, x.year)
        assertEquals(MediaConfidence.MEDIUM, x.confidence)
        assertEquals("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv", x.raw)
    }

    @Test fun numericTitlesKeepTheirNumberAndUseTheLaterYear() {
        assertEquals("1917" to 2019, r("1917.2019.2160p.UHD.mkv").let { it.title to it.year })
        assertEquals("2012" to 2009, r("2012 (2009) 720p.mp4").let { it.title to it.year })
        assertEquals("Blade Runner 2049" to 2017, r("Blade.Runner.2049.2017.1080p.WEB-DL.mkv").let { it.title to it.year })
        val noYear = r("1917.1080p.mkv")
        assertEquals("1917", noYear.title); assertNull(noYear.year); assertEquals(MediaKind.VIDEO, noYear.kind)
    }

    @Test fun sequelsStayDistinct() {
        val two = r("Back.to.the.Future.Part.II.1989.1080p.mkv"); val three = r("Back.to.the.Future.Part.III.1990.1080p.mkv")
        assertEquals("Back to the Future Part II", two.title); assertEquals("Back to the Future Part III", three.title)
        assertTrue(MediaText.similarity(two.title!!, three.title!!) < 1.0)
    }

    @Test fun editionsAreRecordedAndCutFromTitle() {
        val x = r("Blade.Runner.1982.The.Final.Cut.REMASTERED.1080p.BluRay.mkv")
        assertEquals("Blade Runner", x.title); assertEquals(1982, x.year)
        assertEquals(listOf("Final Cut", "Remastered"), x.editions)
        val lotr = r("The Lord of the Rings The Fellowship of the Ring (2001) Extended Edition 2160p.mkv")
        assertEquals("The Lord of the Rings The Fellowship of the Ring", lotr.title); assertEquals(listOf("Extended"), lotr.editions)
    }

    @Test fun titlesContainingFormerlyAmbiguousTokensSurvive() {
        assertEquals("Mad Max Fury Road", r("Mad.Max.Fury.Road.2015.1080p.mkv").title)
        assertEquals("Charlottes Web", r("Charlottes.Web.2006.720p.mkv").title)
        assertEquals("Real Steel", r("Real.Steel.2011.1080p.BluRay.mkv").title)
    }

    @Test fun tvEpisodeWithEpisodeTitle() {
        val x = r("Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv")
        assertEquals(MediaKind.TV_EPISODE, x.kind); assertEquals("Babylon 5", x.title)
        assertEquals(2, x.season); assertEquals(7, x.episode); assertEquals("Soul Mates", x.episodeTitle)
        assertEquals(MediaConfidence.HIGH, x.confidence)
    }

    @Test fun adjacentEpisodesAndMultiEpisodeRanges() {
        assertEquals(2, r("Show.S01E02.720p.mkv").episode); assertEquals(3, r("Show.S01E03.720p.mkv").episode)
        val multi = r("Show.S01E02E03.720p.mkv")
        assertEquals(2, multi.episode); assertEquals(3, multi.lastEpisode)
        assertEquals(5 to 12, r("The Office 5x12 HDTV.avi").let { it.season to it.episode })
    }

    @Test fun seasonPacksFromNameOrFileComposition() {
        val pack = r("Our.Planet.S01.2160p.NF.WEB-DL")
        assertEquals(MediaKind.TV_SEASON, pack.kind); assertEquals(1, pack.season); assertEquals("Our Planet", pack.title)
        val folder = r("Shogun Season 1 Complete")
        assertEquals(MediaKind.TV_SEASON, folder.kind); assertEquals("Shogun", folder.title)
        val byFiles = r("Some Show", "Some Show/ep1.S01E01.mkv" to 900L, "Some Show/ep2.S01E02.mkv" to 900L, "Some Show/x.nfo" to 1L)
        assertEquals(MediaKind.TV_SEASON, byFiles.kind)
    }

    @Test fun animeAbsoluteNumberingIsFlaggedAmbiguous() {
        val x = r("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv")
        assertEquals(MediaKind.TV_EPISODE, x.kind); assertEquals("Frieren", x.title); assertEquals(12, x.absoluteEpisode)
        assertTrue("absolute-episode-number" in x.ambiguities)
    }

    @Test fun musicAlbumFromCompositionWithArtistSplit() {
        val x = r("Radiohead - OK Computer (1997) [FLAC]", "01 Airbag.flac" to 30_000_000L,
            "02 Paranoid Android.flac" to 50_000_000L, "cover.jpg" to 400_000L, "rip.log" to 2_000L)
        assertEquals(MediaKind.MUSIC_ALBUM, x.kind); assertEquals("Radiohead", x.artist)
        assertEquals("OK Computer", x.title); assertEquals(1997, x.year); assertEquals(MediaConfidence.MEDIUM, x.confidence)
    }

    @Test fun unicodeAndNonLatinTitlesArePreserved() {
        val x = r("Amélie.2001.1080p.BluRay.mkv"); assertEquals("Amélie", x.title)
        val jp = r("千と千尋の神隠し (2001) 1080p.mkv"); assertEquals("千と千尋の神隠し", jp.title); assertEquals(2001, jp.year)
        val ru = r("Сталкер.1979.1080p.mkv"); assertEquals("Сталкер", ru.title)
    }

    @Test fun booksComicsAndAudiobooks() {
        val book = r("Frank Herbert - Dune.epub"); assertEquals(MediaKind.BOOK, book.kind)
        val comic = r("The Incredible Hulk 331 (1987).cbz")
        assertEquals(MediaKind.COMIC, comic.kind); assertEquals(331, comic.issue); assertEquals("The Incredible Hulk", comic.title)
        val ab = r("Stephen King - Carrie (Unabridged)", "Carrie.m4b" to 300_000_000L)
        assertEquals(MediaKind.AUDIOBOOK, ab.kind); assertEquals("Stephen King", ab.artist); assertEquals("Carrie", ab.title)
    }

    @Test fun explicitBookAndComicFormatTokensInFolderNamesClassifyWithoutFiles() {
        // Like "[FLAC]" for music: an explicit format token is a clear local cue for a pack/folder name.
        val book = r("Frank Herbert - Dune (epub)")
        assertEquals(MediaKind.BOOK, book.kind); assertEquals("Frank Herbert", book.artist); assertEquals("Dune", book.title)
        assertEquals(MediaKind.BOOK, r("Andy Weir - Project Hail Mary [EPUB MOBI AZW3]").kind)
        val comic = r("Saga 054 (2018) (Digital) (cbz)")
        assertEquals(MediaKind.COMIC, comic.kind); assertEquals(54, comic.issue); assertEquals("Saga", comic.title)
        // A video name or actual file composition still wins over a stray format word.
        assertEquals(MediaKind.MOVIE, r("Hackers.1995.1080p.BluRay.x264.mkv").kind)
        assertEquals(MediaKind.MUSIC_ALBUM, r("Some Pack (epub)", "a.flac" to 30L, "b.flac" to 30L).kind)
        // A bare title remains unknown: nothing is blindly classified for online lookup.
        assertEquals(MediaKind.UNKNOWN, r("Frank Herbert - Dune").kind)
    }

    @Test fun collectionYearRangeIsNotAReleaseYear() {
        val x = r("The Incredible Hulk (1987-1998)", "Hulk 331.cbz" to 30L, "Hulk 332.cbz" to 30L)
        assertEquals(MediaKind.COMIC, x.kind); assertEquals(1987..1998, x.yearRange); assertNull(x.year)
    }

    @Test fun softwareArchivesAndUnknownAbstain() {
        assertEquals(MediaKind.SOFTWARE, r("F-Droid-1.20.apk").kind)
        assertEquals(MediaKind.DISC_IMAGE, r("ubuntu-24.04-desktop-amd64.iso").kind)
        assertEquals(MediaKind.ARCHIVE, r("backup.7z").kind)
        val unknown = r("random_stuff")
        assertEquals(MediaKind.UNKNOWN, unknown.kind); assertEquals(MediaConfidence.NONE, unknown.confidence)
        val mixed = r("Mixed", "a.mkv" to 10L, "b.flac" to 10L, "c.epub" to 10L)
        assertEquals(MediaKind.UNKNOWN, mixed.kind)
    }

    @Test fun gamesNeedPlatformOrGroupEvidence() {
        assertEquals(MediaKind.GAME, r("Hollow.Knight.v1.5-GOG").kind)
        assertEquals(MediaKind.GAME, r("Some Game [FitGirl Repack]", "setup.exe" to 10L, "data.bin" to 1000L).kind)
    }

    @Test fun adultMarkersAreSensitiveAndNeverLookupable() {
        val x = r("Something.XXX.1080p.mp4")
        assertEquals(MediaKind.ADULT, x.kind); assertTrue(x.isSensitive)
        assertTrue(MediaQuerySanitizer.fromRecognition(x) is MediaQueryDecision.Refused)
    }

    @Test fun siteTagsAndReleaseGroupsAreStripped() {
        assertEquals("Dune Part Two", r("www.SomeTracker.org - Dune.Part.Two.2024.1080p.mkv").title)
        assertEquals("Frieren", r("[Erai-raws] Frieren - 03 [1080p].mkv").title)
    }

    @Test fun deterministicAndLosslessRawPreservation() {
        val name = "  Odd__Name..S03E04..ÄÖÜ.mkv"
        assertEquals(MediaRecognizer.recognize(name), MediaRecognizer.recognize(name))
        assertEquals(name, MediaRecognizer.recognize(name).raw)
        assertEquals(name, MediaRecognizer.recognize(name).interpretation.raw)
    }
}
