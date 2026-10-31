package io.github.gamesbyian.chunks.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offline negative controls for torn, reordered, forged and contradictory
 * app-private journal lines. Even a complete VERIFIED log has no right to
 * delete/write a provider URI; that requires fresh read-only observation.
 */
class OwnedMediaStorePublicationJournalTest {
    private val id = "a".repeat(32)
    private val name = "owned-recovery-" + "b".repeat(32) + ".txt"
    private val intent = "CHUNKS_MS1\tINTENT\t$id\t$name\ttext/plain\t4\t" +
        "c".repeat(64)
    @Test fun intentOnlyIsNeverACompletedPublication() {
        val result = OwnedMediaStorePublicationJournal.parse(id, listOf(intent))
        assertEquals(OwnedMediaStorePublicationJournal.Phase.INTENT, result?.phase)
        assertNull(result?.rowUri)
    }
    @Test fun missingOrTornAndForgedIntentAreRejected() {
        assertNull(OwnedMediaStorePublicationJournal.parse(id, emptyList()))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(intent.dropLast(3))))
        assertNull(OwnedMediaStorePublicationJournal.parse("d".repeat(32), listOf(intent)))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent.replace(name, "../outside.txt"))))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent.replace("text/plain", "application/x-unsafe"))))
    }
    @Test fun outOfOrderOrDuplicatedPublicationTransitionsAreRejected() {
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent, "CHUNKS_MS1\tSYNCED")))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent, "CHUNKS_MS1\tPUBLISH")))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent, "CHUNKS_MS1\tVERIFIED")))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent, intent)))
        assertNull(OwnedMediaStorePublicationJournal.parse(id, listOf(
            intent, "CHUNKS_MS1\tROW\tcontent://unowned/123")))
    }
}
