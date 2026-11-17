package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class SearchRelevanceTest {
    private fun movie(name: String) = CatalogMovie("https://example.org/films/$name", name, "", "")

    @Test fun rejectsHomepageCardsForUnrelatedQuery() {
        val results = SearchRelevance.find(listOf(
            movie("Мстители: Финал"),movie("12 12 12"),movie("В сердце Нашвилла")
        ),"бригада")
        assertTrue(results.isEmpty())
    }

    @Test fun ranksExactlyNamedFilmsBeforeRelatedTitles() {
        val results = SearchRelevance.find(listOf(
            movie("Бригада 2"),movie("бригада"),movie("Супер Бригада 2022")
        ),"БРИГАДА")
        assertEquals("бригада",results.first().title)
        assertEquals(3,results.size)
    }

    @Test fun permitsRussianAccusativeSpeechEndingForARealTitle() {
        assertEquals("Бригада 2002",
            SearchRelevance.find(listOf(movie("Бригада 2002")), "бригаду").single().title)
        assertTrue(SearchRelevance.find(listOf(movie("Титаник")), "бригада").isEmpty())
    }

    @Test fun matchesPunctuationAndYo() {
        assertEquals(1,SearchRelevance.find(listOf(movie("Миссия: невыполнима")),
            "миссия невыполнима").size)
        assertEquals(1,SearchRelevance.find(listOf(movie("Ёлки")),
            "елки").size)
    }

    @Test fun quickSearchUsesOnlySameMirrorEndpointAndEscapesQuery() {
        val js=MirrorQuickSearch.javascript("фильм');alert(1);//")
        assertTrue(js.contains("encodeURIComponent(q)"))
        assertTrue(js.contains("new URL(resp.url).origin!==location.origin"))
        assertTrue(js.contains("fetch('/engine/ajax/search.php?q='"))
        assertFalse(js.contains("fetch('https://"))
    }
}
