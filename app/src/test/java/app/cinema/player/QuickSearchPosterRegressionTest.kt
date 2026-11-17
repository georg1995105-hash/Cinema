package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickSearchPosterRegressionTest {
    private val source = "https://example.org"
    private val brigada = CatalogMovie("$source/series/drama/12-brigada.html", "Бригада", "", "2002")

    @Test fun rejectsMirrorLogoAndKeepsRealAlternatives() {
        val candidate = JSONObject().put("poster", "$source/assets/hdrezka.png")
            .put("posters", JSONArray(listOf("$source/img/no_poster.jpg", "$source/img/12.jpg")))
        assertEquals(listOf("$source/img/12.jpg"), MirrorCatalog.readPosters(candidate))
    }

    @Test fun posterEnrichmentJoinsTheIdenticalFilmUrl() {
        val response = JSONArray()
            .put(JSONObject().put("url", "$source/series/drama/13-other.html")
                .put("posters", JSONArray(listOf("$source/img/13.jpg"))))
            .put(JSONObject().put("url", brigada.url)
                .put("posters", JSONArray(listOf("$source/img/12.jpg"))))
        val result = MirrorQuickSearch.mergePosters(JSONObject.quote(response.toString()), listOf(brigada))
        assertEquals(listOf(brigada.copy(poster = "$source/img/12.jpg")), result)
    }
}
