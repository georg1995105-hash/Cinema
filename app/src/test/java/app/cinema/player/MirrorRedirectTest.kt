package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorRedirectTest {
    private val film = "https://rezka-ua.pub/films/action/4242-film.html?lang=ru"

    @Test fun rootRedirectToDynamicRezkaMirrorKeepsExactFilm() {
        val actual = MirrorRedirect.resolveFilmUrl(
            film,
            film,
            "https://hdrezka7.org/",
            emptyList()
        )
        assertEquals(
            "https://hdrezka7.org/films/action/4242-film.html?lang=ru",
            actual
        )
    }

    @Test fun directSameFilmRedirectOnRecognizedMirrorIsAccepted() {
        val actual = MirrorRedirect.resolveFilmUrl(
            film,
            film,
            "https://hdrezka.me/films/action/4242-film.html",
            emptyList()
        )
        assertEquals("https://hdrezka.me/films/action/4242-film.html", actual)
    }

    @Test fun unrelatedHostAndDifferentFilmAreRejected() {
        assertNull(MirrorRedirect.resolveFilmUrl(
            film, film, "https://example.com/", emptyList()
        ))
        assertNull(MirrorRedirect.resolveFilmUrl(
            film, film, "https://hdrezka.me/films/action/999-other.html", emptyList()
        ))
    }

    @Test fun userSavedMirrorIsAllowedEvenIfHostNameIsNotRezkaFamily() {
        val saved = listOf("https://my-cinema-mirror.example/")
        val actual = MirrorRedirect.resolveFilmUrl(
            film, film, "https://my-cinema-mirror.example/", saved
        )
        assertEquals(
            "https://my-cinema-mirror.example/films/action/4242-film.html?lang=ru",
            actual
        )
    }
}
