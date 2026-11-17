package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FilmTextSanitizerTest {
    @Test fun choosesRealPlotOverPremiumSeoWhenProvidedByMirror() {
        val raw = org.json.JSONObject.quote(
            """{"description":"Подписка HDRezka Premium — смотреть без рекламы","descriptions":["Подписка HDRezka Premium — смотреть без рекламы","После исчезновения друга герой раскрывает старую тайну."]}"""
        )
        val info = FilmPageParser.parse(raw, "https://mirror.example.com")
        assertEquals("После исчезновения друга герой раскрывает старую тайну.",info?.description)
    }
    @Test fun hidesLongPremiumAdvertisements() {
        assertEquals("", FilmTextSanitizer.description(
            "Подписка HDRezka Premium: фильмы и сериалы в озвучке HDRezka Studio, " +
                "переводы без цензуры, субтитры, качество до 4K UHD и просмотр без рекламы. " +
                "Более 3500 переведенных релизов."
        ))
    }
    @Test fun keepsLongRealPlotWhenThereIsNoAdvertising() {
        assertTrue(FilmTextSanitizer.description(
            "Уличный боец возвращается домой и сталкивается с последствиями войны."
        ).startsWith("Уличный боец"))
    }
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
