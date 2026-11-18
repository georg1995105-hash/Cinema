package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class WatchChoicePolicyTest {
    @Test fun selectionIsRequiredToStartPlayback() {
        val options = listOf(MirrorVoice("11", "Стандартная"), MirrorVoice("29", "Дубляж"))
        assertFalse(WatchChoicePolicy.validChoice(options, ""))
        assertFalse(WatchChoicePolicy.validChoice(options, "0"))
        assertTrue(WatchChoicePolicy.validChoice(options, "11"))
    }

    @Test fun seasonsAndEpisodesComeOnlyFromSource() {
        val episodes = listOf(
            MirrorEpisode("1", "1", "Первая серия"),
            MirrorEpisode("1", "2", "Вторая серия"),
            MirrorEpisode("2", "1", "Сезон 2"))
        assertEquals(2, WatchChoicePolicy.episodesFor(episodes, "1").size)
        assertEquals("1", WatchChoicePolicy.episodesFor(episodes, "2").single().id)
    }
}
