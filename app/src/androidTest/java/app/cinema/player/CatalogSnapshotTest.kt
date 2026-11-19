package app.cinema.player

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start snapshot is a real public metadata result. It remains readable
 * when no network request is issued; private URLs must not enter the cache.
 */
class CatalogSnapshotTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistedCardsSurviveReaderReloadWithoutNetwork() = runBlocking {
        val first = CatalogMovie(
            CinemetaCatalog.movieUrl("series", "tt0337898"),
            "Бригада",
            "https://example.org/brigada.jpg",
            "2002 · Россия"
        )
        CinemetaCatalog.saveCachedCards(context, "Кэш тест", "Сериалы", "Бригада", listOf(first))
        val restored = CinemetaCatalog.cachedCards(context, "Кэш тест", "Сериалы", "Бригада")
        assertEquals(1, restored.size)
        assertEquals(first.url, restored.single().url)
        assertEquals(first.title, restored.single().title)
        assertEquals(first.details, restored.single().details)
    }

    @Test fun missingSnapshotIsAnEmptyListNotAnException() = runBlocking {
        val data = CinemetaCatalog.cachedCards(
            context, "Кэш тест", "Сериалы", "несуществующая запись 2026"
        )
        assertTrue(data.isEmpty())
    }
}
