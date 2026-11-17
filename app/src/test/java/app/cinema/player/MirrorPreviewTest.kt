package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorPreviewTest {
    @Test fun aMinutelongResponseIsNotA43MinuteEpisode() {
        val expected = MirrorPreview.expectedDuration("43 мин.")
        assertEquals(2_580_000L, expected)
        assertTrue(MirrorPreview.isPreview(60_000, expected))
        assertFalse(MirrorPreview.isPreview(2_575_000, expected))
    }

    @Test fun shortFilmsAndUnknownRuntimesAreNotRatedByDurationAlone() {
        assertFalse(MirrorPreview.isPreview(60_000, MirrorPreview.expectedDuration("PT1M")))
        assertFalse(MirrorPreview.isPreview(60_000, null))
        assertFalse(MirrorPreview.isPreview(-1, MirrorPreview.expectedDuration("PT43M")))
    }

    @Test fun distinguishesAPaidResponseFromAPreviewAndDoesNotAutoSelectKnownPaidVoices() {
        assertEquals(VoiceAvailability.PREVIEW,
            MirrorMediaParser.parseStreamInfo("""{"preview":true}""").failure?.voiceAvailability())
        assertEquals(VoiceAvailability.SUBSCRIPTION,
            MirrorMediaParser.parseStreamInfo("""{"__kinoStatus":402}""").failure?.voiceAvailability())
        val choices = listOf(
            MirrorVoice("1", "Оригинал", requiresSubscription = true),
            MirrorVoice("2", "Открытый дубляж")
        )
        assertEquals("2", AutoVoicePolicy.next(choices, "", emptyList())?.id)
    }
}
