package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteProbe

/**
 * Read-only grading of what this rTorrent build *advertises* for each Chunks
 * feature (Smart Connect #464). Advertised is not authorized: a gateway or
 * proxy can still refuse a method, so every mutation keeps its own readback.
 * When system.listMethods is unavailable everything is UNKNOWN, never assumed.
 */
internal object RemoteCapabilities {
    enum class Status { ADVERTISED, MISSING, UNKNOWN }

    data class Feature(val label: String, val methods: List<String>)
    data class Graded(val feature: Feature, val status: Status, val missing: List<String>)

    val features = listOf(
        Feature("Transfer list", listOf("d.multicall2", "d.hash", "d.name")),
        // These are separate protocol routes, not an all-or-nothing Add bundle.
        // The ruTorrent HTTP upload form cannot be inferred from XML-RPC introspection.
        Feature("Add magnet via XML-RPC", listOf("d.multicall2", "d.name", "load.verbose")),
        Feature("Add .torrent via XML-RPC", listOf("d.multicall2", "d.name", "load.raw_verbose")),
        Feature("Use server default Add folder", listOf("directory.default")),
        Feature("Start and stop", listOf("d.open", "d.start", "d.resume", "d.stop", "d.close", "d.state")),
        Feature("File list and priorities", listOf("f.multicall", "f.priority.set", "d.update_priorities")),
        Feature("Tracker health", listOf("d.tracker_size", "t.multicall")),
        // Erase requires main-view preflight and exact d.hash readback,
        // not merely an advertised d.erase setter.
        Feature("Remove from server session", listOf("d.multicall2", "d.hash", "d.erase")),
        Feature("Copy files to this phone", listOf("network.listen.port", "network.bind_address", "d.is_private", "d.is_active")),
        Feature("Finish in Order", listOf("f.multicall", "f.priority.set", "d.update_priorities", "d.bitfield", "d.size_chunks"))
    )

    fun grade(probe: RemoteProbe): List<Graded> {
        val methods = probe.advertisedMethods
        return features.map { feature ->
            if (methods == null) Graded(feature, Status.UNKNOWN, emptyList())
            else {
                val missing = feature.methods.filter { it !in methods }
                Graded(feature, if (missing.isEmpty()) Status.ADVERTISED else Status.MISSING, missing)
            }
        }
    }

    fun summary(probe: RemoteProbe, graded: List<Graded>): String = when {
        probe.advertisedMethods == null ->
            "rTorrent ${probe.version} does not list its methods, so Chunks cannot pre-check features. " +
                "Each action is still verified when you use it."
        graded.all { it.status == Status.ADVERTISED } ->
            "rTorrent ${probe.version} advertises every method Chunks uses. A proxy may still refuse some; " +
                "each action is verified when you use it."
        else -> "rTorrent ${probe.version}: ${graded.count { it.status == Status.MISSING }} feature(s) look " +
            "unavailable on this server."
    }
}
