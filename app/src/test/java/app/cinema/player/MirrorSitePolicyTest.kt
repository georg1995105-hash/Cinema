package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorSitePolicyTest {
    @Test fun recognizesReferenceAndDynamicRezkaMirrorsOnly() {
        assertTrue(MirrorSitePolicy.isRecognizedMirror("https://rezka-ua.pub/"))
        assertTrue(MirrorSitePolicy.isRecognizedMirror("https://hdrezka.me/"))
        assertTrue(MirrorSitePolicy.isRecognizedMirror("https://standby-rezka.tv/"))
        assertTrue(MirrorSitePolicy.isRecognizedMirror("https://hdrezka7.org/"))
        assertTrue(MirrorSitePolicy.isRecognizedMirror("https://hdrezka.website/"))
        assertFalse(MirrorSitePolicy.isRecognizedMirror("https://example.com/"))
        assertFalse(MirrorSitePolicy.isRecognizedMirror("http://hdrezka.me/"))
    }

    @Test fun usesReferenceAndroidHeadersAndStandbyHostAlias() {
        val normal = MirrorSitePolicy.requestHeaders("https://hdrezka.me/", "UA")
        assertEquals("1", normal["X-Hdrezka-Android-App"])
        assertEquals("2.2.5", normal["X-Hdrezka-Android-App-Version"])
        assertEquals("UA", normal["User-Agent"])
        assertNull(normal["Host"])

        val standby = MirrorSitePolicy.requestHeaders("https://standby-rezka.tv/", "UA")
        assertEquals("hdrezka.me", standby["Host"])
        assertTrue(MirrorSitePolicy.cookieScopes("https://standby-rezka.tv/").contains("https://hdrezka.me/"))
    }

    @Test fun sharedCookiesFillMissingAuthWithoutOverwritingLocalValues() {
        val merged = MirrorSitePolicy.mergeCookieHeaders(listOf(
            "theme=dark; guest=local",
            "dle_user_id=42; dle_password=secret; guest=shared"
        ))
        assertTrue(merged.contains("theme=dark"))
        assertTrue(merged.contains("dle_user_id=42"))
        assertTrue(merged.contains("dle_password=secret"))
        assertTrue(merged.contains("guest=local"))
        assertFalse(merged.contains("guest=shared"))
    }
}
