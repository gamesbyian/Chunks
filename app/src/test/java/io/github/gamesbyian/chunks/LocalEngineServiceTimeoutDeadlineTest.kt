package io.github.gamesbyian.chunks

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Handler/service regression, not a claim about cancellable JNI or a real OS timeout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalEngineServiceTimeoutDeadlineTest {
    @Test fun timeoutStopsServiceWhileControlWorkerNeverReturnsAndLateResultIsIgnored() =
        withBlockedControl(queuedUserStop = false)

    @Test fun timeoutDoesNotWaitBehindAPreviousUserStopOrRestartItsDeadline() =
        withBlockedControl(queuedUserStop = true)

    @Test fun destroyingOldServiceInvalidatesItsDeadlineAndLateWorkerForReplacementService() {
        val oldController = Robolectric.buildService(LocalEngineService::class.java).create()
        val old = oldController.get()
        val lanes = lanes(old)
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val workerReturned = CountDownLatch(1)
        val main = shadowOf(Looper.getMainLooper())
        val notifications = old.getSystemService(NotificationManager::class.java)
        var replacement: org.robolectric.android.controller.ServiceController<LocalEngineService>? = null
        try {
            lanes.submitControl {
                blockerEntered.countDown()
                releaseBlocker.await(10, TimeUnit.SECONDS)
            }
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            old.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            lanes.submitControl { workerReturned.countDown() }
            oldController.destroy()
            replacement = Robolectric.buildService(LocalEngineService::class.java).create()

            releaseBlocker.countDown()
            assertTrue(workerReturned.await(2, TimeUnit.SECONDS))
            main.idleFor(Duration.ofSeconds(5))
            assertFalse("Old callbacks cannot stop the replacement lifetime",
                shadowOf(replacement.get()).isStoppedBySelf)
            assertFalse("An abandoned timeout cannot publish a late zero-job or unknown notice",
                notifications.activeNotifications.any { it.id == LocalServiceTimeoutNotice.NOTIFICATION_ID })
        } finally {
            releaseBlocker.countDown()
            replacement?.destroy()
            main.idle()
        }
    }

    @Test fun startDeliveredToFinishedInstanceCannotReattachItsForegroundLifetime() {
        val controller = Robolectric.buildService(LocalEngineService::class.java).create()
        val service = controller.get()
        val main = shadowOf(Looper.getMainLooper())
        val notifications = service.getSystemService(NotificationManager::class.java)
        try {
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            main.idleFor(Duration.ofSeconds(4))
            assertTrue(shadowOf(service).isStoppedBySelf)
            service.onStartCommand(Intent(service, LocalEngineService::class.java), 0, 2)
            assertFalse("An already finished instance must detach the new foreground request",
                notifications.activeNotifications.any { it.id == LocalEngineService.NOTIFICATION_ID })
            assertEquals(2, shadowOf(service).stopSelfId)
        } finally {
            controller.destroy()
            main.idle()
        }
    }

    private fun lanes(service: LocalEngineService): LocalServiceWorkLanes =
        service.javaClass.getDeclaredField("workLanes").run {
            isAccessible = true
            get(service) as LocalServiceWorkLanes
        }

    private fun withBlockedControl(queuedUserStop: Boolean) {
        val controller = Robolectric.buildService(LocalEngineService::class.java).create()
        val service = controller.get()
        val lanes = lanes(service)
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val allControlReturned = CountDownLatch(1)
        val main = shadowOf(Looper.getMainLooper())
        val notifications = service.getSystemService(NotificationManager::class.java)
        fun noticeText(): String? = notifications.activeNotifications
            .firstOrNull { it.id == LocalServiceTimeoutNotice.NOTIFICATION_ID }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        try {
            assertTrue(lanes.submitControl {
                blockerEntered.countDown()
                releaseBlocker.await(10, TimeUnit.SECONDS)
            })
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            if (queuedUserStop) service.onStartCommand(
                Intent(service, LocalEngineService::class.java)
                    .setAction(LocalEngineService.ACTION_STOP_ALL), 0, 1
            )
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            // Even a duplicate platform callback must retain the first deadline.
            main.idleFor(Duration.ofMillis(3_999))
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNull(noticeText())
            main.idleFor(Duration.ofMillis(1))

            assertTrue("OS stop must not depend on returning native/control work",
                shadowOf(service).isStoppedBySelf)
            assertEquals("Worker remains blocked when the service stops", 1L, releaseBlocker.count)
            val uncertain = requireNotNull(noticeText())
            assertTrue("A deadline is no stop receipt", uncertain.contains("could not confirm"))
            val notification = notifications.activeNotifications.single {
                it.id == LocalServiceTimeoutNotice.NOTIFICATION_ID
            }.notification
            assertEquals("Chunks background time limit",
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

            assertTrue(lanes.submitControl { allControlReturned.countDown() })
            releaseBlocker.countDown()
            assertTrue(allControlReturned.await(2, TimeUnit.SECONDS))
            main.idle()
            assertEquals("Late zero-job readback must not rewrite uncertainty", uncertain, noticeText())
        } finally {
            releaseBlocker.countDown()
            controller.destroy()
            main.idle()
        }
    }
}
