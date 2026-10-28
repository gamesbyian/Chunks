package io.github.gamesbyian.chunks.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Only process memory authorizes screens. The service must never inherit a
 * Chunks UI unlock, and successful device auth is not rTorrent authentication.
 */
internal object TransferPrivacyRuntime {
    val session = TransferPrivacySession { SystemClock.elapsedRealtime() }
    var lifecycleRevision by mutableIntStateOf(0)
        private set

    fun onForeground() {
        session.onForeground()
        lifecycleRevision++
    }

    fun onBackground() {
        session.onBackground()
        lifecycleRevision++
    }
}

/**
 * Android platform biometric/device-credential adapter. Minimum supported
 * Android version is API 29. API 29 uses the supported credential fallback
 * option, while API 30+ uses an explicit strong-biometric | device-credential
 * authenticator policy. No biometric templates or PIN values are accessible.
 */
internal object TransferDeviceAuthenticator {
    fun hasSecureLock(activity: Activity): Boolean =
        try {
            activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        } catch (_: RuntimeException) { false }

    /**
     * Caller owns returned CancellationSignal and MUST revoke its independent
     * generation-bound ticket when the screen is left or Android backgrounds.
     * A biometric mismatch is nonterminal; only success or final error replies.
     */
    fun authenticate(
        activity: Activity,
        title: String,
        finished: (Boolean) -> Unit
    ): CancellationSignal? {
        if (!hasSecureLock(activity)) {
            finished(false)
            return null
        }
        val called = AtomicBoolean(false)
        val once: (Boolean) -> Unit = { successful ->
            if (called.compareAndSet(false, true)) finished(successful)
        }
        return try {
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle("Use your device fingerprint, face or screen lock.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            val signal = CancellationSignal()
            builder.build().authenticate(
                signal, activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        once(true)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        once(false)
                    }
                }
            )
            signal
        } catch (_: Exception) {
            once(false)
            null
        }
    }
}
