package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class EpisodeNavigatorTest {
    private val episodes = listOf(
        MirrorEpisode("2", "1", "Серия 1"),
        MirrorEpisode("1", "10", "Серия 10"),
        MirrorEpisode("1", "2", "Серия 2"),
        MirrorEpisode("1", "2", "Повтор"),
        MirrorEpisode("1", "1", "Серия 1")
    )

    @Test fun advancesByNumberInsteadOfLexicalOrder() {
        assertEquals("10", EpisodeNavigator.next(episodes, "1", "2")?.id)
    }

    @Test fun continuesAcrossSeasonBoundary() {
        val next = EpisodeNavigator.next(episodes, "1", "10")
        assertEquals("2", next?.season)
        assertEquals("1", next?.id)
    }

    @Test fun stopsAtTheLastEpisode() {
        assertNull(EpisodeNavigator.next(episodes, "2", "1"))
    }

    @Test fun neverStartsUnknownEpisode() {
        assertNull(EpisodeNavigator.next(episodes, "3", "99"))
    }

    @Test fun canMoveBackToPreviousSeason() {
        assertEquals("10", EpisodeNavigator.previous(episodes, "2", "1")?.id)
    }
}
