package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Navigation taxonomy only. Preferences remain in their existing single stores. */
internal enum class OptionsCategory(
    val title: String, val summary: String, val fill: Color, val icon: Int,
    private val synonyms: String
) {
    CONNECTIONS("Connections", "Remote clients, Smart Connect, saved profiles", Color(0xFF653184),
        R.drawable.chunks_ic_server, "clients environments server remote account login endpoint profile password authentication"),
    DOWNLOADS("Downloads & storage", "Save to, ZIP archives and snapshot recovery", Color(0xFF765A00),
        R.drawable.chunks_ic_folder, "files place folder storage save path directory downloads zip extract snapshot restore collection"),
    SEEDING("Seeding & limits", "Ratio rules, seeding time and speed limits", Color(0xFF006C80),
        R.drawable.chunks_ic_play, "automation seed ratio rules bandwidth throttle upload tracker priority"),
    ARTWORK("Artwork & metadata", "Automatic identification, posters and providers", Color(0xFF376E0C),
        R.drawable.chunks_ic_file_video_on_dark, "media intelligence cover image lookup consent provider tmdb cache"),
    NOTIFICATIONS("Notifications", "Android notification permissions and channels", Color(0xFF8C2664),
        R.drawable.chunks_ic_options_notifications, "alerts completion background channel permission"),
    APPEARANCE("Appearance & accessibility", "Environment colours, artwork size and display", Color(0xFF81500E),
        R.drawable.chunks_ic_options_appearance, "theme font motion larger touch targets colour color palette density widget display"),
    PRIVACY("Privacy & network", "Access protection, DHT and clipboard preferences", Color(0xFF006F70),
        R.drawable.chunks_ic_options_privacy, "lock biometric authentication wifi wi-fi network permissions dht peers clipboard copied prefill"),
    ABOUT("About Chunks", "Version, help, diagnostics and advanced tools", Color(0xFF63358E),
        R.drawable.chunks_ic_options_info, "help about version licenses credits integrations create export backup health report magnet");

    val tag: String get() = "chunks.options.category.${name.lowercase(java.util.Locale.ROOT)}"
    fun matches(query: String): Boolean = optionsWordsMatch(title, summary + " " + synonyms, query)
}

@Composable
internal fun OptionsCategoryCard(category: OptionsCategory, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = category.fill, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag(category.tag)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(category.icon), contentDescription = null,
                    tint = Color.Unspecified, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(category.summary, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

/** Keep long-lived reviews composed while another category/search hides their presentation. */
@Composable
internal fun OptionsSettingsGroup(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (visible) Modifier.padding(bottom = 12.dp)
        else Modifier.height(0.dp).clipToBounds().focusProperties { canFocus = false }
            .clearAndSetSemantics { }),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

internal val LocalOptionsShortcut.category: OptionsCategory get() = when (this) {
    LocalOptionsShortcut.CONNECTIONS -> OptionsCategory.CONNECTIONS
    LocalOptionsShortcut.SPEED_LIMITS, LocalOptionsShortcut.SEEDING_RULES -> OptionsCategory.SEEDING
    LocalOptionsShortcut.MEDIA -> OptionsCategory.ARTWORK
    LocalOptionsShortcut.DHT, LocalOptionsShortcut.CLIPBOARD_PREFILL, LocalOptionsShortcut.PRIVACY -> OptionsCategory.PRIVACY
    LocalOptionsShortcut.DOWNLOAD_LOCATION, LocalOptionsShortcut.SNAPSHOT_RESTORE,
    LocalOptionsShortcut.ZIP_REVIEW, LocalOptionsShortcut.COLLECTION_NAMES,
    LocalOptionsShortcut.SERVER_PLACES, LocalOptionsShortcut.SERVER_ORGANIZE -> OptionsCategory.DOWNLOADS
    LocalOptionsShortcut.SERVER_NAMES -> OptionsCategory.APPEARANCE
    else -> OptionsCategory.ABOUT
}
