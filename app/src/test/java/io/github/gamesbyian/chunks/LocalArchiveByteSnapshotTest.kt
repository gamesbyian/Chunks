package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalArchivePiecePlan
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LocalArchiveByteSnapshotTest {
    private fun row(index: Int, length: Long, done: Long = 0L) =
        LocalTransferFile(index, "file-$index", length, done, true, 4)
    private val a = row(0, 2)
    private val b = row(1, 5)
    private val c = row(2, 3)
    private val selected = listOf(b.copy(doneBytes = 5L))
    private val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    private val layout = listOf(LocalArchivePiecePlan.Span(a, 0), LocalArchivePiecePlan.Span(b, 2),
        LocalArchivePiecePlan.Span(c, 7))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun plan() = requireNotNull(LocalArchivePiecePlan.build(selected, layout, 10, 4, 3) {
        hash(data.copyOfRange(it * 4, minOf(it * 4 + 4, data.size)))
    })
    private fun source(file: LocalTransferFile, input: ByteArray): MediaByteSource =
        object : MediaByteSource {
            val start = layout.single { it.file.index == file.index }.offset.toInt()
            override val size = file.sizeBytes
            override fun read(offset: Long, length: Int) =
                input.copyOfRange(start + offset.toInt(), start + offset.toInt() + length)
        }

    @Test fun capturesOnlySelectedBytesButVerifiesEntireSharedBoundaryPieces() {
        val p = plan()
        assertEquals(listOf(0, 1), p.pieces.map { it.index })
        val result = requireNotNull(LocalArchiveByteSnapshot.capture(p, { source(it, data) }, { false }))
        assertEquals(5L, result.sizeBytes)
        assertArrayEquals(data.copyOfRange(2, 7), result.sources().single().read(0, 5))
        result.sources().single().read(0, 5).fill(99)
        assertArrayEquals(data.copyOfRange(2, 7), result.sources().single().read(0, 5))
        val corrupt = data.clone().apply { this[7] = 99 }
        assertNull(LocalArchiveByteSnapshot.capture(p, { source(it, corrupt) }, { false }))
        assertNull(LocalArchiveByteSnapshot.capture(p, { source(it, data) }, { true }))
    }

    @Test fun refusesMissingHashSkippedBoundaryOrNoncontiguousLayout() {
        val good = "0".repeat(40)
        assertNull(LocalArchivePiecePlan.build(selected, layout, 10, 4, 3) {
            if (it == 1) null else good
        })
        assertNull(LocalArchivePiecePlan.build(selected,
            listOf(layout[0].copy(file = a.copy(wanted = false)), layout[1], layout[2]),
            10, 4, 3) { good })
        assertNull(LocalArchivePiecePlan.build(selected,
            listOf(layout[0], layout[1].copy(offset = 3), layout[2]), 10, 4, 3) { good })
    }
}
