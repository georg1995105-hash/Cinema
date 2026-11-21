package app.cinema.player

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
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
    expectedYear: Int? = null,
    resumeUrl: String = "",
    onFilmPageVisited: (CatalogMovie) -> Unit = {},
    showAllResults: Boolean = false,
    compact: Boolean = false,
    onResultSelected: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val device = CinemaDevices.detect(ctx, androidx.compose.ui.platform.LocalConfiguration.current)
    val isTv = device == CinemaDevice.TV
    // Keep the same live WebView while the query changes. Lift updates results
    // on input; rebuilding the entire browser for every typed word is slow.
    val stateKey = if (showAllResults) "all-lift-search" else title
    var loading by remember(stateKey) { mutableStateOf(true) }
    var failure by remember(stateKey) { mutableStateOf("") }
    var refresh by remember(stateKey) { mutableIntStateOf(0) }
    var matching by remember(stateKey, resumeUrl, showAllResults) {
        mutableStateOf(!showAllResults && title.isNotBlank() && !LiftMatchPolicy.safeDetailsUrl(resumeUrl))
    }
    var resultOpened by remember(stateKey, showAllResults) { mutableStateOf(false) }
    var hasWebHistory by remember(stateKey) { mutableStateOf(false) }
    var choices by remember(stateKey) { mutableStateOf<List<LiftFilmMatch>>(emptyList()) }
    var foundDetails by remember(stateKey, resumeUrl) { mutableStateOf(LiftMatchPolicy.safeDetailsUrl(resumeUrl)) }
    var chosenDetailsUrl by remember(stateKey, resumeUrl) {
        mutableStateOf(resumeUrl.takeIf(LiftMatchPolicy::safeDetailsUrl).orEmpty())
    }
    var lookupMessage by remember(stateKey) { mutableStateOf("") }
    var browserGeneration by remember(stateKey) { mutableIntStateOf(0) }
    var loadCycle by remember(stateKey) { mutableIntStateOf(0) }
    val alive = remember(stateKey, browserGeneration) { AtomicBoolean(true) }
    var expanded by remember(stateKey) { mutableStateOf(false) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val selectedTitle = remember(title) { LiftWebSource.safeTitle(title) }
    val currentTitle by rememberUpdatedState(selectedTitle)
    val start = remember(stateKey, resumeUrl) {
        resumeUrl.takeIf(LiftMatchPolicy::safeDetailsUrl) ?: LiftWebSource.startUrl(selectedTitle)
    }

    val browser = remember(ctx, stateKey, browserGeneration) {
        WebView(ctx).apply {
            // TV remotes send D-pad and OK key events to the focused WebView.
            if (isTv) {
                isFocusable = true
                isFocusableInTouchMode = true
            }
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
                    if (fullscreenView != null) onHideCustomView()
                    fullscreenCallback = callback
                    fullscreenView = view
                }

                override fun onHideCustomView() {
                    val previous = fullscreenCallback
                    fullscreenCallback = null
                    fullscreenView = null
                    previous?.onCustomViewHidden()
                }
            }
            webViewClient = object : WebViewClient() {
                private fun startSearch(view: WebView, attempt: Int) {
                    if (!alive.get() || foundDetails ||
                        view.url?.startsWith(LiftWebSource.SEARCH) != true) return
                    view.evaluateJavascript(
                        LiftMatchPolicy.searchScript(currentTitle, liveOnly = showAllResults)
                    ) { result ->
                        if (!alive.get() || foundDetails) return@evaluateJavascript
                        if (result == "true") {
                            // Search tab keeps the complete first-party result grid.
                            // No exact-title selection or 35-result DOM cap applies here.
                            if (showAllResults) {
                                matching = false
                                view.evaluateJavascript(LiftMatchPolicy.hideDuplicateNavigationScript, null)
                            } else pollMatches(view, 0)
                        } else if (attempt >= 8) {
                            matching = false
                            lookupMessage = "Поиск Lift не загрузился. Повторите или введите название вручную."
                        } else {
                            view.postDelayed({ startSearch(view, attempt + 1) }, 400L)
                        }
                    }
                }

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
                        // Avoid choosing a partial first render of the result grid.
                        if (exact != null && attempt >= 2) {
                            foundDetails = true
                            chosenDetailsUrl = exact.url
                            matching = false
                            choices = emptyList()
                            view.loadUrl(exact.url)
                            return@evaluateJavascript
                        }
                        if (attempt >= 18) {
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
                    val url = request.url.toString()
                    if (!LiftWebSource.allowedMainFrame(url)) return true
                    if (showAllResults && LiftMatchPolicy.safeDetailsUrl(url) &&
                        onResultSelected != null) {
                        if (!resultOpened) {
                            resultOpened = true
                            onResultSelected(url)
                        }
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (showAllResults && LiftMatchPolicy.safeDetailsUrl(url) &&
                        onResultSelected != null) {
                        if (!resultOpened) {
                            resultOpened = true
                            onResultSelected(url)
                        }
                        view.stopLoading()
                        return
                    }
                    loadCycle++
                    loading = true
                    failure = ""
                }

                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                    hasWebHistory = view.canGoBack()
                    if (showAllResults && url.startsWith(LiftWebSource.SEARCH)) {
                        view.evaluateJavascript(LiftMatchPolicy.hideDuplicateNavigationScript, null)
                    }
                    if (failure.isEmpty() && !showAllResults && LiftMatchPolicy.safeDetailsUrl(url)) {
                        // Record actual first-party film pages, including links opened
                        // inside Lift. A site visit is not a verified video position.
                        foundDetails = true
                        chosenDetailsUrl = url
                        view.evaluateJavascript(LibraryCardMetadata.script) { raw ->
                            if (!alive.get() || view.url != url) return@evaluateJavascript
                            val metadata = LibraryCardMetadata.parse(raw, url)
                            LiftHistoryPolicy.cardFromVisit(url, metadata)
                                ?.let(onFilmPageVisited)
                        }
                    }
                    if (currentTitle.isNotBlank() && !foundDetails &&
                        url.startsWith(LiftWebSource.SEARCH)) {
                        // Inspect only visible first-party results: no streams or credentials.
                        view.postDelayed({ startSearch(view, 0) }, 650L)
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

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // WebView cannot be used after renderer termination. Replace it
                    // without killing the Activity or retaining a dead player.
                    (view.parent as? ViewGroup)?.removeView(view)
                    fullscreenView = null
                    fullscreenCallback = null
                    matching = selectedTitle.isNotBlank()
                    foundDetails = LiftMatchPolicy.safeDetailsUrl(resumeUrl)
                    chosenDetailsUrl = resumeUrl.takeIf(LiftMatchPolicy::safeDetailsUrl).orEmpty()
                    choices = emptyList()
                    browserGeneration++
                    return true
                }
            }
            loadUrl(start)
        }
    }

    // A new typed query reuses the existing Lift browser and its result DOM.
    LaunchedEffect(selectedTitle, browser, showAllResults) {
        if (showAllResults && selectedTitle.isNotBlank()) {
            if (browser.url.orEmpty().startsWith(LiftWebSource.SEARCH)) {
                browser.evaluateJavascript(
                    LiftMatchPolicy.searchScript(selectedTitle, liveOnly = true), null
                )
            } else if (browser.url != null) {
                browser.loadUrl(LiftWebSource.SEARCH)
            }
        }
    }

    LaunchedEffect(refresh, stateKey) {
        if (refresh > 0) {
            failure = ""
            loading = true
            matching = selectedTitle.isNotBlank()
            choices = emptyList()
            lookupMessage = ""
            foundDetails = false
            chosenDetailsUrl = ""
            // A failed film selection should retry first-party search, not
            // repeatedly reload a dead player or unrelated nested page.
            if (selectedTitle.isNotBlank()) browser.loadUrl(LiftWebSource.startUrl(selectedTitle))
            else browser.reload()
        }
    }

    // Timeout belongs to the current page navigation, not to the lifetime
    // of the WebView. A later video navigation must get its own timeout.
    LaunchedEffect(browser, loadCycle) {
        if (!loading) return@LaunchedEffect
        delay(22_000)
        if (loading && failure.isEmpty()) {
            loading = false
            failure = "Страница не ответила за 22 секунды. Повторите загрузку."
        }
    }

    LaunchedEffect(browser, isTv) {
        if (isTv) browser.post { browser.requestFocus() }
    }

    DisposableEffect(browser) {
        onDispose {
            alive.set(false)
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            fullscreenView = null
            runCatching { browser.stopLoading() }
            runCatching { browser.onPause() }
            runCatching { browser.destroy() }
        }
    }

    BackHandler(enabled = !compact || fullscreenView != null || expanded || hasWebHistory) {
        if (fullscreenView != null) {
            browser.webChromeClient?.onHideCustomView()
        } else if (expanded) {
            expanded = false
        } else if (browser.canGoBack()) {
            browser.goBack()
        } else onClose()
    }

    // The HTML5 player requests WebChromeClient full-screen. A manual
    // expand control is also available for player sites that omit that API.
    if (fullscreenView != null || expanded) PlayerImmersiveLifecycle(fullscreenView ?: browser)

    Box(Modifier.fillMaxSize().background(appBackground)) {
        Column(Modifier.fillMaxSize()) {
            if (!compact && !expanded && fullscreenView == null) Row(
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
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = "На весь экран")
                }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize())
                if (expanded && fullscreenView == null) {
                    IconButton(
                        onClick = { expanded = false },
                        modifier = Modifier.align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(appPanel.copy(alpha = 0.9f))
                    ) {
                        Icon(Icons.Outlined.FullscreenExit,
                            contentDescription = "Выйти из полного экрана")
                    }
                }
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
                                        chosenDetailsUrl = candidate.url
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
                        Button(onClick = { refresh++ }) { Text(if (resumeUrl.isNotBlank()) "Найти фильм заново" else "Повторить") }
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
