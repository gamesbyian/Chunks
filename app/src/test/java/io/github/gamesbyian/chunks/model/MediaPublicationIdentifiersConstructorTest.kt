package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPublicationIdentifiersConstructorTest {
    @Test fun directConstructorsCannotBypassChecksumOrCanonicalForm() {
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780306406158", null) }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780000000000", null) }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9790306406157", null) }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("978-0-306-40615-7", null) }
        assertThrows(IllegalArgumentException::class.java) { CheckedIssn("0317-8472") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIssn("0000-0000") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIssn("1092-003x") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIssn("03178471") }
        assertEquals("0317-8471", CheckedIssn("0317-8471").canonical)
        assertEquals("1092-003X", CheckedIssn("1092-003X").canonical)
    }

    @Test fun observedIsbn10MustBeValidAndConsistentWith978Edition() {
        val parsed = MediaPublicationIdentifiers.isbn("ISBN-10: 0-306-40615-2")!!
        assertEquals(parsed, CheckedIsbn("9780306406157", "0306406152"))
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780306406157", "0306406153") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780306406157", "080442957X") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780306406157", "030-6406152") }
        assertThrows(IllegalArgumentException::class.java) { CheckedIsbn("9780306406157", "") }
        val isbn979 = MediaPublicationIdentifiers.isbn("9791234567896")!!
        assertEquals(null, isbn979.observedIsbn10)
        assertThrows(IllegalArgumentException::class.java) { isbn979.copy(observedIsbn10 = "0306406152") }
    }

    @Test fun generatedDataClassCopiesCannotCreateUncheckedIdentifierInstances() {
        val good = CheckedIsbn("9780306406157", null)
        assertThrows(IllegalArgumentException::class.java) { good.copy(isbn13="9780306406158") }
        val serial = CheckedIssn("0317-8471")
        assertThrows(IllegalArgumentException::class.java) { serial.copy(canonical="0317-8472") }
    }
}
