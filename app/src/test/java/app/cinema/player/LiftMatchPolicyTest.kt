package app.cinema.player

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiftMatchPolicyTest {
    private val tent1969 =
        "https://liftw.ws/details.html?id=18899&name=%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%B0%D1%8F%20%D0%BF%D0%B0%D0%BB%D0%B0%D1%82%D0%BA%D0%B0&year=1969"
    private val tent2018 =
        "https://liftw.ws/details.html?id=29999&name=%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%B0%D1%8F%20%D0%BF%D0%B0%D0%BB%D0%B0%D1%82%D0%BA%D0%B0&year=2018"

    @Test fun exactTitleAndYearSelectsRightFilm() {
        val rows = listOf(
            LiftFilmMatch(tent2018, "Красная палатка", 2018),
            LiftFilmMatch(tent1969, "Красная палатка", 1969)
        )
        assertEquals(tent1969,
            LiftMatchPolicy.singleExact("Красная палатка", 1969, rows)?.url)
        assertNull(LiftMatchPolicy.singleExact("Красная палатка", null, rows))
        assertNull(LiftMatchPolicy.singleExact("Красная палатка", 2026, rows))
        assertNull(LiftMatchPolicy.singleExact("Красная шапочка", 1969, rows))
    }

    @Test fun normalizesPunctuationAndYoButNotDifferentTitles() {
        val rows = listOf(LiftFilmMatch(tent1969, "Красная палатка", 1969))
        assertEquals(tent1969,
            LiftMatchPolicy.singleExact("КРАСНАЯ — ПАЛАТКА!", 1969, rows)?.url)
        assertEquals("все еще люблю", LiftMatchPolicy.normalize("Всё ещё люблю"))
        assertEquals(1969, LiftMatchPolicy.yearFromDetails("1969 · СССР · Драма"))
        assertNull(LiftMatchPolicy.yearFromDetails("СССР · Драма"))
    }

    @Test fun rejectsSpoofedHostsOrUnrelatedLinks() {
        assertTrue(LiftMatchPolicy.safeDetailsUrl(tent1969))
        assertFalse(LiftMatchPolicy.safeDetailsUrl("https://liftw.ws.evil.org/details.html?id=18899"))
        assertFalse(LiftMatchPolicy.safeDetailsUrl("http://liftw.ws/details.html?id=18899"))
        assertFalse(LiftMatchPolicy.safeDetailsUrl("https://x@liftw.ws/details.html?id=18899"))
        assertFalse(LiftMatchPolicy.safeDetailsUrl("https://liftw.ws/details.html?name=Film"))
        assertFalse(LiftMatchPolicy.safeDetailsUrl("https://liftw.ws/search.html?id=18899"))
    }

    @Test fun readsRealBrowserJavascriptResultWithoutUsingStreamUrls() {
        val inner = """[{"url":"$tent1969","title":"SD Красная палатка","year":"1969"}]"""
        val matches = LiftMatchPolicy.parseBrowserResults(JSONObject.quote(inner))
        assertEquals(1, matches.size)
        assertEquals("Красная палатка", matches.single().title)
        assertEquals(1969, matches.single().year)
        assertEquals(tent1969, matches.single().url)
        assertTrue(LiftMatchPolicy.parseBrowserResults("not-json").isEmpty())
    }

    @Test fun resultWithoutNameParameterUsesCleanVisibleTitle() {
        val url = "https://liftw.ws/details.html?id=18899&year=1969"
        val json = """[{"url":"$url","title":"FHD Красная палатка","year":"1969"}]"""
        val match = LiftMatchPolicy.parseBrowserResults(JSONObject.quote(json)).single()
        assertEquals("Красная палатка", match.title)
        assertEquals(url, LiftMatchPolicy.singleExact("Красная палатка", 1969, listOf(match))?.url)
    }

    @Test fun originalSiteSearchSupportsIngaWithoutExternalMetadataFilters() {
        assertEquals(LiftWebSource.SEARCH, LiftWebSource.startUrl("Инга"))
        val script = LiftMatchPolicy.searchScript("Инга")
        assertTrue(script.contains("descriptor.set.call(field,"))
        assertTrue(script.contains("new Event('input'"))
        assertTrue(script.contains("Инга"))
        // Only the duplicate site's own tab navigation is hidden; the
        // first-party film result links remain visible and clickable.
        assertTrue(LiftMatchPolicy.hideDuplicateNavigationScript.contains("profile.html"))
        assertFalse(LiftMatchPolicy.hideDuplicateNavigationScript.contains("document.body.style.display"))
    }

    @Test fun browserJavascriptQuotesUntrustedUserTitle() {
        val script = LiftMatchPolicy.searchScript("\"; alert(1); //")
        assertTrue(script.contains("input#q"))
        assertTrue(script.contains("dispatchEvent"))
        assertTrue(script.contains("\\\"; alert(1); //"))
        assertFalse(script.contains("descriptor.set.call(field,\";"))
    }
}
