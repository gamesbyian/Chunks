package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.UserImageUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UserImageUrlPolicyTest {
    private fun refused(u: String) = UserImageUrlPolicy.check(u) is UserImageUrlPolicy.Check.Refused

    @Test fun ordinaryPublicHttpsImageLinksAreAllowed() {
        assertEquals(UserImageUrlPolicy.Check.Ok("https://upload.wikimedia.org/a/b.jpg"), UserImageUrlPolicy.check(" https://upload.wikimedia.org/a/b.jpg "))
    }

    @Test fun ssrfAndUnsafeShapesAreRefused() {
        listOf("http://example.com/a.jpg", "ftp://example.com/a.jpg", "file:///sdcard/a.jpg", "content://media/1",
            "https://localhost/a.jpg", "https://127.0.0.1/a.jpg", "https://192.168.1.1/a.jpg", "https://[::1]/a.jpg",
            "https://10.0.0.5/x", "https://router.lan/x.jpg", "https://nas.local/x.jpg", "https://printer/x.jpg",
            "https://user:pass@example.com/x.jpg", "https://example.com:8443/x.jpg", "https://seedbox.home.arpa/x.jpg",
            "https://2130706433/x.jpg", "not a url").forEach { assertTrue(it, refused(it)) }
    }

    @Test fun resolvedAddressesMustAllBePublic() {
        fun ip(s: String) = InetAddress.getByName(s)
        val rebinding = UserImageUrlPolicy.hostPolicy { listOf(ip("93.184.215.14"), ip("192.168.0.10")) }
        assertFalse(rebinding.allows("images.example.com"))
        assertTrue(UserImageUrlPolicy.hostPolicy { listOf(ip("93.184.215.14")) }.allows("images.example.com"))
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "::1", "fe80::1", "fd00::1", "::ffff:192.168.1.1").forEach { assertFalse(it, UserImageUrlPolicy.isPublicAddress(ip(it))) }
        assertTrue(UserImageUrlPolicy.isPublicAddress(ip("2606:4700::1111")))
        assertFalse(UserImageUrlPolicy.hostPolicy { throw java.net.UnknownHostException() }.allows("nowhere.example"))
    }
}
