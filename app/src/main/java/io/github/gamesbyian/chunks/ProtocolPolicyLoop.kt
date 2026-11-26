package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The live composition as the typed-client policy loop sees it. Members read CURRENT state. */
internal interface ProtocolPolicyHost {
    /** The saved typed-client roster right now. */
    fun savedProfiles(): List<RememberedRemoteProtocolProfiles.Profile>
    /** The remember-off typed connection right now. */
    fun transientProfile(): CombinedReadOnlyProtocolProfile?
    /** A typed-client Connect is running. */
    val connectInFlight: Boolean
    /** The app is started and no credential change is pending. */
    fun mayRun(): Boolean
}

/**
 * The 30-second policy loop for the verified typed clients (Transmission, qBittorrent, Deluge),
 * moved unchanged out of `ChunksApp` (A2611-02). A typed policy session is reusable only for the
 * exact roster ([expectedSaved]) and authentication generation that admitted it; [profiles] is the
 * combined roster the loop was started for. It ends only by cancellation.
 */
internal suspend fun runProtocolPolicyLoop(
    context: Context,
    host: ProtocolPolicyHost,
    profiles: List<CombinedReadOnlyProtocolProfile>,
    expectedSaved: Map<String, RememberedRemoteProtocolProfiles.Profile>,
    expectedTransient: CombinedReadOnlyProtocolProfile?,
    /** The tick period; tests shorten it. */
    tickMillis: Long = 30_000L
): Nothing {
    val clients = mutableMapOf<String, io.github.gamesbyian.chunks.rpc.ClientOperations>()
    val privacyStore = TransferPrivacyPolicyStore(context)
    var clientGeneration = RemoteCredentialLock.capture()
    val job = currentCoroutineContext()[Job]
    while (true) {
        delay(tickMillis)
        if (!host.mayRun() || profiles.isEmpty()) continue
        val generation = RemoteCredentialLock.capture()
        if (generation != clientGeneration) {
            clients.clear()
            clientGeneration = generation
        }
        val policies = withContext(Dispatchers.IO) { FinishInOrderStore.scheduledPolicies(context) }
        for (profile in profiles) {
            val factory = profile.operations ?: continue
            val privacyScope = CombinedReadOnlyProtocolAdmission.privacyScope(profile) ?: continue
            val lifecycle = TransferPrivacyRuntime.lifecycleRevision
            val privacy = TransferPrivacyPolicyStore.revision
            val privacySession = TransferPrivacyRuntime.session
            if (!withContext(Dispatchers.IO) { privacyStore.loadInto(privacySession, privacyScope) }) continue
            val expected = expectedSaved[profile.id]
            val ownerCurrent: () -> Boolean = {
                job?.isActive == true && host.mayRun() && !host.connectInFlight &&
                    RemoteCredentialLock.current(generation) &&
                    lifecycle == TransferPrivacyRuntime.lifecycleRevision &&
                    privacy == TransferPrivacyPolicyStore.revision &&
                    privacySession.mayAct(privacyScope) &&
                    host.savedProfiles().firstOrNull { it.id == profile.id }.let { current ->
                        if (expected != null) current == expected &&
                            profile.verifiedAtMillis == expected.lastVerifiedAtMillis
                        else current == null && profile === expectedTransient &&
                            host.transientProfile() === expectedTransient
                    }
            }
            try {
                tickProtocolRemotePolicies(context, profile.id, policies, ownerCurrent) {
                    clients.getOrPut(profile.id) { factory() }
                }
            } catch (cancel: CancellationException) {
                // An invalidated owner ends this profile's work. A cancelled
                // parent still owns the loop's termination.
                if (job?.isActive != true || ownerCurrent()) throw cancel
            } catch (_: Exception) {
                // Unreachable or refused: no immediate retry.
            }
        }
    }
}
