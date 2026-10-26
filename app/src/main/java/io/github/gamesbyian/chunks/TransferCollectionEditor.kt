package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.CollectionMembershipRules
import io.github.gamesbyian.chunks.rpc.RemoteTransfer

/**
 * An explicitly editable membership for a single scoped torrent.
 * No operation in this card calls rTorrent, edits paths or changes sharing.
 */
@Composable
internal fun TransferCollectionEditor(
    transfer: RemoteTransfer,
    scope: String,
    store: TransferCollectionStore,
    onMembershipChanged: () -> Unit
) {
    var expanded by remember(scope, transfer.hash) { mutableStateOf(false) }
    var draft by remember(scope, transfer.hash) { mutableStateOf("") }
    var memberships by remember(scope, transfer.hash) {
        mutableStateOf(store.forTransfer(scope, transfer.hash))
    }
    var message by remember(scope, transfer.hash) { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(transfer.name, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge)
            Text(
                if (memberships.isEmpty()) "No manual collections"
                else memberships.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (expanded) "Close collection editor" else "Edit collections")
            }
            if (expanded) {
                Text(
                    "A transfer can belong to several collections. These names stay on this phone; no folders or torrent data change.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; message = null },
                    label = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled = CollectionMembershipRules.cleanName(draft) != null &&
                        memberships.size < CollectionMembershipRules.MAX_PER_TRANSFER,
                    onClick = {
                        val saved = store.add(scope, transfer.hash, draft)
                        if (saved) {
                            memberships = store.forTransfer(scope, transfer.hash)
                            draft = ""
                            message = "Membership saved on this phone. Sharing is unchanged."
                            onMembershipChanged()
                        } else {
                            message = "Collection could not be saved. Nothing changed."
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Add to collection") }
                for (name in memberships) {
                    OutlinedButton(
                        onClick = {
                            if (store.remove(scope, transfer.hash, name)) {
                                memberships = store.forTransfer(scope, transfer.hash)
                                message = "Collection membership removed. Torrent files are unchanged."
                                onMembershipChanged()
                            } else {
                                message = "Membership could not be removed. Nothing changed."
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Remove from $name") }
                }
                if (message != null) Text(requireNotNull(message),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
