package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class CatalogAccessPolicyTest {
    @Test fun serverAccessDenialsNeedBrowserInspectionBeforeFallback() {
        assertTrue(CatalogAccessPolicy.needsBrowserInspection(403))
        assertTrue(CatalogAccessPolicy.needsBrowserInspection(429))
        assertTrue(CatalogAccessPolicy.needsBrowserInspection(503))
        assertFalse(CatalogAccessPolicy.needsBrowserInspection(200))
        assertFalse(CatalogAccessPolicy.needsBrowserInspection(404))
    }

    @Test fun documentCheckerRecognizesChallengeWithoutRequestingCredentials() {
        val script = CatalogAccessPolicy.challengeJavascript
        assertTrue(script.contains("#anubis_challenge"))
        assertTrue(script.contains(".cf-turnstile"))
        assertFalse(script.contains("dle_password"))
        assertFalse(script.contains("premium"))
    }
}
