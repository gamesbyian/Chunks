package io.github.gamesbyian.chunks.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import android.view.WindowManager
import io.github.gamesbyian.chunks.LocalEngineService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private fun Context.activityOrNull(): Activity? {
    var here: Context? = this
    repeat(8) {
        if (here is Activity) return here as Activity
        here = (here as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Protection is enforced BEFORE composing any local torrent UI, not merely
 * drawn as a translucent overlay. It does not affect the native engine or
 * background seeding service. Only the known local scope is enabled here;
 * remote per-account gating requires a distinct protected route.
 */
@Composable
internal fun LocalTransferPrivacyGate(
    scope: String,
    onBack: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.activityOrNull() }
    val store = remember(context) { TransferPrivacyPolicyStore(context) }
    val session = TransferPrivacyRuntime.session

    // Load persisted settings synchronously, BEFORE deciding whether protected
    // content can be composed. The new process session begins locked by default.
    var mode by remember(scope, store) {
        val saved = store.mode(scope)
        session.configure(scope, saved, authenticatedForChange = false)
        mutableStateOf(saved)
    }
    var localRevision by remember { mutableIntStateOf(0) }
    val foregroundRevision = TransferPrivacyRuntime.lifecycleRevision
    var settingsOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var unlockTicket by remember { mutableStateOf<TransferPrivacySession.Ticket?>(null) }
    var policyTicket by remember { mutableStateOf<TransferPrivacySession.Ticket?>(null) }
    val currentSignal by rememberUpdatedState(signal)
    val currentUnlockTicket by rememberUpdatedState(unlockTicket)
    val currentPolicyTicket by rememberUpdatedState(policyTicket)

    // Read both revisions so a background policy revocation or an auth callback
    // updates the visible gate immediately, without polling or persisted tokens.
    @Suppress("UNUSED_VARIABLE")
    val observedRevisions = foregroundRevision to localRevision
    val allowed = mode == TransferLockMode.NEVER || session.mayView(scope)

    DisposableEffect(activity, mode != TransferLockMode.NEVER) {
        if (mode != TransferLockMode.NEVER)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (mode != TransferLockMode.NEVER)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    DisposableEffect(scope, activity) {
        onDispose {
            currentSignal?.cancel()
            currentUnlockTicket?.let { session.onCancelUnlock(it) }
            currentPolicyTicket?.let { session.onCancelPolicyChange(it) }
            // An Android rotation preserves the process's once-per-session
            // grant. An actual exit from the transfer route does not satisfy
            // EVERY_ENTRY on the next visit.
            if (activity?.isChangingConfigurations != true) session.onExit(scope)
        }
    }

    fun authenticateUnlock() {
        if (busy || activity == null) return
        val ticket = session.beginUnlock(scope)
        if (ticket == null) {
            notice = "This scope is already unlocked or has an authentication pending."
            return
        }
        unlockTicket = ticket
        busy = true
        notice = null
        signal = TransferDeviceAuthenticator.authenticate(activity,
            "Unlock Chunks phone transfers") { success ->
            val authorized = session.finishUnlock(ticket, success)
            unlockTicket = null
            signal = null
            busy = false
            localRevision++
            if (!authorized)
                notice = "Device authentication cancelled, unavailable or expired. Try again."
        }
    }

    fun changeMode(requested: TransferLockMode) {
        if (busy || requested == mode || activity == null) return
        if (mode == TransferLockMode.NEVER) {
            if (!TransferDeviceAuthenticator.hasSecureLock(activity)) {
                notice = "Set up a secure screen lock in Android Settings before enabling protection."
                return
            }
            val saved = store.setMode(scope, requested, authenticatedForChange = false)
            val configured = saved && session.configure(scope, requested,
                authenticatedForChange = false)
            if (configured) {
                mode = requested
                // Remove stale, clickable foreground-service Stop all actions
                // immediately when protection becomes enabled.
                LocalEngineService.redactActiveNotification(context)
                settingsOpen = false
                notice = null
                localRevision++
            } else {
                notice = "Could not persist privacy protection. No unlock was granted."
            }
            return
        }
        if (!session.mayView(scope)) {
            notice = "Unlock this transfer screen before changing privacy settings."
            return
        }
        val ticket = session.beginPolicyChange(scope)
        if (ticket == null) {
            notice = "Another authentication is already pending. Try again."
            return
        }
        busy = true
        policyTicket = ticket
        notice = null
        signal = TransferDeviceAuthenticator.authenticate(activity,
            "Confirm Chunks privacy policy change") { success ->
            val fresh = session.finishPolicyChange(ticket, success)
            policyTicket = null
            signal = null
            busy = false
            if (fresh && store.setMode(scope, requested, authenticatedForChange = true) &&
                session.configure(scope, requested, authenticatedForChange = true)) {
                mode = requested
                if (requested != TransferLockMode.NEVER)
                    LocalEngineService.redactActiveNotification(context)
                settingsOpen = false
                notice = "Privacy setting saved. Re-authentication may be required."
            } else {
                notice = "Policy unchanged. Fresh device authentication was not verified."
            }
            localRevision++
        }
    }

    if (!allowed) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(onClick = onBack,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Back to Chunks")
            }
            Text("Phone transfers locked", style = MaterialTheme.typography.headlineMedium)
            Text("Your torrent names, files, folders, Collections and transfer controls " +
                "are hidden until Android verifies your fingerprint, face or device screen lock.")
            OutlinedButton(
                onClick = ::authenticateUnlock, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(if (busy) "Authenticating…" else "Unlock phone transfers")
            }
            if (activity == null || !TransferDeviceAuthenticator.hasSecureLock(activity))
                Text("This device currently has no usable secure screen lock. " +
                    "Set up a PIN, pattern or password in Android Settings, then retry.",
                    color = MaterialTheme.colorScheme.error)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Locked Chunks UI does not pause or stop already-running local torrents.",
                style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { settingsOpen = !settingsOpen },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text("Privacy & authentication")
                    }
                    if (mode != TransferLockMode.NEVER) {
                        OutlinedButton(
                            onClick = {
                                session.lockNow(scope)
                                localRevision++
                                settingsOpen = false
                                notice = null
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) { Text("Lock now") }
                    }
                }
                if (settingsOpen) {
                    Text(if (mode == TransferLockMode.NEVER)
                        "Phone transfer privacy is off. Enabling it requires a device " +
                            "screen lock and will immediately hide transfers."
                        else "Current privacy mode: " + mode.name.replace('_', ' ').lowercase() +
                            ". Every change, including disabling, requires fresh Android authentication.",
                        style = MaterialTheme.typography.bodySmall)
                    val options = listOf(
                        TransferLockMode.NEVER to "Never (disable)",
                        TransferLockMode.ONCE_PER_PROCESS to "Once per app process",
                        TransferLockMode.EACH_FOREGROUND_RETURN to "On return from background",
                        TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND to "After 5 minutes away",
                        TransferLockMode.EVERY_ENTRY to "Every time opening transfers"
                    )
                    options.filter { it.first != mode }.forEach { (choice, label) ->
                        OutlinedButton(
                            onClick = { changeMode(choice) },
                            enabled = !busy && (mode != TransferLockMode.NEVER ||
                                (activity != null && TransferDeviceAuthenticator.hasSecureLock(activity))),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(label) }
                    }
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                content()
            }
        }
    }
}
