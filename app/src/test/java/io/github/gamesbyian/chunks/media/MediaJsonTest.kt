package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaJsonTest {
    @Test fun parsesRealProviderResponse() {
        val shows = Json.parse(fixture("tvmaze-search-babylon5.json")).array
        assertEquals("Babylon 5", shows[0]["show"]!!["name"]!!.string)
        assertEquals(475L, shows[0]["show"]!!["id"]!!.long)
    }

    @Test fun roundTripsUnicodeAndEscapes() {
        val original = Json.obj("t" to Json.Str("Amélie \"Le Fabuleux\"\n東京物語 \u0001"), "n" to Json.num(42), "x" to Json.Null)
        val parsed = Json.parse(original.encode())
        assertEquals(original, parsed)
        assertEquals("Amélie \"Le Fabuleux\"\n東京物語 \u0001", parsed["t"]!!.string)
    }

    @Test fun decodesUnicodeEscapesAndKeepsFirstDuplicateKey() {
        val j = Json.parse("""{"a":"\u00e9\ud83c\udfac","a":"second"}""")
        assertEquals("é🎬", j["a"]!!.string)
    }

    @Test fun rejectsMalformedAndHostileInput() {
        listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "01", "1.", "\"\\x\"", "{} x", "\"a\nb\"").forEach { bad ->
            assertThrows("should reject <$bad>", MediaJsonException::class.java) { Json.parse(bad) }
        }
        assertThrows(MediaJsonException::class.java) { Json.parse("[".repeat(40) + "]".repeat(40)) }
        assertThrows(MediaJsonException::class.java) { Json.parse("\"" + "x".repeat(Json.MAX_CHARS) + "\"") }
    }

    @Test fun typedAccessorsReturnNullForWrongShapes() {
        val j = Json.parse("""{"s":"x","n":1.5,"b":true,"big":99999999999}""")
        assertNull(j["s"]!!.long); assertNull(j["n"]!!.long); assertEquals(1.5, j["n"]!!.double!!, 0.0)
        assertNull(j["big"]!!.int); assertEquals(99999999999L, j["big"]!!.long)
        assertNull(j["missing"]); assertEquals(true, j["b"]!!.bool)
    }
}
