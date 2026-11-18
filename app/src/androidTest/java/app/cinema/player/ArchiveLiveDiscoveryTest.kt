package app.cinema.player

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercises the actual Internet Archive catalogue endpoint, not a fixture
 * or hardcoded fallback. This does not itself claim video playback success.
 */
class ArchiveLiveDiscoveryTest {
    @Test fun archiveHasRealPublicDomainFeatureFilmCandidates() = runBlocking {
        withTimeout(40_000L) {
            val films = ArchivePublicCatalog.liveDiscovery()
            assertTrue("Real public-domain film API returned no indexed feature films",
                films.size >= 5)
            assertTrue("Every candidate must be a valid Archive title",
                films.all { ArchivePublicCatalog.isArchiveUrl(it.url) })
        }
    }

    @Test fun publicDomainTitleOpensWithActualArchiveMetadata() = runBlocking {
        withTimeout(45_000L) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val movie = InternetArchiveStreams.details(
                ctx, ArchivePublicCatalog.url("Sita_Sings_the_Blues"))
            assertTrue(movie.item.title.contains("Sita", ignoreCase = true))
            assertTrue(movie.source.contains("Public Domain/CC0"))
            assertEquals("https://archive.org/details/Sita_Sings_the_Blues", movie.sourceUrl)
        }
    }
}
