package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.model.CollectionNameRegistryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collection membership in canonical Details, for any scope that already owns a
 * Chunks display-name scope (the local app namespace or an authorized server alias
 * scope, exactly as the Organize screen uses). App-local data only: no engine or
 * server command. Store reads and writes stay off the UI thread.
 */
@Composable
internal fun DetailsCollections(scope: String, infoHash: String, displayName: String) {
    val context = LocalContext.current
    val store = remember(context) { TransferCollectionStore(context) }
    val registry = remember(context) { CollectionNameRegistry(context) }
    val coroutines = rememberCoroutineScope()
    var revision by remember(scope, infoHash) { mutableIntStateOf(0) }
    var known by remember(scope, infoHash) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(scope, infoHash, revision) {
        known = withContext(Dispatchers.IO) {
            CollectionNameRegistryRules.ordered(registry.names(scope) + store.forTransfer(scope, infoHash))
        }
    }
    TransferCollectionEditor(infoHash, displayName, scope, store, known, title = "Collections") {
        coroutines.launch {
            withContext(Dispatchers.IO) {
                // Persist only names explicitly saved via manual membership.
                store.forTransfer(scope, infoHash).forEach { registry.create(scope, it) }
            }
            revision++
        }
    }
}
