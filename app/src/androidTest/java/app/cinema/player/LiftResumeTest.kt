package app.cinema.player

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LiftResumeTest {
    @Test fun storesOnlyFirstPartySelectedFilmLinks() {
        val store = LocalStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val movie = "https://v3-cinemeta.strem.io/meta/movie/tt9876543.json"
        val valid = "https://liftw.ws/details.html?id=987654&name=%D0%A2%D0%B5%D1%81%D1%82&year=2026"
        store.removeLiftVisit(movie)
        try {
            assertNull(store.liftVisitedUrl(movie))
            store.saveLiftVisit(movie, "https://liftw.ws.evil.example/details.html?id=987654")
            assertNull(store.liftVisitedUrl(movie))
            store.saveLiftVisit(movie, valid)
            assertEquals(valid, store.liftVisitedUrl(movie))
            store.saveLiftVisit(movie, "http://liftw.ws/details.html?id=123")
            assertEquals(valid, store.liftVisitedUrl(movie))
            store.removeLiftVisit(movie)
            assertNull(store.liftVisitedUrl(movie))
        } finally {
            store.removeLiftVisit(movie)
        }
    }
}
