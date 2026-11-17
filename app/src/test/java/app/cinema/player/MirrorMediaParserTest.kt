package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorMediaParserTest {
    @Test fun parsesMediaResolutions() {
        val quality = MirrorMediaParser.parseQualityList(
            "[480p]https://media.example/a.mp4,[720p]https://media.example/b.mp4"
        )
        assertEquals(2, quality.size)
        assertEquals("720p", quality[0].label)
        assertEquals("https://media.example/b.mp4", quality[0].url)
    }

    @Test fun rejectsUntrustedAndInsecureMediaUrls() {
        assertTrue(MirrorMediaParser.parseQualityList("[720p]javascript:alert(1)").isEmpty())
        assertTrue(MirrorMediaParser.parseQualityList("[720p]http://media.example/video.mp4").isEmpty())
    }

    @Test fun ignoresPremiumMarkedQuality() {
        val quality = MirrorMediaParser.parseQualityList(
            "[720p]https://media.example/free.mp4," +
            "[<span class=\"pjs-prem-quality\">1080p</span>]https://media.example/premium.mp4"
        )
        assertEquals(1, quality.size)
        assertEquals("720p", quality.first().label)
    }

    @Test fun handlesAlternativeLinks() {
        val quality = MirrorMediaParser.parseQualityList(
            "[480p]https://media.example/first.m3u8 or https://media.example/second.mp4"
        )
        assertEquals("https://media.example/second.mp4", quality.single().url)
    }
}
