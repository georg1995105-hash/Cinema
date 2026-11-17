package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorSearchUrlTest {
    @Test fun encodesRussianFilmNamesAndUsesMirror() {
        val url = MirrorCatalog.searchUrl("https://example.org", "  бригада  ")
        assertEquals(
            "https://example.org/search/?do=search&subaction=search&q=%D0%B1%D1%80%D0%B8%D0%B3%D0%B0%D0%B4%D0%B0",
            url
        )
    }
    @Test fun respectsMirrorPathAndEscapesQuery() {
        val url = MirrorCatalog.searchUrl("https://example.org/", "Актер & фильм")
        assertTrue(url.startsWith("https://example.org/search/?"))
        assertTrue(url.contains("%26"))
        assertFalse(url.contains(" & "))
    }
}
