package app.cinema.player

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class LibraryCardMetadataTest {
    @Test fun recoversRealTitleAndPoster() {
        val payload = JSONObject().put("title", "Настоящий фильм")
            .put("poster", "https://images.example.com/film.jpg").toString()
        val wrapped = JSONObject.quote(payload)
        val result = LibraryCardMetadata.parse(wrapped, "https://mirror.example.com/films/title")
        assertEquals("Настоящий фильм", result?.title)
        assertEquals("https://images.example.com/film.jpg", result?.poster)
    }

    @Test fun refusesEmptyOrInvalidResults() {
        assertNull(LibraryCardMetadata.parse(JSONObject.quote("""{"title":""}"""),
            "https://mirror.example.com/films/empty"))
        assertNull(LibraryCardMetadata.parse(JSONObject.quote("""{"title":"Error"}"""),
            "http://insecure.example.com/"))
    }
}
