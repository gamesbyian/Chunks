package io.github.gamesbyian.chunks.rpc

import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-time, MANUAL, main-only fixture provisioning for the two already
 * authorized numbered development rTorrent instances. Adds stopped metadata
 * only. Never Start, Stop, Erase, remove bytes or retry an uncertain Add.
 *
 * Each main-only dispatch has a new run-specific, originally absent hash.
 * Failures leave the prepared, attempted hash in a sanitized receipt for
 * read-only manual reconciliation. A rerun cannot reissue any mutation.
 */
class SeedhostP0CohortProvisionTest {
    private data class Fixture(
        val id: String, val name: String, val bytes: ByteArray, val expectedFiles: Int
    ) {
        val hash: String get() = TorrentMetainfoReader.preview(bytes).infoHashV1
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)

    private fun single(name: String, text: String, id: String): Fixture {
        val payload = text.toByteArray(Charsets.UTF_8)
        val infoPrefix = ("d6:lengthi${payload.size}e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:").toByteArray(Charsets.ISO_8859_1)
        val metadata = "d4:info".toByteArray() +
            infoPrefix + sha1(payload) + "ee".toByteArray()
        return Fixture(id, name, metadata, 1)
    }

    private fun multi(name: String, text: String, id: String): Fixture {
        val first = "first.bin"
        val second = "second.bin"
        val a = ("first:" + text).toByteArray(Charsets.UTF_8)
        val b = ("second:" + text).toByteArray(Charsets.UTF_8)
        val header = (
            "d4:infod5:filesl" +
            "d6:lengthi${a.size}e4:pathl${first.length}:$first" + "ee" +
            "d6:lengthi${b.size}e4:pathl${second.length}:$second" + "ee" +
            "e4:name${name.length}:$name" +
            "12:piece lengthi16384e6:pieces20:"
        ).toByteArray(Charsets.ISO_8859_1)
        return Fixture(id, name,
            header + sha1(a + b) + "ee".toByteArray(Charsets.ISO_8859_1), 2)
    }

    private fun plan(runId: String, instance: String): List<Fixture> {
        require(runId.matches(Regex("[0-9]{7,20}")))
        require(instance == "2" || instance == "3")
        return listOf(
            // Identical torrent metadata on two DIFFERENT servers: same hash,
            // different exact environment identity. Intentionally stopped.
            single("Chunks-P0-shared-$runId.bin",
                "Chunks shared exact-hash test fixture for run $runId",
                "remote-$instance-samehash"),
            multi("Chunks-P0-priority-$runId-$instance",
                "Chunks different-priority test for run $runId instance $instance",
                "remote-$instance-multifile")
        )
    }

    private fun route(instance: String, rawUrl: String) {
        val exact = "https://ianseeds.wood.seedhost.eu/rutorrent$instance/"
        require((instance == "2" || instance == "3") && rawUrl == exact) {
            "Cohort provisioning requires the exact authorized numbered instance route"
        }
        val uri = URI(rawUrl)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
    }

    @Test fun fixtureIdentitiesAreStablePerDispatchAndDistinctAcrossRuns() {
        val a = plan("41769999999", "2")
        val b = plan("41769999999", "3")
        val next = plan("41770000000", "2")
        assertEquals(a[0].hash, b[0].hash)
        assertNotEquals(a[1].hash, b[1].hash)
        assertNotEquals(a[0].hash, next[0].hash)
        assertEquals(listOf(1, 2), a.map { it.expectedFiles })
        assertTrue(a.all { it.hash.matches(Regex("[0-9a-fA-F]{40}")) })
        route("2", "https://ianseeds.wood.seedhost.eu/rutorrent2/")
        route("3", "https://ianseeds.wood.seedhost.eu/rutorrent3/")
        try {
            route("2", "https://ianseeds.wood.seedhost.eu/rutorrent3/")
            fail("Wrong numbered route should be rejected before using secrets")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun addStoppedMetadataOnlyToExactlyReviewedDevelopmentInstance() = runBlocking {
        val rawUrl = System.getenv("CHUNKS_P0_COHORT_URL").orEmpty()
        assumeTrue("Never run live Add outside explicit trusted-main manual dispatch",
            rawUrl.isNotBlank())
        val instance = System.getenv("CHUNKS_P0_COHORT_INSTANCE").orEmpty()
        val runId = System.getenv("CHUNKS_P0_COHORT_RUN_ID").orEmpty()
        val attempt = System.getenv("CHUNKS_P0_COHORT_ATTEMPT").orEmpty()
        val apply = System.getenv("CHUNKS_P0_COHORT_APPLY").orEmpty()
        route(instance, rawUrl)
        require(runId.matches(Regex("[0-9]{7,20}")) && attempt == "1" && apply == "true") {
            "Refusing non-first-attempt or unapproved cohort mutation"
        }
        val user = System.getenv("CHUNKS_P0_COHORT_USER").orEmpty()
        val password = System.getenv("CHUNKS_P0_COHORT_PASS").orEmpty()
        require(user == "ianseeds" && password.isNotEmpty())
        val receiptPath = System.getenv("CHUNKS_P0_COHORT_RECEIPT").orEmpty()
        require(receiptPath.endsWith("/cohort-instance-$instance.tsv"))
        val receipt = File(receiptPath)
        require(receipt.parentFile.isDirectory)
        val remote = RTorrentRemote(RemoteCredentials(rawUrl, user, password))
        val fixtures = plan(runId, instance)
        val rows = mutableListOf(
            "fixture_id\tenvironment_alias\tsession_generation\tinfohash_v1\tsource_ref\tselected_destination_alias\texpected_relative_files\tallowed_mutations\tdelete_data_authorized\tobserved_state"
        )
        fun snapshot(f: Fixture, state: String) {
            // Only aliases and hashes are published. No credentials, host
            // filesystem path, tracker links, or original inventory content.
            rows.removeAll { it.startsWith(f.id + "\t") }
            rows += listOf(f.id, "remote-$instance", runId, f.hash,
                "generated-validated-stopped-metainfo-$runId", "verified-picker-shared-download-parent",
                if (f.expectedFiles == 2) "first.bin;second.bin" else f.name,
                "reviewed-priority/start/stop/reconnect/keep-data", "no", state
            ).joinToString("\t")
            receipt.writeText(rows.joinToString("\n", postfix = "\n"))
        }

        // Write a fail-closed receipt before *any* remote mutation. The earlier
        // attempted run failed before the first fixture record when
        // directory.default was unavailable on these empty instances.
        for (fixture in fixtures) snapshot(fixture, "not-dispatched")
        // An already verified primary download parent is only a CANDIDATE:
        // the numbered instance must independently validate that exact path
        // through its read-only ruTorrent directory picker in this same job.
        val picked = System.getenv("CHUNKS_P0_COHORT_DEST_FILE").orEmpty()
        require(picked.endsWith("/cohort-confirmed-dir-$instance.txt"))
        val pickedFile = File(picked)
        if (!pickedFile.isFile) {
            fixtures.forEach { snapshot(it, "blocked-numbered-directory-preflight-no-write") }
            error("Numbered-instance directory preflight unavailable; no Add sent")
        }
        val selectedParent = pickedFile.readText().trim()
        if (selectedParent != "/home20/ianseeds/downloads") {
            fixtures.forEach { snapshot(it, "blocked-directory-identity-mismatch-no-write") }
            error("Read-only picked directory differed from reviewed parent; no Add sent")
        }
        for (fixture in fixtures) {
            // An old or hidden session with this hash is an absolute refusal.
            require(remote.lookupName(fixture.hash) == null) {
                "Unexpected pre-existing test infohash; no Add was sent"
            }
            snapshot(fixture, "dispatch-possible-manual-reconciliation-if-interrupted")
            val result = remote.loadStoppedTorrentBytes(
                fixture.bytes, selectedParent, useServerDefault = false)
            if (result is RemoteAddOutcome.Uncertain) {
                snapshot(fixture, "uncertain-manual-reconciliation-required")
                error("Exact Add outcome uncertain. No retry or cleanup permitted")
            }
            if (result !is RemoteAddOutcome.VerifiedStopped &&
                result !is RemoteAddOutcome.PendingVerification) {
                snapshot(fixture, "refused-or-incomplete-manual-check")
                error("No verified stopped Add. Stop; do not retry or mutate this hash")
            }
            var observed: RemoteTransfer? = null
            repeat(20) {
                observed = remote.listTransfers("main")
                    .firstOrNull { it.hash.equals(fixture.hash, true) }
                if (observed != null) return@repeat
                Thread.sleep(300)
            }
            val confirmed = observed
            if (confirmed == null || confirmed.name != fixture.name || confirmed.started) {
                snapshot(fixture, "not-verified-manual-reconciliation-required")
                error("Fresh exact stopped state not verified; manual reconcile")
            }
            val expected = if (fixture.expectedFiles == 1) selectedParent
                else selectedParent.trimEnd('/') + "/" + fixture.name
            if (confirmed.directory != expected) {
                snapshot(fixture, "destination-mismatch-manual-reconciliation-required")
                error("Selected server-default target did not match readback")
            }
            var files: List<RemoteFile> = emptyList()
            repeat(20) {
                files = remote.listFiles(fixture.hash)
                if (files.size != fixture.expectedFiles) Thread.sleep(300)
            }
            if (files.size != fixture.expectedFiles) {
                snapshot(fixture, "file-inventory-incomplete-manual-reconciliation-required")
                error("Fresh test fixture file inventory did not match expected paths")
            }
            snapshot(fixture, "verified-stopped-no-payload-bytes-proven")
            println("CHUNKS_P0_COHORT: instance=$instance fixture=${fixture.id} " +
                "hash=${fixture.hash} state=verified-stopped")
        }
    }
}
