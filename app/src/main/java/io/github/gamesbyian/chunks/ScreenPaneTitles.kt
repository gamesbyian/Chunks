package io.github.gamesbyian.chunks

/**
 * The accessibility pane title for the visible route. Compose announces a
 * pane-title change to TalkBack as a window-state change, so moving between
 * phone, a server, All environments, Add and Options screens is spoken
 * without stealing focus or announcing scroll positions. Titles name the
 * screen and environment only, never a transfer, path or credential, so a
 * privacy lock still redacts content.
 */
internal object ScreenPaneTitles {
    fun of(sectionName: String, sectionLabel: String, connectedServerLabel: String?): String =
        when (sectionName) {
            "LOCAL" -> "Transfers on this device"
            "LOCAL_ADD" -> "Add on this device"
            "OPTIONS" -> "Options"
            "COMBINED" -> "All environments"
            "TRANSFERS" -> if (connectedServerLabel == null) "Connect to a server"
                else "Transfers on $connectedServerLabel"
            "ADD" -> if (connectedServerLabel == null) "Add torrent"
                else "Add torrent on $connectedServerLabel"
            else -> sectionLabel
        }
}
