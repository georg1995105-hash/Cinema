package app.cinema.player

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
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
                loadDataWithBaseURL(film, html, "text/html", "UTF-8", film)
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
              '<div class="b-search__live_section"><ul><li><a href="/series/drama/12-brigada.html"><span class="enty">Бригада</span><small>2002, Россия, Драмы</small><img src="/static/hdrezka.png"></a></li><li><a href="/films/drama/99-other.html"><span class="enty">Другой фильм</span></a></li></ul></div>' : '<div class="b-search__live_section"><ul><li><a href="/films/drama/99-other.html"><span class="enty">Другой фильм</span></a></li></ul></div>';
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

    @Test fun launcherShowsCinemaWithAnAdaptiveIcon() {
        val context = instrumentation.targetContext
        val activity = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java), 0)
        assertEquals("Cinema", activity.loadLabel(context.packageManager).toString())
        assertTrue(activity.loadIcon(context.packageManager) is android.graphics.drawable.AdaptiveIconDrawable)
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
        assertEquals("fixture@example.org", store.savedAccountName("$origin/"))
        store.saveAccountName("$origin/", "updated@example.org")
        assertEquals("updated@example.org", LocalStore(context).savedAccountName(origin))
        assertEquals(15, store.seekSeconds())
        assertEquals(3, store.holdSpeed())
        assertFalse(store.autoNextEpisode())
        store.savePlaybackSpeed(1.5f)
        store.savePictureMode("Заполнить")
        assertEquals(1.5f, LocalStore(context).playbackSpeed(), 0f)
        assertEquals("Заполнить", LocalStore(context).pictureMode())
    }

    @Test fun media3PlaysTheParsedHttpsStreamWithoutForwardingSessionToCdn() {
        val context = instrumentation.targetContext
        val clip = instrumentation.context.assets.open("kino-test-video.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.Builder()
            .commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val fixtureTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { mirror ->
            MockWebServer().use { cdn ->
                listOf(mirror, cdn).forEach {
                    it.useHttps(serverTls.sslSocketFactory(), false)
                    it.start()
                }
                fun localUrl(server: MockWebServer, path: String) =
                    server.url(path).newBuilder().host("localhost").build()
                val filmPage = localUrl(mirror, "/films/drama/12-brigada.html").toString()
                val cookies = android.webkit.CookieManager.getInstance()
                cookies.setCookie(filmPage, "cinema_fixture=active; Path=/; Secure; Max-Age=120")
                mirror.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse().setResponseCode(302)
                            .addHeader("Location", localUrl(cdn, "/clip.mp4").toString())
                }
                cdn.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val response = MockResponse().addHeader("Content-Type", "video/mp4")
                            .addHeader("Accept-Ranges", "bytes")
                        val range = request.getHeader("Range")
                        if (range == null) return response.setBody(Buffer().write(clip))
                        val bounds = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range)
                            ?: return response.setResponseCode(416)
                        val start = bounds.groupValues[1].toIntOrNull() ?: return response.setResponseCode(416)
                        val end = minOf(clip.lastIndex, bounds.groupValues[2].toIntOrNull() ?: clip.lastIndex)
                        if (start > end) return response.setResponseCode(416)
                        return response.setResponseCode(206)
                            .addHeader("Content-Range", "bytes $start-$end/${clip.size}")
                            .setBody(Buffer().write(clip, start, end - start + 1))
                    }
                }
                val stream = MirrorMediaParser.parseQualityList("[720p]" + localUrl(mirror, "/stream.mp4")).single()
                val client = MirrorHttp.client.newBuilder()
                    .sslSocketFactory(fixtureTrust.sslSocketFactory(), fixtureTrust.trustManager).build()
                val data = OkHttpDataSource.Factory(client)
                    .setDefaultRequestProperties(mapOf("Referer" to filmPage))
                val playing = CountDownLatch(1)
                val frame = CountDownLatch(1)
                val texture = android.graphics.SurfaceTexture(false)
                val surface = android.view.Surface(texture)
                var player: ExoPlayer? = null
                val playbackError = java.util.concurrent.atomic.AtomicReference<PlaybackException>()
                try {
                    instrumentation.runOnMainSync {
                        player = ExoPlayer.Builder(context)
                            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
                            .build().apply {
                                setVideoSurface(surface)
                                addListener(object : Player.Listener {
                                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                                        if (isPlaying) playing.countDown()
                                    }
                                    override fun onRenderedFirstFrame() { frame.countDown() }
                                    override fun onPlayerError(error: PlaybackException) {
                                        playbackError.set(error)
                                        playing.countDown()
                                        frame.countDown()
                                    }
                                })
                                setMediaItem(MediaItem.fromUri(stream.url))
                                prepare()
                                play()
                            }
                    }
                    assertTrue("Media3 entered playing state. Requests: ${mirror.requestCount}/${cdn.requestCount}",
                        playing.await(20, TimeUnit.SECONDS))
                    playbackError.get()?.let { throw AssertionError("Media3 HTTPS playback failed", it) }
                    assertTrue("Media3 rendered a video frame", frame.await(15, TimeUnit.SECONDS))
                    playbackError.get()?.let { throw AssertionError("Media3 HTTPS frame failed", it) }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                    var position = 0L
                    do {
                        instrumentation.runOnMainSync { position = requireNotNull(player).currentPosition }
                        playbackError.get()?.let { throw AssertionError("Media3 stopped during playback", it) }
                        if (position > 200) break
                        Thread.sleep(150)
                    } while (android.os.SystemClock.elapsedRealtime() < deadline)
                    assertTrue("Playback advanced beyond 200 ms, last position: $position", position > 200)
                    val originRequest = requireNotNull(mirror.takeRequest(5, TimeUnit.SECONDS))
                    assertEquals(filmPage, originRequest.getHeader("Referer"))
                    assertTrue(originRequest.getHeader("Cookie").orEmpty().contains("cinema_fixture=active"))
                    val redirected = requireNotNull(cdn.takeRequest(5, TimeUnit.SECONDS))
                    assertNull(redirected.getHeader("Cookie"))
                } finally {
                    instrumentation.runOnMainSync { player?.release() }
                    surface.release()
                    texture.release()
                    cookies.setCookie(filmPage, "cinema_fixture=; Path=/; Secure; Max-Age=0")
                }
            }
        }
    }
}
