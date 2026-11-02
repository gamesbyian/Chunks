package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPublicationIdentifierIsmnBoundaryTest {
    @Test fun official9790MusicScoreMustNotBecomeBookIsbn() {
        // ISO 10957:2024's ISMN example. ISMN and ISBN share GTIN-13 checksum math.
        val officialIsmn = "9790345123458"
        assertEquals(null, MediaPublicationIdentifiers.isbn(officialIsmn))
        assertEquals(null, MediaPublicationIdentifiers.isbn("ISBN 979-0-3451-2345-8"))
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn(officialIsmn, null) }
    }

    @Test fun valid979BookCodesRemainSupportedWithoutIsbn10Conversion() {
        val validIsbn = "9791234567896"
        assertEquals(validIsbn, MediaPublicationIdentifiers.isbn(validIsbn)!!.isbn13)
        assertEquals(null, MediaPublicationIdentifiers.isbn(validIsbn)!!.observedIsbn10)
        assertEquals(validIsbn, CheckedIsbn(validIsbn, null).isbn13)
        assertThrows(IllegalArgumentException::class.java) {
            CheckedIsbn(validIsbn, null).copy(isbn13="9790345123458")
        }
    }
}
