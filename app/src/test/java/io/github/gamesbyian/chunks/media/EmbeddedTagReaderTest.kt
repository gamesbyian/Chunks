package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.ArtworkBytesPolicy
import io.github.gamesbyian.chunks.media.artwork.ImageFormat
import io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource
import io.github.gamesbyian.chunks.media.inspection.EmbeddedMediaTags
import io.github.gamesbyian.chunks.media.inspection.EmbeddedTagFormat
import io.github.gamesbyian.chunks.media.inspection.EmbeddedTagReader
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Real ffmpeg-encoded fixtures (Lavf60.16.100), generated 2026-11-05 with tags and attached covers. */
class EmbeddedTagReaderTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResource("media-fixtures/embedded/$name")!!.readBytes()
    private fun tags(name: String) = EmbeddedTagReader.read(ByteArrayMediaByteSource(bytes(name)))!!
    private val cover by lazy { bytes("cover.jpg") }

    @Test fun id3v23WithFrontCover() {
        val t = tags("tagged-id3v23.mp3")
        assertEquals(EmbeddedTagFormat.ID3V2, t.format)
        assertEquals("Airbag", t.title); assertEquals("Radiohead", t.artist); assertEquals("OK Computer", t.album)
        assertEquals(1997, t.year); assertEquals(1, t.track); assertEquals("image/jpeg", t.pictureMime)
        assertArrayEquals(cover, t.picture)
        assertEquals(ImageFormat.JPEG, ArtworkBytesPolicy.sniff(t.picture!!))
    }

    @Test fun id3v24Utf8Unicode() {
        val t = tags("tagged-id3v24-utf8.mp3")
        assertEquals("Ünïcödé Sóng", t.title); assertEquals("Sigur Rós", t.artist); assertEquals("( )", t.album); assertEquals(2002, t.year)
        assertNull(t.picture)
    }

    @Test fun flacVorbisCommentsAndPictureBlock() {
        val t = tags("tagged.flac")
        assertEquals(EmbeddedTagFormat.FLAC, t.format)
        assertEquals("Paranoid Android", t.title); assertEquals("Radiohead", t.albumArtist); assertEquals("OK Computer", t.album)
        assertEquals(1997, t.year); assertArrayEquals(cover, t.picture)
    }

    @Test fun mp4AudiobookWithMoovAtEnd() {
        val b = bytes("tagged.m4b")
        assertTrue("moov is after mdat in this fixture", String(b, Charsets.ISO_8859_1).indexOf("moov") > String(b, Charsets.ISO_8859_1).indexOf("mdat"))
        val t = EmbeddedTagReader.read(ByteArrayMediaByteSource(b))!!
        assertEquals(EmbeddedTagFormat.MP4, t.format)
        assertEquals("Carrie", t.title); assertEquals("Stephen King", t.artist); assertEquals("Carrie (Unabridged)", t.album)
        assertEquals(1974, t.year); assertArrayEquals(cover, t.picture)
    }

    @Test fun untaggedAndForeignFilesYieldNothing() {
        val t = EmbeddedTagReader.read(ByteArrayMediaByteSource(bytes("untagged.mp3")))
        assertTrue(t == null || (!t.hasIdentity && t.picture == null))
        assertNull(EmbeddedTagReader.read(ByteArrayMediaByteSource(cover)))
        assertNull(EmbeddedTagReader.read(ByteArrayMediaByteSource(ByteArray(0))))
    }

    @Test fun truncatedAndCorruptedInputNeverThrowsOrOverreads() {
        val rnd = Random(403)
        for (name in listOf("tagged-id3v23.mp3", "tagged.flac", "tagged.m4b", "tagged-id3v24-utf8.mp3")) {
            val good = bytes(name)
            for (cut in listOf(4, 10, 11, 50, good.size / 3, good.size / 2, good.size - 1)) {
                EmbeddedTagReader.read(ByteArrayMediaByteSource(good.copyOf(cut))) // must not throw
            }
            repeat(300) {
                val bad = good.copyOf()
                repeat(rnd.nextInt(1, 12)) { bad[rnd.nextInt(bad.size)] = rnd.nextInt(256).toByte() }
                var read = 0L
                val counting = object : MediaByteSource {
                    override val size = bad.size.toLong()
                    override fun read(offset: Long, length: Int): ByteArray { read += length.coerceAtLeast(0); return ByteArrayMediaByteSource(bad).read(offset, length) }
                }
                EmbeddedTagReader.read(counting)
                assertTrue("bounded reads for $name: $read", read <= 2L * EmbeddedTagReader.MAX_TAG_BYTES + 64L * 1024 * 1024)
            }
        }
        // A forged huge length must not allocate it.
        val huge = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F)
        assertNull(EmbeddedTagReader.read(ByteArrayMediaByteSource(huge)))
    }

    @Test fun embeddedTagsRefineRecognitionWithoutTouchingRawName() {
        val raw = "01 - track.flac"
        val r = MediaRecognizer.recognize("Some Rip", listOf(MediaFileHint("Some Rip/$raw", 30_000_000)))
        val refined = MediaRecognizer.withEmbeddedTags(r, tags("tagged.flac"))
        assertEquals(MediaKind.MUSIC_ALBUM, refined.kind); assertEquals("OK Computer", refined.title); assertEquals("Radiohead", refined.artist)
        assertEquals(MediaConfidence.HIGH, refined.confidence); assertEquals("Some Rip", refined.raw)
        assertTrue(refined.evidence.last().contains("FLAC"))
        val book = MediaRecognizer.withEmbeddedTags(MediaRecognizer.recognize("Carrie", listOf(MediaFileHint("Carrie.m4b", 9))), tags("tagged.m4b"))
        assertEquals(MediaKind.AUDIOBOOK, book.kind); assertEquals("Carrie", book.title); assertEquals("Stephen King", book.artist)
        // Audio tags never reclassify a video transfer.
        val movie = MediaRecognizer.recognize("Heat.1995.1080p.mkv")
        assertEquals(movie, MediaRecognizer.withEmbeddedTags(movie, tags("tagged.flac")))
        val adult = MediaRecognizer.recognize("Something.XXX.mp4")
        assertEquals(adult, MediaRecognizer.withEmbeddedTags(adult, EmbeddedMediaTags(EmbeddedTagFormat.MP4, album = "A", artist = "B")))
    }
}
