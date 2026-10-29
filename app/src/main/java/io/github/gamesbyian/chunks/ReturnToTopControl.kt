package io.github.gamesbyian.chunks

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Pure visibility rules: never changes the queue, filter, selection, server or engine.
 * Do not show on empty lists or when the list has not moved about a viewport.
 */
internal object ReturnToTopPolicy {
    fun remote(
        firstVisibleIndex: Int,
        firstVisibleOffsetPx: Int,
        viewportHeightPx: Int,
        hasTransfers: Boolean
    ): Boolean = hasTransfers && viewportHeightPx > 0 &&
        (firstVisibleIndex >= 2 || firstVisibleOffsetPx >= viewportHeightPx)

    fun local(
        scrollPx: Int,
        maxScrollPx: Int,
        viewportHeightPx: Int,
        hasTransfers: Boolean
    ): Boolean = hasTransfers && viewportHeightPx > 0 &&
        maxScrollPx >= viewportHeightPx && scrollPx >= viewportHeightPx * 3 / 4

    /** Respect the Android animator-duration setting (including animation disabled). */
    fun animationsEnabled(context: Context): Boolean =
        try {
            Settings.Global.getFloat(context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (_: Exception) {
            false // Unknown system setting: the instant jump is the safe option.
        }
}

/** Shared high-contrast, explicitly labelled and 48dp-minimum scroll action. */
@Composable
internal fun ReturnToTopControl(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "Back to top" },
        colors = ButtonDefaults.buttonColors(
            containerColor = ChunksColors.Cyan,
            contentColor = ChunksColors.OnAccent
        )
    ) {
        Row {
            Text("↑", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("Back to top")
        }
    }
}
