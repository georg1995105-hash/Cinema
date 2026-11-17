package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorHtmlMetadataTest {
    private val url = "https://rezka.example/series/drama/123-brigada.html"

    @Test fun readsARealFilmPageWithoutWebViewJavascript() {
        val html = """
          <html><head><meta property="og:image" content="https://rezka.example/images/brigada.jpg"></head>
          <body><article class="b-post">
          <div class="b-post__title"><h1>Бригада</h1></div>
          <div class="b-post__description_text">История четырёх друзей.</div>
          <table class="b-post__infotable">
           <tr><th>Страна</th><td>Россия</td></tr>
           <tr><th>Режиссёр</th><td><a href="/person/1/">Режиссёр Один</a></td></tr>
           <tr><th>В ролях</th><td><a href="/person/2/">Актёр Два</a></td></tr>
          </table>
          <ul id="translators-list"><li data-translator_id="17">Стандартная</li></ul>
          <div class="b-simple_season__item" data-tab_id="1">Сезон 1</div>
          <div class="b-simple_episode__item" data-episode_id="1">Серия 1</div>
          <script>initCDNSeriesEvents(123,17,0,0,0);</script></article></body></html>
        """
        val data = MirrorHtmlMetadata.parse(url,html)
        assertEquals("",data.failure)
        val info = data.info!!
        assertEquals("Бригада",info.title)
        assertTrue(info.description.contains("четырёх"))
        assertEquals("Россия",info.facts.first { it.first == "Страна" }.second)
        assertEquals("Режиссёр Один",info.directors.single().name)
        assertEquals("Актёр Два",info.cast.single().name)
        assertEquals(1,info.voices.size)
        assertEquals("17",info.defaultVoice)
        assertEquals("17",data.player!!.defaultVoice)
        assertEquals("1",data.player!!.episodes.single().id)
    }

    @Test fun premiumLandingPageIsNotMistakenForARealMovie() {
        val html="<html><title>HDRezka Premium</title><body>Premium подписка</body></html>"
        val result=MirrorHtmlMetadata.parse(url,html)
        assertNull(result.info)
        assertNull(result.player)
        assertTrue(result.failure.contains("Premium"))
    }

    @Test fun missingMetadataDoesNotInventFilmsOrVoices() {
        val result=MirrorHtmlMetadata.parse(url,"<html><body>Welcome</body></html>")
        assertNull(result.info)
        assertNull(result.player)
    }
}
