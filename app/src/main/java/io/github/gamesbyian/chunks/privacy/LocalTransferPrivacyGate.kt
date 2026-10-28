package io.github.gamesbyian.chunks.privacy

import android.app.Activity
import android.os.Build
import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import android.view.WindowManager
import io.github.gamesbyian.chunks.LocalEngineService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

private data class Android10CredentialChallenge(
    val ticket: TransferPrivacySession.Ticket,
    val originalMode: TransferLockMode,
    val requestedMode: TransferLockMode?
)

private fun Context.activityOrNull(): Activity? {
    var here: Context? = this
    repeat(8) {
        if (here is Activity) return here as Activity
        here = (here as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * These wrappers deliberately have separate scopes and UI captions. An OS
 * authentication grants only the exact scope whose ticket was verified.
 * Local/remote engine lifecycles are NEVER tied to this Compose gate.
 */
@Composable
internal fun LocalTransferPrivacyGate(
    scope: String,
    onBack: () -> Unit = {},
    content: @Composable () -> Unit
) {
    TransferPrivacyGate(scope, onBack, "Phone transfers", "Phone transfer",
        redactLocalService = true, content = content)
}

@Composable
internal fun RemoteTransferPrivacyGate(
    scope: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    TransferPrivacyGate(scope, onBack, "rTorrent profile", "rTorrent profile",
        redactLocalService = false, content = content)
}

/** Never composes protected content while a scope is locked. */
@Composable
private fun TransferPrivacyGate(
    scope: String,
    onBack: () -> Unit,
    lockedTitle: String,
    subject: String,
    redactLocalService: Boolean,
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
        val synchronized = session.configure(
            scope, saved, authenticatedForChange = false)
        // If a saved policy and an already-live process gate disagree,
        // a failed configure must NEVER cause a stale opt-out to reveal
        // transfer content. The user must reauthenticate/reconcile.
        mutableStateOf(if (synchronized) saved else TransferLockMode.ONCE_PER_PROCESS)
    }
    var localRevision by remember { mutableIntStateOf(0) }
    val foregroundRevision = TransferPrivacyRuntime.lifecycleRevision
    var settingsOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var unlockTicket by remember { mutableStateOf<TransferPrivacySession.Ticket?>(null) }
    var policyTicket by remember { mutableStateOf<TransferPrivacySession.Ticket?>(null) }
    var android10Challenge by remember { mutableStateOf<Android10CredentialChallenge?>(null) }
    val currentSignal by rememberUpdatedState(signal)
    val currentUnlockTicket by rememberUpdatedState(unlockTicket)
    val currentPolicyTicket by rememberUpdatedState(policyTicket)
    val currentAndroid10Challenge by rememberUpdatedState(android10Challenge)

    // Read both revisions so a background policy revocation or an auth callback
    // updates the visible gate immediately, without polling or persisted tokens.
    @Suppress("UNUSED_VARIABLE")
    val observedRevisions = foregroundRevision to localRevision
    // Removing Android's secure screen lock cannot leave a previously granted
    // Chunks UI scope visibly unlocked within this process.
    val deviceStillSecure = activity != null &&
        TransferDeviceAuthenticator.hasSecureLock(activity)
    val allowed = mode == TransferLockMode.NEVER ||
        (deviceStillSecure && session.mayView(scope))

    // Capture the mode this effect applied: onDispose must undo exactly that,
    // not re-read state that may already say NEVER after a policy change.
    val secureWindow = mode != TransferLockMode.NEVER
    DisposableEffect(activity, secureWindow) {
        if (secureWindow)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (secureWindow)
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    DisposableEffect(scope, activity) {
        onDispose {
            currentSignal?.cancel()
            currentUnlockTicket?.let { session.onCancelUnlock(it) }
            currentPolicyTicket?.let { session.onCancelPolicyChange(it) }
            currentAndroid10Challenge?.let { challenge ->
                if (challenge.requestedMode == null)
                    session.onCancelUnlock(challenge.ticket)
                else session.onCancelPolicyChange(challenge.ticket)
            }
            // An Android rotation preserves the process's once-per-session
            // grant. An actual exit from the transfer route does not satisfy
            // EVERY_ENTRY on the next visit.
            if (activity?.isChangingConfigurations != true) session.onExit(scope)
        }
    }

    // Android 10's credential confirmation returns via a distinct system
    // Activity. Its prelaunch ticket is invalidated when Chunks backgrounds;
    // after RESULT_OK, require the same scope/policy and issue a NEW ticket
    // in this foreground. This never reuses a stale callback authorization.
    val android10Credential = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { outcome ->
        val challenge = android10Challenge
        android10Challenge = null
        signal = null
        busy = false
        if (challenge != null) {
            if (challenge.requestedMode == null)
                session.onCancelUnlock(challenge.ticket)
            else session.onCancelPolicyChange(challenge.ticket)
            unlockTicket = null
            policyTicket = null
            val successful = outcome.resultCode == Activity.RESULT_OK &&
                mode == challenge.originalMode &&
                store.mode(scope) == challenge.originalMode &&
                activity != null && TransferDeviceAuthenticator.hasSecureLock(activity)
            if (!successful) {
                notice = "Android screen-lock confirmation cancelled or out of date."
            } else if (challenge.requestedMode == null) {
                val fresh = session.beginUnlock(scope)
                val admitted = fresh != null && session.finishUnlock(fresh, true)
                notice = if (admitted) null else
                    "A fresh transfer-session authorization could not be confirmed."
            } else {
                // A foreground-return policy may revoke the earlier view grant.
                // The trusted OS result first restores it, then authorizes one
                // exact requested policy change, never another scope's state.
                if (!session.mayView(scope)) {
                    session.beginUnlock(scope)?.let { session.finishUnlock(it, true) }
                }
                val fresh = session.beginPolicyChange(scope)
                val admitted = fresh != null && session.finishPolicyChange(fresh, true)
                val requested = challenge.requestedMode
                if (admitted &&
                    store.setMode(scope, requested, authenticatedForChange = true) &&
                    session.configure(scope, requested, authenticatedForChange = true)) {
                    mode = requested
                    settingsOpen = false
                    if (requested != TransferLockMode.NEVER && redactLocalService)
                        LocalEngineService.redactActiveNotification(context)
                    notice = "Privacy policy updated after device credential confirmation."
                } else {
                    notice = "Privacy policy unchanged. The verified device " +
                        "credential could not authorize the requested change."
                }
            }
        }
        localRevision++
    }

    fun launchAndroid10Credential(
        ticket: TransferPrivacySession.Ticket,
        requestedMode: TransferLockMode?
    ) {
        val activityNow = activity
        val confirmation = activityNow?.let {
            TransferDeviceAuthenticator.android10CredentialIntent(
                it, if (requestedMode == null)
                    "Unlock Chunks " + subject else "Confirm Chunks privacy change")
        }
        if (confirmation == null) {
            if (requestedMode == null) session.onCancelUnlock(ticket)
            else session.onCancelPolicyChange(ticket)
            busy = false
            notice = "Android 10 could not open device credential confirmation."
            return
        }
        android10Challenge = Android10CredentialChallenge(ticket, mode, requestedMode)
        busy = true
        notice = null
        try {
            android10Credential.launch(confirmation)
        } catch (_: Exception) {
            android10Challenge = null
            if (requestedMode == null) session.onCancelUnlock(ticket)
            else session.onCancelPolicyChange(ticket)
            unlockTicket = null
            policyTicket = null
            busy = false
            notice = "Android could not start screen-lock verification."
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
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            launchAndroid10Credential(ticket, requestedMode = null)
            return
        }
        signal = TransferDeviceAuthenticator.authenticate(activity,
            "Unlock Chunks " + subject) { success ->
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
                if (redactLocalService)
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
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            launchAndroid10Credential(ticket, requestedMode = requested)
            return
        }
        signal = TransferDeviceAuthenticator.authenticate(activity,
            "Confirm Chunks privacy policy change") { success ->
            val fresh = session.finishPolicyChange(ticket, success)
            policyTicket = null
            signal = null
            busy = false
            if (fresh && store.setMode(scope, requested, authenticatedForChange = true) &&
                session.configure(scope, requested, authenticatedForChange = true)) {
                mode = requested
                if (requested != TransferLockMode.NEVER && redactLocalService)
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
            Text(lockedTitle + " locked", style = MaterialTheme.typography.headlineMedium)
            Text("Your torrent names, files, folders, Collections and transfer controls " +
                "are hidden until Android verifies your fingerprint, face or device screen lock.")
            OutlinedButton(
                onClick = ::authenticateUnlock, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(if (busy) "Authenticating…" else "Unlock " + lockedTitle.lowercase())
            }
            if (activity == null || !TransferDeviceAuthenticator.hasSecureLock(activity))
                Text("This device currently has no usable secure screen lock. " +
                    "Set up a PIN, pattern or password in Android Settings, then retry.",
                    color = MaterialTheme.colorScheme.error)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(if (redactLocalService)
                "Locked Chunks UI does not pause or stop already-running local torrents."
                else "This lock neither disconnects the rTorrent server nor stops phone transfers.",
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
                        subject + " privacy is off. Enabling it requires a device " +
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
