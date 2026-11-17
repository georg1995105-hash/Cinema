package app.cinema.player

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SourceFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://source.example"
    private val film = "$origin/series/drama/12-brigada.html"

    @SuppressLint("SetJavaScriptEnabled")
    private fun withPage(html: String, check: (WebView) -> Unit) {
        lateinit var web: WebView
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { finished.countDown() }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.isForMainFrame) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }
                loadDataWithBaseURL(film, html, "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("WebView loaded fixture", finished.await(15, TimeUnit.SECONDS))
            check(web)
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    private fun evaluate(web: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { value -> result = value; completed.countDown() }
        }
        assertTrue("JavaScript evaluation", completed.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun poll(web: WebView, expression: String): String = runBlocking {
        repeat(25) {
            val value = evaluate(web, expression)
            if (value.isNotBlank() && value != "null") return@runBlocking value
            delay(120)
        }
        error("No source response from fixture")
    }

    @Test fun spokenTitleGetsTheRightFilmAndPosterInWebView() = withPage(
        """
        <script>
        window.calls = [];
        window.fetch = async function(url) {
          const address = new URL(url, location.href);
          window.calls.push(address.pathname + address.search);
          if (address.pathname === '/engine/ajax/search.php') {
            const html = address.searchParams.get('q') === 'Бригада' ?
              '<div class="b-search__live_section"><ul><li><a href="/series/drama/12-brigada.html"><span class="enty">Бригада</span><small>2002, Россия, Драмы</small><img src="/static/hdrezka.png"></a></li><li><a href="/films/drama/99-other.html"><span class="enty">Другой фильм</span></a></li></ul></div>' : '';
            return {ok:true, url:address.href, text:async()=>html};
          }
          return {ok:true, url:location.origin + '/series/drama/12-canonical.html',
            text:async()=>'<div class="b-post__poster"><img src="/static/hdrezka.png" data-src="/posters/12.jpg"></div>'};
        };
        </script>
        """.trimIndent()
    ) { web ->
        val query = VoiceSearchQuery.clean("найди Бригаду")
        evaluate(web, MirrorQuickSearch.javascript(query))
        val raw = poll(web, "window.__kinoQuickSearchResult || null")
        val matches = SearchRelevance.find(MirrorCatalog.parse(raw, origin), query)
        assertEquals("Бригада", matches.single().title)
        assertEquals(film, matches.single().url)
        assertEquals("2002, Россия, Драмы", matches.single().details)
        assertTrue(matches.single().poster.isBlank())
        evaluate(web, MirrorQuickSearch.postersJavascript(matches))
        val resolved = MirrorQuickSearch.mergePosters(poll(web, "window.__kinoSearchPosters || null"), matches)
        assertEquals("$origin/posters/12.jpg", resolved.single().poster)
        assertEquals("3", evaluate(web, "window.calls.length"))
    }

    @Test fun filmDetailsUseOnlyTheCurrentTitlePlotPeopleAndRelations() = withPage(
        """
        <h1 class="b-post__title">Бригада</h1>
        <meta property="og:image" content="/static/hdrezka.png">
        <div class="b-post__poster"><img src="/posters/12.jpg"></div>
        <div class="b-post__description_text">Оформите подписку HDRezka Premium</div>
        <script type="application/ld+json">{"@graph":[
          {"@type":"WebSite","name":"Реклама","description":"Ложное описание"},
          {"@type":"TVSeries","name":"Другое","url":"/series/13-other.html","description":"Чужое описание"},
          {"@type":"TVSeries","name":"Бригада","url":"/series/drama/12-brigada.html","description":"История четырёх друзей."}
        ]}</script>
        <table class="b-post__infotable">
          <tr><td>В ролях:</td><td><a href="/person/12-actor.html">Актёр</a></td></tr>
          <tr><td>Режиссёр:</td><td><a href="/person/13-director.html">Режиссёр</a></td></tr>
          <tr><td>Композитор:</td><td>Композитор</td></tr>
        </table>
        <div class="b-content__inline_item"><a href="/films/drama/23-unrelated.html">Случайный фильм</a></div>
        <div class="b-post__related">
          <div class="b-content__inline_item"><a href="/series/12-canonical.html">Бригада</a></div>
          <div class="b-content__inline_item"><a href="/films/drama/34-next.html">Бригада: Наследник</a></div>
        </div>
        """.trimIndent()
    ) { web ->
        val info = requireNotNull(FilmPageParser.parse(evaluate(web, FilmPageParser.javascript), origin))
        assertEquals("Бригада", info.title)
        assertEquals("История четырёх друзей.", info.description)
        assertEquals("$origin/posters/12.jpg", info.posters.single())
        assertEquals("Актёр", info.cast.single().name)
        assertEquals("Режиссёр", info.directors.single().name)
        assertEquals("Бригада: Наследник", info.related.single().title)
    }

    @Test fun playerResolvesVoiceEpisodesAndAjaxResponsesFromWebView() = withPage(
        """
        <script type="application/x-fixture">sof.tv.initCDNSeriesEvents(12, 7, 0, 1, 0);</script>
        <ul id="translators-list"><li data-translator_id="7" class="active">Оригинал</li>
        <li data-translator-id="8" data-ads="0">Дубляж</li></ul>
        <input id="favs" value="fixture-token">
        <script>
        window.fetch = async function(url, request) {
          const body = new URLSearchParams(request.body);
          const correct = url.startsWith('/ajax/get_cdn_series/') && request.method === 'POST' &&
            request.credentials === 'same-origin' && body.get('id') === '12' && body.get('translator_id') === '7';
          if (correct && body.get('action') === 'get_episodes') {
            const episodes = '<ul id="simple-episodes-list-2"><li class="b-simple_episode__item" data-episode_id="3">Третья серия</li></ul>';
            return {ok:true, status:200, text:async()=>JSON.stringify({success:1,episodes:episodes})};
          }
          if (correct && body.get('action') === 'get_stream' && body.get('season') === '2' &&
              body.get('episode') === '3' && body.get('favs') === 'fixture-token') {
            return {ok:true, status:200, text:async()=>JSON.stringify({success:1,url:'[720p]https://media.example/film.m3u8'})};
          }
          return {ok:false, status:403, text:async()=>'Access denied'};
        };
        </script>
        """.trimIndent()
    ) { web ->
        val page = requireNotNull(MirrorMediaParser.pageFromWebView(evaluate(web, mirrorPlayerMetadataJavascript), film))
        assertEquals("12", page.id)
        assertEquals(listOf("Оригинал", "Дубляж"), page.voices.map(MirrorVoice::name))
        assertEquals("7", page.defaultVoice)
        val episodes = runBlocking {
            withContext(Dispatchers.Main) {
                mirrorAjax(web, film, mapOf("action" to "get_episodes", "id" to page.id, "translator_id" to page.defaultVoice))
            }
        }
        val response = requireNotNull(episodes?.let(MirrorMediaParser::objectFromWebView))
        val episode = MirrorMediaParser.parseEpisodes(response.optJSONArray("__kinoEpisodes")).single()
        val streams = runBlocking {
            withContext(Dispatchers.Main) {
                mirrorAjax(web, film, MirrorPlaybackRequest.stream(page, page.defaultVoice, episode.season, episode.id))
            }
        }
        val streamInfo = MirrorMediaParser.parseStreamInfo(requireNotNull(streams))
        assertEquals("https://media.example/film.m3u8", streamInfo.qualities.single().url)
        val denied = runBlocking {
            withContext(Dispatchers.Main) { mirrorAjax(web, film, mapOf("action" to "get_movie")) }
        }
        assertEquals(PlaybackFailure.BLOCKED, MirrorMediaParser.parseStreamInfo(requireNotNull(denied)).failure)
    }

    @Test fun readsExistingUserDataWithThePublishedPreferenceKeys() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("cinema_local_v1", android.content.Context.MODE_PRIVATE)
        val oldSelection = """{"$film":{"season":"2","episode":"3","voice":"7","quality":"720p","positionMs":45000}}"""
        assertTrue(preferences.edit()
            .putString("sources", """["$origin"]""")
            .putString("active_source", origin)
            .putString("favourites", """["$film"]""")
            .putString("watch_history", """[{"url":"$film","positionMs":45000}]""")
            .putString("playback_selections_v2", oldSelection)
            .putString("account_names_v1", """{"$origin":"fixture@example.org"}""")
            .putInt("seek_seconds", 15)
            .putInt("hold_speed", 3)
            .putBoolean("auto_next_episode", false)
            .commit())
        val store = LocalStore(context)
        assertEquals(origin, store.activeSource())
        assertEquals(listOf(film), store.favourites())
        assertEquals(WatchedVideo(film, 45000), store.history().single())
        assertEquals(PlaybackSelection("2", "3", "7", "720p", 45000), store.playbackSelection(film))
        assertEquals("fixture@example.org", store.savedAccountName(origin))
        assertEquals(15, store.seekSeconds())
        assertEquals(3, store.holdSpeed())
        assertFalse(store.autoNextEpisode())
    }

    @Test fun media3DecodesAnAuthorizedTestClipAndAdvancesPlayback() {
        val context = instrumentation.targetContext
        val clip = File(context.cacheDir, "kino-test-video.mp4")
        instrumentation.context.assets.open("kino-test-video.mp4").use { input ->
            clip.outputStream().use { output -> input.copyTo(output) }
        }
        val playing = CountDownLatch(1)
        lateinit var player: ExoPlayer
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) playing.countDown()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
                    prepare()
                    play()
                }
            }
            assertTrue("Media3 entered playing state", playing.await(15, TimeUnit.SECONDS))
            Thread.sleep(700)
            instrumentation.runOnMainSync { assertTrue("Playback advanced", player.currentPosition > 200) }
        } finally {
            instrumentation.runOnMainSync { player.release() }
            clip.delete()
        }
    }
}
