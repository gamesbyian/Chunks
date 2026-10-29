package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.RemoteCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Capability introspection may show methods, but cannot prove command authority. */
class RemoteCapabilityEvidenceLabelTest {
    private val feature = RemoteCapabilities.Feature(
        label = "Start and stop", methods = listOf("d.start", "d.stop")
    )

    @Test fun advertisedMethodsAreExplicitlyUnverified() {
        val text = capabilityEvidenceLabel(RemoteCapabilities.Graded(
            feature, RemoteCapabilities.Status.ADVERTISED, emptyList()
        ))
        assertEquals("Advertised, not yet verified: Start and stop", text)
        assertFalse("An advertised method must not be shown as an executed success", text.contains("✓"))
    }

    @Test fun missingMethodsReportObservedAbsenceNotDefinitiveUnsupportedState() {
        val text = capabilityEvidenceLabel(RemoteCapabilities.Graded(
            feature, RemoteCapabilities.Status.MISSING, listOf("d.stop")
        ))
        assertEquals("Not advertised: Start and stop (missing d.stop)", text)
        assertFalse(text.contains("✗"))
    }

    @Test fun unavailableIntrospectionStaysUnknown() {
        val text = capabilityEvidenceLabel(RemoteCapabilities.Graded(
            feature, RemoteCapabilities.Status.UNKNOWN, emptyList()
        ))
        assertEquals("Unknown: Start and stop (server did not expose a method list)", text)
    }
}
