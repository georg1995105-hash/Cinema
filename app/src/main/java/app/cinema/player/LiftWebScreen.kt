package app.cinema.player

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URI

/**
 * The official Lift WEB SERVICE, embedded as a top-level browsing experience.
 *
 * It does NOT copy Lift's native signing library, forge an app identity,
 * rewrite its API requests, extract stream URLs or bypass region/DRM checks.
 * Content, availability and publisher rights remain Lift's responsibility.
 * Native Cinema's Media3 is kept for independent authorized MP4 providers.
 */
internal object LiftWebSource {
    const val HOME = "https://liftw.ws/"
    const val SEARCH = "https://liftw.ws/search.html"

    fun allowedMainFrame(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) &&
            (uri.host.equals("liftw.ws", true) ||
             uri.host.equals("www.liftw.ws", true)) &&
            uri.userInfo == null
    }.getOrDefault(false)

    /** Search must use the public first-party page, never a guessed API URL. */
    fun startUrl(title: String): String =
        if (title.isBlank()) HOME else SEARCH

    fun safeTitle(title: String): String = title.trim().take(140)

    /** Encoded with JSON quoting, not by interpolating untrusted text into JS. */
    internal fun fillSearchScript(title: String): String {
        val quoted = JSONObject.quote(safeTitle(title))
        return """
            (function(){
              var field=document.querySelector('input[type="search"], input[placeholder*="Поиск"], input[placeholder*="поиск"], input[placeholder*="назван"], input:not([type="hidden"])');
              if(!field) return false;
              var descriptor=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');
              if(descriptor && descriptor.set) descriptor.set.call(field,$quoted);
              else field.value=$quoted;
              field.dispatchEvent(new Event('input',{bubbles:true}));
              field.dispatchEvent(new Event('change',{bubbles:true}));
              field.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',bubbles:true}));
              return true;
            })();
        """.trimIndent()
    }
}

@Composable
internal fun LiftWebScreen(
    onClose: () -> Unit,
    title: String = ""
) {
    val ctx = LocalContext.current
    var loading by remember(title) { mutableStateOf(true) }
    var failure by remember(title) { mutableStateOf("") }
    var refresh by remember(title) { mutableIntStateOf(0) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val selectedTitle = remember(title) { LiftWebSource.safeTitle(title) }
    val start = remember(title) { LiftWebSource.startUrl(selectedTitle) }

    val browser = remember(ctx, title) {
        WebView(ctx).apply {
            setBackgroundColor(AndroidColor.rgb(19, 24, 28))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = callback
                    fullscreenView = view
                }

                override fun onHideCustomView() {
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return !LiftWebSource.allowedMainFrame(request.url.toString())
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loading = true
                    failure = ""
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    if (selectedTitle.isNotBlank() && url.startsWith(LiftWebSource.SEARCH)) {
                        // Lift initializes its search input asynchronously on some pages.
                        view.postDelayed({
                            if (view.url?.startsWith(LiftWebSource.SEARCH) == true)
                                view.evaluateJavascript(LiftWebSource.fillSearchScript(selectedTitle), null)
                        }, 800)
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        loading = false
                        failure = "Не удалось загрузить Lift. Проверьте сеть и повторите."
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        loading = false
                        failure = "Lift ответил HTTP ${errorResponse.statusCode}."
                    }
                }
            }
            loadUrl(start)
        }
    }

    LaunchedEffect(refresh, title) {
        if (refresh > 0) {
            failure = ""
            loading = true
            browser.reload()
        }
        delay(22_000)
        if (loading && failure.isEmpty()) {
            loading = false
            failure = "Lift не загрузился за 22 секунды. Повторите или проверьте сеть."
        }
    }

    DisposableEffect(browser) {
        onDispose {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            fullscreenView = null
            browser.stopLoading()
            browser.onPause()
            browser.destroy()
        }
    }

    BackHandler {
        if (fullscreenView != null) {
            browser.webChromeClient?.onHideCustomView()
        } else if (browser.canGoBack()) {
            browser.goBack()
        } else onClose()
    }

    Box(Modifier.fillMaxSize().background(appBackground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (browser.canGoBack()) browser.goBack() else onClose()
                }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    if (selectedTitle.isEmpty()) "Lift · Смотреть"
                    else "Поиск в Lift: $selectedTitle",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Повторить загрузку")
                }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize())
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (failure.isNotBlank()) {
                    Column(
                        Modifier.align(Alignment.Center)
                            .background(appPanel).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(failure)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { refresh++ }) { Text("Повторить") }
                    }
                }
            }
        }
        if (fullscreenView != null) {
            AndroidView(
                factory = { FrameLayout(it) },
                modifier = Modifier.fillMaxSize().background(Color.Black),
                update = { container ->
                    val view = fullscreenView
                    if (view != null && view.parent != container) {
                        (view.parent as? ViewGroup)?.removeView(view)
                        container.removeAllViews()
                        container.addView(view,
                            FrameLayout.LayoutParams(-1, -1))
                    }
                }
            )
        }
    }
}
