package io.github.gamesbyian.chunks

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.DeviceSpaceSnapshot
import io.github.gamesbyian.chunks.model.DeviceSpaceStatus
import io.github.gamesbyian.chunks.model.Units

/** No permissions, background scans, invented torrent usage or filesystem writes. */
internal object DeviceSpaceProbe {
    fun read(context: Context): DeviceSpaceSnapshot = try {
        val root = context.applicationContext.filesDir
        val fs = StatFs(root.absolutePath)
        val total = fs.totalBytes.takeIf { it > 0 }
        val available = fs.availableBytes.takeIf { it >= 0 }
        val low = context.getSystemService(StorageManager::class.java)
            ?.getStorageLowBytes(root)?.takeIf { it >= 0 }
        DeviceSpaceSnapshot(total, available, low)
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
    readSpace: ((Context) -> DeviceSpaceSnapshot)? = null
) {
    val context = LocalContext.current
    val reader = readSpace ?: DeviceSpaceProbe::read
    var snapshot by remember(context, readSpace) { mutableStateOf(reader(context)) }

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
                    Text("Volume allocation across all apps; not Chunks torrent usage.",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Phone volume capacity unavailable. No space estimate is shown.")
            }
            when (snapshot.status) {
                DeviceSpaceStatus.AT_OR_BELOW_ANDROID_FLOOR ->
                    Text("Android reports low phone storage. Avoid adding large local downloads.",
                        color = MaterialTheme.colorScheme.error)
                DeviceSpaceStatus.ABOVE_ANDROID_FLOOR ->
                    Text("Above Android's low-space warning threshold.")
                DeviceSpaceStatus.UNKNOWN ->
                    Text("Android low-space threshold status unavailable.")
            }
            val floor = snapshot.androidLowSpaceBytes
            if (floor != null) Text("Android warning threshold: " + Units.bytes(floor),
                style = MaterialTheme.typography.bodySmall)
            Text("Chunks cannot yet measure or enforce an on-device torrent cap. Remote storage capacity is unknown without a verified server capability.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { snapshot = reader(context) },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Refresh phone space")
            }
        }
    }
}
