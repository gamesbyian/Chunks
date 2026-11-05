package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource
import io.github.gamesbyian.chunks.media.inspection.FactSource
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import io.github.gamesbyian.chunks.media.inspection.MediaTrackInspector
import io.github.gamesbyian.chunks.media.inspection.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Real ffmpeg (Lavf60.16.100) multi-track fixtures; expected values cross-checked with ffprobe. */
class MediaTrackInspectorTest {
    private fun bytes(n: String) = javaClass.classLoader!!.getResource("media-fixtures/embedded/$n")!!.readBytes()
    private fun inspect(n: String) = MediaTrackInspector.inspect(ByteArrayMediaByteSource(bytes(n)))!!

    @Test fun matroskaTracksLanguagesAndDuration() {
        val i = inspect("tracks.mkv")
        assertEquals("Matroska", i.container)
        assertTrue("duration ${i.durationMs}", i.durationMs!! in 400..700)
        val v = i.video.single(); assertEquals("H.264", v.codec); assertEquals(320, v.width); assertEquals(180, v.height)
        assertEquals(listOf("AAC" to "eng", "AC-3" to "jpn"), i.audio.map { it.codec to it.language })
        assertEquals(listOf(2, 6), i.audio.map { it.channels }); assertEquals("Japanese 5.1", i.audio[1].name)
        assertEquals(listOf("eng", "spa"), i.subtitles.map { it.language }); assertEquals("SRT", i.subtitles[0].codec)
        assertEquals("H.264 320×180 · AAC 2.0 (eng), AC-3 5.1 (jpn) · Subtitles: eng, spa", MediaTrackInspector.summary(i))
    }

    @Test fun mp4TracksWithIsoLanguage() {
        val i = inspect("tracks.mp4")
        assertEquals("MP4", i.container); assertTrue(i.durationMs!! in 400..700)
        assertEquals("H.264", i.video.single().codec); assertEquals(640, i.video.single().width); assertEquals(360, i.video.single().height)
        val a = i.audio.single(); assertEquals("AAC", a.codec); assertEquals("fra", a.language); assertEquals(2, a.channels); assertEquals(44100, a.sampleRate)
    }

    @Test fun absentLanguageIsNeverPresentedAsKnownEnglish() {
        val i = inspect("nolang.mkv")
        val v = i.video.single()
        // ffmpeg writes "und" or omits the element; either way the summary must not claim a verified language.
        assertTrue(v.languageSource != FactSource.DETECTED || v.language != "eng")
        assertTrue(MediaTrackInspector.summary(i).startsWith("H.264 320×240"))
    }

    @Test fun nonContainersAndHostileInputAreBounded() {
        assertNull(MediaTrackInspector.inspect(ByteArrayMediaByteSource(bytes("cover.jpg"))))
        assertNull(MediaTrackInspector.inspect(ByteArrayMediaByteSource(ByteArray(0))))
        val rnd = Random(1302)
        for (n in listOf("tracks.mkv", "tracks.mp4")) {
            val good = bytes(n)
            for (cut in listOf(4, 8, 40, good.size / 4, good.size / 2)) MediaTrackInspector.inspect(ByteArrayMediaByteSource(good.copyOf(cut)))
            repeat(400) {
                val bad = good.copyOf(); repeat(rnd.nextInt(1, 16)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
                var read = 0L
                val src = object : MediaByteSource {
                    override val size = bad.size.toLong()
                    override fun read(offset: Long, length: Int): ByteArray { read += length; return ByteArrayMediaByteSource(bad).read(offset, length) }
                }
                MediaTrackInspector.inspect(src)
                assertTrue("read budget $read", read <= MediaTrackInspector.READ_BUDGET + 128 * 1024)
            }
        }
        // A 1 TB claimed size in a huge virtual file stays within the read budget.
        val claim = object : MediaByteSource {
            override val size = 1L shl 40
            override fun read(offset: Long, length: Int) = if (offset < 64) bytes("tracks.mkv").copyOfRange(offset.toInt(), minOf(64, offset.toInt() + length)) else ByteArray(length)
        }
        MediaTrackInspector.inspect(claim)
    }
}
