package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Persistent per-environment colours (UNIFIED-ENVIRONMENTS, "Environment
 * colours"). Each stable environment ID owns one Chunks palette hue, never
 * black or white. It is assigned automatically, preferring hues no other
 * environment uses, and the user may pick any hue (repeats allowed) or restore
 * the automatic choice. Colour is decoration on top of the environment's name,
 * never the only identity cue.
 */
internal enum class EnvironmentColor(val label: String, val color: Color) {
    CYAN("Cyan", ChunksColors.Cyan),
    BLUE("Blue", ChunksColors.Blue),
    PURPLE("Purple", ChunksColors.Purple),
    PINK("Pink", ChunksColors.Pink),
    YELLOW("Yellow", ChunksColors.Yellow),
    GREEN("Green", ChunksColors.Green),
    // Orange and red also mean warnings and errors, so they are chosen automatically last.
    ORANGE("Orange", ChunksColors.Orange),
    RED("Red", ChunksColors.Red);

    companion object {
        fun parse(name: String?): EnvironmentColor? = entries.firstOrNull { it.name == name }
    }
}

internal object EnvironmentIds {
    const val PHONE = "phone"

    /** The seeding/colour environment ID of an authenticated alias scope (endpoint + NUL + username). */
    fun server(aliasScope: String): String = "remote:" + RememberedRemoteProfileRoster.identityOf(
        aliasScope.substringBefore('\u0000'), aliasScope.substringAfter('\u0000', ""))
}

/** WCAG contrast ratio of two opaque colours. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return if (la > lb) (la / lb).toDouble() else (lb / la).toDouble()
}

/**
 * The hue itself when it is readable as text on [background] (4.5:1), else the
 * nearest lighter tint of it that is, so a name can always be drawn in its colour.
 */
internal fun textSafe(color: Color, background: Color): Color {
    if (contrastRatio(color, background) >= 4.5) return color
    for (step in 1..20) {
        val tint = lerp(color, Color.White, step / 20f)
        if (contrastRatio(tint, background) >= 4.5) return tint
    }
    return Color.White
}

internal class EnvironmentColorStore(private val prefs: Prefs) {
    /** The few key/value operations the store needs, so JVM tests can use a map. */
    interface Prefs {
        fun all(): Map<String, String>
        fun put(key: String, value: String): Boolean
        fun remove(key: String): Boolean
    }

    data class Choice(val color: EnvironmentColor, val manual: Boolean)

    fun saved(id: String): Choice? = prefs.all()[KEY + id]?.let { decode(it) }

    /** The saved colour, or a newly assigned automatic one (saved so it never shifts). */
    fun colorFor(id: String): EnvironmentColor {
        saved(id)?.let { return it.color }
        val chosen = automatic(id)
        prefs.put(KEY + id, encode(Choice(chosen, manual = false)))
        return chosen
    }

    fun choose(id: String, color: EnvironmentColor): Boolean = prefs.put(KEY + id, encode(Choice(color, manual = true)))

    /** Forget the user's pick and assign automatically again among the other environments' hues. */
    fun restoreAutomatic(id: String): Boolean {
        if (!prefs.remove(KEY + id)) return false
        return prefs.put(KEY + id, encode(Choice(automatic(id), manual = false)))
    }

    private fun automatic(id: String): EnvironmentColor {
        val used = prefs.all().filterKeys { it.startsWith(KEY) && it != KEY + id }.values
            .mapNotNull { decode(it)?.color }
        val counts = EnvironmentColor.entries.associateWith { c -> used.count { it == c } }
        // Least-used hue in preference order: unused ones first, then repeats spread evenly.
        return EnvironmentColor.entries.minBy { counts.getValue(it) }
    }

    private fun encode(choice: Choice) = choice.color.name + if (choice.manual) ":manual" else ":auto"
    private fun decode(raw: String): Choice? =
        EnvironmentColor.parse(raw.substringBefore(':'))?.let { Choice(it, raw.substringAfter(':') == "manual") }

    class SharedPrefs(context: Context) : Prefs {
        private val prefs = context.applicationContext.getSharedPreferences("chunks_environment_colours", Context.MODE_PRIVATE)
        override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        override fun put(key: String, value: String) = try { prefs.edit().putString(key, value).commit() } catch (_: RuntimeException) { false }
        override fun remove(key: String) = try { prefs.edit().remove(key).commit() } catch (_: RuntimeException) { false }
    }

    companion object {
        private const val KEY = "c:"

        /** Bumped after any change so every badge on screen redraws with the new colour. */
        var revision by mutableIntStateOf(0)

        fun of(context: Context) = EnvironmentColorStore(SharedPrefs(context))
    }
}
