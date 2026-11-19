package app.cinema.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmInfoMergerTest {
    private val info = FilmInfo(
        originalTitle = "",
        description = "",
        facts = emptyList(),
        rating = "",
        cast = emptyList(),
        related = emptyList()
    )

    @Test fun pendingPortraitsAreNotCancelledByAnotherSnapshot() {
        val resolved = info.copy(portraitsResolved = true)
        val unresolved = info.copy(portraitsResolved = false)
        assertFalse(FilmInfoMerger.merge(resolved, unresolved).portraitsResolved)
        assertFalse(FilmInfoMerger.merge(unresolved, resolved).portraitsResolved)
        assertTrue(FilmInfoMerger.merge(resolved, resolved).portraitsResolved)
    }

    @Test fun personNamesAreNormalizedBeforeMerging() {
        val previous = info.copy(cast = listOf(
            FilmPerson("  ЁЛКИН   ИВАН ", "old-photo", "old-url", "31")
        ))
        val latest = info.copy(cast = listOf(
            FilmPerson("елкин иван", photo = "", url = "new-url")
        ))

        val person = FilmInfoMerger.merge(previous, latest).cast.single()
        assertEquals("елкин иван", person.name)
        assertEquals("old-photo", person.photo)
        assertEquals("new-url", person.url)
        assertEquals("31", person.pid)
    }
}
