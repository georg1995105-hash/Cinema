package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FilmTextSanitizerTest {
    @Test fun hidesMarketingDescription() {
        assertEquals("", FilmTextSanitizer.description(
            "Смотреть сериал Люпен онлайн в хорошем качестве совершенно бесплатно и без регистрации! Приятного просмотра!"
        ))
    }
    @Test fun preservesRealSynopsis() {
        assertEquals("Главный герой пытается раскрыть тайну исчезновения друга.",
            FilmTextSanitizer.description(" Главный герой пытается раскрыть тайну исчезновения друга. "))
    }
    @Test fun handlesBlank() {
        assertEquals("", FilmTextSanitizer.description(" "))
    }
}
