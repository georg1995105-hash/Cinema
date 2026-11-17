package app.cinema.player

import android.annotation.SuppressLint
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
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

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MirrorNativePlayer(
    filmUrl: String,
    filmTitle: String,
    startPositionMs: Long,
    seekSeconds: Int,
    holdSpeed: Int,
    onSave: (Long)->Unit,
    onClose: ()->Unit
) {
    BackHandler(onBack=onClose)
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
    var expanded by remember {mutableStateOf(false)}

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
                            voice=parsed.voices.first().id
                            episodes=parsed.episodes
                            season=parsed.episodes.firstOrNull()?.season.orEmpty()
                            episode=parsed.episodes.firstOrNull()?.id.orEmpty()
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
            episodes=incoming
            season=incoming.first().season
            episode=incoming.first().id
        }else if(episodes.isEmpty()){
            season="1";episode="1"
        }
        readyVoice=voice
    }
    LaunchedEffect(movie,voice,readyVoice,season,episode){
        val item=movie?:return@LaunchedEffect
        if(voice.isBlank()||(item.isSeries&&(readyVoice!=voice||season.isBlank()||episode.isBlank())))return@LaunchedEffect
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
            quality=available.firstOrNull()?.label.orEmpty()
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
                        override fun onPlaybackStateChanged(state:Int){playbackState=state}
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
    LaunchedEffect(player){
        if(player!=null)while(true){
            position=player.currentPosition.coerceAtLeast(0)
            total=player.duration.coerceAtLeast(0)
            delay(350)
        }
    }

    Column(Modifier.fillMaxSize().background(appBackground)){
        // WebView must be attached to an Android window to run the site's session JavaScript.
        AndroidView(factory={web},modifier=Modifier.size(2.dp).alpha(0.01f))
        if(!expanded)Row(verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onClose){Icon(Icons.Outlined.ArrowBack,contentDescription="Назад")}
            Column(Modifier.weight(1f)){
                Text(filmTitle,maxLines=1,style=MaterialTheme.typography.titleMedium)
                Text("Встроенный плеер",color=appMuted,style=MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick={expanded=true}){Icon(Icons.Outlined.Maximize,contentDescription="Развернуть")}
        }
        Box(
            (if(expanded)Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f/9f))
                .background(Color.Black),
            contentAlignment=Alignment.Center
        ){
            if(player!=null){
                AndroidView(factory={ctx->PlayerView(ctx).apply{
                    this.player=player;useController=false;keepScreenOn=true
                }},modifier=Modifier.fillMaxSize(),update={it.player=player})
                Box(Modifier.fillMaxSize().pointerInput(player,seekSeconds,holdSpeed){
                    detectTapGestures(
                        onTap={controls=!controls},
                        onDoubleTap={tap->
                            val jump=seekSeconds*1000L
                            val next=if(tap.x<size.width/2f)(player.currentPosition-jump).coerceAtLeast(0)
                                else player.currentPosition+jump
                            player.seekTo(if(player.duration>0)next.coerceAtMost(player.duration)else next)
                            controls=true
                        },
                        onPress={
                            coroutineScope{
                                var active=false
                                val before=player.playbackParameters.speed
                                val task=launch{
                                    delay(425)
                                    active=true
                                    player.setPlaybackSpeed(holdSpeed.toFloat())
                                    accelerated=true
                                }
                                try{tryAwaitRelease()}finally{
                                    task.cancel()
                                    if(active){player.setPlaybackSpeed(before);accelerated=false}
                                }
                            }
                        }
                    )
                })
                if(controls)FilledIconButton(onClick={
                    if(player.isPlaying)player.pause()else player.play()
                }){
                    Icon(if(paused)Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription=if(paused)"Продолжить"else"Пауза")
                }
                if(accelerated)Text(holdSpeed.toString()+"×",color=Color.White,
                    modifier=Modifier.align(Alignment.TopEnd).padding(12.dp))
                if(playbackState==Player.STATE_BUFFERING||playbackState==Player.STATE_IDLE)
                    CircularProgressIndicator(color=appBlue,modifier=Modifier.size(29.dp))
                if(playerIssue.isNotBlank())Surface(color=Color.Black.copy(alpha=.94f)){
                    Text(playerIssue,color=Color.White,modifier=Modifier.padding(15.dp))
                }
            }else{
                Column(horizontalAlignment=Alignment.CenterHorizontally,
                    modifier=Modifier.padding(16.dp)){
                    if(message.isNotBlank()){
                        CircularProgressIndicator(color=appBlue,modifier=Modifier.size(30.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(message,color=Color.White)
                    }else{
                        Icon(Icons.Outlined.VideocamOff,contentDescription=null,
                            tint=appMuted,modifier=Modifier.size(35.dp))
                        Spacer(Modifier.height(11.dp))
                        Text(error.ifBlank{"Нет доступного видеопотока"},
                            color=Color.White,style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if(expanded)IconButton(
                onClick={expanded=false},
                modifier=Modifier.align(Alignment.TopEnd)
            ){Icon(Icons.Outlined.Minimize,contentDescription="Свернуть",tint=Color.White)}
        }
        if(!expanded){
            if(player!=null)Row(verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={
                    player.seekTo((player.currentPosition-seekSeconds*1000L).coerceAtLeast(0))
                }){Text("−"+seekSeconds+" с")}
                Slider(
                    value=if(total>0)(position.toFloat()/total).coerceIn(0f,1f)else 0f,
                    onValueChange={if(total>0)player.seekTo((it*total).toLong())},
                    modifier=Modifier.weight(1f)
                )
                TextButton(onClick={
                    val next=player.currentPosition+seekSeconds*1000L
                    player.seekTo(if(total>0)next.coerceAtMost(total)else next)
                }){Text("+"+seekSeconds+" с")}
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal=12.dp,vertical=10.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)
            ){
                val info=movie
                if(info!=null){
                    Text("Озвучка",style=MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        items(info.voices,key={it.id}){item->
                            FilterChip(selected=voice==item.id,onClick={
                                if(voice!=item.id){
                                    voice=item.id;readyVoice="";options=emptyList()
                                    message="Переключаем озвучку"
                                }
                            },label={Text(item.name,maxLines=1)})
                        }
                    }
                }
                if(info?.isSeries==true){
                    Text("Сезон",style=MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        items(episodes.map{it.season}.distinct()){s->
                            FilterChip(selected=season==s,onClick={
                                season=s;episode=episodes.firstOrNull{it.season==s}?.id.orEmpty()
                            },label={Text(s)})
                        }
                    }
                    Text("Серия",style=MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        items(episodes.filter{it.season==season}){e->
                            FilterChip(selected=episode==e.id,onClick={episode=e.id},
                                label={Text(e.label)})
                        }
                    }
                }
                if(options.isNotEmpty()){
                    Text("Качество",style=MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){
                        items(options,key={it.label}){q->
                            FilterChip(selected=quality==q.label,onClick={
                                resumeMs=player?.currentPosition?.coerceAtLeast(0)?:resumeMs
                                quality=q.label
                            },label={Text(q.label)})
                        }
                    }
                }
                if(error.isNotBlank()&&player==null)
                    Text(error,color=MaterialTheme.colorScheme.error)
                Text("Воспроизводятся только потоки, доступные для вашего подключения.",
                    color=appMuted,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
