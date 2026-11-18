package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class SourceBackedCatalogTest {
    @Test fun sourceBackedFilmsSurviveMetadataRefresh() {
        val studio = SourceBackedCatalog.initial("Смотреть", "Фильмы")
            .first { MosfilmCatalog.isVideoUrl(it.url) }
        val metadataOnly = CatalogMovie(
            CinemetaCatalog.movieUrl("movie", "tt1196946"),
            "Метаданные без источника", "", "2026"
        )
        val merged = SourceBackedCatalog.merge(listOf(studio), listOf(metadataOnly))
        assertEquals(listOf(studio), merged)
    }

    @Test fun allHomeSectionsHaveOnlyPlaybackProviderRoutes() {
        for (tab in listOf("Смотреть", "Мосфильм", "Архив")) {
            val cards = SourceBackedCatalog.initial(tab, "Фильмы")
            assertTrue("Empty tab: $tab", cards.isNotEmpty())
            assertTrue(cards.all { SourceBackedCatalog.isPlaybackCandidate(it.url) })
        }
    }

    @Test fun sourceSearchAndFiltersCannotDropVerifiedStudioIdentity() {
        val matches = SourceBackedCatalog.initial("Поиск", "Фильмы", "Бриллиантовая рука")
        assertEquals(1, matches.size)
        assertTrue(MosfilmCatalog.isVideoUrl(matches.single().url))
        assertTrue(SourceBackedCatalog.initial("Поиск", "Сериалы", "Бриллиантовая рука").isEmpty())
    }

    @Test fun oldMetadataCacheCannotAppearAsWatchableFilms() {
        val metadataOnly = CatalogMovie(
            CinemetaCatalog.movieUrl("series", "tt0337898"),
            "Бригада", "", "2002"
        )
        assertTrue(SourceBackedCatalog.merge(emptyList(), listOf(metadataOnly)).isEmpty())
    }
}
