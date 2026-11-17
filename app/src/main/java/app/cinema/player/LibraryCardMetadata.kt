package app.cinema.player

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Recover metadata for older history entries created before title/poster persistence.
 * Never invents artwork, only reads real public metadata from the entry's own mirror.
 */
internal object LibraryCardMetadata {
    val script = """
        (function() {
          function abs(url) {
            try {return new URL(url,document.baseURI).href;}catch(e){return '';}
          }
          var heading=document.querySelector('.b-post__title, .b-post__origtitle, .b-post__head h1');
          var og=document.querySelector('meta[property="og:title"]');
          var poster=document.querySelector('.b-post__poster img, .b-post__cover img, .b-post__mainitem img');
          var imageMeta=document.querySelector('meta[property="og:image"]');
          var name=(heading?.textContent || og?.content || '').replace(/\s+/g,' ').trim();
          var image=poster?.getAttribute('data-src') ||
              poster?.getAttribute('data-original') ||
              poster?.getAttribute('src') || imageMeta?.content || '';
          return JSON.stringify({title:name,poster:abs(image)});
        })();
    """.trimIndent()

    fun parse(raw: String, filmUrl: String): CatalogMovie? = runCatching {
        val data = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
        val values = JSONObject(data)
        val title = values.optString("title").replace(Regex("\\s+"), " ").trim().take(130)
        if (!SourceRules.isValidHttpsUrl(filmUrl) || title.isBlank()) return@runCatching null
        val image = values.optString("poster")
            .takeIf(SourceRules::isValidHttpsUrl).orEmpty()
        CatalogMovie(url = filmUrl, title = title, poster = image, details = "")
    }.getOrNull()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RecoverLibraryCards(
    urls: List<String>,
    local: LocalStore,
    onRecovered: () -> Unit
) {
    val context = LocalContext.current
    var position by remember(urls) { mutableIntStateOf(0) }
    val current = urls.getOrNull(position)
    val currentUrl by rememberUpdatedState(current)
    val latestOnRecovered by rememberUpdatedState(onRecovered)
    val handler = remember { Handler(Looper.getMainLooper()) }
    val web = remember(context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
        }
    }
    DisposableEffect(web) {
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val requested = request.url
                val expected = runCatching { URI(currentUrl).host }.getOrNull()
                return requested.scheme != "https" ||
                    !requested.host.equals(expected, ignoreCase = true)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) position++
            }

            override fun onPageFinished(view: WebView, url: String) {
                val expected = currentUrl ?: return
                if (runCatching { URI(url).host != URI(expected).host }.getOrDefault(true))
                    return
                handler.postDelayed({
                    if (currentUrl != expected) return@postDelayed
                    view.evaluateJavascript(LibraryCardMetadata.script) { json ->
                        if (currentUrl != expected) return@evaluateJavascript
                        val film = LibraryCardMetadata.parse(json, expected)
                        if (film != null) {
                            local.rememberMovie(film)
                            latestOnRecovered()
                        }
                        position++
                    }
                }, 750)
            }
        }
        onDispose {
            handler.removeCallbacksAndMessages(null)
            web.stopLoading()
            web.destroy()
        }
    }
    LaunchedEffect(current) {
        if (current != null && SourceRules.isValidHttpsUrl(current)) {
            web.loadUrl(current)
            kotlinx.coroutines.delay(9000)
            if (currentUrl == current) position++
        }
    }
    if (current != null) {
        AndroidView(factory = { web }, modifier = Modifier.size(2.dp).alpha(0.01f))
    }
}
