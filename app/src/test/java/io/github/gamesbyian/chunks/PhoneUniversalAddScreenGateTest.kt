package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Actual phone review UI with app-owned coordinator injected.
 * No fake engine Add is executed; native safety remains an installed gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class PhoneUniversalAddScreenGateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rawMagnet = "magnet:?xt=urn:btih:" + "a".repeat(40)

    private fun attempt(exactSessionKnown: Boolean = false,
                        startUnverified: Boolean = false): PhoneAddAttemptSession {
        val session = PhoneAddAttemptSession()
        val place = LocalDownloadPlace(LocalPlaceKind.APP_STORAGE,
            File("/tmp/chunks-phone-add-ui-test"), "Phone test", "Test-only scope")
        val checked = MagnetDraftCheck.inspect(rawMagnet) as MagnetDraftCheck.Valid
        val intent = PhoneUniversalAddAdapter.magnetIntent(checked, place,
            "", startWhenAdded = startUnverified, allowArtwork = true)
        assertTrue(PhoneUniversalAddAdapter.review(session.coordinator, intent,
            inventoryConfirmed = true, inventoryStale = false).accepted)
        assertTrue(session.coordinator.authorizeByUser())
        val ticket = requireNotNull(session.coordinator.beginSubmission(10_000L))
        session.bindPlace(ticket, place)
        session.coordinator.requireReadback(ticket)
        if (exactSessionKnown || startUnverified) {
            assertEquals(if (startUnverified)
                    UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED
                else UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
                session.coordinator.reconcileExactSession(ticket,
                    UniversalAddCoordinator.ExactSessionObservation(
                        environment = intent.target.environment,
                        infoHash = checked.infoHash,
                        observedAtEpochMillis = 11_000L,
                        registered = true,
                        reviewedPlaceVerified = true,
                        started = if (startUnverified) null else false,
                        newRegistrationVerified = false
                    )))
        }
        return session
    }

    private fun show(session: PhoneAddAttemptSession) {
        val source = mutableStateOf(rawMagnet)
        rule.setContent {
            ChunksTheme {
                Surface {
                    LocalEngineScreen(
                        magnetDraftState = source,
                        phoneAddAttemptSession = session,
                        onAcknowledgedAdd = { error("Read-only screen cannot authorize an Add") }
                    )
                }
            }
        }
    }

    @Test fun unresolvedAttemptDisablesSourceReplacementAndAnySecondAdd() {
        val session = attempt()
        show(session)
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("Previous phone Add:", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsNotEnabled()
        rule.onNodeWithText("Choose .torrent file").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Review another phone Add").assertDoesNotExist()
        assertNotNull(session.attemptedTicket)
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, session.coordinator.phase)
    }

    @Test fun newerCompleteNativeInventoryRevokesUnverifiedStartAcknowledgmentWithoutRetry() {
        val session = attempt(startUnverified = true)
        val ticket = requireNotNull(session.attemptedTicket)
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        // The initial positive observation above is synthetic. Mounting the
        // real LocalEngineScreen also starts a genuine native list poll. An
        // empty *complete* newer sample contradicts that synthetic job and
        // must take away its acknowledgement CTA, never silently retire the
        // attempted write or replay Add. Make the contradictory sample explicit
        // instead of racing the native observation pump in this UI fixture.
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            session.reconcileInventory(emptyList(), 12_000L))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED,
            session.coordinator.phase)
        assertFalse(session.acknowledgeObservedJob())
        show(session)
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("Previous phone Add: waiting for exact phone transfer verification", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsNotEnabled()
        rule.onNodeWithText("Acknowledge existing job (Start unverified)").assertDoesNotExist()
        rule.onNodeWithText("Review another phone Add").assertDoesNotExist()
        assertSame("Contrary inventory keeps the exact attempted ticket", ticket, session.attemptedTicket)
        assertNull("No new engine Add is authorized by contrary readback",
            session.coordinator.beginSubmission(13_000L))
    }

    @Test fun positivePeerlessMagnetAcknowledgmentRequiresUserActionAndNeverReplaysAdd() {
        val session = attempt(startUnverified = true)
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        // Separate positive-control contract: acknowledgement remains
        // possible while no *newer* authoritative read has contradicted the
        // exact hash and Place. The full UI is not mounted here because its
        // real native poll would invalidate this synthetic fixture.
        assertNotNull(session.attemptedTicket)
        assertTrue(session.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, session.coordinator.phase)
        assertNull(session.attemptedTicket)
        assertNull("Read-only acknowledgement cannot authorize another Add",
            session.coordinator.beginSubmission(12_000L))
    }

    @Test fun exactSessionRecoveryRequiresAnExplicitAcknowledgementBeforeNextReview() {
        val session = attempt(exactSessionKnown = true)
        show(session)
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("Acknowledge existing job, then review another"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsNotEnabled()
        rule.onNodeWithText("Acknowledge existing job, then review another")
            .performScrollTo().performClick()
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsEnabled()
        assertNull(session.attemptedTicket)
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, session.coordinator.phase)
    }
}
