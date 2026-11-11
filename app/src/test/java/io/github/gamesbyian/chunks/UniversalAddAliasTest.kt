package io.github.gamesbyian.chunks

import org.junit.Assert.*
import org.junit.Test

class UniversalAddAliasTest {
    private val hash = "a".repeat(40)

    @Test fun blankAliasLeavesTorrentNameUntouchedAndDraftValidationFailsClosed() {
        assertNull(normalizedUniversalAddAlias(""))
        assertNull(normalizedUniversalAddAlias("   "))
        assertTrue(validUniversalAddAlias("Custom Chunks name"))
        assertFalse(validUniversalAddAlias("x".repeat(161)))
        assertFalse(validUniversalAddAlias("bad\u202Ename"))
        assertEquals("Exact user choice", normalizedUniversalAddAlias(" Exact user choice "))
    }

    @Test fun onlyTheSubmittedExactHashCanAcquireTheFrozenAlias() {
        assertEquals("Alias for new job",
            exactNewAddAlias("Alias for new job", hash, hash.uppercase()))
        assertNull(exactNewAddAlias("No proof", hash, null))
        assertNull(exactNewAddAlias("Wrong job", hash, "b".repeat(40)))
        assertNull(exactNewAddAlias(null, hash, hash))
        assertNull(exactNewAddAlias("Invalid hash", "123", "123"))
    }
}
