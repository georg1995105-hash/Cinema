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
    @Test fun nestedTranslatorAndPlainDescriptionAreParsed() {
        val html = """
          <div class="b-post">
            <div class="b-post__title"><h1>Бригада</h1></div>
            <div class="b-post__description">Сюжет сериала, написанный обычным текстом.</div>
            <div id="translators-list"><li>
              <a data-translator_id="33" data-title="Стандартная">Стандартная</a>
            </li></div>
            <script>initCDNSeriesEvents(123,33,0,0,0);</script>
          </div>
        """
        val result=MirrorHtmlMetadata.parse(url,html)
        assertEquals("Сюжет сериала, написанный обычным текстом.",
            result.info!!.description)
        assertEquals("33",result.info!!.voices.single().id)
        assertEquals("Стандартная",result.info!!.voices.single().name)
    }

    @Test fun initOnlyTranslatorRemainsPlayableAndKeepsRuntimeFlags() {
        val html = """
          <div class="b-post">
            <div class="b-post__title"><h1>Бригада</h1></div>
            <div class="b-post__description_text">Описание.</div>
            <table class="b-post__infotable">
              <tr><th>Время</th><td>52 мин.</td></tr>
            </table>
            <script>initCDNSeriesEvents(123,77,1,0,1);</script>
          </div>
        """
        val result=MirrorHtmlMetadata.parse(url,html)
        val player=result.player!!
        assertEquals("77",player.defaultVoice)
        assertEquals("77",player.voices.single().id)
        assertEquals("По умолчанию",player.voices.single().name)
        assertEquals(1,player.defaultFlags!!.isCamrip)
        assertEquals(1,player.defaultFlags!!.isDirector)
        assertEquals(52L * 60_000L,player.expectedDurationMs)
    }

    @Test fun referencePremiumClassIsNotTreatedAsFreeVoice() {
        val html = """
          <div class="b-post">
            <div class="b-post__title"><h1>Фильм</h1></div>
            <div class="b-post__description_text">Описание.</div>
            <ul id="translators-list">
              <li class="b-translator__item active" data-translator_id="17">Обычная</li>
              <li class="b-translator__item b-prem_translator" data-translator_id="44">Premium</li>
            </ul>
            <script>initCDNMoviesEvents(123,17,0,0,0);</script>
          </div>
        """
        val voices = MirrorHtmlMetadata.parse(url, html).info!!.voices
        assertFalse(voices.first { it.id == "17" }.requiresSubscription)
        assertTrue(voices.first { it.id == "44" }.requiresSubscription)
    }
    @Test fun nativeFilmMetadataPreservesServerIssuedRequestParameters() {
        val html = """
          <div class="b-post">
            <h1 class="b-post__title">Бригада</h1>
            <div class="b-post__description_text">Сериал.</div>
            <input type="hidden" id="ctrl_favs" value="abc_12"/>
            <ul id="translators-list">
              <li data-translator_id="17">Обычная</li>
            </ul>
            <script>initCDNSeriesEvents(123,17,0,0,0);</script>
          </div>
        """
        val player = requireNotNull(MirrorHtmlMetadata.parse(url, html).player)
        assertEquals("abc_12", player.favs)
        val fields = MirrorPlaybackRequest.stream(player, "17", "1", "1")
        assertEquals("abc_12", fields["favs"])
        assertEquals("get_stream", fields["action"])
    }

    @Test fun preservesDifferentSourceIssuedFavsForEachSeriesTranslator() {
        val html = """
          <div class="b-post">
          <h1 class="b-post__title">Сериал</h1>
          <div class="b-post__description_text">Сериал и его озвучки.</div>
          <input id="favs" value="globalFallback"/>
          <ul id="translators-list">
            <li data-translator_id="17" data-favs="favsVoice17"
                data-ads="1" class="active">Обычная</li>
            <li data-translator_id="18" data-favs="favsVoice18"
                data-ads="0">Другой перевод</li>
          </ul>
          <script>initCDNSeriesEvents(123,17,0,1,0);</script>
          </div>
        """
        val page = requireNotNull(MirrorHtmlMetadata.parse(url, html).player)
        assertEquals("favsVoice17", MirrorPlaybackRequest.stream(page, "17", "1", "1")["favs"])
        assertEquals("favsVoice18", MirrorPlaybackRequest.stream(page, "18", "1", "1")["favs"])
        assertEquals("1", MirrorPlaybackRequest.stream(page, "17", "1", "1")["is_ads"])
        assertEquals("0", MirrorPlaybackRequest.stream(page, "18", "1", "1")["is_ads"])
    }

    @Test fun selectedTranslatorInheritsInitFlagsWhenNoDataAttributesExist() {
        val html = """
          <div class="b-post">
            <h1 class="b-post__title">Сериал</h1>
            <div class="b-post__description_text">Описание.</div>
            <ul id="translators-list"><li data-translator_id="17" class="active">Обычная</li></ul>
            <script>initCDNSeriesEvents(123,17,0,1,0);</script>
          </div>
        """
        val player = requireNotNull(MirrorHtmlMetadata.parse(url, html).player)
        assertEquals("1", MirrorPlaybackRequest.stream(
            player, "17", "1", "1")["is_ads"])
    }

    @Test fun nativeSeriesParserUsesEightArgumentInitFromReference() {
        val html = """
          <div class="b-post">
            <h1 class="b-post__title">Сериал</h1>
            <div class="b-post__description_text">Описание.</div>
            <ul id="translators-list">
              <li data-translator_id="17" class="active">Обычная</li>
            </ul>
            <script>initCDNSeriesEvents(123,17,1,5,0,null,1,0);</script>
          </div>
        """
        val page = requireNotNull(MirrorHtmlMetadata.parse(url, html).player)
        assertTrue(page.isSeries)
        assertEquals("17", page.defaultVoice)
        assertEquals("1", MirrorPlaybackRequest.stream(page, "17", "1", "5")["is_ads"])
        assertEquals("0", MirrorPlaybackRequest.stream(page, "17", "1", "5")["is_camrip"])
    }

}
