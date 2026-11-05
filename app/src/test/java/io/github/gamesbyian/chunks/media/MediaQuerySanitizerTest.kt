package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQuerySanitizerTest {
    private fun manual(text: String) = MediaQuerySanitizer.manual(MediaKind.MOVIE, text)

    @Test fun ordinaryTitlesPass() {
        listOf("The Matrix", "Amélie", "千と千尋の神隠し", "Sid and Nancy", "Token", "Mr. Robot", "(500) Days of Summer", "M*A*S*H")
            .forEach { assertTrue(it, manual(it) is MediaQueryDecision.Allowed) }
    }

    @Test fun redTeamSecretsPathsAndHostsAreRefusedWithoutEcho() {
        val bad = listOf(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            "https://tracker.example.org/announce?passkey=abcd",
            "Movie passkey=deadbeef",
            "0123456789abcdef0123456789abcdef01234567",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567",
            "/storage/emulated/0/Download/private",
            "C:\\Users\\me\\film",
            "my.seedbox.example.net film",
            "10.0.0.12 film",
            "me@example.com",
            "Movie%20Name%3Fkey",
            "aB3dE5gH7jK9mN1pQ3sT5vX7zA9bC1",
            "call 5551234567",
            "Film name api_key")
        for (b in bad) {
            val d = manual(b)
            assertTrue(b, d is MediaQueryDecision.Refused)
            assertFalse("reason must not echo input", (d as MediaQueryDecision.Refused).reason.contains(b))
        }
    }

    @Test fun recognitionQueriesContainOnlyTitleAndTypedHints() {
        val r = MediaRecognizer.recognize("Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL-GRP.mkv")
        val q = (MediaQuerySanitizer.fromRecognition(r) as MediaQueryDecision.Allowed).query
        assertEquals("Babylon 5", q.title); assertEquals(2, q.season); assertEquals(7, q.episode)
        assertFalse(q.canonical.contains("GRP")); assertFalse(q.canonical.contains("1080p"))
    }

    @Test fun nonLookupKindsAndLowEvidenceAreRefused() {
        listOf("backup.7z", "random_stuff", "ubuntu.iso", "F-Droid.apk").forEach {
            assertTrue(it, MediaQuerySanitizer.fromRecognition(MediaRecognizer.recognize(it)) is MediaQueryDecision.Refused)
        }
    }

    @Test fun invisibleFormatCharactersAreRemovedAndUnicodeNormalized() {
        val q = (manual("Ame\u0301lie\u200B") as MediaQueryDecision.Allowed).query
        assertEquals("Amélie", q.title)
    }

    @Test fun overlongQueriesRefused() {
        assertTrue(manual("word ".repeat(20)) is MediaQueryDecision.Refused)
    }

    @Test fun textNormalizationIsComparisonOnly() {
        assertEquals(1.0, MediaText.similarity("The Office", "Office"), 0.0)
        assertEquals(1.0, MediaText.similarity("Amélie", "Amelie"), 0.0)
        assertTrue(MediaText.similarity("Back to the Future Part 2", "Back to the Future Part 3") <= 0.6)
        assertTrue(MediaText.similarity("Babylon 5", "Babylon Berlin") < 0.78)
    }

    @Test fun slashAndAtTitlesAllowedButPathsAndEmailsRefused() {
        listOf("Face/Off", "Fahrenheit 9/11", "AC/DC", "Up@Night").forEach { assertTrue(it, manual(it) is MediaQueryDecision.Allowed) }
        listOf("a/b/c", "//host", "/root", "x@y.com").forEach { assertTrue(it, manual(it) is MediaQueryDecision.Refused) }
    }

    @Test fun manualAndAutomaticQueriesHaveSeparateCacheIdentity() {
        val auto = (MediaQuerySanitizer.fromRecognition(MediaRecognizer.recognize("Heat.1995.1080p.mkv")) as MediaQueryDecision.Allowed).query
        val man = (MediaQuerySanitizer.manual(MediaKind.MOVIE, "Heat", 1995) as MediaQueryDecision.Allowed).query
        assertFalse(auto.canonical == man.canonical)
        assertFalse(MediaQueryKeys.of("tmdb", auto) == MediaQueryKeys.of("tmdb", man))
    }
}
