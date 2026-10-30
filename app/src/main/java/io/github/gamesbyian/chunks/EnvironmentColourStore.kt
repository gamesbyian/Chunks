package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentPalette

/** Persists automatic and user-chosen environment colours by stable environment ID. */
internal class EnvironmentColourStore(context: Context) {
    companion object {
        // Multiple screens use separate store instances backed by one preferences
        // file. A shared lock makes assign+commit atomic so overlapping effects
        // cannot pick the same "unused" automatic hue for separate profiles.
        private val persistenceLock = Any()
        // Colour changes invalidate already composed badges across navigation.
        private val paletteRevision = mutableIntStateOf(0)
        val revision: Int get() = paletteRevision.intValue
        private fun changed() { paletteRevision.intValue++ }
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        "chunks-environment-colours-v1", Context.MODE_PRIVATE
    )

    private fun read(prefix: String): Map<EnvironmentId, EnvironmentHue> =
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix)) return@mapNotNull null
            val id = key.removePrefix(prefix).takeIf { TransferListSortStore.validEnvironment(it) }
                ?: return@mapNotNull null
            val hue = EnvironmentHue.entries.firstOrNull { it.name == value } ?: return@mapNotNull null
            EnvironmentId(id) to hue
        }.toMap()

    fun load(): EnvironmentPalette.State = synchronized(persistenceLock) {
        EnvironmentPalette.State(read("auto_"), read("user_"))
    }

    /** Assigns and saves automatic colours for any newly seen environment. */
    fun ensure(configured: List<EnvironmentId>): EnvironmentPalette.State = synchronized(persistenceLock) {
        val before = load()
        val after = EnvironmentPalette.assign(before, configured)
        val added = after.automatic - before.automatic.keys
        if (added.isEmpty()) return@synchronized after
        val persisted = preferences.edit().apply {
            added.forEach { (id, hue) -> putString("auto_" + id.value, hue.name) }
        }.commit()
        if (!persisted) return@synchronized before // Never claim an unsaved assignment.
        changed()
        after
    }

    fun choose(id: EnvironmentId, hue: EnvironmentHue): Boolean = synchronized(persistenceLock) {
        if (!TransferListSortStore.validEnvironment(id.value)) return@synchronized false
        if (!preferences.edit().putString("user_" + id.value, hue.name).commit()) return@synchronized false
        changed()
        true
    }

    fun restoreAutomatic(id: EnvironmentId): Boolean = synchronized(persistenceLock) {
        if (!TransferListSortStore.validEnvironment(id.value)) return@synchronized false
        if (!preferences.edit().remove("user_" + id.value).commit()) return@synchronized false
        changed()
        true
    }
}
