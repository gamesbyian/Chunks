package io.github.gamesbyian.chunks.privacy

import android.app.Activity
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * Reference-counted FLAG_SECURE per Activity window. Several independent owners (each privacy
 * gate, combined Transfers' own decision, a gate row inside a lazy list that is disposed when it
 * scrolls off-screen) may want the window secure at once. Window flags are not counted, so one
 * owner's clear must never remove another owner's protection: the flag is set by the first
 * acquire and cleared only by the last release. Main thread only (Compose effects).
 */
internal object SecureWindowFlag {
    private val holders = WeakHashMap<Activity, Int>()

    fun acquire(activity: Activity?) {
        activity ?: return
        val count = holders[activity] ?: 0
        holders[activity] = count + 1
        if (count == 0) activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity?) {
        activity ?: return
        val count = holders[activity] ?: return
        if (count <= 1) {
            holders.remove(activity)
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else holders[activity] = count - 1
    }

    internal fun holdersFor(activity: Activity): Int = holders[activity] ?: 0
}
