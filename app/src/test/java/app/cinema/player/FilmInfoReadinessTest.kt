package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FilmInfoReadinessTest {
    private fun info(description: String = "", franchisesLoaded: Boolean = true) =
        FilmInfo("", description, emptyList(), "", emptyList(),
            emptyList(), franchiseResolved = franchisesLoaded)

    @Test fun emptyPreviewCannotCompleteDetails() {
        assertFalse(FilmInfoReadiness.hasContent(info()))
        assertFalse(FilmInfoReadiness.canFinish(info()))
    }

    @Test fun realDescriptionCanCompleteAfterFranchiseCheck() {
        assertTrue(FilmInfoReadiness.hasContent(info("Описание фильма")))
        assertTrue(FilmInfoReadiness.canFinish(info("Описание фильма")))
        assertFalse(FilmInfoReadiness.canFinish(info("Описание фильма", false)))
    }

    @Test fun missingActorPhotosKeepBackgroundEnrichmentOpen() {
        val actor=FilmPerson("Актёр", "", "https://example.org/person/1")
        val film=info("Описание").copy(cast=listOf(actor))
        assertTrue(FilmInfoReadiness.hasContent(film))
        assertFalse(FilmInfoReadiness.canFinish(film))
    }
}
