package io.github.gamesbyian.chunks

import android.app.Activity
import android.view.WindowManager
import io.github.gamesbyian.chunks.privacy.SecureWindowFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One owner's release (e.g. a gate row scrolled off a lazy list) never removes another's FLAG_SECURE. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureWindowFlagTest {
    private fun secure(a: Activity) =
        a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun theFlagStaysWhileAnyOwnerHoldsIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(secure(activity))
        SecureWindowFlag.acquire(activity)   // combined Transfers' own decision
        SecureWindowFlag.acquire(activity)   // a typed client's gate row
        SecureWindowFlag.release(activity)   // the row scrolls off-screen and is disposed
        assertTrue("another owner still protects the window", secure(activity))
        SecureWindowFlag.release(activity)
        assertFalse("cleared by the last release", secure(activity))
        assertEquals(0, SecureWindowFlag.holdersFor(activity))
    }

    @Test fun anUnmatchedReleaseNeverGoesNegativeOrClearsALaterHolder() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SecureWindowFlag.release(activity)
        SecureWindowFlag.acquire(activity)
        assertTrue(secure(activity))
        assertEquals(1, SecureWindowFlag.holdersFor(activity))
        SecureWindowFlag.release(activity)
    }
}
