package app.cinema.player

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Deliberately talks to real public services, not MockWebServer.
 * HTTP responses establish metadata and MP4 byte availability,
 * not end-to-end playback on the user's phone.
 */
class CinemetaLiveNetworkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualCyrillicSearchFindsBrigada() = runBlocking {
        withTimeout(40_000) {
            val movies = CinemetaCatalog.search(context, "Бригада", "Сериалы")
            val ranked = SearchRelevance.find(movies, "Бригада")
            assertTrue("Real Russian query must contain Brigada", ranked.isNotEmpty())
            assertEquals("Бригада", ranked.first().title)
            assertEquals("series" to "tt0337898",
                CinemetaCatalog.identity(ranked.first().url))
            assertEquals("Known incorrect Cinemeta cover must not be displayed",
                "", ranked.first().poster)
            assertTrue("Unrelated titles must be excluded",
                ranked.none { it.title == "First Dates" || it.title == "Law & Order" })
        }
    }

    @Test fun mentalistDetailsHaveRealEpisodeMetadata() = runBlocking {
        withTimeout(40_000) {
            val meta = CinemetaCatalog.metadata(
                context, CinemetaCatalog.movieUrl("series", "tt1196946"))
            assertTrue(meta.actors.contains("Simon Baker"))
            assertTrue(meta.episodes.size > 100)
            assertTrue(meta.episodes.any { it.season == 1 && it.episode == 1 })
        }
    }

    @Test fun independentTvMazeSeriesLookupReturnsRealMetadata() = runBlocking {
        withTimeout(75_000) {
            val search = TvMazeSeries.search("The Mentalist")
            assertTrue("TVmaze should find real IMDb-mapped Mentalist series",
                search.any { CinemetaCatalog.identity(it.url) == ("series" to "tt1196946") })
            val details = TvMazeSeries.metadata("tt1196946")
            assertTrue(details.episodes.size > 100)
            assertTrue(details.actors.any { it.contains("Simon Baker") })
            assertTrue(details.source.contains("CC BY-SA"))
        }
    }

    @Test fun onlyLicenseVerifiedArchiveStreamsCanBeAdvertised() = runBlocking {
        withTimeout(50_000) {
            val stream = InternetArchiveStreams.available(
                context, CinemetaCatalog.movieUrl("movie", "tt0063350"))
            assertTrue("Archive movie must provide byte-range verified MP4, not HTTP 200 text",
                stream.isNotEmpty())
            assertTrue(stream.all { it.url.startsWith("https://archive.org/download/") })
            assertTrue(InternetArchiveStreams.available(
                context, CinemetaCatalog.movieUrl("series", "tt1196946")).isEmpty())
        }
    }
}
