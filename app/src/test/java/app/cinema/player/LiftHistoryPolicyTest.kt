package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class LiftHistoryPolicyTest {
    private val film = "https://liftw.ws/details.html?id=18899&name=%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%B0%D1%8F%20%D0%BF%D0%B0%D0%BB%D0%B0%D1%82%D0%BA%D0%B0&year=1969"
    private val otherFilm = "https://liftw.ws/details.html?id=20001&name=%D0%94%D1%80%D1%83%D0%B3%D0%BE%D0%B9"

    @Test fun directLiftSearchCreatesNamedHistoryCard() {
        val card = requireNotNull(LiftHistoryPolicy.cardFromVisit(film))
        assertEquals(film, card.url)
        assertEquals("Красная палатка", card.title)
        assertEquals("", card.poster)
        assertEquals(film, LiftHistoryPolicy.historyKey(null, null, film))
    }

    @Test fun originKeepsItsHistoryKeyButFollowingDifferentFilmIsIndependent() {
        val source = "https://v3-cinemeta.strem.io/meta/movie/tt9876543.json"
        assertEquals(source, LiftHistoryPolicy.historyKey(source, null, film))
        assertEquals(source, LiftHistoryPolicy.historyKey(source, film, film))
        assertEquals(otherFilm, LiftHistoryPolicy.historyKey(source, film, otherFilm))
    }

    @Test fun firstPartyMetadataAddsPosterWithoutOverwritingUrlTitle() {
        val metadata = CatalogMovie(film, "Lift", "https://example.org/poster.jpg", "")
        val card = requireNotNull(LiftHistoryPolicy.cardFromVisit(film, metadata))
        assertEquals("Красная палатка", card.title)
        assertEquals("https://example.org/poster.jpg", card.poster)
    }

    @Test fun absentUrlNameUsesPageTitleOrHonestFallback() {
        val address = "https://liftw.ws/details.html?id=55"
        assertEquals("Фильм Lift №55", LiftHistoryPolicy.cardFromVisit(address)?.title)
        assertEquals("Фильм с сайта", LiftHistoryPolicy.cardFromVisit(
            address, CatalogMovie(address, "Фильм с сайта", "", ""))?.title)
    }

    @Test fun spoofedLinksCannotEnterHistory() {
        assertNull(LiftHistoryPolicy.cardFromVisit("https://liftw.ws.evil.org/details.html?id=1"))
        assertNull(LiftHistoryPolicy.historyKey(null, null, "http://liftw.ws/details.html?id=1"))
        assertNull(LiftHistoryPolicy.cardFromVisit("https://liftw.ws/search.html?id=18899"))
    }
}
