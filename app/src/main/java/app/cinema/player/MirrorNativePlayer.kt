package app.cinema.player

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
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
    var init=js.match(/initCDN(Series|Movies)Events\(\s*(\d+)\s*,\s*(\d+)/);
    var holder=document.querySelector('#post_id, input[name="post_id"]');
    var id=holder?.value || document.querySelector('.b-userset__fav_holder')?.getAttribute('data-post_id') || '';
    if(!id) id=(document.querySelector('.b-post')?.id||'').replace(/^post-/,'');
    if(!id && init) id=init[2];
    var voices=Array.from(document.querySelectorAll('#translators-list li[data-translator_id], .b-translator__item[data-translator_id]')).map(
      function(x){return {id:x.getAttribute('data-translator_id'),name:clean(x.getAttribute('title')||x.textContent)}}
    ).filter(function(x){return x.id && x.name});
    var episodes=Array.from(document.querySelectorAll('.b-simple_episode__item[data-episode_id]')).map(
      function(x){return{season:x.getAttribute('data-season_id')||x.closest('ul[id^="simple-episodes-list-"]')?.id.replace('simple-episodes-list-','')||'1',episode:x.getAttribute('data-episode_id'),label:clean(x.textContent)}}
    );
    return JSON.stringify({
      id:String(id),series:!!((init&&init[1]==='Series')||document.querySelector('#simple-episodes-tabs')),
      voice:String(init?init[3]:'0'),voices:voices,episodes:episodes
    });
})()
""".trimIndent()

private suspend fun mirrorAjax(web: WebView, payload: Map<String,String>): String? =
    withTimeoutOrNull(16000L) {
        suspendCancellableCoroutine { continuation ->
            val handler=Handler(Looper.getMainLooper())
            val marker="__kino_" + System.nanoTime().toString().replace("-","")
            val body=JSONObject(payload).toString()
            val script="""
            (function(){
               window['$marker']=null;
               fetch('/ajax/get_cdn_series/?t='+Date.now(),{
                 method:'POST',credentials:'same-origin',
                 headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                   'X-Requested-With':'XMLHttpRequest'},
                 body:new URLSearchParams($body).toString()
               }).then(function(response){if(!response.ok)throw Error('http');return response.json()})
                 .then(function(data){
                    if(data.episodes){
                      var holder=document.createElement('div');
                      holder.innerHTML=(data.episodes||'')+(data.seasons||'');
                      data.__kinoEpisodes=Array.from(holder.querySelectorAll('.b-simple_episode__item[data-episode_id]'))
                        .map(function(x){return {season:x.getAttribute('data-season_id')||'1',
                                episode:x.getAttribute('data-episode_id'),label:(x.textContent||'').trim()};});
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
    var quality by remember(filmUrl){mutableStateOf("")}
    var resumeMs by remember(filmUrl){mutableLongStateOf(startPositionMs)}
    var endedCounter by remember(filmUrl) { mutableIntStateOf(0) }
    var expanded by remember(filmUrl) { mutableStateOf(true) }
    BackHandler { if (expanded) expanded = false else onClose() }
    DisposableEffect(expanded) {
        val activity = findHostingActivity(context)
        val root = activity?.window?.decorView
        val oldFlags = root?.systemUiVisibility ?: 0
        val oldOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (expanded) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            root?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        onDispose {
            if (expanded) {
                root?.systemUiVisibility = oldFlags
                activity?.requestedOrientation = oldOrientation
            }
        }
    }

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
                    view.evaluateJavascript(playerMetaJs){ raw ->
                        val parsed=MirrorMediaParser.pageFromWebView(raw)
                        if(parsed==null){
                            message=""
                            error="Источник не предоставил данные для запуска встроенного плеера"
                        }else{
                            movie=parsed
                            voice=parsed.voices.firstOrNull { it.id == savedSelection?.voice }?.id
                                ?: parsed.voices.first().id
                            episodes=parsed.episodes
                            val chosen=parsed.episodes.firstOrNull {
                                it.season == savedSelection?.season && it.id == savedSelection.episode
                            } ?: parsed.episodes.firstOrNull()
                            season=chosen?.season.orEmpty()
                            episode=chosen?.id.orEmpty()
                        }
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
        onDispose{web.stopLoading();web.destroy()}
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
                ?: incoming.firstOrNull { it.season == savedSelection?.season && it.id == savedSelection.episode }
                ?: incoming.first()
            episodes=incoming
            season=chosen.season
            episode=chosen.id
        }else if(episodes.isEmpty()){
            season="1";episode="1"
        }
        readyVoice=voice
    }
    LaunchedEffect(movie,voice,readyVoice,season,episode){
        val item=movie?:return@LaunchedEffect
        if(voice.isBlank()||(item.isSeries&&(readyVoice!=voice||season.isBlank()||episode.isBlank())))return@LaunchedEffect
        val desiredQuality = quality.ifBlank { savedSelection?.quality.orEmpty() }
        options=emptyList();quality="";error=""
        message="Получаем доступные видеопотоки"
        val request=mutableMapOf("id" to item.id,"translator_id" to voice,
            "action" to (if(item.isSeries)"get_stream" else "get_movie"))
        if(item.isSeries){request["season"]=season;request["episode"]=episode}
        val result=mirrorAjax(web,request)
        if(result==null){message="";error="Источник не ответил"}
        else{
            val (available,reason)=MirrorMediaParser.parseStreamResponse(result)
            options=available
            quality=available.firstOrNull { it.label == desiredQuality }?.label
                ?: available.firstOrNull()?.label.orEmpty()
            error=reason
            message=""
        }
    }
    val selected=options.firstOrNull {it.label==quality}?:options.firstOrNull()
    var playbackState by remember(selected?.url){mutableIntStateOf(Player.STATE_IDLE)}
    var playerIssue by remember(selected?.url){mutableStateOf("")}
    var position by remember(selected?.url){mutableLongStateOf(resumeMs)}
    var total by remember(selected?.url){mutableLongStateOf(0L)}
    var paused by remember(selected?.url){mutableStateOf(false)}
    var controls by remember {mutableStateOf(true)}
    var accelerated by remember {mutableStateOf(false)}
    val player=remember(selected?.url){
        selected?.let {stream ->
            val http=DefaultHttpDataSource.Factory()
                .setUserAgent(WebSettings.getDefaultUserAgent(context))
                .setDefaultRequestProperties(mapOf("Referer" to filmUrl))
            val data=DefaultDataSource.Factory(context,http)
            ExoPlayer.Builder(context,DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(data))
                .build().apply {
                    addListener(object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int){
                            playbackState=state
                            if (state == Player.STATE_READY) {
                                val length=duration
                                if (length in 1..150_000L) {
                                    playerIssue="Источник передал короткий ролик вместо полного видео. Проверьте другую доступную озвучку или качество."
                                    pause()
                                }
                            }
                            if (state == Player.STATE_ENDED) {
                                if (currentPosition < 150_000L) {
                                    playerIssue="Источник завершил короткое видео. Выберите другой доступный вариант."
                                } else {
                                    endedCounter++
                                }
                            }
                        }
                        override fun onPlayerError(failure:PlaybackException){
                            playerIssue="Ошибка воспроизведения: "+failure.errorCodeName
                        }
                        override fun onIsPlayingChanged(isPlaying:Boolean){paused=!isPlaying}
                    })
                    setMediaItem(MediaItem.fromUri(stream.url))
                    prepare()
                    if(resumeMs>0)seekTo(resumeMs)
                    playWhenReady=true
                }
        }
    }
    DisposableEffect(player){
        onDispose{
            if(player!=null){
                val ms=player.currentPosition.coerceAtLeast(0)
                if(ms>0){resumeMs=ms;onSave(ms)}
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
                if (position > 0L && (checkpoint < 0 ||
                        kotlin.math.abs(position - checkpoint) >= 5000L)) {
                    onSave(position)
                    onSaveSelection(PlaybackSelection(season, episode, voice, quality, position))
                    checkpoint = position
                }
                delay(500)
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf("Настройки") }
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
    var speed by remember { mutableFloatStateOf(1f) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    val pictureModes = listOf("По размеру", "Заполнить", "Растянуть")
    var pictureMode by remember { mutableStateOf("По размеру") }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    val hasSubtitles = player?.currentTracks?.groups?.any { group ->
        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.isTrackSupported(it) }
    } == true
    LaunchedEffect(player, subtitlesEnabled) {
        player?.let { target ->
            target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled).build()
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
                    if (expanded) expanded = false else onClose()
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
                    Icon(Icons.Outlined.Settings, contentDescription = "Настройки плеера",
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
                FilledIconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = appBlue, contentColor = Color.Black
                    )
                ) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = if (paused) "Воспроизвести" else "Пауза",
                        modifier = Modifier.size(34.dp))
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
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                color = Color.Black.copy(alpha = .92f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = appBlue)
                    Spacer(Modifier.height(10.dp))
                    Text(playerIssue, color = Color.White,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        settingsSection = "Настройки"
                        showSettings = true
                    }) { Text("Выбрать другой вариант") }
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
                        value = if (total > 0L) (position.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        onValueChange = { if (total > 0L) player.seekTo((it * total).toLong()) },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        settingsSection = "Качество"
                        showSettings = true
                    }) {
                        Text(quality.ifBlank { "Авто" }, color = Color.White)
                    }
                    TextButton(onClick = {
                        settingsSection = "Скорость"
                        showSettings = true
                    }) {
                        Text(speed.toString() + "×", color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        settingsSection = "Настройки"
                        showSettings = true
                    }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Озвучка, серии и качество",
                            tint = Color.White)
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            contentDescription = if (expanded) "Выйти из полного экрана" else "Полный экран",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false; settingsSection = "Настройки" },
            containerColor = appCard,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (settingsSection != "Настройки") IconButton(onClick = {
                        settingsSection = "Настройки"
                    }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                    Text(settingsSection, style = MaterialTheme.typography.titleLarge)
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
                        "Субтитры" to (if (subtitlesEnabled) "Включены" else "Выключены")
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
                                .padding(vertical = 13.dp),
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
                        "Субтитры" -> listOf("Включены", "Выключены")
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
                        "Субтитры" -> if (subtitlesEnabled) 0 else 1
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
                                    "Субтитры" -> subtitlesEnabled = index == 0
                                    "Озвучка" -> {
                                        val newVoice = movie?.voices?.getOrNull(index)
                                        if (newVoice != null && voice != newVoice.id) {
                                            voice = newVoice.id
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
                            }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected = index == currentIndex, onClick = null)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
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
