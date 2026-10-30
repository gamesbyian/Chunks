package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A presentation-only priority chooser. The owning controller retains
 * exact hash/file-index authorization, review, and readback requirements.
 */
@Composable
internal fun RemoteFilePriorityChoices(
    fileIndex: Int,
    filePath: String,
    desired: Int,
    onChoose: (Int) -> Unit
) {
    val names = listOf("Skip", "Normal", "High")
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || fontScale >= 1.5f
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { index, name ->
                    FilePriorityChoice(index, name, fileIndex, filePath,
                        desired == index, Modifier.fillMaxWidth(), onChoose)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEachIndexed { index, name ->
                    FilePriorityChoice(index, name, fileIndex, filePath,
                        desired == index, Modifier, onChoose)
                }
            }
        }
    }
}

@Composable
private fun FilePriorityChoice(
    value: Int,
    name: String,
    fileIndex: Int,
    filePath: String,
    selected: Boolean,
    modifier: Modifier,
    onChoose: (Int) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onChoose(value) },
        label = { Text(name) },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Choose $name priority for file ${fileIndex + 1}: $filePath"
        }
    )
}
