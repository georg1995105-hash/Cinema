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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
          if (address.pathname === '/search/') {
            const listing = '<div class="b-content__inline_item" data-url="/series/drama/12-brigada.html"><div class="b-content__inline_item-cover"><a href="/series/drama/12-brigada.html"><img src="//source.example/posters/12.jpg" alt="Бригада"></a></div><div class="b-content__inline_item-link"><a href="/series/drama/12-brigada.html">Бригада</a><div>2002, Россия, Драмы</div></div></div>';
            return {ok:true, url:address.href, text:async()=>listing};
          }
          throw Error('unexpected film page request');
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
        assertEquals("$origin/posters/12.jpg", matches.single().poster)
        assertEquals("3", evaluate(web, "window.calls.length"))
        assertEquals("true", evaluate(web, "window.calls[2].startsWith('/search/')"))
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
          {"@type":"TVSeries","name":"Бригада","url":"/series/drama/12-brigada.html","description":"История четырёх друзей.",
           "actor":[{"@type":"Person","name":"Вторая актриса","url":"/person/41-actor.html","image":"/photos/41.jpg"}]}
        ]}</script>
        <table class="b-post__infotable">
          <tr><th>В главных ролях</th><td><span class="person-name-item" itemprop="actors">
          <a href="/person/12-actor.html"><span itemprop="name">Актёр</span></a>
          <meta itemprop="image" content="/photos/12.jpg"></span></td></tr>
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
        assertEquals(mapOf("Актёр" to "$origin/photos/12.jpg", "Вторая актриса" to "$origin/photos/41.jpg"),
            info.cast.associate { it.name to it.photo })
        assertEquals("Режиссёр", info.directors.single().name)
        assertEquals("Бригада: Наследник", info.related.single().title)
    }

    @Test fun playerResolvesVoiceEpisodesAndAjaxResponsesFromWebView() = withPage(
        """
        <script type="application/x-fixture">sof.tv.initCDNSeriesEvents(12, 7, 0, 1, 0);</script>
        <ul id="translators-list"><li data-translator_id="7" class="active">Оригинал</li>
        <li data-translator-id="8" data-ads="0" data-premium="1">Дубляж</li></ul>
        <table class="b-post__infotable"><tr><th>Время</th><td>43 мин.</td></tr></table>
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
        assertEquals(2_580_000L, page.expectedDurationMs)
        assertTrue(MirrorPreview.isPreview(60_000, page.expectedDurationMs))
        assertFalse(page.voices.first().requiresSubscription)
        assertTrue(page.voices.last().requiresSubscription)
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

    @Test fun nativeRezkaRequestSolvesAnubisStoresCookieAndRetriesFilm() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val fixtureTrust = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate).build()

        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            fun localUrl(path: String) =
                server.url(path).newBuilder().host("localhost").build()
            val filmPage = localUrl(
                "/films/fiction/30651-mstiteli-final-2019.html"
            ).toString()
            val challengeData = "fixture-seed"
            val passValid = java.util.concurrent.atomic.AtomicBoolean(false)
            val retriedWithCookie = java.util.concurrent.atomic.AtomicBoolean(false)
            val filmRequests = java.util.concurrent.atomic.AtomicInteger(0)
            val passRequests = java.util.concurrent.atomic.AtomicInteger(0)

            fun sha256(value: String): String =
                MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

            val challengeHtml = """
                <html><head>
                <script id="anubis_base_prefix" type="application/json">""</script>
                <script id="anubis_challenge" type="application/json">
                {
                  "challenge":{"id":"fixture-id","randomData":"$challengeData"},
                  "rules":{"algorithm":"fast","difficulty":1}
                }
                </script>
                </head><body>Making sure you're not a bot</body></html>
            """.trimIndent()

            val filmHtml = """
                <html><body>
                <div class="b-post__title"><h1>Мстители: Финал</h1></div>
                <div class="b-post__description_text">Финальная битва команды.</div>
                <script>sof.tv.initCDNMoviesEvents(30651, 56, 0, 0, 0);</script>
                <ul id="translators-list">
                  <li class="active" data-translator_id="56">Дубляж</li>
                  <li data-translator_id="57">Оригинал</li>
                </ul>
                </body></html>
            """.trimIndent()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val address = requireNotNull(request.requestUrl)
                    return when (address.encodedPath) {
                        "/films/fiction/30651-mstiteli-final-2019.html" -> {
                            filmRequests.incrementAndGet()
                            if (request.getHeader("Cookie").orEmpty()
                                    .contains("techaro.lol-anubis-auth=fixture")) {
                                retriedWithCookie.set(true)
                                MockResponse().setResponseCode(200)
                                    .addHeader("Content-Type", "text/html; charset=UTF-8")
                                    .setBody(filmHtml)
                            } else {
                                MockResponse().setResponseCode(200)
                                    .addHeader("Content-Type", "text/html; charset=UTF-8")
                                    .setBody(challengeHtml)
                            }
                        }
                        "/.within.website/x/cmd/anubis/api/pass-challenge" -> {
                            passRequests.incrementAndGet()
                            val nonce = address.queryParameter("nonce")?.toLongOrNull()
                            val response = address.queryParameter("response").orEmpty()
                            val valid = nonce != null &&
                                response == sha256(challengeData + nonce) &&
                                response.startsWith("0") &&
                                address.queryParameter("id") == "fixture-id" &&
                                address.queryParameter("redir") == filmPage
                            passValid.set(valid)
                            if (!valid) {
                                MockResponse().setResponseCode(400).setBody("bad pow")
                            } else {
                                MockResponse().setResponseCode(302)
                                    .addHeader(
                                        "Set-Cookie",
                                        "techaro.lol-anubis-auth=fixture; Path=/; Secure; Max-Age=120"
                                    )
                                    .addHeader("Location", filmPage)
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            MirrorHttp.clearStaleAnubisCookies(filmPage)
            val client = MirrorHttp.client.newBuilder()
                .sslSocketFactory(
                    fixtureTrust.sslSocketFactory(),
                    fixtureTrust.trustManager
                )
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            try {
                val result = runBlocking {
                    MirrorNetworkSource.film(
                        filmPage,
                        "Cinema-Android-Test",
                        client = client
                    )
                }
                assertEquals("", result.reason)
                assertEquals("Мстители: Финал", result.metadata?.info?.title)
                val player = requireNotNull(result.metadata?.player)
                assertEquals("56", player.defaultVoice)
                assertEquals(listOf("Дубляж", "Оригинал"), player.voices.map { it.name })
                assertEquals(2, filmRequests.get())
                assertEquals(1, passRequests.get())
                assertTrue("PoW query is valid", passValid.get())
                assertTrue("film is retried with Anubis cookie", retriedWithCookie.get())
                assertTrue(MirrorHttp.hasAnubisCookie(filmPage))
            } finally {
                MirrorHttp.clearStaleAnubisCookies(filmPage)
            }
        }
    }

    @Test fun selectedMirrorWinsOverFastFallbackWhenItServesTheRealFilm() {
        val cert = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { selected ->
            MockWebServer().use { fallback ->
                selected.useHttps(serverTls.sslSocketFactory(), false)
                fallback.useHttps(serverTls.sslSocketFactory(), false)
                selected.start()
                fallback.start()
                val page = selected.url("/films/drama/12-brigada.html")
                    .newBuilder().host("localhost").build().toString()
                val fallbackOrigin = fallback.url("/")
                    .newBuilder().host("localhost").build().toString()
                val filmHtml = """
                    <html><body>
                    <div class="b-post__title"><h1>Бригада</h1></div>
                    <div class="b-post__description_text">Страница выбранного зеркала.</div>
                    <script>sof.tv.initCDNMoviesEvents(12, 7, 0, 0, 0);</script>
                    <ul id="translators-list"><li class="active" data-translator_id="7">Дубляж</li></ul>
                    </body></html>
                """.trimIndent()
                selected.enqueue(MockResponse().setResponseCode(200)
                    .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    .addHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody(filmHtml))
                fallback.enqueue(MockResponse().setResponseCode(200).setBody(filmHtml))
                val client = MirrorHttp.client.newBuilder()
                    .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                    .followRedirects(false).followSslRedirects(false).build()
                val resolved = runBlocking {
                    MirrorNetworkSource.resolveFilm(
                        page, listOf(fallbackOrigin), "Cinema-Android-Test", client = client
                    )
                }
                assertEquals(page, resolved.finalUrl)
                assertEquals("", resolved.reason)
                assertEquals("Бригада", resolved.metadata?.info?.title)
                assertEquals("Дубляж", resolved.metadata?.player?.voices?.single()?.name)
                assertEquals(1, selected.requestCount)
                assertEquals("Fallback must not replace a valid selected mirror", 0,
                    fallback.requestCount)
            }
        }
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
