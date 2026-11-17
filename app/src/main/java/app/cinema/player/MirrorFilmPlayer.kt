package app.cinema.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URI

/**
 * Hosts the site's existing player in a native video-oriented screen.
 * Does not expose or decrypt site video links, or bypass access/DRM checks.
 * When the site player cannot be found, the user can explicitly open the full site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MirrorFilmPlayer(
    filmUrl: String,
    title: String,
    onBack: () -> Unit,
    onOpenSite: () -> Unit
) {
    BackHandler(onBack = onBack)
    if (!SourceRules.isValidHttpsUrl(filmUrl)) {
        Column {
            ScreenHeading("Воспроизведение", back = onBack)
            Text("Адрес фильма не найден.", color = appMuted)
        }
        return
    }
    val context = LocalContext.current
    var playerReady by remember(filmUrl) { mutableStateOf(false) }
    var checking by remember(filmUrl) { mutableStateOf(true) }
    var error by remember(filmUrl) { mutableStateOf("") }
    var refresh by remember(filmUrl) { mutableIntStateOf(0) }
    val player = remember(context, filmUrl, refresh) {
        val handler = Handler(Looper.getMainLooper())
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true

            var fullscreen: View? = null
            var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
            var oldUiFlags = 0
            val activity = findActivity(context)
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    val root = activity?.window?.decorView as? FrameLayout
                    if (view == null || root == null || fullscreen != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    oldUiFlags = root.systemUiVisibility
                    fullscreen = view
                    fullscreenCallback = callback
                    (view.parent as? ViewGroup)?.removeView(view)
                    root.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    root.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }

                override fun onHideCustomView() {
                    val root = activity?.window?.decorView as? FrameLayout
                    fullscreen?.let { root?.removeView(it) }
                    root?.systemUiVisibility = oldUiFlags
                    fullscreen = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                }
            }
            val mainHost = URI(filmUrl).host.lowercase()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url
                    val host = url.host?.lowercase() ?: return true
                    return url.scheme != "https" || (host != mainHost && !host.endsWith("." + mainHost))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, cause: WebResourceError) {
                    if (request.isForMainFrame) {
                        checking = false
                        error = "Сайт фильма не загрузился. Проверьте соединение."
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    val waitMs = listOf(350L, 1300L, 2900L, 5400L)
                    waitMs.forEachIndexed { index, delay ->
                        handler.postDelayed({
                            if (view.url != url || playerReady) return@postDelayed
                            view.evaluateJavascript(showOnlyPlayerJavaScript) { raw ->
                                if (raw?.contains("player") == true) {
                                    playerReady = true
                                    checking = false
                                    error = ""
                                } else if (index == waitMs.lastIndex && !playerReady) {
                                    checking = false
                                    error = "На странице не найден доступный встроенный плеер. Возможно, сайт изменил оформление или требует вход."
                                }
                            }
                        }, delay)
                    }
                }
            }
            setTag(handler)
            loadUrl(filmUrl)
        }
    }
    DisposableEffect(player) {
        onDispose {
            (player.tag as? Handler)?.removeCallbacksAndMessages(null)
            player.onPause()
            player.stopLoading()
            player.destroy()
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
            }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Воспроизведение" },
                    maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text("Плеер выбранного зеркала", color = appMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { refresh++; playerReady = false; checking = true; error = "" }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Перезагрузить")
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black),
            contentAlignment = Alignment.Center) {
            AndroidView(factory = { player }, modifier = Modifier.fillMaxSize())
            if (checking && !playerReady) {
                Column(
                    modifier = Modifier.background(Color.Black.copy(alpha = .95f))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = appBlue, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Загружаем плеер", color = Color.White)
                }
            }
            if (!checking && error.isNotBlank() && !playerReady) {
                Surface(color = Color.Black.copy(alpha = .98f),
                    modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.VideocamOff, contentDescription = null,
                            tint = appMuted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (playerReady) {
            Text("Выбор дорожки, серии и качества зависит от плеера сайта.",
                color = appMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { refresh++; playerReady = false; checking = true; error = "" },
                shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Повторить")
            }
            TextButton(onClick = onOpenSite) {
                Text("Полная страница")
            }
        }
    }
}

private fun findActivity(context: Context): Activity? = when(context) {
    is Activity -> context
    is ContextWrapper -> findActivity(context.baseContext)
    else -> null
}

private val showOnlyPlayerJavaScript = """
    (function() {
      var selectors = [
        '#oframecdnplayer', '#cdnplayer', '#player', '#videoplayer',
        '.b-post__player', '.b-post__player_videobalancer',
        '.b-post__player_container', '[id="video-player"]'
      ];
      var node = null;
      for(var i=0;i<selectors.length;i++){
        var candidate=document.querySelector(selectors[i]);
        if(candidate && (candidate.querySelector('iframe,video') || candidate.tagName==='IFRAME' ||
                         candidate.tagName==='VIDEO' || candidate.id==='cdnplayer')) {
          node=candidate;break;
        }
      }
      if(!node) return 'not-found';
      if(!document.getElementById('kino-player-style')){
        var style=document.createElement('style');
        style.id='kino-player-style';
        style.textContent='html,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important}' +
          '[data-kino-player]{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;' +
          'max-height:none!important;max-width:none!important;z-index:2147483646!important;display:block!important;background:#000!important}' +
          '[data-kino-player] iframe,[data-kino-player] video{width:100%!important;height:100%!important;max-width:none!important}';
        document.head.appendChild(style);
      }
      node.setAttribute('data-kino-player','true');
      var p=node.parentElement;
      while(p && p!==document.body){
        p.style.overflow='visible';
        p=p.parentElement;
      }
      return 'player';
    })();
""".trimIndent()
