package io.github.gamesbyian.chunks.rpc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Deluge Web UI stand-in with the one property that matters for #2072: a single
 * process-global daemon slot. `web.connect` rebinds it, `core.*` runs against whatever
 * is bound when the call EXECUTES (after the simulated latency), and host status reports
 * "Connected" only for the bound host. Nothing here is atomic across calls, like the real
 * thing, so interleaved brackets of two sessions really can disturb each other.
 */
internal class FakeSharedSlotWebUi(
    val hostIds: List<String>,
    private val latencyMs: Long = 2L,
    private val sessionId: String = "fakeSessionIdentifier1234567890"
) : DelugeReadOnlyRpc.Wire {
    @Volatile var slot: String? = null
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    /** daemon -> (hash -> save_path). Identical hashes may live on several daemons. */
    val inventories: Map<String, ConcurrentHashMap<String, String>> =
        hostIds.associateWith { ConcurrentHashMap<String, String>() }
    /** (slot at execution, hash) of every write that reached a daemon. */
    val writes = CopyOnWriteArrayList<Pair<String?, String>>()
    @Volatile var gateMethod: String? = null
    @Volatile var gateEntered = CompletableDeferred<Unit>()
    @Volatile var gateRelease = CompletableDeferred<Unit>()
    @Volatile var switchSlotAfterNextCore: String? = null
    private val maxConcurrent = AtomicInteger(0)
    private val concurrent = AtomicInteger(0)
    @Volatile var observedMaxConcurrentExchanges = 0

    fun count(method: String) = counters[method]?.get() ?: 0
    fun totalCalls() = counters.values.sumOf { it.get() }
    fun coreCalls() = counters.entries.filter { it.key.startsWith("core.") }.sumOf { it.value.get() }
    fun resetCounts() = counters.clear()

    fun gateNext(method: String) {
        gateEntered = CompletableDeferred(); gateRelease = CompletableDeferred(); gateMethod = method
    }

    private fun reply(id: Int, result: String) =
        DelugeReadOnlyRpc.Response(200, """{"id":$id,"error":null,"result":$result}""")
    private fun fail(id: Int, code: Int, message: String) = DelugeReadOnlyRpc.Response(200,
        """{"id":$id,"result":null,"error":{"code":$code,"message":"$message"}}""")

    private fun status(host: String) = """["$host","${if (slot == host) "Connected" else "Online"}","2.1.1"]"""
    private val methods = """["core.get_torrents_status","core.get_torrent_status","core.add_torrent_magnet",""" +
        """"core.set_torrent_options","core.get_config_value","core.pause_torrents","core.resume_torrents"]"""

    override suspend fun exchange(request: DelugeReadOnlyRpc.Request): DelugeReadOnlyRpc.Response {
        val call = JSONObject(request.body.toString(Charsets.UTF_8))
        val method = call.getString("method")
        val id = call.getInt("id")
        val params = call.getJSONArray("params")
        counters.computeIfAbsent(method) { AtomicInteger() }.incrementAndGet()
        val now = concurrent.incrementAndGet()
        maxConcurrent.updateAndGet { maxOf(it, now) }
        observedMaxConcurrentExchanges = maxConcurrent.get()
        try {
            if (method == "auth.login") {
                return DelugeReadOnlyRpc.Response(200, """{"id":$id,"error":null,"result":true}""",
                    mapOf("Set-Cookie" to listOf("_session_id=$sessionId; Path=/json")))
            }
            if (request.sessionCookie != "_session_id=$sessionId") return fail(id, 1, "Not authenticated")
            if (gateMethod == method) {
                gateMethod = null
                gateEntered.complete(Unit)
                gateRelease.await()
            }
            if (latencyMs > 0) delay(latencyMs)
            return when (method) {
                "web.get_hosts" -> reply(id, JSONArray(hostIds.map { JSONArray(listOf(it, "127.0.0.1", 58846, "fixture")) }).toString())
                "web.get_host_status" -> reply(id, status(params.getString(0)))
                "web.connect" -> { slot = params.getString(0); reply(id, methods) }
                "daemon.get_method_list" -> if (slot == null) fail(id, 3, "Not connected") else reply(id, methods)
                "daemon.info" -> reply(id, "\"2.1.1\"")
                "web.update_ui" -> {
                    val daemon = slot ?: return fail(id, 3, "Not connected")
                    val rows = JSONObject()
                    inventories.getValue(daemon).forEach { (h, p) ->
                        rows.put(h, JSONObject().put("name", "t-$daemon").put("state", "Seeding").put("total_size", 10)
                            .put("total_done", 10).put("download_payload_rate", 0).put("upload_payload_rate", 0).put("save_path", p))
                    }
                    reply(id, JSONObject().put("torrents", rows).toString())
                }
                else -> core(method, params, id)
            }
        } finally {
            concurrent.decrementAndGet()
        }
    }

    private fun core(method: String, params: JSONArray, id: Int): DelugeReadOnlyRpc.Response {
        val daemon = slot ?: return fail(id, 3, "Not connected")
        val out = when (method) {
            "core.get_config_value" -> "\"/downloads\""
            "core.get_torrents_status" -> {
                val only = params.optJSONObject(0)?.optJSONArray("id")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                val rows = JSONObject()
                inventories.getValue(daemon).filter { only == null || it.key in only }.forEach { (h, p) ->
                    rows.put(h, JSONObject().put("name", "t-$daemon").put("state", "Seeding").put("total_wanted", 10)
                        .put("total_done", 10).put("download_payload_rate", 0).put("upload_payload_rate", 0).put("save_path", p))
                }
                rows.toString()
            }
            "core.get_torrent_status" -> """{"files":[{"index":0,"path":"f.bin","size":10}],"file_priorities":[4],"file_progress":[1.0]}"""
            "core.add_torrent_magnet" -> {
                val hash = Regex("btih:([0-9a-fA-F]{40})").find(params.getString(0))!!.groupValues[1].lowercase()
                val path = params.optJSONObject(1)?.optString("download_location")?.takeIf { it.isNotEmpty() } ?: "/downloads"
                inventories.getValue(daemon)[hash] = path
                writes += daemon to hash
                "\"$hash\""
            }
            "core.set_torrent_options" -> { writes += daemon to params.getJSONArray(0).getString(0); "null" }
            "core.pause_torrents", "core.resume_torrents" -> { writes += daemon to params.getJSONArray(0).getString(0); "null" }
            else -> return fail(id, 2, "Unknown method")
        }
        switchSlotAfterNextCore?.let { slot = it; switchSlotAfterNextCore = null }
        return reply(id, out)
    }
}
