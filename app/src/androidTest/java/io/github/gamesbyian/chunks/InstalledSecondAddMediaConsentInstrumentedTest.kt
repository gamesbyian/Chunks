package io.github.gamesbyian.chunks

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.FileMediaAutomationPreferences
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** The receiver in the runner counts decrypted HTTP requests from the installed app. */
@RunWith(AndroidJUnit4::class)
class InstalledSecondAddMediaConsentInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val media get() = ChunksMedia.get(context)
    private val add get() = InstalledUniversalAddJourney(rule)
    private val state get() = context.getSharedPreferences("second-add-media-qa", 0)
    private val port = 18091

    private fun fixture(path: String): String =
        URL("http://127.0.0.1:$port/$path").openConnection(Proxy.NO_PROXY).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }.getInputStream().bufferedReader().use { it.readText() }

    private fun count(label: String) = JSONObject(fixture("counts")).optInt(label)
    private fun hash(source: String) = fixture("hash/$source").also {
        check(it.matches(Regex("[a-f0-9]{40}")))
    }

    private fun key(hash: String) = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentSnapshots.LOCAL, hash))

    private fun withControlledHttps(block: () -> Unit) {
        assumeTrue("Disposable controlled HTTPS run only", args.getString("chunks_media_consent") == "controlled")
        val qemu = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").let {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assertEquals("Disposable emulator required", "1", qemu)
        val originalSelector = ProxySelector.getDefault()
        val originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(requireNotNull(args.getString("chunks_media_cert")), Base64.DEFAULT).inputStream())
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("disposable-https-fixture", cert)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
        // Only this disposable process trusts the ephemeral certificate. Every HTTPS
        // connection is routed to a fixture which cannot forward to any public host.
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                listOf(if (uri.scheme == "https") proxy else Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        ProxySelector.setDefault(selector)
        try {
            block()
        } finally {
            ProxySelector.setDefault(originalSelector)
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory)
        }
    }

    private fun waitForTag(tag: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }

    private fun reviewAndAdd(
        scenario: ActivityScenario<MainActivity>,
        source: String,
        expectedHash: String,
        optOut: Boolean
    ) {
        LazyComposeActions.replaceText(rule, hasTestTag(ChunksSemantics.ADD_SOURCE_FIELD), "https://example.com/$source.torrent")
        LazyComposeActions.activateOnce(rule, hasText("Get torrent from link") and hasClickAction())
        rule.waitUntil(30_000) {
            var reviewed = false
            scenario.onActivity {
                reviewed = ViewModelProvider(it)[LocalAddAttemptOwner::class.java]
                    .reviewedTorrent.value?.preview?.infoHashV1 == expectedHash
            }
            reviewed
        }
        assertNull("HTTPS review must make no native Add write", LocalEngineHolder.current()?.status(expectedHash))
        add.chooseStopped()
        val disclosure = hasText("Artwork & metadata") and hasClickAction()
        if (rule.onAllNodes(disclosure and hasStateDescription("Expanded")).fetchSemanticsNodes().isEmpty()) {
            LazyComposeActions.activateOnce(rule, disclosure and hasStateDescription("Collapsed"))
        }
        val choice = hasTestTag(ChunksSemantics.ADD_ARTWORK_CHOICE)
        fun requireChoice(expected: ToggleableState) {
            rule.waitUntil(15_000) {
                val nodes = rule.onAllNodes(choice).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    val scroller = hasScrollToIndexAction()
                    if (rule.onAllNodes(scroller).fetchSemanticsNodes().size == 1) {
                        try {
                            rule.onNode(scroller).performScrollToNode(choice)
                        } catch (failure: AssertionError) {
                            if (!LazyComposeActions.retryableScrollFailure(failure)) throw failure
                        }
                    }
                }
                rule.onAllNodes(choice).fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.ToggleableState) == expected
            }
        }
        requireChoice(ToggleableState.On)
        if (optOut) {
            add.activate(ChunksSemantics.ADD_ARTWORK_CHOICE)
            requireChoice(ToggleableState.Off)
        }
        add.submit()
        rule.waitUntil(45_000) { LocalEngineHolder.current()?.status(expectedHash)?.stopped == true }
        waitForTag(ChunksSemantics.ADD_ANOTHER)
    }

    private fun assertExclusion(hash: String, expected: Boolean) {
        val prefs = FileMediaAutomationPreferences(File(context.filesDir, "media/automation.json"))
        assertEquals("Exact Local job exclusion on disk", expected, prefs.isExcluded(key(hash)))
        val other = MediaTransferKey.of(UnifiedTransferKey.of(EnvironmentId("rtorrent:fixture"), hash))
        assertFalse("Exclusion must not spill into another environment", prefs.isExcluded(other))
    }

    private fun assertNoExcludedRequests() {
        for (category in listOf("search_excluded", "episode_excluded", "metadata_unexpected", "artwork")) {
            assertEquals("Independent HTTPS receiver counter for $category", 0, count(category))
        }
    }

    @Test fun phaseA_submitTwoSourcesAndRefreshInventory() = withControlledHttps {
        val first = hash("first")
        val excluded = hash("excluded")
        assertNotEquals(first, excluded)
        assertEquals(0, count("search_first"))
        assertNoExcludedRequests()
        media.updateSettings(MediaPrivacySettings(
            showArtwork = true, downloadArtwork = true, onlineLookupConsent = true, automaticLookup = true,
            disabledProviders = setOf("tmdb", "wikidata", "musicbrainz", "openlibrary")
        ))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            waitForTag(ChunksSemantics.FOOTER_ADD)
            add.activate(ChunksSemantics.FOOTER_ADD)
            reviewAndAdd(scenario, "first", first, optOut = false)
            assertExclusion(first, false)
            rule.waitUntil(45_000) { count("search_first") > 0 }
            assertEquals(1, count("source_first"))

            add.activate(ChunksSemantics.ADD_ANOTHER)
            reviewAndAdd(scenario, "excluded", excluded, optOut = true)
            assertEquals(1, count("source_excluded"))
            assertExclusion(excluded, true)
            add.activate(ChunksSemantics.FOOTER_TRANSFERS)
            waitForTag(ChunksSemantics.TRANSFERS_SEARCH)
            scenario.recreate()
            waitForTag(ChunksSemantics.TRANSFERS_SEARCH)
            assertExclusion(excluded, true)
            assertTrue(requireNotNull(LocalEngineHolder.current()).saveResumeDataFor(excluded, 15_000))
            assertNoExcludedRequests()
            assertTrue(state.edit().putInt("phase-a-pid", Process.myPid()).commit())
        }
    }

    @Test fun phaseB_restoredExclusionAndFreshPositiveControl() = withControlledHttps {
        assertTrue("The first phase completed", state.contains("phase-a-pid"))
        assertNotEquals("Force stop must create a new process", state.getInt("phase-a-pid", 0), Process.myPid())
        val excluded = hash("excluded")
        val control = hash("control")
        assertNotEquals(excluded, control)
        assertEquals(0, count("search_control"))
        assertExclusion(excluded, true)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            waitForTag(ChunksSemantics.FOOTER_TRANSFERS)
            add.activate(ChunksSemantics.FOOTER_TRANSFERS)
            waitForTag(ChunksSemantics.TRANSFERS_SEARCH)
            rule.waitUntil(45_000) { LocalEngineHolder.current()?.status(excluded)?.stopped == true }
            assertNoExcludedRequests()
            add.activate(ChunksSemantics.FOOTER_ADD)
            reviewAndAdd(scenario, "control", control, optOut = false)
            assertExclusion(control, false)
            rule.waitUntil(45_000) { count("search_control") > 0 }
            assertEquals(1, count("source_control"))
            assertExclusion(excluded, true)
            assertNoExcludedRequests()
        }
    }
}
