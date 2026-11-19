package io.github.gamesbyian.chunks.engine

import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle

/**
 * The few native operations a *stopped* file check needs. A narrow port so the
 * command ORDER (the part that went wrong in production) can be proven
 * deterministically against a faithful model of libtorrent's documented
 * `stop_when_ready` semantics, with the disk-check completion placed at every
 * possible point in the command sequence.
 */
internal interface StoppedCheckNative {
    fun forceRecheck()
    fun resume()
    fun armStopWhenReady()
    fun disarmStopWhenReady()
    fun pause()
}

internal fun TorrentHandle.stoppedCheck(): StoppedCheckNative = object : StoppedCheckNative {
    override fun forceRecheck() = this@stoppedCheck.forceRecheck()
    override fun resume() = this@stoppedCheck.resume()
    override fun armStopWhenReady() = this@stoppedCheck.setFlags(TorrentFlags.STOP_WHEN_READY)
    override fun disarmStopWhenReady() = this@stoppedCheck.unsetFlags(TorrentFlags.STOP_WHEN_READY)
    override fun pause() = this@stoppedCheck.pause()
}

/**
 * Re-hash a STOPPED job's existing files without ever starting it: libtorrent's
 * own check, which pauses the job itself when it ends (no peer or payload
 * traffic, never a Start).
 *
 * Why the order is `forceRecheck`, `resume`, THEN arm `stop_when_ready`:
 * - `force_recheck` posts its disk job immediately, even while the torrent is
 *   paused. For a job with no payload on disk it finishes at once and leaves
 *   the torrent in a downloading/finished/seeding state, still paused.
 * - Setting `stop_when_ready` while the torrent is ALREADY in such a state
 *   triggers the stop immediately ("to avoid race condition") and clears the
 *   flag; otherwise the flag stays armed until the torrent leaves checking.
 * - Arming BEFORE `resume()` therefore lets a fast check pause the job and
 *   then have our own `resume()` start it again, with the flag already
 *   consumed: the observed 60 s "Uncertain" with the job left running.
 * - Arming AFTER `resume()` is safe whichever side of the check completion the
 *   command lands on: still checking, the flag stays armed and pauses at the
 *   end of the check; already complete, the immediate trigger pauses it, and
 *   nothing runs after that to undo it.
 */
internal object StoppedCheck {
    /**
     * Start the stopped check. [queueCheck] is false when a check is already
     * queued or running, so it is let run rather than queued again.
     * Returns false after putting the job back to stopped if any native call fails.
     */
    fun begin(job: StoppedCheckNative, queueCheck: Boolean): Boolean = try {
        if (queueCheck) job.forceRecheck()
        job.resume()
        job.armStopWhenReady()
        true
    } catch (_: Exception) {
        restop(job)
        false
    }

    /** Put the job back to stopped. Each step is attempted even if the previous one fails. */
    fun restop(job: StoppedCheckNative) {
        runCatching { job.disarmStopWhenReady() }
        runCatching { job.pause() }
    }

    /**
     * True when a job that must stay stopped is RUNNING with its auto-stop gone
     * (not paused, not armed, not checking). A correct stopped check can never
     * end there. A slow legitimate check (still armed, or still checking) is NOT
     * a lost stop and must be left to stop itself.
     */
    fun stopWasLost(paused: Boolean, armed: Boolean, checking: Boolean): Boolean =
        !paused && !armed && !checking
}
