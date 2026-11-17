package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class CinemaDetailsRegressionTest {
    @Test fun cleansRunningSeriesMetadataAndKeepsTheReleaseYearInRealTitles() {
        assertEquals("Тело", CatalogTitleCleaner.clean("Тело 2026 - ..., США, Драмы"))
        assertEquals("Бегущий по лезвию 2049", CatalogTitleCleaner.clean("Бегущий по лезвию 2049"))
    }

    @Test fun keepsOnlyExplicitCastAndDirectorCredits() {
        val film = FilmPageParser.parse(
            """{"title":"Фильм","people":[{"name":"Гость","role":"unknown"},
                {"name":"Актёр","role":"actor"},{"name":"Режиссёр","role":"director"}]}""",
            "https://example.org"
        )
        assertEquals(listOf("Актёр"), film?.cast?.map(FilmPerson::name))
        assertEquals(listOf("Режиссёр"), film?.directors?.map(FilmPerson::name))
    }
}
