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

        /** Pause every running transfer and persist state. Safe to call without the service. */
        fun pauseAll(context: Context) {
            val engine = LocalEngineHolder.current() ?: return
            engine.list().filter { !it.stopped }.forEach { engine.pause(it.infoHash, readbackTimeoutMs = 2_000) }
            engine.saveResumeData()
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
        requestStopAll()
    }

    /**
     * Do not enqueue a user Stop all or Android timeout behind native polling.
     * A separate worker removes that executor-order dependency; it cannot
     * guarantee JNI itself will return or bypass libtorrent's internal locks.
     */
    private fun requestStopAll() {
        if (!workLanes.submitControl {
                try { pauseAll(this) } finally { handler.post { finish() } }
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

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAll = PendingIntent.getForegroundService(this, 1,
            Intent(this, LocalEngineService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Chunks on this phone")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop all", stopAll).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
