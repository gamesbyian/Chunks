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
        // A live session refusing a stored policy is a conflicting identity/
        // authorization boundary. Its old grant is not evidence for the new
        // policy, even when the device is secure and mayView() returns true.
        if (!synchronized) return Decision(mayShow = false, needsSecureWindow = true)
        val show = savedMode == TransferLockMode.NEVER || (deviceSecure && sessionMayView)
        return Decision(show, needsSecureWindow = savedMode != TransferLockMode.NEVER)
    }

    fun current(context: Context, activity: Activity?, scope: String): Decision {
        val saved = TransferPrivacyPolicyStore(context).mode(scope)
        val session = TransferPrivacyRuntime.session
        val synchronized = session.configure(scope, saved, authenticatedForChange = false)
        val secure = activity != null && TransferDeviceAuthenticator.hasSecureLock(activity)
        return decide(saved, synchronized, secure, session.mayView(scope))
    }
}
