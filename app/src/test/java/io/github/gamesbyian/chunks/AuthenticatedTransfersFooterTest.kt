package io.github.gamesbyian.chunks

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class AuthenticatedTransfersFooterTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fourDestinationFooterKeepsAllTargetsOnOneRowWithoutIssuingAdd() {
        var adds = 0
        var connections = 0
        var options = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(412.dp)) {
                        AuthenticatedTransfersFooter(
                            optionsOpen = false,
                            onTransfers = {},
                            onAdd = { adds++ },
                            onOptions = { options++ },
                            onConnections = { connections++ },
                            imeVisible = false
                        )
                    }
                }
            }
        }
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        val conn = rule.onNodeWithContentDescription("Connections").getUnclippedBoundsInRoot()
        val opts = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertTrue("Connections stays on the same navigation row as Add", conn.top == add.top)
        assertTrue("Options follows Connections in the same row", conn.right <= opts.left && conn.top == opts.top)
        assertTrue(conn.bottom - conn.top >= 48.dp)
        assertTrue(opts.bottom - opts.top >= 48.dp)
        rule.onNodeWithContentDescription("Connections").performClick()
        assertEquals(1, connections)
        assertEquals(0, adds)
        assertEquals(0, options)
    }

    @Test fun normalPhoneWidthShowsFourDestinationsOnOnePersistentRow() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(false, {}, {}, {}, onConnections = {})
                }
            }
        }
        val transfers = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        val connections = rule.onNodeWithContentDescription("Connections").getUnclippedBoundsInRoot()
        val options = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertTrue("four destinations stay in one row",
            transfers.top == add.top && add.top == connections.top && connections.top == options.top)
        assertTrue("every tab remains an accessible 48dp target",
            listOf(transfers, add, connections, options).all { it.bottom - it.top >= 48.dp })
    }

    @Test fun bottomControlsPreserveExactExistingCallbacksAndTouchTargets() {
        var transfers = 0
        var adds = 0
        var options = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(
                        optionsOpen = false,
                        onTransfers = { transfers++ },
                        onAdd = { adds++ },
                        onOptions = { options++ }
                    )
                }
            }
        }
        val top = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        val opts = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        for (rect in listOf(top, add, opts)) {
            assertTrue("footer touch target must remain 48dp class", rect.bottom - rect.top >= 48.dp)
        }
        assertTrue(top.right < add.left && add.right < opts.left)
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithContentDescription("Options").performClick()
        assertEquals(1, transfers)
        assertEquals(1, adds)
        assertEquals(1, options)
    }

    @Test fun atPrimaryPhoneWidthEnlargedTextKeepsThreeNavigationButtonsInOneRow() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(412.dp)) {
                        AuthenticatedTransfersFooter(false, {}, {}, {})
                    }
                }
            }
        }
        val t = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val a = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        val o = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertTrue("all navigation remains on one row at 200% text",
            t.top == a.top && a.top == o.top)
        assertTrue("all targets remain operable", listOf(t, a, o).all { it.bottom - it.top >= 48.dp })
    }

    @Test fun primaryPhoneAt140PercentKeepsFullTransfersWordOnReadableRow() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                ChunksTheme {
                    Box(Modifier.width(412.dp)) {
                        AuthenticatedTransfersFooter(false, {}, {}, {})
                    }
                }
            }
        }
        val transfers = rule.onNodeWithText("Transfers").getUnclippedBoundsInRoot()
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        val options = rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertTrue("140% text retains one row", transfers.top == add.top && add.top == options.top)
        assertTrue("each control has distinct horizontal bounds",
            transfers.right <= add.left && add.right <= options.left)
    }

    @Test fun phoneFooterActionsNameTheExactScopeWithoutChangingRemoteSemantics() {
        var transfers = 0
        var adds = 0
        var options = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(false,
                        onTransfers = { transfers++ },
                        onAdd = { adds++ },
                        onOptions = { options++ },
                        environmentLabel = "this phone")
                }
            }
        }
        rule.onNodeWithContentDescription("Show transfers on this phone").performClick()
        rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
        rule.onNodeWithContentDescription("Options on this phone").performClick()
        assertEquals(1, transfers)
        assertEquals(1, adds)
        assertEquals(1, options)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun enlargedTypeFooterRetainsAllFourActionsInOneRow() {
        var adds = 0
        var connections = 0
        var options = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(320.dp)) {
                        AuthenticatedTransfersFooter(
                            false, {}, { adds++ }, { options++ },
                            onConnections = { connections++ })
                    }
                }
            }
        }
        val transfers = rule.onNodeWithText("Transfers")
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD)
        val connection = rule.onNodeWithContentDescription("Connections")
        val option = rule.onNodeWithContentDescription("Options")
        val bounds = listOf(transfers, add, connection, option).map {
            it.getUnclippedBoundsInRoot()
        }
        assertTrue("all four destinations remain a single row at 320dp/200%",
            bounds.all { it.top == bounds.first().top })
        assertTrue("all destinations have separate horizontal hit regions",
            bounds.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertTrue("each destination keeps an accessible minimum 48dp target",
            bounds.all { it.bottom - it.top >= 48.dp })
        add.performClick()
        connection.performClick()
        option.performClick()
        assertEquals(1, adds)
        assertEquals(1, connections)
        assertEquals(1, options)
    }

    @Test fun softKeyboardKeepsAllDestinationsVisibleAndActionable() {
        var ime by mutableStateOf(true)
        var adds = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    AuthenticatedTransfersFooter(false, {}, { adds++ }, {}, imeVisible = ime)
                }
            }
        }
        rule.onAllNodesWithText("Transfers").assertCountEquals(1)
        rule.onAllNodesWithText("+ Add").assertCountEquals(1)
        rule.onAllNodesWithText("Options").assertCountEquals(1)
        ime = false
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithContentDescription("Options").getUnclippedBoundsInRoot()
        assertEquals(1, adds)
    }

    @Test fun platformImeInsetsCannotHideGlobalFooter() {
        var view: android.view.View? = null
        rule.setContent {
            view = LocalView.current
            ChunksTheme {
                Box(Modifier.width(412.dp)) { AuthenticatedTransfersFooter(false, {}, {}, {}) }
            }
        }
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).getUnclippedBoundsInRoot()
        rule.runOnIdle {
            val root = requireNotNull(view).rootView
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build())
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("+ Add").assertCountEquals(1)
        rule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(view).rootView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.ime(), false)
                    .build())
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("+ Add").assertCountEquals(1)
    }

    @Test fun activeDestinationMovesIndependentlyOfPurpleAddEmphasis() {
        var active by mutableStateOf(FooterDestination.TRANSFERS)
        rule.setContent {
            ChunksTheme {
                AuthenticatedTransfersFooter(
                    optionsOpen = null,
                    onTransfers = { active = FooterDestination.TRANSFERS },
                    onAdd = { active = FooterDestination.ADD },
                    onOptions = { active = FooterDestination.OPTIONS },
                    onConnections = { active = FooterDestination.CONNECTIONS },
                    activeDestination = active
                )
            }
        }
        rule.onNodeWithText("Transfers").assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsNotSelected()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNodeWithContentDescription("Connections").assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsNotSelected()
        rule.onNodeWithContentDescription("Options").performClick()
        rule.onNodeWithContentDescription("Options").assertIsSelected()
        rule.onNodeWithContentDescription("Connections").assertIsNotSelected()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsSelected()
        rule.onNodeWithText("Transfers").assertIsNotSelected()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        rule.onNodeWithText("Transfers").assertIsSelected()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsNotSelected()
    }

    @Test fun sectionMappingPreservesActualSelectedRootIncludingNestedDetails() {
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForRoute("COMBINED"))
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForRoute("LOCAL"))
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForRoute("TRANSFERS"))
        assertEquals(FooterDestination.CONNECTIONS,
            footerDestinationForRoute("SERVER"))
        assertEquals(FooterDestination.ADD,
            footerDestinationForRoute("ADD_CHOOSE"))
        assertEquals(FooterDestination.ADD,
            footerDestinationForRoute("LOCAL_ADD"))
        assertEquals(FooterDestination.OPTIONS,
            footerDestinationForRoute("OPTIONS"))
        assertEquals(FooterDestination.CONNECTIONS,
            footerDestinationForRoute("SEEDING", "SERVER"))
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForRoute("SEEDING", "LOCAL"))
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForRoute("NAMES", "TRANSFERS"))
        assertEquals(FooterDestination.OPTIONS,
            footerDestinationForRoute("MEDIA", "OPTIONS"))
    }

    @Test fun reconnectFallbackSelectsConnectionsNotInvisibleLegacyTransfers() {
        assertEquals(FooterDestination.CONNECTIONS,
            footerDestinationForVisibleScreen("TRANSFERS", authenticated = false))
        assertEquals(FooterDestination.TRANSFERS,
            footerDestinationForVisibleScreen("TRANSFERS", authenticated = true))
        assertEquals(FooterDestination.CONNECTIONS,
            footerDestinationForVisibleScreen("SERVER", authenticated = true))
    }

}
