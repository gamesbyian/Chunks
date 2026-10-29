package io.github.gamesbyian.chunks.privacy

import android.app.Activity
import android.content.Context

/**
 * The same disclosure decision [LocalTransferPrivacyGate] and
 * [RemoteTransferPrivacyGate] make, for read-only surfaces (the combined
 * Transfers view) that summarize a scope without composing its full gate.
 * It never unlocks anything: a locked scope stays locked until the user
 * opens that environment's own screen and authenticates there.
 */
internal object TransferPrivacyVisibility {
    data class Decision(val mayShow: Boolean, val needsSecureWindow: Boolean)

    /** Pure rule. A failed configure falls back to a locking mode, never to NEVER. */
    fun decide(savedMode: TransferLockMode, synchronized: Boolean, deviceSecure: Boolean,
               sessionMayView: Boolean): Decision {
        val mode = if (synchronized) savedMode else TransferLockMode.ONCE_PER_PROCESS
        val show = mode == TransferLockMode.NEVER || (deviceSecure && sessionMayView)
        return Decision(show, needsSecureWindow = mode != TransferLockMode.NEVER)
    }

    fun current(context: Context, activity: Activity?, scope: String): Decision {
        val saved = TransferPrivacyPolicyStore(context).mode(scope)
        val session = TransferPrivacyRuntime.session
        val synchronized = session.configure(scope, saved, authenticatedForChange = false)
        val secure = activity != null && TransferDeviceAuthenticator.hasSecureLock(activity)
        return decide(saved, synchronized, secure, session.mayView(scope))
    }
}
