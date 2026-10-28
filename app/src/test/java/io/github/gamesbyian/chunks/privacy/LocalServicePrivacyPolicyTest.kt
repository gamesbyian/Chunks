package io.github.gamesbyian.chunks.privacy

import org.junit.Assert.*
import org.junit.Test

class LocalServicePrivacyPolicyTest {
    @Test fun enabledModesHideDetailedNotificationsAndDisallowNotificationStop() {
        val sensitive = "12 transfers running · 72% average"
        for (mode in TransferLockMode.entries) {
            if (mode == TransferLockMode.NEVER) {
                assertTrue(LocalServicePrivacyPolicy.allowStopAllNotificationAction(mode))
                assertTrue(LocalServicePrivacyPolicy.allowUserStopCommand(mode))
                assertEquals(sensitive, LocalServicePrivacyPolicy.notificationText(mode, sensitive))
            } else {
                assertFalse(LocalServicePrivacyPolicy.allowStopAllNotificationAction(mode))
                assertFalse(LocalServicePrivacyPolicy.allowUserStopCommand(mode))
                val text = LocalServicePrivacyPolicy.notificationText(mode, sensitive)
                assertFalse(text.contains("12 transfers"))
                assertFalse(text.contains("72%"))
                assertTrue(text.contains("hidden"))
            }
        }
    }
}
