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
                        pane = PhonePane.ADD,
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

    @Test fun peerlessMagnetAcknowledgmentExplainsUnverifiedStartAndNeverReplaysAdd() {
        val session = attempt(startUnverified = true)
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED,
            session.coordinator.phase)
        show(session)
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("Acknowledge existing job (Start unverified)"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsNotEnabled()
        rule.onNodeWithText("requested Start remains unverified.", substring = true).assertExists()
        rule.onNodeWithText("Acknowledge existing job (Start unverified)")
            .performScrollTo().performClick()
        rule.onNodeWithText("Or paste a magnet or torrent link").assertIsEnabled()
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, session.coordinator.phase)
        assertNull(session.attemptedTicket)
        assertNull("No new engine Add is authorized by acknowledging a job",
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
