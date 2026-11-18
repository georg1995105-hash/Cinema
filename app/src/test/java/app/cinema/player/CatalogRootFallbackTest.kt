package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class CatalogRootFallbackTest {
    @Test fun homepageCardsAreFilteredToTheRequestedSection() {
        assertTrue(MirrorCatalog.matchesKind(
            "https://mirror.example/series/drama/1234-show.html", "Сериалы"))
        assertFalse(MirrorCatalog.matchesKind(
            "https://mirror.example/films/drama/1234-movie.html", "Сериалы"))
        assertTrue(MirrorCatalog.matchesKind(
            "https://mirror.example/films/drama/1234-movie.html", "Фильмы"))
        assertFalse(MirrorCatalog.matchesKind(
            "https://mirror.example/series/drama/1234-show.html", "Фильмы"))
        assertFalse(MirrorCatalog.matchesKind(
            "https://mirror.example/cartoons/1234-animation.html", "Аниме"))
        assertTrue(MirrorCatalog.matchesKind(
            "https://mirror.example/animation/1234-anime.html", "Аниме"))
    }

    @Test fun rootFallbackNeverAcceptsUnrelatedSiteCategories() {
        assertFalse(MirrorCatalog.matchesKind(
            "https://mirror.example/?filter=watching", "Сериалы"))
        assertFalse(MirrorCatalog.matchesKind(
            "https://mirror.example/films/123.html", "Сериалы"))
    }
}
