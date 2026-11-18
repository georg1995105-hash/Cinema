package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorProbeRedirectTest {
    @Test fun followsSameSiteRedirect() {
        assertEquals("https://custom.example/ready/",
            MirrorProbeRedirect.next("https://custom.example/", "/ready/"))
    }

    @Test fun followsRecognizedPublicMirrorButNotUnknownExternalHost() {
        assertEquals("https://hdrezka.me/",
            MirrorProbeRedirect.next("https://hdrezka.club/", "https://hdrezka.me/"))
        assertNull(MirrorProbeRedirect.next("https://hdrezka.club/",
            "https://unauthorized.example/"))
    }

    @Test fun rejectsHttpLoginAndPremiumRedirects() {
        assertNull(MirrorProbeRedirect.next("https://hdrezka.club/", "http://hdrezka.me/"))
        assertNull(MirrorProbeRedirect.next("https://hdrezka.club/", "/login/"))
        assertNull(MirrorProbeRedirect.next("https://hdrezka.club/", "/premium/"))
    }
}
