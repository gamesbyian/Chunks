package io.github.gamesbyian.chunks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.DeviceSpaceSnapshot
import io.github.gamesbyian.chunks.model.DeviceSpaceStatus
import io.github.gamesbyian.chunks.model.LocalFolderInventory
import io.github.gamesbyian.chunks.model.LocalFolderInventoryProbe
import io.github.gamesbyian.chunks.model.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** No permissions, background scans, invented torrent usage or filesystem writes. */
internal object DeviceSpaceProbe {
    fun read(context: Context): DeviceSpaceSnapshot = try {
        val root = context.applicationContext.filesDir
        val fs = StatFs(root.absolutePath)
        val total = fs.totalBytes.takeIf { it > 0 }
        val available = fs.availableBytes.takeIf { it >= 0 }
        // A sticky ACTION_DEVICE_STORAGE_LOW is a public signal of an
        // active OS alert. Android's numeric low-space threshold is NOT a
        // public app API, so never synthesize one from storage capacity.
        val alert: Boolean? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        DeviceSpaceSnapshot(total, available, alert)
    } catch (_: Exception) {
        DeviceSpaceSnapshot(null, null, null)
    }
}

/**
 * This card reports only Android OS space on Chunks' internal volume.
 * It must never label free bytes as a Chunks quota, a remote server capacity,
 * or measured torrent payload. Use a real local-engine ownership graph later.
 */
@Composable
internal fun PhoneStoragePanel(
    readSpace: ((Context) -> DeviceSpaceSnapshot)? = null,
    readFolder: ((Context) -> LocalFolderInventory)? = null
) {
    val context = LocalContext.current
    val reader = readSpace ?: DeviceSpaceProbe::read
    var snapshot by remember(context, readSpace) { mutableStateOf(reader(context)) }
    val scope = rememberCoroutineScope()
    var folderUsage by remember(context, readFolder) { mutableStateOf<LocalFolderInventory?>(null) }
    suspend fun surveyFolder() = withContext(Dispatchers.IO) {
        try {
            readFolder?.invoke(context)
                ?: LocalFolderInventoryProbe.measure(LocalEngineHolder.appDownloads(context))
        } catch (_: Exception) {
            LocalFolderInventory(null, 0, false)
        }
    }
    LaunchedEffect(context, readFolder) { folderUsage = surveyFolder() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Phone storage", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge)
            Text("Android's internal volume, across all apps. This is separate from your rTorrent server.")
            val total = snapshot.totalBytes
            val free = snapshot.availableBytes
            if (total != null && free != null) {
                Text(Units.bytes(free) + " free of " + Units.bytes(total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                val occupied = snapshot.occupiedFraction
                if (occupied != null) {
                    LinearProgressIndicator(progress = { occupied },
                        modifier = Modifier.fillMaxWidth())
                    Text("Occupied or reserved on this volume, including other apps. Not Chunks torrent usage.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Phone volume capacity unavailable. No space estimate is shown.")
            }
            when (snapshot.status) {
                DeviceSpaceStatus.LOW_WARNING_ACTIVE ->
                    Text("Android reports low phone storage. Avoid adding large local downloads.",
                        color = MaterialTheme.colorScheme.error)
                DeviceSpaceStatus.NO_WARNING_DETECTED ->
                    Text("No active Android low-storage alert detected. The numeric threshold is not exposed to apps.")
                DeviceSpaceStatus.UNKNOWN ->
                    Text("Android low-storage alert status unavailable.")
            }
            Text("Chunks' download folder on this phone",
                fontWeight = FontWeight.SemiBold)
            val usage = folderUsage
            when {
                usage == null -> Text("Measuring Chunks' local files…")
                usage.logicalBytes == null -> Text("Chunks' downloads folder is unavailable; its size is unknown.")
                else -> {
                    Text(Units.bytes(usage.logicalBytes) + " in " + usage.regularFiles +
                        (if (usage.regularFiles == 1) " file" else " files"))
                    if (!usage.complete) {
                        Text("Partial survey only: some files or folders could not be counted.",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Logical sizes inside Chunks' downloads folder, including files left after removing a torrent. " +
                "Not allocated disk blocks, unique data, all Chunks storage, or proof that deletion is safe.",
                style = MaterialTheme.typography.bodySmall)
            Text("Chunks does not yet enforce a local torrent storage cap. Remote storage capacity is unknown without a verified server capability.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                snapshot = reader(context)
                scope.launch { folderUsage = surveyFolder() }
            },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Refresh phone space")
            }
        }
    }
}
