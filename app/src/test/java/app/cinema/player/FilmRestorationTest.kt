package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FilmRestorationTest {
    @Test fun unrelatedNavigationMustNotReplaceFilmIdentity() {
        val expected = "https://rezka.example/series/12-brigada.html"
        assertTrue(FilmNavigationPolicy.decide(expected, expected + "?foo=1").allow)
        val unrelated = FilmNavigationPolicy.decide(expected, "https://rezka.example/films/23-other.html")
        assertFalse(unrelated.allow)
        assertTrue(unrelated.failureMessage.isNotBlank())
        assertFalse(FilmNavigationPolicy.decide(expected, "https://foreign.example/").allow)
    }

    @Test fun keepDescriptionActorsDirectorsAndFranchiseDuringPartialLoad() {
        val actor = FilmPerson("Актёр", "https://source.example/a.jpg")
        val director = FilmPerson("Режиссёр", "https://source.example/d.jpg")
        val part = CatalogMovie("https://source.example/series/34-more.html", "Продолжение", "", "")
        val old = FilmInfo("", "Сюжет", listOf("Страна" to "Россия"), "",
            listOf(actor), emptyList(), listOf(part), franchiseResolved = true,
            directors = listOf(director))
        val partial = FilmInfo("", "", emptyList(), "", emptyList(), emptyList(), title = "Бригада")
        val combined = FilmInfoMerger.merge(old, partial)
        assertEquals("Сюжет", combined.description)
        assertEquals("Актёр", combined.cast.single().name)
        assertEquals("Режиссёр", combined.directors.single().name)
        assertEquals("Продолжение", combined.franchise.single().title)
        assertTrue(combined.franchiseResolved)
        assertEquals("Бригада", combined.title)
    }

    @Test fun newlyLoadedPhotoDoesNotEraseOtherCast() {
        val previous = FilmInfo("", "Сюжет", emptyList(), "",
            listOf(FilmPerson("Первый", ""), FilmPerson("Второй", "old.jpg")),
            emptyList())
        val latest = FilmInfo("", "", emptyList(), "",
            listOf(FilmPerson("Первый", "new.jpg")), emptyList())
        val merged = FilmInfoMerger.merge(previous, latest)
        assertEquals(2, merged.cast.size)
        assertEquals("new.jpg", merged.cast.first().photo)
        assertEquals("old.jpg", merged.cast.last().photo)
    }
}
