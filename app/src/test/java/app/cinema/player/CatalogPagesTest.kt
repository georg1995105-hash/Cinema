package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class CatalogPagesTest {
    @Test fun buildsStremioCatalogPages() {
        assertEquals("/catalog/movie/top.json", CatalogPages.path("Популярное", "movie", 0))
        assertEquals("/catalog/series/top/skip=100.json", CatalogPages.path("Популярное", "series", 1))
        assertEquals("/catalog/movie/imdbRating/skip=200.json",
            CatalogPages.path("Высокий рейтинг", "movie", 2))
        assertEquals("/catalog/movie/year/genre=2027&skip=100.json",
            CatalogPages.path("Новинки", "movie", 1, 2027))
    }

    @Test fun newestYearUsesDeviceCalendarNotHardcodedConstant() {
        assertEquals(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
            CatalogPages.currentYear())
    }

    @Test fun rejectsInvalidPaginationOrType() {
        assertThrows(IllegalArgumentException::class.java) { CatalogPages.path("Популярное", "tv", 0) }
        assertThrows(IllegalArgumentException::class.java) { CatalogPages.path("Популярное", "movie", -1) }
    }
}
