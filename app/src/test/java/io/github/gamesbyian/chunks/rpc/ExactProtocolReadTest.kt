package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual adapter wire/parser seams, not fake TorrentClient observations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExactProtocolReadTest {
    private val hash = "ab".repeat(20)
    private val other = "cd".repeat(20)
    private val request = ClientAddRequest(ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash"), null, false)

    private class Fixture(val client: TorrentClient, val writes: () -> Int)

    private fun fixture(
        kind: TorrentClientKind,
        replies: List<List<String>>,
        cancelPath: String? = null,
        duplicateWireKeys: Boolean = false,
        fileWire: String? = null,
        addWire: ((Int) -> String)? = null,
        removeWire: ((id: Int, attempt: Int) -> String)? = null,
        transmissionStatus: Any = 0,
        delugeHostStatus: () -> String = { "Connected" },
        sourceWire: ((String) -> String?)? = null,
        sessionWire: String? = null,
        beforeReply: (String) -> Unit = {}
    ): Fixture {
        var writes = 0
        val queue = ArrayDeque(replies)
        fun rows(field: String): String = JSONArray(queue.removeFirst().map {
            JSONObject().put(field, it).put("name", "fixture").put("status", transmissionStatus)
                .put("state", "pausedDL").put("save_path", "/data").put("downloadDir", "/data")
        }).toString()
        val client = when (kind) {
            TorrentClientKind.TRANSMISSION -> TransmissionClient(
                RemoteCredentials("http://127.0.0.1/transmission/rpc", "fixture", "password"),
                TransmissionReadOnlyRpc.Wire { req ->
                    val method = JSONObject(req.body.toString(Charsets.UTF_8)).getString("method")
                    beforeReply(method)
                    val args = when (method) {
                        "session-get" -> sessionWire ?: """{"version":"4.0","download-dir":"/data"}"""
                        "torrent-get" -> sourceWire?.invoke(method) ?: if (fileWire != null)
                            """{"torrents":[{"hashString":"$hash",$fileWire}]}"""
                            else if (duplicateWireKeys)
                            """{"torrents":[{"hashString":"$other"}],"torrents":[]}"""
                            else """{"torrents":${rows("hashString")}}"""
                        "torrent-add" -> {
                            writes++
                            if (addWire != null)
                                return@Wire TransmissionReadOnlyRpc.Response(200, emptyMap(), addWire(0))
                            "{}"
                        }
                        "torrent-remove" -> {
                            writes++
                            if (removeWire != null) return@Wire TransmissionReadOnlyRpc.Response(
                                200, emptyMap(), removeWire(0, writes))
                            "{}"
                        }
                        "torrent-stop" -> { writes++; "{}" }
                        else -> error("Unexpected method $method")
                    }
                    TransmissionReadOnlyRpc.Response(200, emptyMap(), """{"result":"success","arguments":$args}""")
                })
            TorrentClientKind.QBITTORRENT -> QBittorrentClient(
                RemoteCredentials("http://127.0.0.1/", "fixture", "password"),
                QBittorrentReadOnlyRpc.Wire { req ->
                    val method = req.url.path.substringAfter("/api/v2/")
                    beforeReply(method)
                    if (method == cancelPath) throw CancellationException("wire cancelled")
                    when (method) {
                        "auth/login" -> QBittorrentReadOnlyRpc.Response(200, "Ok.",
                            mapOf("Set-Cookie" to listOf("SID=fictional-session-000123")))
                        "app/version" -> QBittorrentReadOnlyRpc.Response(200, "v5.1")
                        "app/webapiVersion" -> QBittorrentReadOnlyRpc.Response(200, "2.11")
                        "app/defaultSavePath" -> QBittorrentReadOnlyRpc.Response(200, "/data")
                        "torrents/properties", "torrents/trackers", "torrents/uploadLimit" ->
                            QBittorrentReadOnlyRpc.Response(200, requireNotNull(sourceWire?.invoke(method)))
                        "torrents/info" -> QBittorrentReadOnlyRpc.Response(200, sourceWire?.invoke(method) ?: if (duplicateWireKeys)
                            """[{"hash":"$other","ha\u0073h":"$hash","state":"pausedDL","save_path":"/data"}]"""
                            else rows("hash"))
                        "torrents/export" -> QBittorrentReadOnlyRpc.Response(404, "")
                        "torrents/files" -> QBittorrentReadOnlyRpc.Response(200, requireNotNull(fileWire))
                        "torrents/add" -> {
                            writes++
                            QBittorrentReadOnlyRpc.Response(200, addWire?.invoke(0) ?: "Ok.")
                        }
                        "torrents/delete", "torrents/stop" -> { writes++; QBittorrentReadOnlyRpc.Response(200, "Ok.") }
                        else -> error("Unexpected method $method")
                    }
                })
            TorrentClientKind.DELUGE -> DelugeClient(
                RemoteCredentials("http://127.0.0.1/json", "", "password"), "fixture-host",
                DelugeReadOnlyRpc.Wire { req ->
                    val call = JSONObject(req.body.toString(Charsets.UTF_8))
                    val id = call.getInt("id")
                    val method = call.getString("method")
                    beforeReply(method)
                    val result = when (method) {
                        "auth.login", "web.connected" -> "true"
                        "web.get_hosts" -> """[["fixture-host","127.0.0.1",58846,"fixture"]]"""
                        "web.get_host_status" -> """["fixture-host","${delugeHostStatus()}","2.1"]"""
                        "daemon.get_method_list" -> """["core.add_torrent_magnet","core.remove_torrent","core.get_torrents_status"]"""
                        "core.get_config_value" -> """"/data""""
                        "core.get_torrent_status" -> sourceWire?.invoke(method) ?: requireNotNull(fileWire)
                        "core.get_torrents_status" -> sourceWire?.invoke(method) ?: if (duplicateWireKeys)
                            """{"$hash":{"name":"one"},"$hash":{"name":"two","state":"Paused","save_path":"/data"}}"""
                            else JSONObject().apply {
                                queue.removeFirst().forEach { put(it, JSONObject().put("name", "fixture")
                                    .put("state", "Paused").put("save_path", "/data")) }
                            }.toString()
                        "core.add_torrent_magnet" -> {
                            writes++
                            if (addWire != null) return@Wire DelugeReadOnlyRpc.Response(200, addWire(id))
                            "true"
                        }
                        "core.remove_torrent" -> {
                            writes++
                            if (removeWire != null) return@Wire DelugeReadOnlyRpc.Response(200, removeWire(id, writes))
                            "true"
                        }
                        "core.pause_torrent" -> { writes++; "true" }
                        else -> error("Unexpected method $method")
                    }
                    DelugeReadOnlyRpc.Response(200, """{"id":$id,"error":null,"result":$result}""",
                        if (method == "auth.login") mapOf("Set-Cookie" to listOf("_session_id=fictional-session-000123")) else emptyMap())
                })
            else -> error("Only the three JSON protocol adapters")
        }
        return Fixture(client) { writes }
    }

    private val kinds = listOf(TorrentClientKind.TRANSMISSION, TorrentClientKind.QBITTORRENT, TorrentClientKind.DELUGE)
    private val invalid = listOf(listOf(other), listOf(hash, other), listOf(hash, hash.uppercase()), listOf("bad"))

    @Test fun uploadCapsRequireExactTypedAndScopedWireEvidence() = runBlocking {
        val trans = TorrentClientKind.TRANSMISSION
        val qb = TorrentClientKind.QBITTORRENT
        val deluge = TorrentClientKind.DELUGE
        suspend fun observe(kind: TorrentClientKind, body: String, units: String = """{"units":{"speed-bytes":1000}}"""): Long? {
            val f = fixture(kind, emptyList(), sourceWire = { body }, sessionWire = units)
            // A malformed exact envelope may throw IOException; the policy port
            // treats that as unknown, just as it treats a null observation.
            val observed = try { f.client.uploadCapBytesPerSecond(hash) }
                catch (_: java.io.IOException) { null }
            assertEquals("Cap observation is read-only", 0, f.writes())
            return observed
        }
        fun tr(enabled: String, limit: String, h: String = hash) =
            """{"torrents":[{"hashString":"$h","uploadLimited":$enabled,"uploadLimit":$limit}]}"""
        fun de(value: String) = """{"$hash":{"max_upload_speed":$value}}"""
        assertEquals(128000L, observe(trans, tr("true", "128")))
        assertEquals(0L, observe(trans, tr("false", "128")))
        assertEquals(128000L, observe(trans, tr("true", "125"), """{"units":{"speed-bytes":1024}}"""))
        assertEquals(128000L, observe(qb, """{"$hash":128000}"""))
        assertEquals(0L, observe(qb, """{"$hash":0}"""))
        assertEquals(128000L, observe(deluge, de("125.0")))
        assertEquals(1536L, observe(deluge, de("1.5")))
        assertEquals(0L, observe(deluge, de("-1.0")))

        for (body in listOf(tr("null", "128"), tr("0", "128"), tr("\"false\"", "128"),
            tr("true", "\"128\""), tr("true", "128.5"), tr("true", "0"),
            tr("false", "-2"), tr("true", Long.MAX_VALUE.toString()), tr("true", "128", other))) {
            assertNull("Transmission: $body", observe(trans, body))
        }
        for (units in listOf("""{"units":{"speed-bytes":"1000"}}""",
            """{"units":{"speed-bytes":1000,"speed-bytes":1024}}""")) {
            assertNull(observe(trans, tr("true", "128"), units))
        }
        for (body in listOf("""{"$hash":"128000"}""", """{"$hash":128000.5}""",
            """{"$hash":-2}""", """{"$hash":null}""", """{"$hash":1,"$hash":128000}""",
            """{"$hash":128000,"$other":1}""", """{"$other":128000}""",
            """{"$hash":128000,"${hash.uppercase()}":1}""")) {
            assertNull("qBittorrent: $body", observe(qb, body))
        }
        for (body in listOf(de("\"125\""), de("125.0001"), de("-2"), de("null"), de("1e100"),
            """{"$hash":{"max_upload_speed":125},"$other":{"max_upload_speed":125}}""",
            """{"$hash":{"max_upload_speed":125,"max_upload_speed":126}}""")) {
            assertNull("Deluge: $body", observe(deluge, body))
        }
    }

    @Test fun magnetFallbackAndTrackerRulesRequireAnExplicitPublicBoolean() = runBlocking {
        val tracker = "https://tracker.invalid/announce"
        val magnet = "magnet:?xt=urn:btih:$hash&tr=$tracker"
        for (kind in kinds) for (privacy in listOf(null, JSONObject.NULL, 0, "false", true, false)) {
            val calls = mutableListOf<String>()
            val f = fixture(kind, emptyList(), beforeReply = { calls += it }, sourceWire = { method ->
                val trackers = JSONArray().put(JSONObject()
                    .put(if (kind == TorrentClientKind.TRANSMISSION) "announce" else "url", tracker))
                val row = JSONObject().put("trackers", trackers)
                    .put(if (kind == TorrentClientKind.TRANSMISSION) "isPrivate" else "private", privacy)
                when (kind) {
                    TorrentClientKind.TRANSMISSION -> JSONObject().put("torrents", JSONArray()
                        .put(row.put("hashString", hash).put("magnetLink", magnet))).toString()
                    TorrentClientKind.DELUGE ->
                        if (method == "core.get_torrent_status") row.toString()
                        else JSONObject().put(hash, row).toString()
                    TorrentClientKind.QBITTORRENT -> when (method) {
                        "torrents/properties" -> "{}"
                        "torrents/trackers" -> trackers.toString()
                        else -> JSONArray().put(row.put("hash", hash).put("magnet_uri", magnet)).toString()
                    }
                    else -> error("Unexpected adapter")
                }
            })
            val exported = f.client.transferSource(hash)
            val facts = requireNotNull(f.client.seedingFacts(hash))
            // The one documented carve-out: qBittorrent's explicit JSON null means "no metadata yet".
            // It may copy identity and name only; no announce URL may travel with unknown privacy.
            val hashOnly = kind == TorrentClientKind.QBITTORRENT && privacy === JSONObject.NULL
            assertEquals(privacy == false || hashOnly, exported is ClientAddSource.Magnet)
            if (hashOnly) {
                val uri = (exported as ClientAddSource.Magnet).uri
                assertEquals(hash, exported.infoHash)
                assertFalse("No tracker with unknown privacy", uri.contains("tr=") || uri.contains("tracker.invalid"))
            }
            assertEquals(privacy as? Boolean, facts.isPrivate)
            assertEquals(if (privacy == false) listOf(tracker) else null, facts.publicTrackerUrls)
            if (kind == TorrentClientKind.QBITTORRENT) {
                assertEquals(privacy === null, "torrents/properties" in calls)
                assertEquals(privacy == false, "torrents/trackers" in calls)
            }
            assertEquals(0, f.writes())
        }
    }

    @Test fun trackerReplyCannotCoerceAPreviouslyPublicFlag() = runBlocking {
        for (kind in listOf(TorrentClientKind.TRANSMISSION, TorrentClientKind.DELUGE)) {
            var count = 0
            val f = fixture(kind, emptyList(), sourceWire = {
                val privacy: Any = if (++count == 1) false else "false"
                val row = JSONObject().put("isPrivate", privacy).put("private", privacy)
                    .put("trackers", JSONArray().put(JSONObject()
                        .put("announce", "https://tracker.invalid").put("url", "https://tracker.invalid")))
                when (kind) {
                    TorrentClientKind.TRANSMISSION -> JSONObject().put("torrents", JSONArray()
                        .put(row.put("hashString", hash))).toString()
                    else -> JSONObject().put(hash, row).toString()
                }
            })
            val facts = requireNotNull(f.client.seedingFacts(hash))
            assertEquals(2, count)
            assertEquals(false, facts.isPrivate)
            assertNull(facts.publicTrackerUrls)
            assertEquals(0, f.writes())
        }
    }

    @Test fun ambiguousAddAcknowledgmentRemainsUncertainEvenWhenTheJobWouldBeVisible() = runBlocking {
        val bad = listOf<Pair<TorrentClientKind, (Int) -> String>>(
            TorrentClientKind.TRANSMISSION to {
                """{"result":"success","arguments":{"torrent-added":{"hashString":"$other","hashString":"$hash"}}}"""
            },
            TorrentClientKind.TRANSMISSION to {
                """{"result":"success","arguments":{"torrent-added":{"hashString":"$hash"},"torrent-duplicate":{"hashString":"$other"}}}"""
            },
            TorrentClientKind.QBITTORRENT to {
                """{"success_count":0,"success_count":1,"failure_count":0,"pending_count":0,"added_torrent_ids":["$hash"]}"""
            },
            TorrentClientKind.QBITTORRENT to {
                """{"success_count":1.0,"failure_count":0,"pending_count":0,"added_torrent_ids":["$hash"]}"""
            },
            TorrentClientKind.DELUGE to { id ->
                """{"id":$id,"error":null,"result":"$other","result":"$hash"}"""
            },
            TorrentClientKind.DELUGE to { id ->
                """{"id":$id,"error":{"code":1},"result":"$hash"}"""
            }
        )
        for ((kind, reply) in bad) {
            var reads = 0
            val f = fixture(kind, listOf(emptyList(), listOf(hash)), addWire = reply, beforeReply = {
                if (it in setOf("torrent-get", "torrents/info", "core.get_torrents_status")) reads++
            })
            val outcome = ClientOperations(f.client, readbackAttempts = 1, stoppedSettleReads = 0).add(request)
            assertTrue("$kind upgraded an ambiguous Add acknowledgment", outcome is ClientOperationOutcome.Uncertain)
            assertEquals(1, f.writes())
            assertEquals(1, reads)
        }
    }

    @Test fun exactFileAdaptersRejectMalformedIndexAndPriorityEvidence() = runBlocking {
        val qb = TorrentClientKind.QBITTORRENT
        val deluge = TorrentClientKind.DELUGE
        val transmission = TorrentClientKind.TRANSMISSION
        fun qbRows(firstIndex: String, secondIndex: String) =
            """[{$firstIndex"name":"root/first","size":1,"priority":1},
                {$secondIndex"name":"root/other","size":2,"priority":6}]"""
        val bad = listOf(
            qb to qbRows(""""index":0,""", """"index":0,"""),
            qb to qbRows(""""index":"0",""", """"index":1,"""),
            qb to qbRows(""""index":null,""", """"index":1,"""),
            qb to qbRows("", """"index":1,"""),
            qb to qbRows(""""index":0,""", """"index":2,"""),
            qb to """[{"index":0,"name":"one","size":1,"priority":null}]""",
            deluge to """{"files":[{"index":0,"path":"one","size":1}],"file_priorities":[]}""",
            deluge to """{"files":[{"index":0,"path":"one","size":1}],"file_priorities":["4"]}""",
            deluge to """{"files":[{"index":0.5,"path":"one","size":1}],"file_priorities":[4]}""",
            transmission to """"files":[{"name":"one","length":1}],"fileStats":[{"wanted":"true","priority":0}]""",
            transmission to """"files":[{"name":"one","length":1}],"fileStats":[{"wanted":true,"priority":8}]"""
        )
        for ((kind, wire) in bad) {
            val f = fixture(kind, emptyList(), fileWire = wire)
            assertTrue("$kind accepted a malformed file roster",
                runCatching { f.client.files(hash) }.exceptionOrNull() is java.io.IOException)
            assertEquals(0, f.writes())
        }
    }

    @Test fun exactFileAdaptersAcceptCompleteExplicitOrLegacyPositionalEvidence() = runBlocking {
        val qb = fixture(TorrentClientKind.QBITTORRENT, emptyList(), fileWire =
            """[{"index":1,"name":"root/other","size":2,"priority":6},
                {"index":0,"name":"root/first","size":1,"priority":1}]""")
        assertEquals(mapOf(0 to ClientFilePriority.NORMAL, 1 to ClientFilePriority.HIGH),
            qb.client.files(hash).associate { it.index to it.priority })
        val legacy = fixture(TorrentClientKind.QBITTORRENT, emptyList(), fileWire =
            """[{"name":"one","size":1,"priority":0,"progress":"1.0"}]""")
        assertEquals(0, legacy.client.files(hash).single().index)
        assertNull(legacy.client.files(hash).single().completedBytes)
        val deluge = fixture(TorrentClientKind.DELUGE, emptyList(), fileWire =
            """{"files":[{"index":1,"path":"root/other","size":2},{"index":0,"path":"root/first","size":1}],
                "file_priorities":[0,7]}""")
        assertEquals(mapOf(0 to ClientFilePriority.SKIP, 1 to ClientFilePriority.HIGH),
            deluge.client.files(hash).associate { it.index to it.priority })
        val transmission = fixture(TorrentClientKind.TRANSMISSION, emptyList(), fileWire =
            """"files":[{"name":"one","length":1,"bytesCompleted":1}],"fileStats":[{"wanted":false,"priority":-1}]""")
        assertEquals(ClientFile(0, "one", 1, 1, ClientFilePriority.SKIP), transmission.client.files(hash).single())
    }

    @Test fun retiredPolicyOwnerCannotSendMutationsAfterAuthentication() = runBlocking {
        for (kind in kinds) {
            var current = true
            val f = fixture(kind, emptyList(), beforeReply = { method ->
                if (method in setOf("session-get", "auth/login", "auth.login")) current = false
            })
            withContext(RemoteWriteOwnerFence { current }) {
                f.client.session()
                assertFalse("Fixture retired the owner during $kind authentication", current)
                try {
                    f.client.setStarted(hash, false)
                    fail("$kind accepted a revoked policy dispatch")
                } catch (_: CancellationException) { }
            }
            assertEquals("zero mutation requests for $kind", 0, f.writes())
        }
    }

    @Test fun duplicateDecodedWireKeysNeverAuthorizeAnExactReadOrAdd() = runBlocking {
        for (kind in kinds) {
            val fixture = fixture(kind, emptyList(), duplicateWireKeys = true)
            try {
                fixture.client.torrent(hash)
                fail("$kind accepted an ambiguous wire reply")
            } catch (_: java.io.IOException) { }
            val ops = ClientOperations(fixture.client, readbackAttempts = 1)
            assertTrue(ops.add(request) is ClientOperationOutcome.Refused)
            assertEquals("zero mutation requests for $kind", 0, fixture.writes())
        }
    }

    @Test fun emptyAndCaseInsensitiveExactPositiveRemainDistinct() = runBlocking {
        for (kind in kinds) {
            val f = fixture(kind, listOf(emptyList(), listOf(hash.uppercase())))
            assertNull(f.client.torrent(hash))
            assertEquals(hash, f.client.torrent(hash)?.hash)
            assertEquals(0, f.writes())
        }
    }

    @Test fun unexpectedOrAmbiguousRowsCannotBecomeAbsenceOrAuthorizeAdd() = runBlocking {
        for (kind in kinds) for (bad in invalid) {
            val f = fixture(kind, listOf(bad, bad))
            try {
                f.client.torrent(hash)
                fail("$kind accepted an invalid exact response")
            } catch (expected: java.io.IOException) { }
            val result = ClientOperations(f.client, readbackAttempts = 1, stoppedSettleReads = 0).add(request)
            assertTrue("$kind $result", result is ClientOperationOutcome.Refused)
            assertEquals(0, f.writes())
        }
    }

    @Test fun wrongIdentityAfterRemovalCannotConfirmAbsence() = runBlocking {
        for (kind in kinds) {
            val f = fixture(kind, listOf(listOf(hash), listOf(other)))
            val result = ClientOperations(f.client, readbackAttempts = 1).remove(hash, false)
            assertTrue("$kind $result", result is ClientOperationOutcome.Uncertain)
            assertEquals(1, f.writes())
        }
    }

    @Test fun malformedRemovalRepliesStayUncertainWithoutASecondWriteOrReadback() = runBlocking {
        val deluge = TorrentClientKind.DELUGE
        val transmission = TorrentClientKind.TRANSMISSION
        val bad: List<Pair<TorrentClientKind, (Int, Int) -> String>> = listOf(
            deluge to { id, _ -> """{"id":$id,"error":{"code":"1","message":"Not authenticated"},"result":null}""" },
            deluge to { id, _ -> """{"id":$id,"error":{"code":1.5,"message":"Not authenticated"},"result":null}""" },
            deluge to { id, _ -> """{"id":$id,"error":{"code":1,"message":"Not authenticated"},"result":true}""" },
            deluge to { id, _ -> """{"id":$id,"error":{"code":4,"code":1,"message":"Not authenticated"},"result":null}""" },
            deluge to { id, _ -> """{"id":$id.25,"error":null,"result":true}""" },
            deluge to { id, _ -> """{"id":$id,"error":{"code":4,"message":"Daemon error"},"result":null}""" },
            deluge to { id, _ -> """{"id":$id,"error":null,"result":"true"}""" },
            transmission to { _, _ -> """{"result":"failure","result":"success","arguments":{}}""" },
            transmission to { _, _ -> """{"result":null,"arguments":{}}""" }
        )
        for ((kind, wire) in bad) {
            var exactReads = 0
            val f = fixture(kind, listOf(listOf(hash), emptyList()), removeWire = wire, beforeReply = {
                if (it == "torrent-get" || it == "core.get_torrents_status") exactReads++
            })
            val outcome = ClientOperations(f.client, readbackAttempts = 1).remove(hash, deleteData = true)
            assertTrue("$kind $outcome", outcome is ClientOperationOutcome.Uncertain)
            assertEquals(1, f.writes())
            assertEquals(1, exactReads)
        }
    }

    @Test fun delugeOnlyRetriesADocumentedPreDispatchRejectionAndHonorsCancellation() = runBlocking {
        for (cancelLogin in listOf(false, true)) {
            var logins = 0
            val f = fixture(TorrentClientKind.DELUGE, listOf(listOf(hash), emptyList()),
                removeWire = { id, attempt ->
                    if (attempt == 1) """{"id":$id,"error":{"code":1,"message":"Not authenticated"},"result":null}"""
                    else """{"id":$id,"error":null,"result":true}"""
                }, beforeReply = { method ->
                    if (method == "auth.login" && ++logins == 2 && cancelLogin) {
                        throw CancellationException("cancelled during reauthentication")
                    }
                })
            val ops = ClientOperations(f.client, readbackAttempts = 1)
            if (cancelLogin) {
                try {
                    ops.remove(hash, deleteData = false)
                    fail("Cancellation was converted into an operation result")
                } catch (_: CancellationException) { }
                assertEquals(1, f.writes())
            } else {
                assertTrue(ops.remove(hash, deleteData = false) is ClientOperationOutcome.Verified)
                assertEquals(2, f.writes())
            }
            assertEquals(2, logins)
        }
    }

    @Test fun delugeGlobalWebConnectionCannotAuthorizeAnotherDaemonsWrite() = runBlocking {
        var host = "Connected"
        val f = fixture(TorrentClientKind.DELUGE, listOf(listOf(hash)),
            delugeHostStatus = { host }, beforeReply = { method ->
                if (method == "core.get_torrents_status") host = "Online"
            })
        // The selected daemon was still bound at preflight. Before dispatch
        // it became merely available while Web UI stayed globally connected.
        val outcome = ClientOperations(f.client, readbackAttempts = 1).remove(hash, deleteData = true)
        assertTrue(outcome is ClientOperationOutcome.Refused)
        assertEquals(0, f.writes())
        assertTrue(runCatching { f.client.torrent(hash) }.exceptionOrNull() is ClientRefusedException)
    }

    @Test fun delugeReadIsDiscardedWhenTheSelectedDaemonIsNoLongerBoundAfterIt() = runBlocking {
        var host = "Connected"
        val statusCalls = ArrayList<String>()
        val f = fixture(TorrentClientKind.DELUGE, listOf(listOf(hash)),
            delugeHostStatus = { host }, beforeReply = { method ->
                statusCalls += method
                // The status check before the read still passed; the shared slot moved during the read.
                if (method == "core.get_torrents_status") host = "Online"
            })
        // The rows below could belong to another daemon behind the same Web UI: never usable evidence.
        assertTrue(runCatching { f.client.torrent(hash) }.exceptionOrNull() is java.io.IOException)
        assertEquals("web.get_host_status", statusCalls.last())
        assertEquals(0, f.writes())
    }

    @Test fun delugeRemovalReadbackCannotVerifyWhenTheSelectedDaemonIsNotBoundAfterTheAbsentRead() = runBlocking {
        var host = "Connected"
        var inventoryReads = 0
        val f = fixture(TorrentClientKind.DELUGE, listOf(listOf(hash), emptyList()),
            delugeHostStatus = { host }, beforeReply = { method ->
                // The readback that would show the job absent is the one served by another daemon.
                if (method == "core.get_torrents_status" && ++inventoryReads == 2) host = "Online"
            })
        val outcome = ClientOperations(f.client, readbackAttempts = 1).remove(hash, deleteData = false)
        assertFalse("An absence read from an unbound slot verified a removal: $outcome",
            outcome is ClientOperationOutcome.Verified)
        assertEquals(1, f.writes())
    }

    @Test fun malformedTransmissionStatusCannotProveStoppedAdd() = runBlocking {
        for (wireStatus in listOf("0", 0.5, 4_294_967_296L)) {
            val f = fixture(TorrentClientKind.TRANSMISSION, listOf(emptyList(), listOf(hash)),
                transmissionStatus = wireStatus, addWire = {
                    """{"result":"success","arguments":{"torrent-added":{"hashString":"$hash"}}}"""
                })
            val outcome = ClientOperations(f.client, readbackAttempts = 1, stoppedSettleReads = 0).add(request)
            assertTrue("Malformed status $wireStatus became successful readback", outcome is ClientOperationOutcome.Pending)
            assertEquals(1, f.writes())
        }
    }

    @Test fun qbittorrentWireCancellationIsNotMissingSourceOrInvalidFilesOrSeedingFacts() = runBlocking {
        suspend fun cancelled(path: String, operation: suspend (TorrentClient) -> Unit) {
            val f = fixture(TorrentClientKind.QBITTORRENT, listOf(listOf(hash)), path)
            try {
                operation(f.client)
                fail("Cancellation converted into a result at $path")
            } catch (expected: CancellationException) { }
            assertEquals(0, f.writes())
        }
        cancelled("torrents/info") { it.transferSource(hash) }
        cancelled("torrents/files") { it.files(hash) }
        cancelled("torrents/info") { it.seedingFacts(hash) }
        cancelled("torrents/properties") { it.seedingFacts(hash) }
    }
}
