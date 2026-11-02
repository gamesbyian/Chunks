package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPublicationIdentifiersTest {
    @Test fun standardIsbn10AndIsbn13AreEquivalentBookProductIdentifiers() {
        val a=MediaPublicationIdentifiers.isbn("ISBN-10: 0-306-40615-2")!!
        val b=MediaPublicationIdentifiers.isbn("ISBN 978-0-306-40615-7")!!
        assertEquals("9780306406157",a.isbn13)
        assertEquals(a.isbn13,b.isbn13)
        assertEquals("0306406152",a.observedIsbn10)
        assertEquals(null, b.observedIsbn10)
    }

    @Test fun invalidIsbnChecksumAndFormatDoNotCreateFalseJoins() {
        assertEquals(null, MediaPublicationIdentifiers.isbn("0-306-40615-3"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("978-0-306-40615-8"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("ISBN-10: 9780306406157"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("ISBN-13: 0306406152"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("ISBN-13: 0000000000000"))
    }

    @Test fun preserve979WithoutInventingIsbn10OrAbstractWork() {
        val first12="979123456789"
        val d=(10-(first12.mapIndexed{ i, c->c.digitToInt()*(if(i%2==0)1 else 3)}.sum()%10))%10
        val isbn=MediaPublicationIdentifiers.isbn(first12+d)!!
        assertEquals(first12+d,isbn.isbn13)
        assertEquals(null, isbn.observedIsbn10)
    }

    @Test fun ISBN10WithXCheckDigitCaseFolded() {
        val value=MediaPublicationIdentifiers.isbn("0-8044-2957-x")
        assertEquals("080442957X",value!!.observedIsbn10)
        assertTrue(value.isbn13.startsWith("978"))
    }

    @Test fun rejectsTitlesPathsUrlsAndArbitraryEmbeddedDigits() {
        assertEquals(null, MediaPublicationIdentifiers.isbn("Carrie.1974.9780306406157.epub"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("https://books.example/isbn/9780306406157?token=x"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("../9780306406157"))
        assertEquals(null, MediaPublicationIdentifiers.isbn("9780306406157 and other"))
    }

    @Test fun issueAndEditionIdentificationAreDifferentFromIssnSerialIdentity() {
        assertEquals("0317-8471",MediaPublicationIdentifiers.issn("ISSN 0317-8471")!!.canonical)
        assertEquals("1092-003X",MediaPublicationIdentifiers.issn("1092-003x")!!.canonical)
        assertEquals(null, MediaPublicationIdentifiers.issn("0317-8472"))
        assertEquals(null, MediaPublicationIdentifiers.issn("ISSN 0000-0000"))
        assertEquals(null, MediaPublicationIdentifiers.issn("Hulk Issue #331"))
    }

    @Test fun mixedTypeIdentifiersCannotBeSubstitutedForEachOther() {
        assertEquals(null, MediaPublicationIdentifiers.isbn("0317-8471"))
        assertEquals(null, MediaPublicationIdentifiers.issn("9780306406157"))
    }
}
