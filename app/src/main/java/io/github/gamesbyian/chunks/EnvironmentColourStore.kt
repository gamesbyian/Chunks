package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentPalette

/** Persists automatic and user-chosen environment colours by stable environment ID. */
internal class EnvironmentColourStore(context: Context) {
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

    fun load(): EnvironmentPalette.State = EnvironmentPalette.State(read("auto_"), read("user_"))

    /** Assigns and saves automatic colours for any newly seen environment. */
    fun ensure(configured: List<EnvironmentId>): EnvironmentPalette.State {
        val before = load()
        val after = EnvironmentPalette.assign(before, configured)
        val added = after.automatic - before.automatic.keys
        if (added.isNotEmpty()) preferences.edit().apply {
            added.forEach { (id, hue) -> putString("auto_" + id.value, hue.name) }
        }.commit()
        return after
    }

    fun choose(id: EnvironmentId, hue: EnvironmentHue): Boolean =
        TransferListSortStore.validEnvironment(id.value) &&
            preferences.edit().putString("user_" + id.value, hue.name).commit()

    fun restoreAutomatic(id: EnvironmentId): Boolean =
        preferences.edit().remove("user_" + id.value).commit()
}
