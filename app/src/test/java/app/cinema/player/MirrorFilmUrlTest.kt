package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorFilmUrlTest {
    @Test fun keepsRealFilmIdentityWhenSwitchingMirror() {
        val expected="https://rezka-ua.pub/series/crime/123-brigada.html"
        val actual=MirrorFilmUrl.changeOrigin(expected,"https://hdrezka.website/")
        assertEquals("https://hdrezka.website/series/crime/123-brigada.html",actual)
        assertEquals(MirrorPostId.fromUrl(expected),MirrorPostId.fromUrl(actual!!))
    }

    @Test fun neverRewritesArbitraryPagesOrCredentials() {
        assertNull(MirrorFilmUrl.changeOrigin("https://rezka-ua.pub/login/","https://hdrezka.website"))
        assertNull(MirrorFilmUrl.changeOrigin("https://rezka-ua.pub/series/123-film.html",
            "http://unsafe.example"))
        assertNull(MirrorFilmUrl.changeOrigin("https://rezka-ua.pub/series/123-film.html",
            "https://user:pass@host.example/"))
    }

    @Test fun queryCanBePreservedWithoutUsingOldHost() {
        val actual=MirrorFilmUrl.changeOrigin("https://rezka-ua.pub/films/22-film.html?lang=ru",
            "https://hdrezka.club")
        assertEquals("https://hdrezka.club/films/22-film.html?lang=ru",actual)
    }

    @Test fun preservesAlreadyEncodedPathAndQuery() {
        val actual=MirrorFilmUrl.changeOrigin(
            "https://rezka-ua.pub/films/22-a%20b.html?q=x%2Fy",
            "https://hdrezka.club"
        )
        assertEquals("https://hdrezka.club/films/22-a%20b.html?q=x%2Fy",actual)
    }

    @Test fun acceptsOnlySavedMirrorForSameFilmId() {
        val film="https://rezka-ua.pub/films/22-film.html"
        val saved=listOf("https://hdrezka.club", "https://hdrezka.website")
        assertTrue(MirrorFilmUrl.isAllowedResolved(
            film, "https://hdrezka.club/films/22-film.html", saved))
        assertFalse(MirrorFilmUrl.isAllowedResolved(
            film, "https://unknown.example/films/22-film.html", saved))
        assertFalse(MirrorFilmUrl.isAllowedResolved(
            film, "https://hdrezka.club/films/23-other.html", saved))
    }

    @Test fun acceptsRecognizedDynamicMirrorForSameFilmWithoutSavingItFirst() {
        val film="https://rezka-ua.pub/films/22-film.html"
        assertTrue(MirrorFilmUrl.isAllowedResolved(
            film, "https://hdrezka8.net/films/22-film.html", emptyList()))
        assertFalse(MirrorFilmUrl.isAllowedResolved(
            film, "https://example.com/films/22-film.html", emptyList()))
    }

    @Test fun canonicalWwwAliasStillCountsAsTheSameResolvedFilm() {
        val film="https://mirror.example/series/drama/8155-brigada-2002.html"
        assertTrue(MirrorFilmUrl.isAllowedResolved(
            film, "https://www.mirror.example/series/8155-brigada.html/", emptyList()))
    }
}
