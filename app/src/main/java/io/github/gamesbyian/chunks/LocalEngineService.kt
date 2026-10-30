package io.github.gamesbyian.chunks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.LocalServicePrivacyPolicy
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome

/**
 * Keeps the in-process local engine alive while the user has transfers running,
 * shown as an ongoing notification with a Stop all action. It never starts a
 * transfer itself, stops when nothing is running, and on Android's dataSync
 * time limit (Android 15+) pauses everything rather than pretending to continue.
 * This is not a promise of unlimited background seeding.
 */
class LocalEngineService : Service() {
    companion object {
        const val CHANNEL_ID = "local-engine"
        const val NOTIFICATION_ID = 4101
        const val ACTION_STOP_ALL = "io.github.gamesbyian.chunks.action.STOP_LOCAL_TRANSFERS"
        private const val TICK_MS = 2_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LocalEngineService::class.java))
        }

        /** Immediately remove any old unredacted/status-action notification
         * on enabling privacy, rather than waiting for the next poll tick. */
        fun redactActiveNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return
            val active = try {
                manager.activeNotifications.any { it.id == NOTIFICATION_ID }
            } catch (_: Exception) { false }
            if (!active) return
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Chunks on this phone")
                    .setContentText("Transfer activity hidden by Chunks privacy lock.")
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                    .build())
            } catch (_: RuntimeException) {
                // The policy remains enabled; future service ticks also redact.
                // Never let an OEM notification failure reopen transfer content.
            }
        }

        /**
         * Pause every running transfer and persist state. Safe to call without
         * the service. Reports how many pauses a readback confirmed.
         */
        internal fun pauseAll(
            context: Context,
            timedOut: Boolean = false
        ): LocalServiceTimeoutNotice.PauseSummary {
            // Android gives a time-limited dataSync service only a short grace
            // period to stop after onTimeout. Spend at most one SHARED
            // readback budget across all transfers, but still issue every Stop.
            val timeoutBudget = if (timedOut) LocalServicePauseBudget() else null
            val engine = LocalEngineHolder.current()
                ?: return LocalServiceTimeoutNotice.PauseSummary(listed = 0, confirmed = 0)
            // Attempt each Stop independently. One uncertain native command
            // must not prevent pausing the other active phone transfers.
            val active = try { engine.list().filter { !it.stopped } }
                catch (_: Exception) { null }
            var confirmed = 0
            active?.forEach { transfer ->
                try {
                    val waitMs = timeoutBudget?.nextReadbackTimeoutMs() ?: 2_000L
                    if (engine.pause(transfer.infoHash, readbackTimeoutMs = waitMs).outcome ==
                        LocalCommandOutcome.VERIFIED) confirmed++
                } catch (_: Exception) {
                    // An uncertain outcome must be observed afresh, not retried.
                }
            }
            // Always attempt persistence, including after a failed Stop.
            // During OS timeout, do not spend an additional default five
            // seconds waiting for disk alerts after the shared Stop budget.
            try {
                engine.saveResumeData(timeoutMs = if (timedOut) 1_000L else 5_000L)
            } catch (_: Exception) { }
            return LocalServiceTimeoutNotice.PauseSummary(active?.size, confirmed)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    // Native status observation and user/OS stop commands MUST use independent
    // lanes. Otherwise a hung status() call strands Stop all and FGS timeout
    // handling behind the very read that is failing.
    private val workLanes = LocalServiceWorkLanes(
        pollExecutor = Executors.newSingleThreadExecutor(),
        controlExecutor = Executors.newSingleThreadExecutor()
    )
    // Tick runs on the main thread; a stalled read is never retried, and one
    // warning is shown until the next genuinely admitted observation.
    private val stallNotice = LocalServiceStallNotice()
    @Volatile private var stopping = false
    private val tick = object : Runnable {
        override fun run() {
            if (stopping) return
            // A single admitted poll prevents a backlog of stale native reads.
            // A blocked poll never occupies the separate stop-command lane.
            if (workLanes.submitPoll { refresh() }) stallNotice.newPollAdmitted()
            if (stallNotice.shouldWarn(workLanes.inFlightPollMillis())) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification("Status check delayed; transfer activity unverified")
                )
            }
            if (!stopping) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads on this phone", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Chunks is downloading or sharing on this phone"
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly for every startForegroundService request.
        startForeground(NOTIFICATION_ID, notification("Checking transfers…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == ACTION_STOP_ALL) {
            // A forged/replayed PendingIntent cannot bypass a locally enabled
            // privacy gate. Android's mandatory onTimeout is intentionally
            // separate and must still be allowed to stop the engine.
            if (!LocalServicePrivacyPolicy.allowUserStopCommand(privacyMode())) {
                handler.removeCallbacks(tick)
                handler.post(tick)
                return START_NOT_STICKY
            }
            stopping = true
            requestStopAll()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        // Not sticky: after process death transfers are restored stopped, never auto-restarted.
        return START_NOT_STICKY
    }

    /** Android 15+ dataSync limit reached: stop honestly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        requestStopAll(timedOut = true)
    }

    /**
     * Do not enqueue a user Stop all or Android timeout behind native polling.
     * A separate worker removes that executor-order dependency; it cannot
     * guarantee JNI itself will return or bypass libtorrent's internal locks.
     */
    private fun requestStopAll(timedOut: Boolean = false) {
        if (!workLanes.submitControl {
                try {
                    val summary = pauseAll(this, timedOut = timedOut)
                    if (timedOut) postTimeoutNotice(summary)
                } finally { handler.post { finish() } }
            }) {
            // Only possible once the service is shutting down. Preserve the
            // normal foreground-service stop lifecycle.
            finish()
        }
    }

    private fun refresh() {
        if (stopping) return
        // A failed inventory read does not prove that all transfers stopped.
        // Shutting down the foreground service on a transient engine exception
        // could interrupt an active download without warning.
        val observed = try {
            LocalEngineHolder.current()?.list().orEmpty()
        } catch (_: Exception) { null }
        // Finish in Order advances on this same single poll lane, throttled
        // per transfer; it never starts an engine or blocks the stop lane.
        if (observed != null) {
            try { FinishInOrderController.tick(this, observed) }
            catch (_: Exception) {}
            // Smart Seeding: snapshots for transfers added after it was first
            // enabled; verified caps and pauses only, never removal.
            try { LocalEngineHolder.current()?.let { io.github.gamesbyian.chunks.seeding.PhoneSeeding.tick(this, it) } }
            catch (_: Exception) {}
        }
        val poll = LocalServicePoll.classify(observed)
        handler.post {
            if (stopping) return@post
            when (poll) {
                LocalServicePoll.Idle -> {
                    stopping = true
                    finish()
                }
                LocalServicePoll.Unavailable ->
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Transfer status unavailable; checking again")
                    )
                is LocalServicePoll.Active -> {
                    val text = poll.count.toString() +
                        (if (poll.count == 1) " transfer" else " transfers") +
                        " running · " + poll.averagePercent + "% average"
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacks(tick)
        // Resume persistence must likewise be independent of a stuck poll.
        workLanes.submitControl {
            try { LocalEngineHolder.current()?.saveResumeData() } catch (_: Exception) { }
        }
        workLanes.shutdown()
        super.onDestroy()
    }

    /** A dismissible notice that outlives the ongoing notification removed by finish(). */
    private fun postTimeoutNotice(summary: LocalServiceTimeoutNotice.PauseSummary) {
        val policy = privacyMode()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = LocalServiceTimeoutNotice.text(summary, policy)
        try {
            getSystemService(NotificationManager::class.java).notify(LocalServiceTimeoutNotice.NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(LocalServiceTimeoutNotice.TITLE)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setVisibility(if (policy == TransferLockMode.NEVER)
                        Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build())
        } catch (_: RuntimeException) {
            // Transfers are already paused; a notification failure must not undo that.
        }
    }

    private fun privacyMode(): TransferLockMode = try {
        TransferPrivacyPolicyStore(this).mode(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
    } catch (_: Exception) {
        // An unreadable persisted policy must never be a user-action bypass.
        TransferLockMode.ONCE_PER_PROCESS
    }

    private fun notification(text: String): Notification {
        val policy = privacyMode()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Chunks on this phone")
            .setContentText(LocalServicePrivacyPolicy.notificationText(policy, text))
            .setVisibility(if (policy == TransferLockMode.NEVER)
                Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        // API 31+ only. minSdk is 29: calling it on Android 10/11 throws
        // NoSuchMethodError and takes the download service down with it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (LocalServicePrivacyPolicy.allowStopAllNotificationAction(policy)) {
            val stopAll = PendingIntent.getForegroundService(this, 1,
                Intent(this, LocalEngineService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Stop all", stopAll).build())
        }
        return builder.build()
    }
}
