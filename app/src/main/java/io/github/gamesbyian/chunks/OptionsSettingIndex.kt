package io.github.gamesbyian.chunks

import java.util.Locale

/**
 * Words people search for in Options, mapped to the existing screen that
 * already holds that setting or tool. This is an index, not a second settings
 * system: a result only opens an existing, already-authorized route, and never
 * changes a value, a server, a login or an Add target.
 */
internal enum class OptionsShortcut(val title: String, val where: String, private val words: String) {
    SEEDING_RULES("Seeding rules", "Ratio and seeding-time rules · opens Seeding rules",
        "seeding seed ratio share sharing time rule rules stop upload goal"),
    PHONE_SETTINGS("Phone settings", "Speed limits are in phone Options; DHT and the download location are in phone Add · opens On this phone",
        "speed limit limits bandwidth throttle rate dht peer peers discovery download location place phone storage"),
    ENVIRONMENT_COLOURS("Environment colours", "Colours for the phone and each server · opens All environments",
        "colour colours color colors palette environment environments identity combined all"),
    NAMES("Names", "Display names (aliases) for transfers · opens Names",
        "name names rename alias aliases title titles display label"),
    BACKUP("Inventory backup", "Export a list of this server's transfers · opens Inventory backup",
        "backup export snapshot inventory save list"),
    INSPECT_BACKUP("Inspect backup", "Review a saved inventory backup · opens Inspect backup",
        "inspect import restore open backup snapshot review"),
    PRIVACY("Privacy & authentication", "Lock and hide transfer names · shown at the end of this Options panel",
        "privacy private lock unlock pin password biometric fingerprint hide screenshot recents authentication secure");

    fun matches(rawQuery: String): Boolean {
        val needles = rawQuery.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (needles.isEmpty()) return false
        val haystack = (title + " " + words).lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}&]+"))
        // Every typed word must start one of this setting's words ("spe" finds speed).
        return needles.all { needle -> haystack.any { it.startsWith(needle) } }
    }

    companion object {
        /** Empty for an empty search: shortcuts only answer a typed question. */
        fun search(rawQuery: String): List<OptionsShortcut> = entries.filter { it.matches(rawQuery) }
    }
}
