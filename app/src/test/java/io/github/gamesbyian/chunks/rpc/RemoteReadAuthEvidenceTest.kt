package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real localhost HTTP/401 boundaries, no protected host or mutation. */
class RemoteReadAuthEvidenceTest {
    private val version = "<methodResponse><params><param><value><string>0.9.8</string>" +
        "</value></param></params></methodResponse>"
    private val emptyList = "<methodResponse><params><param><value><array><data/>" +
        "</array></value></param></params></methodResponse>"

    private inner class Gateway(
        val challengeRead: Boolean,
        val rejectAfterAuthorization: Boolean = false,
        val malformedSuccess: Boolean = false
    ) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val address get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                val body = exchange.requestBody.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                requests.add(method to authorization)
                val expectedAuthorization = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("alice:secret".toByteArray(Charsets.UTF_8))
                if (challengeRead && (authorization != expectedAuthorization || rejectAfterAuthorization)) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"read\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    val reply = if (malformedSuccess) "<html>login page</html>" else
                        if (method == "system.client_version") version else emptyList
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun anonymousPublicInventoryCannotConfirmSuppliedUsername() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "invented-user", "wrong-secret"))
            assertEquals("0.9.8", remote.version())
            assertTrue(remote.listTransfers("main").isEmpty())
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
            assertTrue(server.requests.all { it.second == null })
        }
    }

    @Test fun sameEndpointChallengedSuccessfulReadRecordsOnlyBoundedEvidence() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "secret"))
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
            assertEquals("0.9.8", remote.version())
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                remote.readAuthenticationEvidence())
            assertEquals(2, server.requests.size)
            assertNull(server.requests.first().second)
            assertTrue(server.requests.last().second.orEmpty().startsWith("Basic "))
            assertFalse(server.requests.last().second.orEmpty().contains("secret"))
        }
    }

    @Test fun actualProtectedProbeGateRefusesValidPublicReadsWithInventedCredentials() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "invented-user", "wrong-secret"))
            try {
                verifySeedhostReadOnlyAcceptance(remote)
                fail("Public version and main view cannot produce an authenticated acceptance receipt")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("authentication is unverified"))
            }
            assertEquals(setOf("system.client_version", "d.multicall2"), server.requests.map { it.first }.toSet())
            assertTrue("An anonymous endpoint never challenges or validates this identity",
                server.requests.all { it.second == null })
        }
    }

    @Test fun actualProtectedProbeGateAcceptsChallengedVersionAndEmptyMainView() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "secret"))
            assertEquals(0, verifySeedhostReadOnlyAcceptance(remote))
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED, remote.readAuthenticationEvidence())
            assertTrue(server.requests.any { it.second.orEmpty().startsWith("Basic ") })
            assertEquals(setOf("system.client_version", "d.multicall2"), server.requests.map { it.first }.toSet())
        }
    }

    @Test fun actualProtectedProbeGateRefusesRejectedSuppliedCredentials() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "wrong-secret"))
            try {
                verifySeedhostReadOnlyAcceptance(remote)
                fail("Rejected credentials cannot produce an authenticated receipt")
            } catch (_: java.io.IOException) { }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE, remote.readAuthenticationEvidence())
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it.first == "system.client_version" })
        }
    }

    @Test fun rejectedChallengeOrMalformedReadDoesNotGrantAuthEvidence() = runBlocking {
        Gateway(challengeRead = true, rejectAfterAuthorization = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "wrong"))
            try {
                remote.version()
                fail("A second 401 cannot prove credential acceptance")
            } catch (_: java.io.IOException) { }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
        }
        Gateway(challengeRead = false, malformedSuccess = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "secret"))
            try {
                remote.version()
                fail("HTML cannot count as verified XML-RPC")
            } catch (_: Exception) { }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
        }
    }
    @Test fun productionConnectRefusesSuppliedCredentialsOnPublicVersionAndInventory() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            try {
                RemoteSmartConnect.connect(server.address, "invented-user", "wrong-secret") { credentials ->
                    val remote = RTorrentRemote(credentials)
                    remote.version()
                    remote.listTransfers("main")
                    requireRemoteConnectIdentityEvidence(credentials, remote)
                    remote
                }
                fail("Connect must not remember or label invented credentials as authenticated")
            } catch (expected: RemoteSmartConnect.ConnectionFailed) {
                assertEquals(RemoteSmartConnect.Category.IDENTITY, expected.category)
                assertEquals(1, expected.tested)
                assertTrue(expected.message.orEmpty().contains("public reads"))
            }
            assertTrue(server.requests.all { it.second == null })
            assertEquals(setOf("system.client_version", "d.multicall2"),
                server.requests.map { it.first }.toSet())
        }
    }

    @Test fun publicReadOnFirstHostnameCandidateCannotProbeAnotherAccountMount() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val origin = server.address.removeSuffix("/RPC2")
            val planned = RemoteSmartConnect.candidates(origin, "alice")
            val actualEndpointIndex = planned.indexOfFirst { it.endpoint == server.address }
            assertTrue("The public endpoint must be followed by another candidate to test the stop fence",
                actualEndpointIndex >= 0 && actualEndpointIndex < planned.lastIndex)
            try {
                RemoteSmartConnect.connect(origin, "alice", "secret") { credentials ->
                    val remote = RTorrentRemote(credentials)
                    remote.version()
                    remote.listTransfers("main")
                    requireRemoteConnectIdentityEvidence(credentials, remote)
                    remote
                }
                fail("An ambiguous public read must stop discovery, not test another mount")
            } catch (expected: RemoteSmartConnect.ConnectionFailed) {
                assertEquals(RemoteSmartConnect.Category.IDENTITY, expected.category)
                // The generic planner may first try another safely missing
                // path (HTTP 404). Once this endpoint gives public valid RPC,
                // no later guessed account mount may receive a login probe.
                assertEquals(actualEndpointIndex + 1, expected.tested)
            }
            assertEquals(setOf("system.client_version", "d.multicall2"),
                server.requests.map { it.first }.toSet())
        }
    }

    @Test fun productionConnectAllowsOnlyChallengedSuppliedLoginOrExplicitAnonymous() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val accepted = RemoteSmartConnect.connect(server.address, "alice", "secret") { credentials ->
                val remote = RTorrentRemote(credentials)
                remote.version()
                remote.listTransfers("main")
                requireRemoteConnectIdentityEvidence(credentials, remote)
                remote
            }
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                accepted.response.readAuthenticationEvidence())
            assertEquals(1, accepted.attempts)
            assertTrue(server.requests.any { it.second.orEmpty().startsWith("Basic ") })
        }
        Gateway(challengeRead = false).use { server ->
            val anonymous = RemoteSmartConnect.connect(server.address, "", "") { credentials ->
                val remote = RTorrentRemote(credentials)
                remote.version()
                remote.listTransfers("main")
                requireRemoteConnectIdentityEvidence(credentials, remote)
                remote
            }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                anonymous.response.readAuthenticationEvidence())
            assertTrue(server.requests.all { it.second == null })
        }
    }

    @Test fun partialCredentialsOnPublicReadsRemainUnverified() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "", "supplied-password"))
            remote.version()
            remote.listTransfers("main")
            try {
                requireRemoteConnectIdentityEvidence(RemoteCredentials(server.address, "", "supplied-password"), remote)
                fail("A password without an accepted challenge is not authenticated")
            } catch (_: RemoteIdentityUnverified) { }
        }
    }

    @Test fun savedCombinedProfileRefusesPublicInventoryWithInventedCredentials() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            try {
                verifiedSavedProfileMainInventory(
                    RemoteCredentials(server.address, "invented-user", "wrong-secret"))
                fail("The saved account has not reauthenticated after restart")
            } catch (_: RemoteIdentityUnverified) { }
            assertEquals(listOf("system.client_version", "d.multicall2"),
                server.requests.map { it.first })
            assertTrue(server.requests.all { it.second == null })
        }
    }

    @Test fun savedCombinedProfileAcceptsFreshChallengedCredentialsWithoutMutations() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val rows = verifiedSavedProfileMainInventory(
                RemoteCredentials(server.address, "alice", "secret"))
            assertTrue(rows.isEmpty())
            assertTrue(server.requests.any { it.second.orEmpty().startsWith("Basic ") })
            assertTrue(server.requests.all {
                it.first == "system.client_version" || it.first == "d.multicall2"
            })
        }
    }

    @Test fun savedCombinedProfileRejectsExpiredPasswordBeforeShowingRows() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            try {
                verifiedSavedProfileMainInventory(
                    RemoteCredentials(server.address, "alice", "expired-password"))
                fail("Rejected saved credentials may not display a live account")
            } catch (_: java.io.IOException) { }
            assertEquals(listOf("system.client_version", "system.client_version"),
                server.requests.map { it.first })
        }
    }

    @Test fun savedCombinedProfilePermitsDeliberateAnonymousReadOnlyAccess() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val rows = verifiedSavedProfileMainInventory(RemoteCredentials(server.address, "", ""))
            assertTrue(rows.isEmpty())
            assertTrue(server.requests.all { it.second == null })
        }
    }

}
