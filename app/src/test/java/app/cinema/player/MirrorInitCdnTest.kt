package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorInitCdnTest {
    @Test fun eightArgumentSeriesInitUsesTheLastThreeTranslatorFlags() {
        val parsed = requireNotNull(MirrorInitCdnParser.parse(
            "sof.tv.initCDNSeriesEvents(123, 17, 6, 11, 0, false, 1, 0);"
        ))
        assertTrue(parsed.isSeries)
        assertEquals("123", parsed.postId)
        assertEquals("17", parsed.translatorId)
        assertEquals(0, parsed.camrip)
        assertEquals(1, parsed.ads)
        assertEquals(0, parsed.director)
    }

    @Test fun seriesSixthArgumentIsNotMistakenForAds() {
        val parsed = requireNotNull(MirrorInitCdnParser.parse(
            "initCDNSeriesEvents(123,17,1,1,1,0,0,1)"
        ))
        assertEquals(1, parsed.camrip)
        assertEquals(0, parsed.ads)
        assertEquals(1, parsed.director)
    }

    @Test fun legacyFiveArgumentSeriesAndMovieRemainSupported() {
        val series = requireNotNull(MirrorInitCdnParser.parse(
            "initCDNSeriesEvents(123,17,0,1,0)"
        ))
        val movie = requireNotNull(MirrorInitCdnParser.parse(
            "initCDNMoviesEvents(123,17,1,0,1)"
        ))
        assertEquals(1, series.ads)
        assertFalse(movie.isSeries)
        assertEquals(1, movie.camrip)
        assertEquals(1, movie.director)
    }
}
