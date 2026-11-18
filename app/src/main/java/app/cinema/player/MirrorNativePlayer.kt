package app.cinema.player

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.content.Context

import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.resume

internal val mirrorPlayerMetadataJavascript = """
(function(){
    ${MirrorVoiceDiscovery.javascript}
    ${MirrorVoiceDiscovery.episodesJavascript}
    ${MirrorPreview.runtimeJavascript}
    var data = kinoPlayerMetadata();
    var blocked = /captcha|checking your browser|access denied|verify you are human|just a moment|проверяем.{0,30}не бот/i
      .test(document.title || '') || !!document.querySelector('#challenge-form, .cf-turnstile, #anubis_challenge');
    var root = document.querySelector('#cdnplayer, #cdnplayer-container, .b-post__player');
    var access = root?.getAttribute('data-access');
    data.pageState = blocked ? 'protected' : access === 'login' ? 'login' : access === 'premium' ? 'premium' :
        data.defaultVoice || data.voices.length ? 'ready' : root?.querySelector('.b-player__premium') ? 'premium' : 'missing-metadata';
    data.runtime = kinoFilmRuntime();
    data.episodes = kinoEpisodes(document);
    data.favs = (document.querySelector('#favs, input[name="favs"]')?.value ||
        document.querySelector('[data-favs]')?.getAttribute('data-favs') || '').trim();
    return JSON.stringify(data);
})()
""".trimIndent()

internal suspend fun mirrorAjax(web: WebView, pageUrl: String, payload: Map<String, String>): String? =
    withTimeoutOrNull(14500L) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val marker = "__kinoRequest" + System.nanoTime().toString().replace("-", "")
            val body = JSONObject(payload).toString()
            val script = """
            (function(){
               if (location.href !== ${JSONObject.quote(pageUrl)}) return 'changed';
               ${MirrorVoiceDiscovery.episodesJavascript}
               var key = '$marker';
               var controller = new AbortController();
               var entry = window[key] = {abort:function(){controller.abort()}, result:null};
               var timer = setTimeout(entry.abort, 12500);
               fetch('/ajax/get_cdn_series/?t='+Date.now(), {
                 method:'POST', credentials:'same-origin', redirect:'manual', signal:controller.signal,
                 headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                   'X-Requested-With':'XMLHttpRequest','Accept':'application/json, text/plain, */*'},
                 body:new URLSearchParams($body).toString()
               }).then(async function(response) {
                 if (response.type === 'opaqueredirect') return {__kinoFailure:'redirect'};
                 var text = await response.text();
                 var data;
                 try {
                   data = JSON.parse(text);
                   if (typeof data === 'string') data = JSON.parse(data);
                 } catch(e) {
                   if (!response.ok) return {__kinoStatus:response.status};
                   return {__kinoFailure:/captcha|checking your browser|access denied|verify you are human/i.test(text.slice(0,4000)) ? 'blocked' : 'invalid'};
                 }
                 if (!data || typeof data !== 'object' || Array.isArray(data)) {
                   return {__kinoFailure:'invalid'};
                 }
                 data.__kinoStatus = response.status;
                 if (typeof data.episodes === 'string') {
                   var doc = new DOMParser().parseFromString(data.episodes +
                       (typeof data.seasons === 'string' ? data.seasons : ''), 'text/html');
                   data.__kinoEpisodes = kinoEpisodes(doc);
                 }
                 return data;
               }).then(function(data) {
                 entry.result = JSON.stringify(data);
               }).catch(function(error) {
                 entry.result = JSON.stringify({__kinoFailure:error.name === 'AbortError' ? 'timeout' : 'network'});
               }).finally(function(){clearTimeout(timer)});
               return 'started';
            })();
            """.trimIndent()
            fun check() {
                if (!continuation.isActive) return
                if (web.url != pageUrl) {
                    continuation.resume(JSONObject().put("__kinoFailure", "redirect").toString())
                    return
                }
                web.evaluateJavascript("window['$marker']?.result || null") { result ->
                    if (!continuation.isActive) return@evaluateJavascript
                    if (result == "null" || result.isNullOrBlank()) {
                        handler.postDelayed({ check() }, 180)
                    } else {
                        web.evaluateJavascript("delete window['$marker']") {}
                        continuation.resume(result)
                    }
                }
            }
            web.evaluateJavascript(script) {
                if (!continuation.isActive) return@evaluateJavascript
                if (it == "\"started\"") check()
                else continuation.resume(JSONObject().put("__kinoFailure", "redirect").toString())
            }
            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                handler.post {
                    if (web.url == pageUrl) web.evaluateJavascript(
                        "window['$marker']?.abort(); delete window['$marker'];"
                    ) {}
                }
            }
        }
    }

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MirrorNativePlayer(
    filmUrl: String,
    filmTitle: String,
    mirrors: List<String> = emptyList(),
    startPositionMs: Long,
    seekSeconds: Int,
    holdSpeed: Int,
    savedSelection: PlaybackSelection?,
    onSave: (Long)->Unit,
    onSaveSelection: (PlaybackSelection)->Unit,
    onClose: ()->Unit
) {
    if(!SourceRules.isValidHttpsUrl(filmUrl)) {
        ScreenHeading("Просмотр", back=onClose)
        Text("Неверная ссылка на фильм")
        return
    }
    val context=LocalContext.current
    var transportUrl by remember(filmUrl) { mutableStateOf(filmUrl) }
    var pageUrl by remember(filmUrl) { mutableStateOf(filmUrl) }
    var movie by remember(filmUrl){mutableStateOf<MirrorMoviePage?>(null)}
    var message by remember(filmUrl){mutableStateOf("Получаем данные фильма")}
    var error by remember(filmUrl){mutableStateOf("")}
    var rejectedNavigation by remember(filmUrl) { mutableStateOf("") }
    var voice by remember(filmUrl){mutableStateOf("")}
    var episodes by remember(filmUrl){mutableStateOf<List<MirrorEpisode>>(emptyList())}
    var readyVoice by remember(filmUrl){mutableStateOf("")}
    var season by remember(filmUrl){mutableStateOf("")}
    var episode by remember(filmUrl){mutableStateOf("")}
    var options by remember(filmUrl){mutableStateOf<List<MirrorQuality>>(emptyList())}
    var subtitleOptions by remember(filmUrl){mutableStateOf<List<MirrorSubtitle>>(emptyList())}
    var subtitleIndex by remember(filmUrl){mutableIntStateOf(-1)}
    var quality by remember(filmUrl){mutableStateOf("")}
    var resumeMs by remember(filmUrl){mutableLongStateOf(startPositionMs)}
    var endedCounter by remember(filmUrl) { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf("Настройки") }

    BackHandler {
        if (showSettings) {
            if (settingsSection != "Настройки") settingsSection = "Настройки"
            else showSettings = false
        } else onClose()
    }
    DisposableEffect(context, filmUrl) {
        val activity = findHostingActivity(context)
        val root = activity?.window?.decorView
        val originalFlags = root?.systemUiVisibility ?: 0
        val originalOrientation = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        root?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        onDispose {
            root?.systemUiVisibility = originalFlags
            activity?.requestedOrientation = originalOrientation
        }
    }

    val metadataHandler = remember(context,filmUrl,transportUrl) { Handler(Looper.getMainLooper()) }
    val web=remember(context,filmUrl,transportUrl) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if(android.os.Build.VERSION.SDK_INT>=26)settings.safeBrowsingEnabled=true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            webViewClient = object : WebViewClient() {
                private var scanningUrl: String? = null
                private var failed = false

                private fun fail(reason: String) {
                    // An already validated HTTP metadata result must not be discarded
                    // because WebView's separate navigation failed.
                    if (movie != null) return
                    failed = true
                    message = ""
                    error = reason
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val decision = FilmNavigationPolicy.decide(transportUrl, request.url.toString())
                    if (!decision.allow) rejectedNavigation = decision.failureMessage
                    // A blocked navigation attempt must not kill the already loaded film page.
                    return !decision.allow
                }

                private fun scan(view: WebView, url: String) {
                    if (failed || scanningUrl == url || !MirrorPostId.canOpen(transportUrl, url)) return
                    scanningUrl = url
                    message = "Определяем доступный плеер"
                    val timings = listOf(150L, 500L, 1250L, 2400L, 4100L, 6500L)
                    timings.forEachIndexed { index, wait ->
                        metadataHandler.postDelayed({
                            if (failed || view.url != url || movie != null) return@postDelayed
                            view.evaluateJavascript(mirrorPlayerMetadataJavascript) { result ->
                                if (failed || view.url != url || movie != null) return@evaluateJavascript
                                val parsed = MirrorMediaParser.pageFromWebView(result, url)
                                if (parsed != null && (parsed.voices.isNotEmpty() ||
                                        parsed.defaultVoice.isNotBlank())) {
                                    val candidate = MirrorVoiceSelection.choose(
                                        parsed, savedSelection?.voice)
                                    if (candidate.isBlank()) {
                                        if (index == timings.lastIndex)
                                            fail("Источник не предоставил действительные озвучки.")
                                        return@evaluateJavascript
                                    }
                                    pageUrl = url
                                    movie = parsed
                                    voice = candidate
                                    if (voice == parsed.defaultVoice || parsed.voices.size == 1) episodes = parsed.episodes
                                    val chosen = episodes.firstOrNull {
                                        it.season == savedSelection?.season && it.id == savedSelection?.episode
                                    } ?: episodes.firstOrNull()
                                    if (chosen != null && savedSelection?.season?.isNotBlank() == true &&
                                        (chosen.season != savedSelection.season || chosen.id != savedSelection.episode)) resumeMs = 0L
                                    season = chosen?.season.orEmpty()
                                    episode = chosen?.id.orEmpty()
                                    metadataHandler.removeCallbacksAndMessages(null)
                                } else if (index == timings.lastIndex) {
                                    fail(rejectedNavigation.ifBlank {
                                         MirrorMediaParser.pageFailureReason(result)
                                     })
                                }
                            }
                        }, wait)
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String) = scan(view, url)
                override fun onPageFinished(view: WebView, url: String) = scan(view, url)

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    if (request.isForMainFrame) {
                        val failure = MirrorMediaParser.responseFailure(
                            JSONObject().put("__kinoStatus", response.statusCode)
                        )
                        fail(failure?.text ?: "Страница фильма вернула HTTP ${response.statusCode}.")
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, issue: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) fail("Не удалось открыть страницу фильма. Проверьте соединение.")
                }
            }
            loadUrl(transportUrl)
        }
    }
    DisposableEffect(web){
        onDispose{
            metadataHandler.removeCallbacksAndMessages(null)
            web.stopLoading()
            web.destroy()
        }
    }
    LaunchedEffect(filmUrl, mirrors) {
        val fetched = MirrorNetworkSource.resolveFilm(
            filmUrl,
            mirrors,
            WebSettings.getDefaultUserAgent(context),
            requiredVoice = savedSelection?.voice.orEmpty()
        )
        val acceptedUrl = fetched.finalUrl.takeIf {
            MirrorFilmUrl.isAllowedResolved(filmUrl, it, mirrors)
        } ?: filmUrl
        if (acceptedUrl != transportUrl) transportUrl = acceptedUrl

        val player = fetched.metadata?.player
        if (movie == null && player != null) {
            val chosen = MirrorVoiceSelection.choose(player, savedSelection?.voice)
            if (chosen.isNotBlank()) {
                pageUrl = acceptedUrl
                movie = player
                voice = chosen
                episodes = player.episodes
                val preferred = episodes.firstOrNull {
                    it.season == savedSelection?.season && it.id == savedSelection.episode
                } ?: episodes.firstOrNull()
                season = preferred?.season.orEmpty()
                episode = preferred?.id.orEmpty()
                error = ""
                message = "Получаем видеопоток"
            }
        } else if (movie == null && fetched.reason.isNotBlank() && error.isBlank()) {
            rejectedNavigation = fetched.reason
        }
    }
    LaunchedEffect(filmUrl) {
        delay(10000)
        if (movie == null && error.isBlank()) {
            message = ""
            error = rejectedNavigation.ifBlank {
                "Источник не загрузил сведения плеера. Проверьте доступность выбранного зеркала."
            }
        }
    }
    // Attempt distinct source-provided translators in a bounded sequence.
    // No guessed translation IDs, proxy impersonation or paid stream unlocking.
    // Availability is for displaying real source status; it never triggers an
    // automatic translation change. The user's choice remains authoritative.
    val voiceAvailability = remember(filmUrl) {
        mutableStateMapOf<Triple<String, String, String>, VoiceAvailability>()
    }
    fun voiceKey(id: String) = Triple(season, episode, id)
    LaunchedEffect(movie, voice) {
        val item = movie ?: return@LaunchedEffect
        if (!item.isSeries || voice.isBlank()) return@LaunchedEffect
        options = emptyList()
        error = ""
        message = "Загружаем список серий"
        val payload = mapOf("action" to "get_episodes", "id" to item.id,
            "translator_id" to voice)
        var raw: String? = MirrorNetworkSource.playback(pageUrl, payload,
            WebSettings.getDefaultUserAgent(context))
        var response = raw?.let(MirrorMediaParser::objectFromWebView)
        var failure = response?.let(MirrorMediaParser::responseFailure)
        var entries = MirrorMediaParser.parseEpisodes(response?.optJSONArray("__kinoEpisodes"))
        if (entries.isEmpty() && failure !in setOf(
                PlaybackFailure.PREMIUM, PlaybackFailure.AUTH_REQUIRED, PlaybackFailure.DRM)) {
            val alternate = mirrorAjax(web, pageUrl, payload)
            val decoded = alternate?.let(MirrorMediaParser::objectFromWebView)
            val parsed = MirrorMediaParser.parseEpisodes(decoded?.optJSONArray("__kinoEpisodes"))
            if (parsed.isNotEmpty()) {
                raw = alternate
                response = decoded
                failure = decoded?.let(MirrorMediaParser::responseFailure)
                entries = parsed
            }
        }
        failure?.voiceAvailability()?.let { voiceAvailability[voiceKey(voice)] = it }
        val incoming = entries
        val listed = when {
            failure != null -> emptyList()
            incoming.isNotEmpty() -> incoming
            voice == item.defaultVoice || item.voices.size == 1 -> item.episodes
            else -> emptyList()
        }
        if (listed.isEmpty()) {
            episodes = emptyList()
            message = ""
            error = failure?.text ?: if (raw == null) PlaybackFailure.TIMEOUT.text
                else "Источник не предоставил серии для этой озвучки."
            return@LaunchedEffect
        }
        val chosen = listed.firstOrNull { it.season == season && it.id == episode }
            ?: listed.firstOrNull { it.season == savedSelection?.season && it.id == savedSelection?.episode }
            ?: listed.first()
        if (season.isNotEmpty() && (chosen.season != season || chosen.id != episode) ||
            season.isEmpty() && savedSelection?.season?.isNotBlank() == true &&
            (chosen.season != savedSelection.season || chosen.id != savedSelection.episode)) resumeMs = 0L
        episodes = listed
        season = chosen.season
        episode = chosen.id
        readyVoice = voice
    }
    LaunchedEffect(movie, voice, readyVoice, season, episode) {
        val item = movie ?: return@LaunchedEffect
        if (voice.isBlank() || (item.isSeries && (readyVoice != voice || season.isBlank() || episode.isBlank()))) {
            return@LaunchedEffect
        }
        val desiredQuality = quality.ifBlank { savedSelection?.quality.orEmpty() }
        options = emptyList()
        subtitleOptions = emptyList()
        quality = ""
        error = ""
        message = "Получаем доступные видеопотоки"
        val request = MirrorPlaybackRequest.stream(item, voice, season, episode)
        val direct = MirrorNetworkSource.playback(pageUrl, request,
            WebSettings.getDefaultUserAgent(context))
        var streams = MirrorMediaParser.parseStreamInfo(direct)
        if (streams.qualities.isEmpty() && streams.failure !in setOf(
                PlaybackFailure.PREMIUM, PlaybackFailure.AUTH_REQUIRED, PlaybackFailure.DRM,
                PlaybackFailure.PREVIEW)) {
            // Browser-session AJAX is a second path if direct HTTPS is blocked.
            val viaBrowser = mirrorAjax(web, pageUrl, request)
            val retry = viaBrowser?.let(MirrorMediaParser::parseStreamInfo)
            if (retry != null && (retry.qualities.isNotEmpty() ||
                    retry.failure in setOf(PlaybackFailure.PREMIUM,
                        PlaybackFailure.AUTH_REQUIRED, PlaybackFailure.DRM))) {
                streams = retry
            }
        }
        if (streams.qualities.isEmpty()) {
            streams.failure?.voiceAvailability()?.let { voiceAvailability[voiceKey(voice)] = it }
            message = ""
            error = streams.failure?.text ?: PlaybackFailure.UNAVAILABLE.text
            return@LaunchedEffect
        }
        subtitleOptions = streams.subtitles
        subtitleIndex = -1
        options = streams.qualities
        quality = options.firstOrNull { it.label == desiredQuality }?.label ?: options.first().label
        message = ""
    }
    val selected=options.firstOrNull {it.label==quality}?:options.firstOrNull()
    val availableUrls = selected?.let { listOf(it.url) + it.alternatives } ?: emptyList()
    var alternativeIndex by remember(selected?.url, season, episode, voice, quality) {
        mutableIntStateOf(0)
    }
    val currentUrl = availableUrls.getOrNull(alternativeIndex)
    // Keep this key compatible with saved episode state and disposal checks.
    val episodeKey = season + "/" + episode + "/" + voice + "/" + quality
    var playbackState by remember(currentUrl, episodeKey){mutableIntStateOf(Player.STATE_IDLE)}
    var playerIssue by remember(currentUrl, episodeKey){mutableStateOf("")}
    var acceptedStream by remember(currentUrl, episodeKey) { mutableStateOf(false) }
    var position by remember(currentUrl, episodeKey){mutableLongStateOf(resumeMs)}
    var total by remember(currentUrl, episodeKey){mutableLongStateOf(0L)}
    var paused by remember(currentUrl, episodeKey){mutableStateOf(false)}
    var controls by remember {mutableStateOf(true)}
    var accelerated by remember {mutableStateOf(false)}
    fun tryAnotherAvailableQuality(): Boolean {
        val next = options.dropWhile { it.label != quality }.drop(1).firstOrNull()
            ?: return false
        quality = next.label
        message = "Пробуем доступное качество " + next.label
        return true
    }
    val player=remember(currentUrl, subtitleOptions, episodeKey){
        currentUrl?.let {streamUrl ->
            val attempt = voiceKey(voice)
            val expectedDuration = movie?.expectedDurationMs
            val http = OkHttpDataSource.Factory(MirrorHttp.client)
                .setUserAgent(WebSettings.getDefaultUserAgent(context))
                .setDefaultRequestProperties(mapOf("Referer" to pageUrl))
            val data=DefaultDataSource.Factory(context,http)
            ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(data))
                .build().apply {
                    addListener(object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int){
                            playbackState=state
                            if (state == Player.STATE_READY && !acceptedStream) {
                                if (MirrorPreview.isPreview(duration, expectedDuration)) {
                                    pause()
                                    if (alternativeIndex < availableUrls.lastIndex) {
                                        alternativeIndex++
                                        message = "Проверяем другой поток этой озвучки"
                                    } else if (!tryAnotherAvailableQuality()) {
                                        voiceAvailability[attempt] = VoiceAvailability.PREVIEW
                                        playerIssue = PlaybackFailure.PREVIEW.text
                                    }
                                    return
                                }
                                acceptedStream = true
                                voiceAvailability[attempt] = VoiceAvailability.AVAILABLE
                                if (resumeMs > 0) {
                                    val position = if (duration > 0) resumeMs.coerceAtMost(duration) else resumeMs
                                    seekTo(position)
                                }
                                playWhenReady = true
                            }
                            if (state == Player.STATE_ENDED && acceptedStream) endedCounter++
                        }

                        override fun onPlayerError(failure: PlaybackException) {
                            val httpError = failure.cause as? HttpDataSource.InvalidResponseCodeException
                            val reason = httpError?.let {
                                MirrorMediaParser.responseFailure(JSONObject().put("__kinoStatus", it.responseCode))
                            } ?: when (failure.errorCode) {
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailure.NETWORK
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailure.TIMEOUT
                                in 6000..6999 -> PlaybackFailure.DRM
                                else -> null
                            }
                            if (reason in setOf(PlaybackFailure.AUTH_REQUIRED, PlaybackFailure.BLOCKED,
                                    PlaybackFailure.PREMIUM, PlaybackFailure.DRM)) {
                                if (reason == PlaybackFailure.PREMIUM || reason == PlaybackFailure.DRM) {
                                    reason?.voiceAvailability()?.let { voiceAvailability[attempt] = it }
                                }
                                playerIssue = requireNotNull(reason).text
                            } else if (alternativeIndex < availableUrls.lastIndex) {
                                resumeMs = currentPosition.coerceAtLeast(resumeMs)
                                alternativeIndex++
                                message = "Пробуем другой доступный видеосервер"
                            } else if (!tryAnotherAvailableQuality()) {
                                reason?.voiceAvailability()?.let { voiceAvailability[attempt] = it }
                                playerIssue = reason?.text ?: "Не удалось воспроизвести поток: " + failure.errorCodeName
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying:Boolean){paused=!isPlaying}
                    })
                    val externalSubtitles = subtitleOptions.map { sub ->
                        val mime=if(sub.url.substringBefore('?').endsWith(".srt",true))
                            MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                            .setMimeType(mime).setLanguage(sub.language).setLabel(sub.title)
                            .build()
                    }
                    setMediaItem(MediaItem.Builder().setUri(streamUrl)
                        .setSubtitleConfigurations(externalSubtitles)
                        .apply {
                            if(streamUrl.contains(".m3u8",true)) setMimeType(MimeTypes.APPLICATION_M3U8)
                        }.build())
                    prepare()
                }
        }
    }
    DisposableEffect(player, episodeKey) {
        val attachedEpisode = episodeKey
        onDispose {
            if (player != null) {
                val ms=player.currentPosition.coerceAtLeast(0)
                // Do not overwrite the next episode's position with the previous episode's end.
                if (acceptedStream && player.duration > 0 && ms > 0 &&
                    EpisodeNavigator.canResumeDisposedStream(
                    attachedEpisode, season + "/" + episode + "/" + voice + "/" + quality)) {
                    resumeMs = ms
                    onSave(ms)
                    onSaveSelection(PlaybackSelection(season, episode, voice, quality, ms))
                }
                player.release()
            }
        }
    }
    val playbackPreferences = remember(context) { LocalStore(context) }
    var autoNextEpisode by remember { mutableStateOf(playbackPreferences.autoNextEpisode()) }
    var episodeNotice by remember { mutableStateOf("") }
    LaunchedEffect(episodeNotice) {
        if (episodeNotice.isNotBlank()) {
            delay(2300)
            episodeNotice = ""
        }
    }
    LaunchedEffect(player, season, episode, voice, quality) {
        if (player != null) {
            var checkpoint = -1L
            while (true) {
                position = player.currentPosition.coerceAtLeast(0)
                total = player.duration.coerceAtLeast(0)
                if (acceptedStream && total > 0 && position > 0 &&
                    (checkpoint < 0 ||
                        kotlin.math.abs(position - checkpoint) >= 5000L)) {
                    onSave(position)
                    onSaveSelection(PlaybackSelection(season, episode, voice, quality, position))
                    checkpoint = position
                }
                delay(500)
            }
        }
    }

    val nextEpisode = EpisodeNavigator.next(episodes, season, episode)

    fun switchEpisode(target: MirrorEpisode) {
        if (target.season == season && target.id == episode) return
        val oldPosition = player?.currentPosition?.coerceAtLeast(0) ?: 0L
        if (oldPosition > 0L) {
            onSaveSelection(PlaybackSelection(season, episode, voice, quality, oldPosition))
        }
        resumeMs = 0L
        season = target.season
        episode = target.id
        options = emptyList()
        quality = ""
        message = "Загружаем серию"
        onSaveSelection(PlaybackSelection(target.season, target.id, voice, "", 0L))
        showSettings = false
        controls = true
        episodeNotice = "Сезон " + target.season + " · " + target.label
    }

    LaunchedEffect(endedCounter) {
        if (endedCounter > 0 && movie?.isSeries == true && playerIssue.isBlank()) {
            val next = EpisodeNavigator.next(episodes, season, episode)
            if (next == null) {
                episodeNotice = "Последняя доступная серия завершена"
            } else if (autoNextEpisode) {
                switchEpisode(next)
            } else {
                episodeNotice = "Серия окончена"
            }
        }
    }

    // All playback controls are overlaid on the video, including quality, audio and speed.
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    var speed by remember { mutableFloatStateOf(playbackPreferences.playbackSpeed().takeIf { it in speeds } ?: 1f) }
    var draggingSeek by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    val pictureModes = listOf("По размеру", "Заполнить", "Растянуть")
    var pictureMode by remember {
        mutableStateOf(playbackPreferences.pictureMode().takeIf { it in pictureModes } ?: pictureModes.first())
    }
    val hasSubtitles = subtitleOptions.isNotEmpty() ||
        (player?.currentTracks?.groups?.any { group ->
            group.type == C.TRACK_TYPE_TEXT &&
                (0 until group.length).any { group.isTrackSupported(it) }
        } == true)
    LaunchedEffect(player, subtitleIndex, subtitleOptions) {
        player?.let { target ->
            val preferred=subtitleOptions.getOrNull(subtitleIndex)?.language
                ?.takeIf { it != "und" }
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleIndex < 0)
                .setPreferredTextLanguage(preferred).build()
        }
    }
    LaunchedEffect(player, speed) { player?.setPlaybackSpeed(speed) }
    LaunchedEffect(player, controls, showSettings, paused, accelerated) {
        if (player != null && acceptedStream && controls && !showSettings && !paused && !accelerated) {
            delay(4800)
            controls = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The source-session WebView is only an invisible bridge to the mirror.
        AndroidView(factory = { web }, modifier = Modifier.size(2.dp).alpha(0.01f))
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    (android.view.LayoutInflater.from(ctx)
                        .inflate(R.layout.cinema_player_view, null, false) as PlayerView).apply {
                        this.player = player
                        useController = false
                        keepScreenOn = true
                        resizeMode = when (pictureMode) {
                            "Заполнить" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            "Растянуть" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().alpha(if (acceptedStream) 1f else 0f),
                update = {
                    it.player = player
                    it.resizeMode = when (pictureMode) {
                        "Заполнить" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        "Растянуть" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )
            if (acceptedStream) Box(
                Modifier.fillMaxSize()
                    .pointerInput(player, seekSeconds, holdSpeed) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { tap ->
                            val jump = seekSeconds * 1000L
                            val next = if (tap.x < size.width / 2f)
                                (player.currentPosition - jump).coerceAtLeast(0)
                            else player.currentPosition + jump
                            player.seekTo(if (player.duration > 0) next.coerceAtMost(player.duration) else next)
                            controls = true
                        },
                        onPress = {
                            coroutineScope {
                                var activated = false
                                val previous = player.playbackParameters.speed
                                val speedJob = launch {
                                    delay(425)
                                    activated = true
                                    player.setPlaybackSpeed(holdSpeed.toFloat())
                                    accelerated = true
                                }
                                try { tryAwaitRelease() } finally {
                                    speedJob.cancel()
                                    if (activated) {
                                        player.setPlaybackSpeed(previous)
                                        accelerated = false
                                    }
                                }
                            }
                        }
                    )
                }
            )
        }
        if (player == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (message.isNotBlank()) {
                    CircularProgressIndicator(color = appBlue, modifier = Modifier.size(34.dp))
                    Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Icon(Icons.Outlined.VideocamOff, contentDescription = null,
                        tint = appMuted, modifier = Modifier.size(38.dp))
                    Text(error.ifBlank { "Нет доступного видеопотока" },
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    if ((movie?.voices?.size ?: 0) > 1) {
                        TextButton(onClick = {
                            settingsSection = "Озвучка"
                            showSettings = true
                        }) { Text("Выбрать озвучку") }
                    }
                }
            }
        } else if (playerIssue.isBlank() && (!acceptedStream || playbackState == Player.STATE_BUFFERING)) {
            CircularProgressIndicator(
                color = appBlue, modifier = Modifier.align(Alignment.Center).size(30.dp)
            )
        }
        if (episodeNotice.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 66.dp),
                color = Color.Black.copy(alpha = .75f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(episodeNotice, color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        if (accelerated) {
            Surface(
                color = Color.Black.copy(alpha = .65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            ) {
                Text(holdSpeed.toString() + "×", color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
        if (controls || player == null) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = .63f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onClose()
                }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(filmTitle.ifBlank { "Воспроизведение" }, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    if (player != null) Text(
                        listOfNotNull(quality.takeIf { it.isNotBlank() },
                            speed.takeIf { it != 1f }?.let { it.toString() + "×" },
                            if (movie?.isSeries == true && season.isNotBlank())
                                "Сезон " + season + " · Серия " + episode else null
                        ).joinToString(" · "),
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = {
                    settingsSection = "Настройки"
                    showSettings = true
                }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Меню плеера",
                        tint = Color.White)
                }
            }
        }
        if (player != null && acceptedStream && controls && playerIssue.isBlank()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                IconButton(
                    onClick = { player.seekTo((player.currentPosition - seekSeconds * 1000L).coerceAtLeast(0)) },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Outlined.Replay10, contentDescription = "Назад на " + seekSeconds + " секунд",
                        tint = Color.White, modifier = Modifier.size(34.dp))
                }
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(58.dp)
                        .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(50))
                ) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = if (paused) "Воспроизвести" else "Пауза",
                        tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(
                    onClick = {
                        val next = player.currentPosition + seekSeconds * 1000L
                        player.seekTo(if (player.duration > 0) next.coerceAtMost(player.duration) else next)
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Outlined.Forward10,
                        contentDescription = "Вперёд на " + seekSeconds + " секунд",
                        tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
        if (player != null && playerIssue.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(18.dp).widthIn(max = 490.dp),
                color = Color.Black.copy(alpha = .88f),
                shape = RoundedCornerShape(15.dp)
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Видео сейчас недоступно", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(playerIssue, color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(9.dp))
                    TextButton(onClick = {
                        settingsSection = "Озвучка"
                        showSettings = true
                    }) { Text("Выбрать озвучку") }
                }
            }
        }
        if (player != null && acceptedStream && controls) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = .64f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatVideoTime(position), color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (draggingSeek) dragFraction else
                            if (total > 0L) (position.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragFraction = it; draggingSeek = true },
                        onValueChangeFinished = {
                            if (total > 0L) player.seekTo((dragFraction * total).toLong())
                            draggingSeek = false
                        },
                        modifier = Modifier.weight(1f).height(28.dp)
                            .padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = appBlue,
                            activeTrackColor = appBlue,
                            inactiveTrackColor = Color.White.copy(alpha = .35f)
                        ),
                        thumb = {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(appBlue))
                        }
                    )
                    Text(if (total > 0L) formatVideoTime(total) else "—:—",
                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                    if (nextEpisode != null && movie?.isSeries == true) {
                        IconButton(onClick = { switchEpisode(nextEpisode) }) {
                            Icon(Icons.Outlined.SkipNext,
                                contentDescription = "Следующая серия", tint = Color.White)
                        }
                    }
                }
                // Quality, voice, speed and episodes are in the top-right player menu.
                // No duplicated seekbar actions or oversized secondary toolbar.
            }
        }
        // Player settings are part of the same immersive screen, not a separate modal window.
        if (showSettings) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .12f))
                    .clickable {
                        showSettings = false
                        settingsSection = "Настройки"
                    }
            )
            Surface(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 12.dp, bottom = 8.dp)
                    .width(242.dp).fillMaxHeight(.68f),
                shape = RoundedCornerShape(15.dp),
                color = Color(0xF222272E),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (settingsSection != "Настройки") IconButton(onClick = {
                        settingsSection = "Настройки"
                    }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                    Text(settingsSection, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        settingsSection = "Настройки"
                        showSettings = false
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть настройки")
                    }
                }
                Spacer(Modifier.height(9.dp))
                if (settingsSection == "Настройки") {
                    val audio = movie?.voices?.firstOrNull { it.id == voice }?.name ?: "—"
                    val menuRows = listOf(
                        "Качество" to quality.ifBlank { "Авто" },
                        "Скорость" to (speed.toString() + "×"),
                        "Изображение" to pictureMode,
                        "Озвучка" to audio
                    ) + (if (hasSubtitles) listOf(
                        "Субтитры" to (subtitleOptions.getOrNull(subtitleIndex)?.title
                            ?: if (subtitleIndex >= 0) "Включены" else "Выключены")
                    ) else emptyList()) + if (movie?.isSeries == true) listOf(
                        "Сезон" to season.ifBlank { "—" },
                        "Серия" to (episodes.firstOrNull { it.id == episode && it.season == season }
                            ?.label ?: episode.ifBlank { "—" }),
                        "Следующая серия" to (if (autoNextEpisode) "Автоматически" else "Вручную")
                    ) else emptyList()
                    menuRows.forEach { (heading, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { settingsSection = heading }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(heading, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(value, color = appMuted, maxLines = 1,
                                modifier = Modifier.widthIn(max = 160.dp),
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(7.dp))
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                                tint = appMuted)
                        }
                        HorizontalDivider(color = appMuted.copy(alpha = .16f))
                    }
                } else {
                    val choices = when (settingsSection) {
                        "Качество" -> options.map { it.label }
                        "Скорость" -> speeds.map { it.toString() + "×" }
                        "Изображение" -> pictureModes
                        "Субтитры" -> listOf("Выключены") +
                            (if (subtitleOptions.isNotEmpty()) subtitleOptions.map { it.title }
                             else listOf("Включены"))
                        "Озвучка" -> movie?.voices?.map { it.name }.orEmpty()
                        "Сезон" -> episodes.map { it.season }.distinct().map { "Сезон " + it }
                        "Серия" -> episodes.filter { it.season == season }.map { it.label }
                        "Следующая серия" -> listOf("Автоматически", "Вручную")
                        else -> emptyList()
                    }
                    val currentIndex = when (settingsSection) {
                        "Качество" -> options.indexOfFirst { it.label == quality }
                        "Скорость" -> speeds.indexOf(speed)
                        "Изображение" -> pictureModes.indexOf(pictureMode)
                        "Субтитры" -> subtitleIndex + 1
                        "Озвучка" -> movie?.voices?.indexOfFirst { it.id == voice } ?: -1
                        "Сезон" -> episodes.map { it.season }.distinct().indexOf(season)
                        "Серия" -> episodes.filter { it.season == season }.indexOfFirst { it.id == episode }
                        "Следующая серия" -> if (autoNextEpisode) 0 else 1
                        else -> -1
                    }
                    if (choices.isEmpty()) Text(
                        "Нет доступных вариантов",
                        color = appMuted,
                        modifier = Modifier.padding(vertical = 17.dp)
                    )
                    choices.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                when (settingsSection) {
                                    "Качество" -> {
                                        resumeMs = player?.currentPosition?.coerceAtLeast(0) ?: resumeMs
                                        quality = options[index].label
                                    }
                                    "Скорость" -> {
                                        speed = speeds[index]
                                        playbackPreferences.savePlaybackSpeed(speed)
                                    }
                                    "Следующая серия" -> {
                                        autoNextEpisode = index == 0
                                        playbackPreferences.saveAutoNextEpisode(autoNextEpisode)
                                    }
                                    "Изображение" -> {
                                        pictureMode = pictureModes[index]
                                        playbackPreferences.savePictureMode(pictureMode)
                                    }
                                    "Субтитры" -> subtitleIndex = index - 1
                                    "Озвучка" -> {
                                        val newVoice = movie?.voices?.getOrNull(index)
                                        if (newVoice != null && voice != newVoice.id) {
                                                                            resumeMs = player?.currentPosition?.coerceAtLeast(0) ?: resumeMs
                                            voice = newVoice.id
                                            onSaveSelection(PlaybackSelection(season, episode, voice, quality, resumeMs))
                                            readyVoice = ""
                                            options = emptyList()
                                            message = "Меняем озвучку"
                                        }
                                    }
                                    "Сезон" -> {
                                        val id = episodes.map { it.season }.distinct()[index]
                                        episodes.firstOrNull { it.season == id }?.let(::switchEpisode)
                                    }
                                    "Серия" -> {
                                        switchEpisode(episodes.filter { it.season == season }[index])
                                    }
                                }
                                showSettings = false
                                settingsSection = "Настройки"
                                controls = true
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            if (settingsSection == "Озвучка") {
                                val candidate = movie?.voices?.getOrNull(index)
                                val availability = candidate?.let { voiceAvailability[voiceKey(it.id)] }
                                val status = availability?.label
                                    ?: if (candidate?.requiresSubscription == true) "Подписка" else "Не проверена"
                                Text(status, color = if (availability == VoiceAvailability.AVAILABLE) appBlue else appMuted,
                                    style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                            }
                            if (index == currentIndex) {
                                Icon(Icons.Outlined.Check, contentDescription = "Выбрано",
                                    tint = appBlue, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))

                }
            }
        }

    }

}
private fun formatVideoTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = seconds / 60L % 60L
    val remain = seconds % 60L
    return if (hours > 0) {
        hours.toString() + ":" + minutes.toString().padStart(2, '0') +
            ":" + remain.toString().padStart(2, '0')
    } else minutes.toString().padStart(2, '0') + ":" + remain.toString().padStart(2, '0')
}

private fun findHostingActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findHostingActivity(context.baseContext)
    else -> null
}
