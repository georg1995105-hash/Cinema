package app.cinema.player

import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * The permitted official RUTUBE embed, not an HTML scraper or a request to
 * decode the provider's protected video URLs in Media3. It preserves RUTUBE's
 * own quality, audio, advertising and playback controls.
 *
 * A successful page load does NOT prove that the video has played. Only a
 * test on the owner's phone can establish actual availability in their region.
 */
@Composable
internal fun RutubeEmbeddedPlayer(embedUrl: String, onClose: () -> Unit) {
    require(MosfilmCatalog.isEmbedUrl(embedUrl))
    PlayerImmersiveLifecycle(embedUrl)
    BackHandler { onClose() }

    var error by remember(embedUrl) { mutableStateOf("") }
    var loading by remember(embedUrl) { mutableStateOf(true) }
    var retry by remember(embedUrl) { mutableIntStateOf(0) }
    var originalPage by remember(embedUrl) { mutableStateOf(false) }
    val videoPage = "https://rutube.ru/video/${embedUrl.substringAfterLast('/')}/"
    var webView by remember(embedUrl) { mutableStateOf<WebView?>(null) }

    LaunchedEffect(embedUrl, retry, originalPage) {
        delay(20_000)
        if (loading && error.isBlank()) {
            loading = false
            error = "RUTUBE не ответил. Можно повторить или открыть страницу фильма."
        }
    }

    DisposableEffect(webView) {
        // Capture the exact instance. Reading mutable state in onDispose
        // would destroy the NEW WebView when it first replaces null.
        val ownedView = webView
        onDispose {
            ownedView?.onPause()
            ownedView?.stopLoading()
            ownedView?.loadUrl("about:blank")
            ownedView?.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Назад")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                originalPage = !originalPage
                retry++
                error = ""
                loading = true
            }) {
                Text(if (originalPage) "Видеоплеер" else "Страница RUTUBE")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            key(embedUrl, retry, originalPage) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.setSupportMultipleWindows(false)
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView, request: WebResourceRequest
                                ): Boolean {
                                    // A link outside the publisher's embedded player
                                    // must not silently navigate the Cinema screen.
                                    if (!request.isForMainFrame) return false
                                    val uri = request.url
                                    return uri.scheme != "https" || (
                                        uri.host != "rutube.ru" &&
                                        uri.host != "www.rutube.ru")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false // Page loaded, NOT a playback assertion.
                                }
                                override fun onReceivedError(
                                    view: WebView, request: WebResourceRequest,
                                    errorValue: WebResourceError
                                ) {
                                    if (request.isForMainFrame) {
                                        loading = false
                                        error = "Официальный плеер RUTUBE не загрузился"
                                    }
                                }
                                override fun onReceivedHttpError(
                                    view: WebView, request: WebResourceRequest,
                                    errorResponse: WebResourceResponse
                                ) {
                                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                        loading = false
                                        error = "RUTUBE ответил HTTP ${errorResponse.statusCode}"
                                    }
                                }
                            }
                            webView = this
                            loadUrl(if (originalPage) videoPage else embedUrl)
                        }
                    }
                )
            }
            if (loading && error.isBlank()) {
                CircularProgressIndicator(Modifier.size(34.dp), color = appBlue)
            }
            if (error.isNotBlank()) {
                Column(Modifier.background(Color.Black.copy(alpha = .9f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Color.White)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            error = ""
                            loading = true
                            retry++
                        }) { Text("Повторить") }
                        OutlinedButton(onClick = {
                            originalPage = !originalPage
                            error = ""
                            loading = true
                            retry++
                        }) { Text(if (originalPage) "Видеоплеер" else "Страница RUTUBE") }
                    }
                }
            }
        }
    }
}
