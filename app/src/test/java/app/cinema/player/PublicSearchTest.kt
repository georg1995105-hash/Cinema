package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class PublicSearchTest {
    @Test fun extractedArtworkBelongsToTheExactResult() {
        val html = """
            <div class="b-content__search_wrapper">
              <div class="b-content__inline_item" data-id="8155">
                <div class="b-content__inline_item-cover">
                  <a href="/series/drama/8155-brigada-2002.html">
                    <img src="//art.example/i/real.jpeg" alt="Бригада">
                  </a>
                </div>
                <div class="b-content__inline_item-link">
                  <a href="/series/drama/8155-brigada-2002.html">Бригада</a>
                  <div>2002, Россия, Драмы</div>
                </div>
              </div>
            </div>
            <div class="b-content__inline_item"><div class="b-content__inline_item-link">
              <a href="/films/action/35-other.html">Посторонний фильм</a>
            </div></div>
        """.trimIndent()
        val cards = PublicSearch.parse(html, "https://catalog.example/search/")
        val film = cards.single()
        assertEquals("Бригада", film.title)
        assertEquals("8155", MirrorPostId.fromUrl(film.url))
        assertEquals("https://art.example/i/real.jpeg", film.poster)
        assertEquals("2002, Россия, Драмы", film.details)
    }

    @Test fun posterlessCardRemainsAValidSearchResult() {
        val html = """
            <div class="b-content__search_wrapper">
              <div class="b-content__inline_item">
                <div class="b-content__inline_item-link">
                  <a href="/series/drama/8155-brigada-2002.html">Бригада</a>
                  <div>2002, Россия, Драмы</div>
                </div>
              </div>
            </div>
        """.trimIndent()
        val cards = PublicSearch.parse(html, "https://catalog.example/search/")
        assertEquals(1, cards.size)
        assertEquals("Бригада", cards.single().title)
        assertEquals("", cards.single().poster)
        assertEquals(1, SearchRelevance.find(cards, "Бригада").size)
    }

    @Test fun filmRedirectAcceptsOnlyCanonicalAddressForTheSameFilm() {
        val film = "https://mirror.example/series/drama/8155-brigada-2002.html"
        assertTrue(MirrorPostId.canOpen(film, "https://www.mirror.example/series/8155-brigada.html/"))
        assertFalse(MirrorPostId.canOpen(film, "https://elsewhere.example/series/8155-brigada.html"))
        assertFalse(MirrorPostId.canOpen(film, "https://mirror.example/series/8850-brigada.html"))
        assertFalse(MirrorPostId.canOpen(film, "https://mirror.example/login"))
    }
}
