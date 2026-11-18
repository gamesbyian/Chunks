package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.rpc.LocalClientAdapter
import io.github.gamesbyian.chunks.rpc.RemoteClientOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app's Local engine as a shared-port client, for an explicit user action
 * (for example "Copy job to This device"). Resolves the effective verified
 * Local Place, re-verifies it writable, and limits Adds to verified Places.
 * Starts the engine only because the person explicitly asked to use it.
 */
internal object LocalClientOperations {
    suspend fun forApp(context: Context): RemoteClientOperations = withContext(Dispatchers.IO) {
        val resolution = LocalDownloadPlaces.resolve(context)
        val place = resolution.selected ?: throw IllegalStateException("No verified local download folder")
        val root = LocalDownloadPlaces.materialize(place)
            ?: throw IllegalStateException("The local download folder is not writable")
        val allowed = (resolution.choices.filter { it.verified }.map { it.place.root } + root).distinct()
        RemoteClientOperations(LocalClientAdapter(LocalEngineHolder.get(context), root, allowed))
    }
}
