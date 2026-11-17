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
import androidx.media3.datasource.DefaultHttpDataSource
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
import java.net.URI
import kotlin.coroutines.resume

private val playerMetaJs = """
(function(){
    function clean(s){return(s||'').replace(/\s+/g,' ').trim();}
    var js=Array.from(document.scripts).map(function(x){return x.textContent||'';}).join('\n');
    var init=js.match(/initCDN(Series|Movies)Events\s*\(\s*['"]?(\d{1,12})['"]?\s*,\s*['"]?(\d{1,12})/i);
    var scriptId=(js.match(/(?:post_id|news_id|postId)\s*[:=]\s*['"]?(\d{1,12})/i)||[])[1]||'';
    var numeric=function(v){var x=String(v||'').trim();return /^\d{1,12}$/.test(x)?x:'';};
    var idCandidates=[
       document.querySelector('input[name="post_id"]')?.value,
       document.querySelector('#post_id')?.value,
       document.querySelector('#post_id')?.getAttribute('data-post-id'),
       document.querySelector('[data-post_id]')?.getAttribute('data-post_id'),
       document.querySelector('[data-post-id]')?.getAttribute('data-post-id'),
       document.querySelector('.b-userset__fav_holder')?.getAttribute('data-post_id'),
       (document.querySelector('.b-post')?.id||'').replace(/^post-/,''),
       init?init[2]:'',
       scriptId,
       window.post_id,window.postId
    ];
    var id=idCandidates.map(numeric).find(Boolean)||'';
    var voices=Array.from(document.querySelectorAll(
      '#translators-list [data-translator_id], #translators-list [data-translator-id], '+
      '.b-translator__item, [data-translator_id], [data-translator-id]'
    )).map(function(node){
      var x=node.closest('li, .b-translator__item')||node;
      var id=numeric(node.getAttribute('data-translator_id')||
        node.getAttribute('data-translator-id')||
        x.getAttribute('data-translator_id')||
        x.getAttribute('data-translator-id')||
        node.getAttribute('data-id')||
        x.getAttribute('data-id'));
      var name=clean(node.getAttribute('title')||x.getAttribute('title')||
        node.textContent||x.textContent);
      return {id:id,name:name.slice(0,75)};
    }).filter(function(item){return item.id&&item.name;})
      .filter(function(item,index,array){return array.findIndex(function(v){return v.id===item.id})===index;});
    var activeVoice=Array.from(document.querySelectorAll(
      '[data-translator_id].active, [data-translator-id].active, .b-translator__item.active'
    )).map(function(x){return numeric(x.getAttribute('data-translator_id')||
        x.getAttribute('data-translator-id')||x.getAttribute('data-id'));}).find(Boolean)||'';
    var initVoice=numeric(init?init[3]:'');
    var defaultVoice=activeVoice||initVoice||(voices.length===1?voices[0].id:'');
    var episodes=Array.from(document.querySelectorAll('.b-simple_episode__item[data-episode_id]')).map(
      function(x){return{season:x.getAttribute('data-season_id')||x.closest('ul[id^="simple-episodes-list-"]')?.id.replace('simple-episodes-list-','')||'1',episode:x.getAttribute('data-episode_id'),label:clean(x.textContent)}}
    );
    return JSON.stringify({
      id:String(id),series:!!((init&&init[1]==='Series')||document.querySelector('#simple-episodes-tabs')),
      voice:defaultVoice,voiceFromScript:!!init,voices:voices,episodes:episodes,
      pageState:id?'ready':
        /captcha|checking your browser|access denied|verify you are human|anubis|cloudflare/i
          .test((document.title||'')+' '+(document.body?.innerText||'').slice(0,600))
        ?'protected':'missing-metadata'
    });
})()
""".trimIndent()

private suspend fun mirrorAjax(
    web: WebView, payload: Map<String,String>, anonymous: Boolean = false
): String? =
    withTimeoutOrNull(16000L) {
        suspendCancellableCoroutine { continuation ->
            val handler=Handler(Looper.getMainLooper())
            val marker="__kino_" + System.nanoTime().toString().replace("-","")
            val body=JSONObject(payload).toString()
            val script="""
            (function(){
               window['$marker']=null;
               fetch('/ajax/get_cdn_series/?t='+Date.now(),{
                 method:'POST',credentials:'${if (anonymous) "omit" else "same-origin"}',
                 headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                   'X-Requested-With':'XMLHttpRequest'},
                 body:new URLSearchParams($body).toString()
               }).then(function(response){if(!response.ok)throw Error('http');return response.json()})
                 .then(function(data){
                    if(data.episodes){
                      var holder=document.createElement('div');
                      holder.innerHTML=(data.episodes||'')+(data.seasons||'');
                      data.__kinoEpisodes=Array.from(holder.querySelectorAll('.b-simple_episode__item[data-episode_id]'))
                        .map(function(x){
                           var season=x.getAttribute('data-season_id') ||
                               x.closest('ul[id^="simple-episodes-list-"]')?.id.replace('simple-episodes-list-','') ||
                               x.closest('[data-season_id]')?.getAttribute('data-season_id') || '1';
                           return {season:String(season),episode:x.getAttribute('data-episode_id'),
                                   label:(x.textContent||'').trim()};
                        });
                    }
                    window['$marker']=JSON.stringify(data);
                 }).catch(function(){window['$marker']=JSON.stringify({error:'network'});});
               return 'started';
            })();
            """.trimIndent()
            var polls=0
            fun check() {
                if(!continuation.isActive)return
                if(polls++>60){continuation.resume(null);return}
                web.evaluateJavascript("window['$marker'] || null") { result ->
                    if(!continuation.isActive)return@evaluateJavascript
                    if(result=="null" || result.isNullOrBlank()) {
                        handler.postDelayed({check()},230)
                    } else {
                        web.evaluateJavascript("delete window['$marker']"){}
                        continuation.resume(result)
                    }
                }
            }
            web.evaluateJavascript(script){handler.postDelayed({check()},130)}
            continuation.invokeOnCancellation {handler.removeCallbacksAndMessages(null)}
        }
    }

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MirrorNativePlayer(
    filmUrl: String,
    filmTitle: String,
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
    val host=remember(filmUrl){URI(filmUrl).host.lowercase()}
    var movie by remember(filmUrl){mutableStateOf<MirrorMoviePage?>(null)}
    var message by remember(filmUrl){mutableStateOf("Получаем данные фильма")}
    var error by remember(filmUrl){mutableStateOf("")}
    var voice by remember(filmUrl){mutableStateOf("")}
    var episodes by remember(filmUrl){mutableStateOf<List<MirrorEpisode>>(emptyList())}
    var readyVoice by remember(filmUrl){mutableStateOf("")}
    var season by remember(filmUrl){mutableStateOf("")}
    var episode by remember(filmUrl){mutableStateOf("")}
    var options by remember(filmUrl){mutableStateOf<List<MirrorQuality>>(emptyList())}
    var subtitleOptions by remember(filmUrl){mutableStateOf<List<MirrorSubtitle>>(emptyList())}
    var subtitleIndex by remember(filmUrl){mutableIntStateOf(-1)}
    var quality by remember(filmUrl){mutableStateOf("")}
    // One bounded attempt to use the mirror's publicly accessible guest response.
    // This never forges a premium account or bypasses a server-side entitlement check.
    var anonymousRequest by remember(filmUrl) { mutableStateOf(false) }
    var anonymousTried by remember(filmUrl) { mutableStateOf(false) }
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

    val metadataHandler = remember(context,filmUrl) { Handler(Looper.getMainLooper()) }
    val web=remember(context,filmUrl) {
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
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,req:WebResourceRequest):Boolean{
                    if(!req.isForMainFrame)return false
                    val h=req.url.host?.lowercase()?:return true
                    return req.url.scheme!="https"||(h!=host&&!h.endsWith(".$host"))
                }
                override fun onPageFinished(view:WebView,url:String){
                    val responseHost=runCatching { URI(url).host?.lowercase() }.getOrNull()
                    if (responseHost!=host && responseHost?.endsWith(".$host")!=true) return
                    message="Определяем доступный плеер"
                    // onPageFinished is not the same as completion of deferred site JavaScript.
                    // Re-query briefly instead of treating one early null as a permanent failure.
                    val delays=listOf(350L,950L,1800L,3300L,5700L)
                    delays.forEachIndexed { index, waitMs ->
                        metadataHandler.postDelayed({
                            if (view.url!=url || movie!=null) return@postDelayed
                            view.evaluateJavascript(playerMetaJs){ raw ->
                                if (view.url!=url || movie!=null) return@evaluateJavascript
                                val parsed=MirrorMediaParser.pageFromWebView(raw)
                                if (parsed!=null && MirrorMediaParser.hasRealVoiceMetadata(raw)) {
                                    movie=parsed
                                    message=""
                                    error=""
                                    voice=parsed.voices.firstOrNull { it.id == savedSelection?.voice }?.id
                                        ?: parsed.voices.first().id
                                    episodes=parsed.episodes
                                    val chosen=parsed.episodes.firstOrNull {
                                        it.season == savedSelection?.season &&
                                            it.id == savedSelection?.episode
                                    } ?: parsed.episodes.firstOrNull()
                                    season=chosen?.season.orEmpty()
                                    episode=chosen?.id.orEmpty()
                                    metadataHandler.removeCallbacksAndMessages(null)
                                } else if (index==delays.lastIndex) {
                                    message=""
                                    error=if(parsed!=null)
                                        "Сайт не предоставил идентификаторы озвучек. Выберите другое зеркало."
                                    else MirrorMediaParser.pageFailureReason(raw)
                                }
                            }
                        },waitMs)
                    }
                }
                override fun onReceivedError(view:WebView,req:WebResourceRequest,
                                             issue:android.webkit.WebResourceError){
                    if(req.isForMainFrame){
                        message=""
                        error="Не удалось открыть страницу фильма. Проверьте VPN."
                    }
                }
            }
            loadUrl(filmUrl)
        }
    }
    DisposableEffect(web){
        onDispose{
            metadataHandler.removeCallbacksAndMessages(null)
            web.stopLoading()
            web.destroy()
        }
    }
    LaunchedEffect(filmUrl) {
        delay(10000)
        if (movie == null && error.isBlank()) {
            message = ""
            error = "Источник не загрузил сведения плеера. Проверьте доступность выбранного зеркала."
        }
    }
    // Attempt distinct source-provided translators in a bounded sequence.
    // No guessed translation IDs, proxy impersonation or paid stream unlocking.
    val triedVoices=remember(filmUrl, movie?.id){ mutableStateListOf<String>() }
    val verifiedVoices=remember(filmUrl){ mutableStateListOf<String>() }
    fun tryNextVoice(): Boolean {
        val available=movie?.voices.orEmpty()
        val next=AutoVoicePolicy.next(available,voice,triedVoices)?:return false
        if(voice.isNotBlank() && voice !in triedVoices)triedVoices.add(voice)
        voice=next.id
        readyVoice=""
        anonymousRequest=false
        resumeMs=0L
        options=emptyList()
        subtitleOptions=emptyList()
        quality=""
        error=""
        message="Проверяем озвучку: "+next.name
        return true
    }
    fun tryPublicGuestStream(): Boolean {
        if (anonymousTried || anonymousRequest) return false
        anonymousTried = true
        anonymousRequest = true
        resumeMs = 0L
        options = emptyList()
        quality = ""
        error = ""
        message = "Проверяем открытый гостевой поток"
        return true
    }
    LaunchedEffect(movie,voice){
        val item=movie?:return@LaunchedEffect
        if(!item.isSeries||voice.isBlank())return@LaunchedEffect
        options=emptyList()
        message="Загружаем список серий"
        val raw=mirrorAjax(web,mapOf("action" to "get_episodes",
            "id" to item.id,"translator_id" to voice))
        val response=raw?.let(MirrorMediaParser::objectFromWebView)
        val incoming=MirrorMediaParser.parseEpisodes(response?.optJSONArray("__kinoEpisodes"))
        if(incoming.isNotEmpty()){
            val chosen=incoming.firstOrNull { it.season == season && it.id == episode }
                ?: incoming.firstOrNull { it.season == savedSelection?.season && it.id == savedSelection?.episode }
                ?: incoming.first()
            episodes=incoming
            season=chosen.season
            episode=chosen.id
        }else if(episodes.isEmpty()){
            season="1";episode="1"
        }
        readyVoice=voice
    }
    LaunchedEffect(movie,voice,readyVoice,season,episode,anonymousRequest){
        val item=movie?:return@LaunchedEffect
        if(voice.isBlank()||(item.isSeries&&(readyVoice!=voice||season.isBlank()||episode.isBlank())))return@LaunchedEffect
        val desiredQuality = quality.ifBlank { savedSelection?.quality.orEmpty() }
        options=emptyList();quality="";error=""
        message="Получаем доступные видеопотоки"
        val request=mutableMapOf("id" to item.id,"translator_id" to voice,
            "action" to (if(item.isSeries)"get_stream" else "get_movie"))
        if(item.isSeries){request["season"]=season;request["episode"]=episode}
        val result=mirrorAjax(web,request,anonymous=anonymousRequest)
        if(result==null){
            if(!tryNextVoice() && !tryPublicGuestStream()){
                message=""
                error="Зеркало не ответило на запрос потоков."
            }
        } else {
            val streams=MirrorMediaParser.parseStreamInfo(result)
            val available=streams.qualities
            if(available.isEmpty()){
                if(!tryNextVoice() && !tryPublicGuestStream()){
                    message=""
                    error=streams.error.ifBlank { "Доступного потока не обнаружено." }
                }
            } else {
                subtitleOptions=streams.subtitles
                subtitleIndex=-1
                options=available
                quality=available.firstOrNull { it.label == desiredQuality }?.label
                    ?: available.firstOrNull()?.label.orEmpty()
                error=""
                message=""
            }
        }
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
    var position by remember(currentUrl, episodeKey){mutableLongStateOf(resumeMs)}
    var total by remember(currentUrl, episodeKey){mutableLongStateOf(0L)}
    var paused by remember(currentUrl, episodeKey){mutableStateOf(false)}
    var controls by remember {mutableStateOf(true)}
    var accelerated by remember {mutableStateOf(false)}
    fun tryAnotherAvailableQuality(): Boolean {
        val next = options.dropWhile { it.label != quality }.drop(1).firstOrNull()
            ?: return false
        resumeMs = 0L
        quality = next.label
        message = "Пробуем доступное качество " + next.label
        return true
    }
    val player=remember(currentUrl, subtitleOptions, episodeKey){
        currentUrl?.let {streamUrl ->
            val headers = mutableMapOf("Referer" to filmUrl)
            // Only the same-host source can receive its own site session.
            // Do not leak account cookies to externally hosted video/CDN domains.
            if (!anonymousRequest && runCatching {
                    URI(streamUrl).host.equals(URI(filmUrl).host, ignoreCase = true)
                }.getOrDefault(false)) {
                CookieManager.getInstance().getCookie(filmUrl)?.let {
                    if (it.isNotBlank()) headers["Cookie"] = it
                }
            }
            val http=DefaultHttpDataSource.Factory()
                .setUserAgent(WebSettings.getDefaultUserAgent(context))
                .setDefaultRequestProperties(headers)
            val data=DefaultDataSource.Factory(context,http)
            ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(data))
                .build().apply {
                    addListener(object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int){
                            playbackState=state
                            if (state == Player.STATE_READY) {
                                val length=duration
                                if (length > 150_000L && voice !in verifiedVoices) {
                                    verifiedVoices.add(voice)
                                }
                                if (PublicStreamRetryPolicy.isShortPreview(length)) {
                                    if (alternativeIndex < availableUrls.lastIndex) {
                                        alternativeIndex++
                                        message="Проверяем другой доступный сервер"
                                    } else if (!tryAnotherAvailableQuality() &&
                                        !tryNextVoice() && !tryPublicGuestStream()) {
                                        playerIssue="Источник вернул короткий ролик. Открытого полного видео здесь нет."
                                        pause()
                                    }
                                }
                            }
                            if (state == Player.STATE_ENDED) {
                                if (PublicStreamRetryPolicy.isShortPreview(currentPosition)) {
                                    if (alternativeIndex < availableUrls.lastIndex) {
                                        alternativeIndex++
                                    } else if (!tryAnotherAvailableQuality() &&
                                        !tryNextVoice() && !tryPublicGuestStream()) {
                                        playerIssue="Короткий ролик завершён. Полного доступного потока нет."
                                    }
                                } else {
                                    endedCounter++
                                }
                            }
                        }
                        override fun onPlayerError(failure:PlaybackException){
                            if (alternativeIndex < availableUrls.lastIndex) {
                                alternativeIndex++
                                message="Пробуем другой доступный видеосервер"
                            } else if (!tryAnotherAvailableQuality() &&
                                !tryNextVoice() && !tryPublicGuestStream()) {
                                playerIssue="Ошибка воспроизведения: "+failure.errorCodeName
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
                    if(resumeMs>0)seekTo(resumeMs)
                    playWhenReady=true
                }
        }
    }
    DisposableEffect(player, episodeKey) {
        val attachedEpisode = episodeKey
        onDispose {
            if (player != null) {
                val ms=player.currentPosition.coerceAtLeast(0)
                // Do not overwrite the next episode's position with the previous episode's end.
                if (PublicStreamRetryPolicy.shouldSaveProgress(player.duration, ms) &&
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
                if (PublicStreamRetryPolicy.shouldSaveProgress(total, position) &&
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
        anonymousRequest = false
        anonymousTried = false
        triedVoices.clear()
        verifiedVoices.clear()
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
    var speed by remember { mutableFloatStateOf(1f) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    var draggingSeek by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    val pictureModes = listOf("По размеру", "Заполнить", "Растянуть")
    var pictureMode by remember { mutableStateOf("По размеру") }
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
        if (player != null && controls && !showSettings && !paused && !accelerated) {
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
                modifier = Modifier.fillMaxSize(),
                update = {
                    it.player = player
                    it.resizeMode = when (pictureMode) {
                        "Заполнить" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        "Растянуть" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )
            Box(
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
                }
            }
        } else if (playbackState == Player.STATE_BUFFERING) {
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
        if (player != null && controls && playerIssue.isBlank()) {
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
                        settingsSection = "Настройки"
                        showSettings = true
                    }) { Text("Другой доступный вариант") }
                }
            }
        }
        if (player != null && controls) {
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
                    val audio = movie?.voices?.firstOrNull { it.id == voice }?.name ?: "По умолчанию"
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
                                    "Скорость" -> speed = speeds[index]
                                    "Следующая серия" -> {
                                        autoNextEpisode = index == 0
                                        playbackPreferences.saveAutoNextEpisode(autoNextEpisode)
                                    }
                                    "Изображение" -> pictureMode = pictureModes[index]
                                    "Субтитры" -> subtitleIndex = index - 1
                                    "Озвучка" -> {
                                        val newVoice = movie?.voices?.getOrNull(index)
                                        if (newVoice != null && voice != newVoice.id) {
                                            triedVoices.clear()
                                            voice = newVoice.id
                                            anonymousRequest = false
                                            anonymousTried = false
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
                            if (settingsSection == "Озвучка" &&
                                movie?.voices?.getOrNull(index)?.id in verifiedVoices) {
                                Text("Проверено",color=appBlue,
                                    style=MaterialTheme.typography.labelSmall)
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
