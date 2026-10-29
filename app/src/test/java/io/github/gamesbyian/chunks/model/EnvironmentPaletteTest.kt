package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class EnvironmentPaletteTest {
    private val phone = EnvironmentId("phone")
    private fun server(c: Char) = EnvironmentId(c.toString().repeat(64))
    private val empty = EnvironmentPalette.State(emptyMap(), emptyMap())

    @Test fun automaticColoursAvoidRepeatsAndExcludeBlackAndWhite() {
        val ids = listOf(phone) + "abcdefgh".map(::server)
        val state = EnvironmentPalette.assign(empty, ids)
        assertEquals(ids.size, state.automatic.values.toSet().size)
        assertEquals(EnvironmentHue.CYAN, state.hueOf(phone))
        for (hue in EnvironmentHue.entries) {
            assertNotEquals(0xFF000000, hue.argb)
            assertNotEquals(0xFFFFFFFF, hue.argb)
        }
        // A tenth environment must reuse a hue, choosing the least used one.
        val tenth = EnvironmentPalette.assign(state, ids + server('i'))
        assertNotNull(tenth.hueOf(server('i')))
    }

    @Test fun coloursSurviveReorderRemovalAndReturn() {
        val a = server('a'); val b = server('b')
        val first = EnvironmentPalette.assign(empty, listOf(phone, a, b))
        val reordered = EnvironmentPalette.assign(first, listOf(b, a, phone))
        assertEquals(first.automatic, reordered.automatic)
        // b disabled then re-enabled: same identity keeps its colour.
        val back = EnvironmentPalette.assign(EnvironmentPalette.assign(first, listOf(phone, a)), listOf(phone, a, b))
        assertEquals(first.hueOf(b), back.hueOf(b))
        // A brand-new identity does not inherit a removed one's colour by identity.
        assertNull(first.hueOf(server('c')))
    }

    @Test fun userChoiceOverridesAndMayRepeatThenRestoresAutomatic() {
        val a = server('a')
        val auto = EnvironmentPalette.assign(empty, listOf(phone, a))
        val chosen = auto.copy(chosen = mapOf(a to EnvironmentHue.CYAN))
        assertEquals(EnvironmentHue.CYAN, chosen.hueOf(a))
        assertEquals(EnvironmentHue.CYAN, chosen.hueOf(phone))
        assertEquals(auto.hueOf(a), chosen.copy(chosen = emptyMap()).hueOf(a))
        // New environments avoid the chosen hue too.
        val c = server('c')
        val next = EnvironmentPalette.assign(chosen, listOf(phone, a, c))
        assertNotEquals(EnvironmentHue.CYAN, next.hueOf(c))
    }

    @Test fun contrastMatchesWcagAndLabelsUseHueOnlyWhenReadable() {
        assertEquals(21.0, EnvironmentPalette.contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, EnvironmentPalette.contrast(0xFF3B82FF, 0xFF3B82FF), 0.001)
        val surface = 0xFF1A1F2BL
        assertTrue(EnvironmentPalette.labelIsReadable(EnvironmentHue.CYAN, surface))
        assertTrue(EnvironmentPalette.labelIsReadable(EnvironmentHue.YELLOW, surface))
        for (hue in EnvironmentHue.entries)
            assertEquals(hue.name, EnvironmentPalette.contrast(hue.argb, surface) >= 4.5,
                EnvironmentPalette.labelIsReadable(hue, surface))
    }
}
