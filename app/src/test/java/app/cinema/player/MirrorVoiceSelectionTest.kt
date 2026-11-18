package app.cinema.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorVoiceSelectionTest {
    private val page = MirrorMoviePage(
        id = "123",
        isSeries = false,
        voices = listOf(
            MirrorVoice("11", "Стандартная"),
            MirrorVoice("27", "Дубляж")
        ),
        episodes = emptyList(),
        defaultVoice = "11"
    )

    @Test fun selectedTranslatorMustExistOnResolvedMirror() {
        assertTrue(MirrorVoiceSelection.supports(page, "27"))
        assertTrue(MirrorVoiceSelection.supports(page, "11"))
        assertFalse(MirrorVoiceSelection.supports(page, "99"))
        assertFalse(MirrorVoiceSelection.supports(page, ""))
    }

    @Test fun chooseNeverFallsBackToAnotherTranslator() {
        assertEquals("27", MirrorVoiceSelection.choose(page, "27"))
        assertEquals("", MirrorVoiceSelection.choose(page, "99"))
        assertEquals("", MirrorVoiceSelection.choose(page, null))
    }
}
