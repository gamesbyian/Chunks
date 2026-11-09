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
    MEDIA("Artwork & media info", "Online lookup consent, providers, artwork downloads and cache · opens Artwork & info",
        "artwork art poster cover image picture media metadata info identify identification match provider providers tmdb key tvmaze musicbrainz lookup online cache consent"),
    NAMES("Names", "Display names (aliases) for transfers · opens Names",
        "name names rename alias aliases title titles display label"),
    BACKUP("Inventory backup", "Export a list of this server's transfers · opens Inventory backup",
        "backup export snapshot inventory save list"),
    INSPECT_BACKUP("Inspect backup", "Review a saved inventory backup · opens Inspect backup",
        "inspect import restore open backup snapshot review"),
    PRIVACY("Privacy & authentication", "Lock and hide transfer names · shown at the end of this Options panel",
        "privacy private lock unlock pin password biometric fingerprint hide screenshot recents authentication secure");

    fun matches(rawQuery: String): Boolean = optionsWordsMatch(title, words, rawQuery)

    companion object {
        /** Empty for an empty search: shortcuts only answer a typed question. */
        fun search(rawQuery: String): List<OptionsShortcut> = entries.filter { it.matches(rawQuery) }
    }
}

/** Every typed word must start one of the entry's words ("spe" finds speed); empty finds nothing. */
internal fun optionsWordsMatch(title: String, words: String, rawQuery: String): Boolean {
    val needles = rawQuery.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (needles.isEmpty()) return false
    val haystack = (title + " " + words).lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}&]+"))
    return needles.all { needle -> haystack.any { it.startsWith(needle) } }
}

/**
 * The same index for Phone options: each entry names a panel or tool that already exists on the
 * phone screen. A result opens that tool or scrolls to that panel; it never changes a value, starts
 * an engine command or picks a Place.
 */
internal enum class PhoneOptionsShortcut(val title: String, val where: String, private val words: String) {
    CONNECTIONS("Clients & environments", "Manage device and server connections · opens Connections",
        "clients client connections connection server remote local account accounts login endpoint profile profiles password authentication"),
    SERVER_PLACES("Server folders & destinations", "Folders on the authenticated server · opens Server places",
        "remote server place places folder folders directory directories destination path save"),
    SERVER_ORGANIZE("Server collections", "Organize transfers on the authenticated server · opens Organize",
        "remote server collection collections organize organizing grouping groups"),
    SERVER_NAMES("Server transfer names", "Edit display aliases on the authenticated server · opens Names",
        "remote server alias aliases name names rename title"),
    SERVER_BACKUP("Server inventory backup", "Export the authenticated server inventory · opens Inventory backup",
        "remote server snapshot backup export inventory"),
    SPEED_LIMITS("Speed limits", "Download and upload limits for this device · in Options",
        "speed limit limits bandwidth throttle rate download upload kib mib unlimited"),
    DHT("DHT peer discovery", "Find peers without trackers · in Add",
        "dht peer peers discovery trackerless magnet distributed hash table"),
    DOWNLOAD_LOCATION("Download location", "Where new downloads are saved on this device · in Add",
        "download location place folder storage save path directory downloads"),
    SEEDING_RULES("Seeding rules", "Ratio and seeding-time rules · opens Seeding rules",
        "seeding seed ratio share sharing time rule rules stop upload goal"),
    MEDIA("Artwork & media info", "Online lookup consent, providers, artwork downloads and cache · opens Artwork & info",
        "artwork art poster cover image picture media metadata info identify identification match provider providers tmdb key tvmaze musicbrainz lookup online cache consent similar"),
    PRIVACY("Privacy & authentication", "Lock and hide transfer names · in Options",
        "privacy private lock unlock pin password biometric fingerprint hide screenshot recents authentication secure"),
    SNAPSHOT_RESTORE("Restore from a snapshot", "Compare a saved device snapshot and review a stopped restore · in Options",
        "snapshot restore backup import compare recover"),
    CREATE_TORRENT("Create a torrent", "Make a .torrent from files on this phone · opens Create",
        "create make new torrent metainfo share publish"),
    ZIP_REVIEW("Review ZIP archives", "Inspect and extract downloaded ZIP files · opens ZIP review",
        "zip archive archives extract unzip unpack compressed"),
    EXPORTS("Snapshots and exports", "Inventory snapshot, health export and magnet links · in Advanced tools",
        "snapshot export backup inventory health report magnet magnets links copy list"),
    COLLECTION_NAMES("Collection names", "Rename or remove collection names · in Options",
        "collection collections group groups name names rename label");

    fun matches(rawQuery: String): Boolean = optionsWordsMatch(title, words, rawQuery)

    companion object {
        fun search(rawQuery: String): List<PhoneOptionsShortcut> = entries.filter { it.matches(rawQuery) }
    }
}
