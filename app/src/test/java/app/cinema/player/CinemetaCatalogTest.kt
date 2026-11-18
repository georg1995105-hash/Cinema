package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemetaCatalogTest {
    @Test fun cyrillicSeriesIsRecognizedWithIndependentIdentity() {
        val item = JSONObject()
            .put("id", "tt0337898")
            .put("name", "Law of the Lawless")
            .put("type", "series")
            .put("year", "2002")
            .put("poster", "https://images.metahub.space/poster/small/tt0337898/img")
        val film = CinemetaCatalog.parseMovie(item)
        assertNotNull(film)
        assertTrue(CinemetaCatalog.isMovieUrl(film!!.url))
        assertEquals("series" to "tt0337898", CinemetaCatalog.identity(film.url))
        assertTrue(film.details.contains("2002"))
    }

    @Test fun actualEpisodeStructureIsNotMisreadAsVideoStream() {
        val item = JSONObject().put("id", "tt1196946")
            .put("type", "series")
            .put("name", "The Mentalist")
            .put("videos", JSONArray()
                .put(JSONObject().put("season", 1).put("episode", 1).put("title", "Pilot"))
                .put(JSONObject().put("season", 1).put("episode", 2).put("title", "Red Hair and Silver Tape"))
                .put(JSONObject().put("season", 1).put("episode", 1).put("title", "Pilot")))
        val meta = CinemetaCatalog.parseDetails(JSONObject().put("meta", item), "series", "tt1196946")
        assertEquals(2, meta.episodes.size)
        assertEquals(1, meta.episodes[0].season)
        assertEquals(2, meta.episodes[1].episode)
    }

    @Test fun emptyCatalogAndInvalidIdDoNotGenerateFictitiousCards() {
        assertNull(CinemetaCatalog.parseMovie(JSONObject().put("type", "movie")
            .put("id", "bad").put("name", "Fake")))
        assertTrue(CinemetaCatalog.parseList(JSONObject().put("metas", JSONArray())).isEmpty())
        assertFalse(CinemetaCatalog.isMovieUrl("https://example.com/meta/movie/tt0063350.json"))
    }
}
