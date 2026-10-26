package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Owner-authorized diagnosis of why acknowledged Adds are not retained on the
 * development Seedhost. Each variant uses its own unique synthetic identity, is
 * looked up by exact hash, and only our own synthetic transfer is ever erased.
 * Results are evidence lines; this test does not fail on a rejected variant.
 */
class SeedhostAddDiagnosticsTest {
    private fun log(line: String) = println("CHUNKS_LIVE: " + line.replace(Regex("[\\r\\n]"), " ").take(400))

    private fun torrent(name: String): ByteArray {
        val payload = "Chunks acceptance payload\n".toByteArray()
        val piece = MessageDigest.getInstance("SHA-1").digest(payload)
        val head = "d4:infod6:lengthi${payload.size}e4:name${name.length}:${name}12:piece lengthi16384e6:pieces20:"
        return head.toByteArray(Charsets.ISO_8859_1) + piece + "ee".toByteArray()
    }

    @Test fun compareAddVariantsOnDevelopmentSeedhost() = runBlocking {
        val rawUrl = System.getenv("CHUNKS_LIVE_RUTORRENT_URL").orEmpty()
        assumeTrue(rawUrl.isNotBlank() && System.getenv("CHUNKS_LIVE_MUTATION") == "apply" &&
            System.getenv("CHUNKS_LIVE_DIAGNOSE_ADD") == "true")
        val url = URI(rawUrl)
        require(url.scheme == "https" && url.host?.endsWith(".seedhost.eu") == true && url.userInfo == null)
        val rtorrent = RTorrentRemote(RemoteCredentials(rawUrl,
            System.getenv("CHUNKS_LIVE_RTORRENT_USER").orEmpty(), System.getenv("CHUNKS_LIVE_RTORRENT_PASS").orEmpty()))
        val directories = rtorrent.listTransfers("main").map { it.directory }
        val parts = directories.map { it.trimEnd('/').split('/').drop(1) }
        var shared = parts.first()
        for (p in parts.drop(1)) shared = shared.zip(p).takeWhile { (a, b) -> a == b }.map { it.first }
        require(shared.size >= 2)
        val base = "/" + shared.joinToString("/")
        log("diagnose base has spaces=${base.contains(' ')} depth=${shared.size}")
        val run = System.getenv("GITHUB_RUN_ID").orEmpty().ifEmpty { System.currentTimeMillis().toString() }

        val variants = listOf<Pair<String, (ByteArray) -> Pair<String, List<Any>>>>(
            "raw_verbose-no-commands" to { bytes -> "load.raw_verbose" to listOf("", bytes) },
            "raw_verbose-quoted-directory" to { bytes -> "load.raw_verbose" to listOf("", bytes, "d.directory.set=\"$base\"") },
            "raw_verbose-directory_base" to { bytes -> "load.raw_verbose" to listOf("", bytes, "d.directory_base.set=\"$base\"") },
            "raw-no-verbose" to { bytes -> "load.raw" to listOf("", bytes) }
        )
        for ((label, build) in variants) {
            val name = "chunks-acceptance-$run-$label.txt"
            val bytes = torrent(name)
            val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
            val (method, params) = build(bytes)
            val ack = try { "ok:" + rtorrent.call(method, params) } catch (e: Exception) { "error:" + e::class.simpleName + ":" + e.message?.take(160) }
            var found: String? = null
            for (i in 1..30) {
                found = rtorrent.lookupName(hash)
                if (found != null) break
                Thread.sleep(500)
            }
            log("variant=$label method=$method ack=$ack retained=${found != null}")
            if (found != null) {
                val listed = rtorrent.listTransfers("main").firstOrNull { it.hash.equals(hash, true) }
                log("variant=$label listedInMain=${listed != null} name=${found == name} started=${listed?.started} dirIsBase=${listed?.directory == base}")
                if (listed != null && listed.name == name) {
                    log("variant=$label erase=${rtorrent.eraseFromSession(listed.hash, listed.name, listed.directory)::class.simpleName}")
                } else {
                    log("variant=$label erase via d.erase exact hash=${runCatching { rtorrent.call("d.erase", listOf(hash)) }.isSuccess}")
                }
                Thread.sleep(1000)
                log("variant=$label stillPresentAfterErase=${rtorrent.lookupName(hash) != null}")
            }
        }
        // A magnet for a synthetic identity: does any Add path retain a transfer?
        val magnetHash = MessageDigest.getInstance("SHA-1").digest("chunks-acceptance-magnet-$run".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val magnetAck = try { "ok:" + rtorrent.call("load.verbose", listOf("", "magnet:?xt=urn:btih:$magnetHash&dn=chunks-acceptance-$run-magnet")) }
            catch (e: Exception) { "error:" + e::class.simpleName }
        var magnetFound = false
        for (i in 1..30) { if (rtorrent.lookupName(magnetHash) != null) { magnetFound = true; break }; Thread.sleep(500) }
        log("variant=magnet method=load.verbose ack=$magnetAck retained=$magnetFound")
        if (magnetFound) {
            log("variant=magnet erase=${runCatching { rtorrent.call("d.erase", listOf(magnetHash)) }.isSuccess}")
            Thread.sleep(1000)
            log("variant=magnet stillPresentAfterErase=${rtorrent.lookupName(magnetHash) != null}")
        }
    }
}
