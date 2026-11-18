package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MosfilmCatalogTest {
    @Test fun allStudioFilmsHaveValidatedTitlesAndOfficialEmbedUrls() {
        val items = MosfilmCatalog.catalog("Фильмы")
        assertTrue("Expected several real studio movies rather than a single teaser", items.size >= 25)
        assertEquals(items.size, items.map { it.url }.distinct().size)
        assertTrue(items.all { item ->
            MosfilmCatalog.isVideoUrl(item.url) &&
                MosfilmCatalog.embedUrl(item.url)?.let(MosfilmCatalog::isEmbedUrl) == true &&
                item.title.isNotBlank() && item.poster.startsWith("https://pic.rtbcdn.ru/")
        })
    }

    @Test fun searchFindsPublishedRussianMovieEvenIfMetadataApiIsDown() {
        val movies = MosfilmCatalog.search("бриллиантовая рука", "Все")
        assertEquals(1, movies.size)
        assertEquals("Бриллиантовая рука", movies.first().title)
        assertTrue(MosfilmCatalog.search("Солярис", "Фильмы").isNotEmpty())
        assertTrue(MosfilmCatalog.search("Бригада", "Сериалы").isEmpty())
    }

    @Test fun cannotRouteAnyArbitraryIdOrForeignOriginToEmbeddedPlayer() {
        val valid = MosfilmCatalog.catalog("Все").first().url
        val allowed = MosfilmCatalog.embedUrl(valid)
        assertNotNull(allowed)
        assertTrue(MosfilmCatalog.isEmbedUrl(allowed!!))
        assertFalse(MosfilmCatalog.isEmbedUrl(
            "https://rutube.ru/play/embed/00000000000000000000000000000000"))
        assertFalse(MosfilmCatalog.isEmbedUrl(allowed.replace("rutube.ru", "fake-rutube.ru")))
        assertFalse(MosfilmCatalog.isEmbedUrl("$allowed?x=1"))
        assertFalse(MosfilmCatalog.isVideoUrl("https://rutube.ru/video/../play/embed/x"))
    }

    @Test fun officialMetadataHasSourceAndRealRunningTime() {
        val film = MosfilmCatalog.search("Иван Васильевич", "Фильмы").single()
        val info = MosfilmCatalog.metadata(film.url)
        assertTrue(info.runtime.endsWith("мин"))
        assertTrue(info.source.contains("Мосфильм"))
        assertEquals("СССР", info.country)
        assertEquals(film.title, info.originalTitle)
    }
}
