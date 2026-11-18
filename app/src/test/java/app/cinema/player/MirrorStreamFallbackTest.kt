package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorStreamFallbackTest {
    @Test fun directSubscriptionErrorDoesNotSuppressSameOriginBrowserRetry() {
        val native = MirrorMediaParser.parseStreamInfo(
            """{"success":0,"code":"premium_only"}"""
        )
        assertEquals(PlaybackFailure.PREMIUM, native.failure)
        assertTrue(MirrorStreamFallback.shouldTryBrowser(native))

        val guest = MirrorMediaParser.parseStreamInfo(
            """{"success":1,"url":"[720p]https://media.example/series-s1e1.m3u8"}"""
        )
        val final = MirrorStreamFallback.choose(native, guest)
        assertNull(final.failure)
        assertEquals("720p", final.qualities.single().label)
    }

    @Test fun stillRejectsActualPremiumWhenBrowserCannotProvideAStream() {
        val native = MirrorMediaParser.parseStreamInfo(
            """{"success":false,"message":"premium required"}"""
        )
        val browser = MirrorMediaParser.parseStreamInfo("""{"success":0}""")
        val final = MirrorStreamFallback.choose(native, browser)
        assertTrue(final.qualities.isEmpty())
        assertEquals(PlaybackFailure.PREMIUM, final.failure)
    }

    @Test fun explicitBrowserAuthFailureIsMoreInformativeThanInvalidNativeJson() {
        val native = MirrorMediaParser.parseStreamInfo("<html>unknown</html>")
        val browser = MirrorMediaParser.parseStreamInfo("""{"__kinoStatus":401}""")
        assertEquals(PlaybackFailure.AUTH_REQUIRED,
            MirrorStreamFallback.choose(native, browser).failure)
    }

    @Test fun successfulNativeVideoNeedsNoBrowserRequest() {
        val native = MirrorMediaParser.parseStreamInfo(
            """{"success":1,"url":"[480p]https://media.example/full.mp4"}"""
        )
        assertFalse(MirrorStreamFallback.shouldTryBrowser(native))
    }

    @Test fun browserEpisodeAccessErrorIsNotDiscarded() {
        assertTrue(MirrorStreamFallback.useBrowserEpisodes(
            0, PlaybackFailure.INVALID_RESPONSE, 0, PlaybackFailure.AUTH_REQUIRED))
        assertTrue(MirrorStreamFallback.useBrowserEpisodes(
            0, null, 0, PlaybackFailure.BLOCKED))
        assertFalse(MirrorStreamFallback.useBrowserEpisodes(
            0, PlaybackFailure.PREMIUM, 0, PlaybackFailure.AUTH_REQUIRED))
        assertFalse(MirrorStreamFallback.useBrowserEpisodes(
            0, PlaybackFailure.INVALID_RESPONSE, 0, null))
        assertTrue(MirrorStreamFallback.useBrowserEpisodes(
            0, PlaybackFailure.INVALID_RESPONSE, 4, null))
    }
}
