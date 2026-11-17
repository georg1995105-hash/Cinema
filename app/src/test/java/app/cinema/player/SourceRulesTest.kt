package app.cinema.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRulesTest {
    @Test fun acceptsHttps() {
        assertTrue(SourceRules.isValidHttpsUrl("https://example.org/movie.m3u8"))
        assertTrue(SourceRules.isValidHttpsUrl(" https://example.org/ "))
    }

    @Test fun rejectsUnsafeOrBrokenUrls() {
        listOf(
            "http://example.org",
            "ftp://example.org",
            "https://",
            "https://user:password@example.org",
            "https://example.org/video#fragment",
            "javascript:alert(1)",
            ""
        ).forEach { assertFalse(it, SourceRules.isValidHttpsUrl(it)) }
    }
}
