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

    @Test fun metadataForBrigadaDoesNotBecomeFalseWatchableSearchResult() = runBlocking {
        withTimeout(40_000) {
            // Cinemeta still supplies real descriptive metadata for this show.
            val film = CinemetaCatalog.metadata(
                context, CinemetaCatalog.movieUrl("series", "tt0337898"))
            assertEquals("Бригада", film.item.title)
            assertTrue(film.item.poster.startsWith("https://"))
            // No verified full-video integration exists for this ID. It must
            // not be advertised in the source-backed watch catalogue.
            val watch = CinemetaCatalog.search(context, "Бригада", "Сериалы")
            assertFalse(watch.any { CinemetaCatalog.identity(it.url) ==
                ("series" to "tt0337898") })
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
