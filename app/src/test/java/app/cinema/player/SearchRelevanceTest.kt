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

    @Test fun romanianAndUnrelatedMatchesAreExcludedFromRussianBrigadaSearch() {
        val results = SearchRelevance.find(listOf(
            movie("First Dates"),
            movie("Brigada Nimic"),
            movie("Отряд Коста-дель-Соль"),
            movie("Бригада"),
            movie("Закон и порядок: Специальный корпус"),
            movie("Brigada 2011")
        ), "бригада")
        assertEquals(listOf("Бригада"), results.map { it.title })
    }

    @Test fun matchesPunctuationAndYo() {
        assertEquals(1,SearchRelevance.find(listOf(movie("Миссия: невыполнима")),
            "миссия невыполнима").size)
        assertEquals(1,SearchRelevance.find(listOf(movie("Ёлки")),
            "елки").size)
    }

    @Test fun triesNominativeWhenTheSourceHasNoMatchesForSpokenAccusative() {
        assertEquals(listOf("Бригада"), SearchRelevance.alternateQuery(VoiceSearchQuery.clean("найди Бригаду")))
        assertTrue(SearchRelevance.find(listOf(movie("Другая группа")), "бригаду").isEmpty())
        assertEquals("Бригада", SearchRelevance.find(listOf(movie("Бригада: Наследник"), movie("Бригада")), "бригаду").first().title)
    }
    @Test fun yearQualifiedQueryChoosesCorrectRemake() {
        val first = CatalogMovie("https://example.org/films/1", "Красная палатка", "", "1969 · СССР")
        val remake = CatalogMovie("https://example.org/films/2", "Красная палатка", "", "2018 · Россия")
        assertEquals(listOf(first), SearchRelevance.find(listOf(remake, first), "Красная палатка 1969"))
        assertEquals("Красная палатка", SearchRelevance.queryWithoutYear("Красная палатка 1969"))
        assertTrue(SearchRelevance.find(listOf(remake), "Красная палатка 1969").isEmpty())
    }

    @Test fun originalTitleCanBeMatchedAfterRussianLocalization() {
        val movie = CatalogMovie("https://example.org/films/a", "Побег из Шоушенка", "", "1994",
            aliases = listOf("The Shawshank Redemption"))
        assertEquals(movie, SearchRelevance.find(listOf(movie), "The Shawshank Redemption").single())
    }

}
