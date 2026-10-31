package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Read-only restore eligibility check for a store that may not yet exist.
 * Only an observed absent child or an observed empty/no-resume directory
 * means there is nothing to load. Every failed or ambiguous enumeration
 * reaches the engine's strict preflight instead of claiming an empty catalog.
 */
internal object LocalRestoreDiscovery {
    fun shouldAttemptRestore(
        directory: File,
        listFiles: (File) -> Array<File>? = { it.listFiles() }
    ): Boolean {
        val parent = directory.parentFile ?: return true
        val siblings = try { listFiles(parent) } catch (_: Exception) { null }
            ?: return true
        if (siblings.none { it.name == directory.name }) return false
        val entries = try { listFiles(directory) } catch (_: Exception) { null }
            ?: return true
        // Immutable resume events also represent restorable jobs or durable
        // retirement intents; both require strict engine reconciliation.
        return entries.any { it.name.endsWith(".resume") ||
            it.name.contains(".resume-event.") }
    }
}
