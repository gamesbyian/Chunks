package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

/** 
 * Product-wide collection/display must both be enabled to offer a per-Add choice.
 * Provider consent and global automatic lookup are separate online-request gates:
 * neither is inferred from showing this default-checked local preference.
 */
internal fun MediaPrivacySettings.offersArtworkChoiceDuringAdd(): Boolean =
    showArtwork && downloadArtwork

/**
 * This availability read belongs in the STABLE Add screen composition, not
 * inside a disposable LazyColumn item. Otherwise scrolling the item offscreen
 * reinitializes offered=false and briefly removes the child's test/accessibility
 * semantics before another disk read, losing a second Add's opt-out (#1838).
 * It reads only local settings; no online artwork fetch is triggered.
 */
@Composable
internal fun rememberAddArtworkChoiceOffered(): Boolean {
    val context = LocalContext.current
    val media = remember(context) { ChunksMedia.get(context) }
    var offered by remember(media) { mutableStateOf(false) }
    LaunchedEffect(media) {
        offered = withContext(Dispatchers.IO) {
            media.loadSettings().offersArtworkChoiceDuringAdd()
        }
    }
    return offered
}

/** One shared per-Add choice. Expanding or changing it never starts a lookup. */
@Composable
internal fun AddArtworkChoice(
    automatic: Boolean,
    enabled: Boolean,
    offeredByParent: Boolean? = null,
    expandedState: MutableState<Boolean>? = null,
    onAutomatic: (Boolean) -> Unit
) {
    val offered = offeredByParent ?: rememberAddArtworkChoiceOffered()
    if (!offered) return

    // A LazyColumn can dispose its "artwork" item while scrolling. The Add
    // form supplies a retained parent state, so neither disposal nor the
    // second Add's temporary status-only item loses the person's disclosure.
    val localExpanded = rememberSaveable { mutableStateOf(false) }
    val disclosure = expandedState ?: localExpanded
    val expanded = disclosure.value
    Column(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { disclosure.value = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) { Text("Artwork & metadata") }
        if (expanded) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
                .toggleable(automatic, enabled = enabled, role = Role.Checkbox, onValueChange = onAutomatic),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = automatic, onCheckedChange = null, enabled = enabled)
                Text("Automatically identify and fetch artwork & metadata", Modifier.padding(start = 8.dp))
            }
            Text("Uses your provider consent and privacy settings. Turning this off excludes this exact transfer before Add. " +
                "Existing exclusions stay off after restart; Details still allows manual artwork corrections.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
