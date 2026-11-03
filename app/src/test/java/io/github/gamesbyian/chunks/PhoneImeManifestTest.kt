package io.github.gamesbyian.chunks

import android.content.ComponentName
import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** IME resizing must be explicit for a measured, persistent Transfers footer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneImeManifestTest {
    @Test fun transferActivityRequestsKeyboardResizeInsteadOfPanningFooterOverInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
    }
}
