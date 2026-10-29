package io.github.gamesbyian.chunks.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Identity colours for environments, drawn from Chunks' own accent palette
 * (never black or white). Error red and warning yellow come last in the
 * automatic order so an environment rarely looks like a status.
 */
enum class EnvironmentHue(val label: String, val argb: Long) {
    CYAN("Cyan", 0xFF00D4FF), BLUE("Blue", 0xFF3B82FF), PINK("Pink", 0xFFFF4EC4),
    GREEN("Green", 0xFF22D97A), ORANGE("Orange", 0xFFFF8A3D), PURPLE("Purple", 0xFFA56BFF),
    LAVENDER("Lavender", 0xFFC4A2FF), YELLOW("Yellow", 0xFFFFD93D), RED("Red", 0xFFFF4E5C)
}

/**
 * Automatic colours are assigned once per stable environment ID and then kept,
 * so renaming, reordering, disconnecting or restarting never changes them. A
 * user choice overrides the automatic one and may deliberately repeat a hue.
 */
object EnvironmentPalette {
    data class State(val automatic: Map<EnvironmentId, EnvironmentHue>, val chosen: Map<EnvironmentId, EnvironmentHue>) {
        fun hueOf(id: EnvironmentId): EnvironmentHue? = chosen[id] ?: automatic[id]
    }

    /**
     * Gives each new environment (in the given order) the first hue not shown by
     * any other configured environment; when all are used, the least used one.
     */
    fun assign(state: State, configured: List<EnvironmentId>): State {
        val automatic = state.automatic.toMutableMap()
        for (id in configured) {
            if (id in automatic) continue
            val inUse = configured.filter { it != id }.mapNotNull { other ->
                state.chosen[other] ?: automatic[other] }
            val counts = inUse.groupingBy { it }.eachCount()
            automatic[id] = EnvironmentHue.entries.minBy { counts[it] ?: 0 }
        }
        return state.copy(automatic = automatic)
    }

    /** WCAG relative-luminance contrast ratio between two opaque ARGB colours. */
    fun contrast(a: Long, b: Long): Double {
        fun channel(c: Long): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        fun luminance(argb: Long) = 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Use the hue for small label text only where it is AA-readable on [surface]. */
    fun labelIsReadable(hue: EnvironmentHue, surface: Long): Boolean = contrast(hue.argb, surface) >= 4.5
}
