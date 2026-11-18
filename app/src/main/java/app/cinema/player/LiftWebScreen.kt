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
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Delegated to the first-party DOM matcher, never to a private API. */
    internal fun fillSearchScript(title: String): String = LiftMatchPolicy.searchScript(title)

}

@Composable
internal fun LiftWebScreen(
    onClose: () -> Unit,
    title: String = "",
    expectedYear: Int? = null
) {
    val ctx = LocalContext.current
    var loading by remember(title) { mutableStateOf(true) }
    var failure by remember(title) { mutableStateOf("") }
    var refresh by remember(title) { mutableIntStateOf(0) }
    var matching by remember(title) { mutableStateOf(title.isNotBlank()) }
    var choices by remember(title) { mutableStateOf<List<LiftFilmMatch>>(emptyList()) }
    var foundDetails by remember(title) { mutableStateOf(false) }
    var lookupMessage by remember(title) { mutableStateOf("") }
    val alive = remember(title) { AtomicBoolean(true) }
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
            // Lift's website may embed its own video host in another origin.
            // Let the first-party player manage cookies inside this isolated
            // WebView; never import Cinema's unrelated mirror credentials.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
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
                private fun pollMatches(view: WebView, attempt: Int) {
                    if (!alive.get() || foundDetails ||
                        view.url?.startsWith(LiftWebSource.SEARCH) != true) return
                    view.evaluateJavascript(LiftMatchPolicy.inspectResultsScript) { raw ->
                        if (!alive.get() || foundDetails ||
                            view.url?.startsWith(LiftWebSource.SEARCH) != true) return@evaluateJavascript
                        val matches = LiftMatchPolicy.parseBrowserResults(raw)
                        if (matches.isNotEmpty()) choices = matches
                        val exact = LiftMatchPolicy.singleExact(
                            selectedTitle, expectedYear, matches
                        )
                        if (exact != null) {
                            foundDetails = true
                            matching = false
                            choices = emptyList()
                            view.loadUrl(exact.url)
                            return@evaluateJavascript
                        }
                        if (attempt >= 13) {
                            matching = false
                            lookupMessage = if (choices.isEmpty())
                                "Фильм не найден в Lift. Попробуйте поиск вручную."
                            else "Выберите подходящий фильм. Автоматический выбор неоднозначен."
                        } else {
                            view.postDelayed({ pollMatches(view, attempt + 1) }, 650L)
                        }
                    }
                }

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
                    if (selectedTitle.isNotBlank() && !foundDetails &&
                        url.startsWith(LiftWebSource.SEARCH)) {
                        // Inspect only visible first-party results: no streams or credentials.
                        view.postDelayed({
                            if (alive.get() && !foundDetails &&
                                view.url?.startsWith(LiftWebSource.SEARCH) == true) {
                                view.evaluateJavascript(
                                    LiftMatchPolicy.searchScript(selectedTitle)
                                ) { result ->
                                    if (!alive.get()) return@evaluateJavascript
                                    if (result == "true") pollMatches(view, 0)
                                    else {
                                        matching = false
                                        lookupMessage = "Поле поиска Lift не открылось. Попробуйте вручную."
                                    }
                                }
                            }
                        }, 850L)
                    } else if (!url.startsWith(LiftWebSource.SEARCH)) {
                        matching = false
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
            alive.set(false)
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
                if (matching && !foundDetails && failure.isBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        color = appPanel,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("Подбираем фильм «$selectedTitle» по названию и году…",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!matching && !foundDetails && failure.isBlank() &&
                    selectedTitle.isNotBlank() && lookupMessage.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        color = appPanel,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        shadowElevation = 5.dp
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(lookupMessage,
                                style = MaterialTheme.typography.bodyMedium)
                            choices.take(6).forEach { candidate ->
                                TextButton(onClick = {
                                    if (LiftMatchPolicy.safeDetailsUrl(candidate.url)) {
                                        foundDetails = true
                                        choices = emptyList()
                                        lookupMessage = ""
                                        browser.loadUrl(candidate.url)
                                    }
                                }) {
                                    Text(candidate.title +
                                        (candidate.year?.let { " · $it" } ?: ""),
                                        maxLines = 2)
                                }
                            }
                            TextButton(onClick = {
                                lookupMessage = ""
                                choices = emptyList()
                            }) { Text("Продолжить поиск вручную") }
                        }
                    }
                }
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
