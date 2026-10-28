package io.github.gamesbyian.chunks.privacy

/** Shared service/presentation disclosure boundary. */
internal object LocalServicePrivacyPolicy {
    private const val HIDDEN = "Transfer activity hidden by Chunks privacy lock."

    fun notificationText(mode: TransferLockMode, rawStatus: String): String =
        if (mode == TransferLockMode.NEVER) rawStatus else HIDDEN

    /** A notification PendingIntent cannot present a foreground OS auth prompt. */
    fun allowStopAllNotificationAction(mode: TransferLockMode): Boolean =
        mode == TransferLockMode.NEVER

    /** Does not block the OS-mandated foreground-service timeout handler. */
    fun allowUserStopCommand(mode: TransferLockMode): Boolean =
        mode == TransferLockMode.NEVER
}
