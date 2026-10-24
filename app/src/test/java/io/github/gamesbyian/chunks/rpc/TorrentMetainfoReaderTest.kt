package io.github.gamesbyian.chunks.rpc

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class TorrentMetainfoReaderTest {
    private val singleInfo =
        "d6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789" + "e"
    private fun torrent(info: String): ByteArray =
        ("d8:announce14:http://ignored4:info" + info + "e").toByteArray(Charsets.UTF_8)

    @Test fun readSingleFileExactOnDiskInfoHash() {
        val result = TorrentMetainfoReader.preview(torrent(singleInfo))
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(singleInfo.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(digest, result.infoHashV1)
        assertEquals("test.txt", result.name)
        assertEquals(5L, result.sizeBytes)
        assertEquals(1, result.fileCount)
        assertEquals(16384L, result.pieceLength)
    }

    @Test fun readsMultiFileWithFileCountsAndSizes() {
        val info = "d5:filesld6:lengthi3e4:pathl5:a.txt" + "ee" +
            "d6:lengthi4e4:pathl5:b.ogg" + "eee" +
            "4:name4:root12:piece lengthi16384e6:pieces20:01234567890123456789e"
        val result = TorrentMetainfoReader.preview(torrent(info))
        assertEquals(7L, result.sizeBytes)
        assertEquals(2, result.fileCount)
        assertEquals("root", result.name)
    }

    @Test fun rejectsTraversalMalformedAndOversizeMetadata() {
        val badNameInfo = singleInfo.replace("8:test.txt", "2:..")
        val badIntInfo = singleInfo.replace("i5e", "i05e")
        val v2Info = singleInfo.replace("4:name", "12:meta versioni2e4:name")
        val noPieces = singleInfo.replace("6:pieces20:01234567890123456789", "")
        for (candidate in listOf(badNameInfo, badIntInfo, v2Info, noPieces)) {
            try {
                TorrentMetainfoReader.preview(torrent(candidate))
                fail("Must reject invalid torrent metadata")
            } catch (_: Exception) { }
        }
        try {
            TorrentMetainfoReader.preview(ByteArray(TorrentMetainfoReader.MAX_BYTES + 1))
            fail("Must limit metadata length")
        } catch (_: IllegalArgumentException) { }
        try {
            TorrentMetainfoReader.preview("not-bencode".toByteArray())
            fail("Must reject non-torrent files")
        } catch (_: Exception) { }
    }

    @Test fun rejectsDuplicateBencodeKeysAndExtraData() {
        val duplicated = singleInfo.replace("4:name8:test.txt",
            "4:name8:test.txt4:name8:test.txt")
        for (bytes in listOf(torrent(duplicated), torrent(singleInfo) + "trailer".toByteArray())) {
            try {
                TorrentMetainfoReader.preview(bytes)
                fail("Must reject duplicate or trailing data")
            } catch (_: Exception) { }
        }
    }
}
