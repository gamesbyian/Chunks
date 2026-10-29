package io.github.gamesbyian.chunks.discovery

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * The single master switch for Chunks-managed torrent discovery
 * (D-DISCOVERY-20261029, docs/INTAKE-QUEUE-AND-POLICIES.md). Off until the user
 * enables it. Every discovery-triggered network request, refresh and Add
 * submission must hold a [Ticket] and re-check it immediately before acting,
 * so turning Discovery off stops work already in flight at its next step.
 *
 * This gate covers discovery only. Manual Adds, Android Open with/Share,
 * separately authorized local watched-folder intake, existing transfers and
 * Create & Share never consult it.
 */
class DiscoveryGate(private val store: Store) {
    interface Store {
        fun read(): Settings
        /** Returns false when the change could not be saved; the gate then keeps the old state. */
        fun write(settings: Settings): Boolean
    }

    /** Saved switches. Sub-settings stay saved while Discovery is off but have no effect. */
    data class Settings(
        val enabled: Boolean = false,
        val automaticIntake: Boolean = false,
        val autoStartAfterIntake: Boolean = false
    )

    /** Proof that Discovery was on when work began; invalid as soon as it is turned off. */
    class Ticket internal constructor(internal val generation: Long)

    private val generation = AtomicLong(0)
    @Volatile private var current: Settings = store.read()

    val settings: Settings get() = current
    val enabled: Boolean get() = current.enabled

    /** Automatic intake needs Discovery AND its own consent; auto-start additionally needs intake. */
    val automaticIntakeAllowed get() = current.enabled && current.automaticIntake
    val autoStartAllowed get() = automaticIntakeAllowed && current.autoStartAfterIntake

    /** Null while Discovery is off: the caller must not start discovery work. */
    @Synchronized
    fun begin(): Ticket? = if (current.enabled) Ticket(generation.get()) else null

    /** Re-check right before each network request or Add submission. */
    fun stillAllowed(ticket: Ticket): Boolean = current.enabled && ticket.generation == generation.get()

    /**
     * Turning off invalidates every outstanding ticket before anything else, then
     * saves. Sub-settings are kept. Enabling never turns on intake or auto-start.
     */
    @Synchronized
    fun setEnabled(on: Boolean): Boolean {
        if (!on) generation.incrementAndGet()
        val next = current.copy(enabled = on)
        if (!store.write(next)) return false
        current = next
        return true
    }

    @Synchronized
    fun setAutomaticIntake(on: Boolean): Boolean = update(current.copy(automaticIntake = on,
        autoStartAfterIntake = current.autoStartAfterIntake && on))

    @Synchronized
    fun setAutoStart(on: Boolean): Boolean = update(current.copy(autoStartAfterIntake = on && current.automaticIntake))

    private fun update(next: Settings): Boolean {
        if (!store.write(next)) return false
        current = next
        return true
    }

    /** Device-private persistence; a missing or unreadable value means off. */
    class PreferencesStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences("chunks_discovery_v1", Context.MODE_PRIVATE)
        override fun read() = try {
            Settings(prefs.getBoolean("enabled", false), prefs.getBoolean("automatic_intake", false),
                prefs.getBoolean("auto_start", false))
        } catch (_: RuntimeException) { Settings() }
        override fun write(settings: Settings) = try {
            prefs.edit().putBoolean("enabled", settings.enabled).putBoolean("automatic_intake", settings.automaticIntake)
                .putBoolean("auto_start", settings.autoStartAfterIntake).commit()
        } catch (_: RuntimeException) { false }
    }
}
