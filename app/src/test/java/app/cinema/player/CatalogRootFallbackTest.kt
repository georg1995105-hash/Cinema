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

    @Test fun nativeHomepageFallbackUsesTheSameKindFilterAsWebView() {
        val cards = listOf(
            CatalogMovie("https://mirror.example/films/drama/1-film.html", "Фильм", "", ""),
            CatalogMovie("https://mirror.example/series/drama/2-series.html", "Сериал", "", "")
        )
        assertEquals(listOf("Сериал"), MirrorCatalog.filterRootFallback(
            cards, "Сериалы", rootFallback = true, searching = false
        ).map { it.title })
        assertEquals(2, MirrorCatalog.filterRootFallback(
            cards, "Сериалы", rootFallback = false, searching = false
        ).size)
    }

    @Test fun nativeParserReadsRealCardShapeAndRejectsChallenge() {
        val html = """
            <div class="b-content__inline_item" data-id="1741">
              <div class="b-content__inline_item-cover">
                <a href="/series/thriller/1741-mentalist-2008-latest.html">
                  <img data-src="/uploads/mentalist.jpg" alt="Менталист">
                </a>
              </div>
              <div class="b-content__inline_item-link">
                <a href="/series/thriller/1741-mentalist-2008-latest.html">Менталист</a>
                <div>2008, США, Детектив</div>
              </div>
            </div>
        """.trimIndent()
        val cards = MirrorCatalog.parseHtml(html, "https://mirror.example/series/")
        assertEquals(1, cards.size)
        assertEquals("Менталист", cards.single().title)
        assertEquals("1741", MirrorPostId.fromUrl(cards.single().url))
        assertEquals("2008, США, Детектив", cards.single().details)
        assertEquals("https://mirror.example/uploads/mentalist.jpg", cards.single().poster)
        assertTrue(MirrorCatalog.parseHtml(
            "<html><script id='anubis_challenge'>{}</script></html>",
            "https://mirror.example/series/"
        ).isEmpty())
    }
}
