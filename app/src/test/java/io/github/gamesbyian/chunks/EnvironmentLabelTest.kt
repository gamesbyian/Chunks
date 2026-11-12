package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentLabelTest {
    @Test fun serverLabelNamesAccountHostAndInstanceButNeverSecrets() {
        assertEquals("Server · ian @ box.example/rutorrent2/plugins/httprpc/action.php",
            serverEnvironmentLabel("https://box.example/rutorrent2/plugins/httprpc/action.php\u0000ian"))
        assertEquals("Server · box.example:8443/RPC2", serverEnvironmentLabel("https://box.example:8443/RPC2\u0000"))
        assertEquals("On this device", LOCAL_ENVIRONMENT_LABEL)
    }
}
