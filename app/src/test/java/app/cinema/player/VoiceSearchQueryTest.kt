package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class VoiceSearchQueryTest {
    @Test fun searchesSpeechResultImmediatelyByTitle() {
        assertEquals("Одиссея", VoiceSearchQuery.clean("Найди фильм Одиссея"))
        assertEquals("Люпен", VoiceSearchQuery.clean("покажи сериал Люпен."))
        assertEquals("Три богатыря", VoiceSearchQuery.clean("Три богатыря"))
    }
    @Test fun removesMetadataSuffixWithoutChangingRealTitle() {
        assertEquals("Одиссея", CatalogTitleCleaner.clean("Одиссея 2026, Великобритания, Фэнтези"))
        assertEquals("1917", CatalogTitleCleaner.clean("1917"))
        assertEquals("Звёздные войны", CatalogTitleCleaner.clean("Звёздные войны"))
    }
}
