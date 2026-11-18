package app.cinema.player

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.delay

internal data class CatalogMovie(
    val url: String,
    val title: String,
    val poster: String,
    val details: String,
    val posterAlternatives: List<String> = emptyList()
)

/** A bounded in-memory catalogue cache; no user credentials or cookies are persisted. */
internal object CatalogMemory {
    private data class Entry(val timestamp: Long, val movies: List<CatalogMovie>)
    private val values = object : LinkedHashMap<String, Entry>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 12
    }
    @Synchronized fun get(key: String): List<CatalogMovie>? {
        val entry = values[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > 10 * 60 * 1000L) {
            values.remove(key)
            return null
        }
        return entry.movies
    }
    @Synchronized fun put(key: String, data: List<CatalogMovie>) {
        if (data.isNotEmpty()) values[key] = Entry(System.currentTimeMillis(), data)
    }
    @Synchronized fun invalidate(key: String) { values.remove(key) }
}

/** Reads public catalogue metadata from a mirror chosen by the user, not video streams. */
internal object MirrorCatalog {
    private val sections = mapOf(
        "Фильмы" to "films/",
        "Сериалы" to "series/",
        "Мультфильмы" to "cartoons/",
        "Аниме" to "animation/"
    )
    private val sorts = mapOf(
        "Смотрят сейчас" to "watching",
        "Горячие" to "hot",
        "Последние поступления" to "last",
        "Популярное" to "popular",
        "В ожидании" to "soon"
    )

    fun requestUrl(source: String, section: String, kind: String): String {
        val base = source.trimEnd('/') + "/"
        val target = URI(base).resolve(sections[kind].orEmpty()).toString()
        val filter = sorts[section] ?: return target
        return target + (if (target.contains("?")) "&" else "?") + "filter=" + filter
    }

    fun searchUrl(source: String, query: String): String {
        val url = URI(source.trimEnd('/') + "/").resolve("search/").toString()
        val text = URLEncoder.encode(query.trim().take(120), "UTF-8")
        return url + "?do=search&subaction=search&q=" + text
    }

    fun parse(result: String, source: String): List<CatalogMovie> = runCatching {
        val array = MirrorMediaParser.objectFromWebView(result)?.optJSONArray("cards")
            ?: return@runCatching emptyList()
        val list = mutableListOf<CatalogMovie>()
        val seen = mutableSetOf<String>()
        for (i in 0 until minOf(array.length(), 96)) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!SourceRules.sameOrigin(source, url) || MirrorPostId.fromUrl(url) == null) continue
            val details = item.optString("details").replace(Regex("""\s+"""), " ").trim().take(110)
            val title = CatalogTitleCleaner.clean(item.optString("title"), details).take(130)
            if (title.isBlank() || !seen.add(url)) continue
            val pictures = readPosters(item)
            val poster = pictures.firstOrNull().orEmpty()
            list.add(CatalogMovie(url, title, poster, details, pictures.drop(1)))
        }
        list
    }.getOrDefault(emptyList())

    fun readPosters(item: JSONObject): List<String> = buildList {
        val raw = item.optJSONArray("posters")
        if (raw != null) for (index in 0 until minOf(raw.length(), 8)) {
            val link = raw.optString(index).trim()
            if (MirrorPoster.isPhoto(link) && link !in this) add(link)
        }
        val primary = item.optString("poster").trim()
        if (MirrorPoster.isPhoto(primary) && primary !in this) add(primary)
    }

    val cardsJavascript = """
        ${MirrorPoster.javascript}
        function kinoCatalogCards(doc, base, origin) {
          var nodes = Array.from(doc.querySelectorAll(
            '.b-content__inline_item, .b-content__list_item, .b-content__item'
          )).slice(0,96);
          return nodes.flatMap(function(item) {
            var link = item.querySelector('.b-content__inline_item-link');
            var a = link?.matches('a[href]') ? link : link?.querySelector('a[href]');
            var cover = item.querySelector('.b-content__inline_item-cover') || item.querySelector('img');
            var address = a?.getAttribute('href') || item.getAttribute('data-url') ||
                cover?.querySelector('a[href]')?.getAttribute('href');
            if (!address) return [];
            var url;
            try { url = new URL(address, base); } catch(e) { return []; }
            if (url.origin !== origin || !/\/\d{1,12}-[^/]+\.html$/i.test(url.pathname)) return [];
            var name = (a?.textContent || a?.getAttribute('title') ||
                cover?.querySelector('img[alt]')?.getAttribute('alt') || '').replace(/\s+/g,' ').trim();
            var data = link?.querySelector('div');
            return name ? [{url:url.href,title:name,posters:kinoPosters(cover,base),
                            details:data?.textContent.trim() || ''}] : [];
          });
        }
    """.trimIndent()

    val javascript = """
        (function() {
          $cardsJavascript
          return JSON.stringify({cards:kinoCatalogCards(document,document.baseURI,location.origin)});
        })();
    """.trimIndent()

}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogGrid(
    source: String?,
    section: String,
    kind: String,
    cardsPerRow: Int,
    onOpen: (CatalogMovie) -> Unit,
    onBrowse: (String) -> Unit,
    onAddSource: () -> Unit,
    loadContent: Boolean = true,
    requestOverride: String? = null,
    searchText: String = "",
    movieFilter: (CatalogMovie) -> Boolean = { true }
) {
    if (source == null || !SourceRules.isValidHttpsUrl(source)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = appMuted,
                    modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("Добавьте зеркало", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Каталог загружается из выбранного источника.", color = appMuted)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAddSource) { Text("Добавить зеркало") }
            }
        }
        return
    }
    val context = LocalContext.current
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val columns = if (isTablet) cardsPerRow.coerceIn(2,8) else cardsPerRow.coerceIn(2,3)
    var attempt by remember(source, section, kind, requestOverride) { mutableIntStateOf(0) }
    var useUnfiltered by remember(source, section, kind, requestOverride, attempt) {
        mutableStateOf(false)
    }
    val requestUrl = remember(source, section, kind, requestOverride, useUnfiltered) {
        requestOverride ?: MirrorCatalog.requestUrl(
            source, if (useUnfiltered) "Новинки" else section, kind
        )
    }
    val cacheKey = requestUrl
    var movies by remember(requestUrl, attempt) {
        mutableStateOf(if (attempt == 0) CatalogMemory.get(cacheKey).orEmpty() else emptyList())
    }
    var nativeSearchChecked by remember(requestUrl, attempt) {
        mutableStateOf(searchText.isBlank() || movies.isNotEmpty())
    }
    LaunchedEffect(requestUrl, attempt, loadContent) {
        if (loadContent && !nativeSearchChecked) {
            val cards = PublicSearch.read(source, searchText, WebSettings.getDefaultUserAgent(context))
            if (cards.isNotEmpty()) {
                movies = cards
                CatalogMemory.put(cacheKey, cards)
            }
            nativeSearchChecked = true
        }
    }
    var pending by remember(requestUrl, attempt, nativeSearchChecked) { mutableStateOf(movies.isEmpty()) }
    var problem by remember(requestUrl, attempt) { mutableStateOf("") }
    var challengeActive by remember(requestUrl, attempt) { mutableStateOf(false) }
    fun recoverEmptyCatalog(reason: String) {
        if (!useUnfiltered && requestOverride == null && section != "Новинки") {
            useUnfiltered = true
        } else {
            pending = false
            problem = reason
        }
    }
    // Page callbacks are not guaranteed after a network stall or blocked navigation.
    // Bound each load, then try only the standard category once before showing an error.
    LaunchedEffect(requestUrl, attempt, loadContent, nativeSearchChecked, challengeActive) {
        if (loadContent && nativeSearchChecked && pending) {
            delay(if (challengeActive) 28_000L else 12_500L)
            if (pending) recoverEmptyCatalog(
                if (challengeActive) "Зеркало не завершило проверку браузера."
                else "Источник не завершил загрузку каталога. Проверьте доступность зеркала."
            )
        }
    }
    val browser = if (loadContent && nativeSearchChecked && pending && movies.isEmpty()) remember(context, requestUrl, attempt) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            val handler = Handler(Looper.getMainLooper())
            webViewClient = object : WebViewClient() {
                private var scanningUrl: String? = null

                override fun onPageStarted(
                    view: WebView, url: String, favicon: android.graphics.Bitmap?
                ) {
                    // A same-address challenge can reload after setting cookies;
                    // a stale previous scan must not discard the new document.
                    scanningUrl = null
                    challengeActive = false
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val blocked = !SourceRules.sameOrigin(source, request.url.toString())
                    if (blocked) recoverEmptyCatalog("Зеркало перенаправило на другой адрес.")
                    return blocked
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        MirrorTrace.add("webview", request.url.toString(), result = "io")
                        recoverEmptyCatalog("Не удалось загрузить каталог. Проверьте соединение.")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest,
                    error: android.webkit.WebResourceResponse
                ) {
                    if (!request.isForMainFrame || error.statusCode == 404) return
                    val failedUrl = request.url.toString()
                    val status = error.statusCode
                    MirrorTrace.add("webview", failedUrl, status,
                        result = if (status in setOf(403, 429, 503)) "blocked" else "response")
                    if (status !in setOf(403, 429, 503)) {
                        recoverEmptyCatalog("Зеркало вернуло HTTP $status.")
                        return
                    }
                    // WebView can receive HTTP 403 for an interactive JS proof-of-work
                    // page which later redirects to the original catalog. An immediate
                    // fallback destroys the browser and prevents the challenge from
                    // finishing. Inspect the loaded document before treating it as a
                    // permanent denial. Never fabricate authentication or entitlement.
                    handler.postDelayed({
                        if (!pending || view.url != failedUrl) return@postDelayed
                        view.evaluateJavascript(CatalogAccessPolicy.challengeJavascript) { value ->
                            if (!pending || view.url != failedUrl) return@evaluateJavascript
                            if (value == "true") {
                                challengeActive = true
                                scanningUrl = null
                            } else {
                                recoverEmptyCatalog("Зеркало ограничило каталог (HTTP $status).")
                            }
                        }
                    }, 950L)
                }

                private fun scan(view: WebView, url: String) {
                    if (!SourceRules.sameOrigin(source, url) || scanningUrl == url || !pending) return
                    scanningUrl = url

                    fun active() = pending && scanningUrl == url && view.url == url

                    fun finish(cards: List<CatalogMovie>, failure: String = "") {
                        if (!active()) return
                        if (cards.isEmpty()) {
                            recoverEmptyCatalog(failure.ifBlank { "По названию ничего не найдено на этом зеркале." })
                            return
                        }
                        movies = cards
                        if (cards.any { it.poster.isNotEmpty() }) CatalogMemory.put(cacheKey, cards)
                        pending = false
                        problem = ""
                    }

                    fun poll(expression: String, attempts: Int, done: (String?) -> Unit) {
                        if (!active()) return
                        view.evaluateJavascript(expression) { result ->
                            if (!active()) return@evaluateJavascript
                            when {
                                result.isNotBlank() && result != "null" -> done(result)
                                attempts > 0 -> handler.postDelayed({ poll(expression, attempts - 1, done) }, 200)
                                else -> done(null)
                            }
                        }
                    }

                    fun mergeResults(primary: List<CatalogMovie>, extra: List<CatalogMovie>): List<CatalogMovie> {
                        val merged = (primary + extra).groupBy { MirrorPostId.fromUrl(it.url) ?: it.url }
                            .values.map { entries ->
                                val first = entries.first()
                                val photos = entries.flatMap { listOf(it.poster) + it.posterAlternatives }
                                    .filter(String::isNotBlank).distinct()
                                first.copy(poster = photos.firstOrNull().orEmpty(),
                                    posterAlternatives = photos.drop(1),
                                    details = entries.firstOrNull { it.details.isNotBlank() }?.details.orEmpty())
                            }
                        return SearchRelevance.find(merged, searchText)
                    }

                    fun quickSearch(existing: List<CatalogMovie>) {
                        view.evaluateJavascript(MirrorQuickSearch.javascript(searchText)) {
                            poll("window.__kinoQuickSearchResult || null", 32) { raw ->
                                val quick = raw?.let { MirrorCatalog.parse(it, source) }.orEmpty()
                                val matches = mergeResults(existing, quick)
                                if (matches.isEmpty() && (raw == null ||
                                        MirrorMediaParser.objectFromWebView(raw)?.optBoolean("failed") == true)) {
                                    finish(emptyList(), "Не удалось получить ответ поиска от зеркала.")
                                } else finish(matches)
                            }
                        }
                    }

                    val timings = if (searchText.isBlank()) listOf(120L, 650L, 1600L, 3300L)
                        else listOf(120L, 650L, 1450L)
                    timings.forEachIndexed { index, delayMs ->
                        handler.postDelayed({
                            if (!active() || challengeActive) return@postDelayed
                            view.evaluateJavascript(MirrorCatalog.javascript) { raw ->
                                if (!active()) return@evaluateJavascript
                                val extracted = MirrorCatalog.parse(raw, source)
                                if (searchText.isBlank()) {
                                    if (extracted.isNotEmpty()) finish(extracted)
                                    else if (index == timings.lastIndex) recoverEmptyCatalog(
                                        "Источник не предоставил карточки или открыл проверку доступа."
                                    )
                                    return@evaluateJavascript
                                }
                                val matches = SearchRelevance.find(extracted, searchText)
                                val expectedSearchPage = url.contains("do=search")
                                if (expectedSearchPage && matches.isNotEmpty() && matches.all { it.poster.isNotBlank() }) {
                                    finish(matches)
                                } else if (index == timings.lastIndex) quickSearch(matches)
                            }
                        }, delayMs)
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String) = scan(view, url)
                override fun onPageFinished(view: WebView, url: String) = scan(view, url)
            }
            setTag(handler)
            MirrorGuestSession.loadPublicPage(this, requestUrl)
        }
    } else null
    DisposableEffect(browser) {
        onDispose {
            (browser?.tag as? Handler)?.removeCallbacksAndMessages(null)
            browser?.stopLoading()
            browser?.destroy()
        }
    }
    // Never show the mirror's default homepage cards as results for an unrelated search.
    val visibleMovies = (if (searchText.isBlank()) movies
        else SearchRelevance.find(movies, searchText)).filter(movieFilter)
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // The hidden view reads the ordinary page over the user's current network and VPN.
            if (browser != null) AndroidView(factory = { browser }, modifier = Modifier.size(2.dp).alpha(0.01f))
            when {
                visibleMovies.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                    Text((if (useUnfiltered) "Общий каталог • " else "") +
                        visibleMovies.size.toString() + " результатов", color = appMuted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(visibleMovies, key = { it.url }) { movie ->
                            FilmPoster(movie) { onOpen(movie) }
                        }
                    }
                }
                (pending || !nativeSearchChecked) -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = appBlue, modifier = Modifier.size(34.dp))
                    if (challengeActive) {
                        Spacer(Modifier.height(12.dp))
                        Text("Зеркало проверяет браузер…", color = appMuted)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text("Загружаем фильмы", color = appMuted)
                }
                else -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = appMuted,
                        modifier = Modifier.size(39.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(if (searchText.isNotBlank()) "Совпадений не найдено"
                        else "Не удалось получить карточки",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(7.dp))
                    Text(problem.ifBlank {
                        if (searchText.isNotBlank()) "Зеркало не нашло фильм с таким названием."
                        else "Источник не ответил."
                    }, color = appMuted)
                    Spacer(Modifier.height(15.dp))
                    Button(onClick = { CatalogMemory.invalidate(cacheKey); attempt++ }) { Text("Повторить") }
                    TextButton(onClick = { onBrowse(source) }) { Text("Открыть зеркало") }
                }
            }
        }
    }
    if (searchText.isBlank()) {
        PullToRefreshBox(
            isRefreshing = attempt > 0 && pending,
            onRefresh = { CatalogMemory.invalidate(cacheKey); attempt++ },
            modifier = Modifier.fillMaxSize()
        ) { content() }
    } else content()
}

@Composable
internal fun FilmPoster(movie: CatalogMovie, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        MoviePosterImage(
            movie = movie,
            modifier = Modifier.fillMaxWidth().aspectRatio(.70f).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(7.dp))
        Text(movie.title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (movie.details.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(movie.details, color = appMuted, maxLines = 2,
                style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun MoviePosterImage(movie: CatalogMovie, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val loader = remember(context) { posterImageLoader(context) }
    val candidates = remember(movie.poster, movie.posterAlternatives) {
        (listOf(movie.poster) + movie.posterAlternatives).filter(MirrorPoster::isPhoto).distinct()
    }
    var index by remember(movie.url, candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(index)
    val image = remember(url, movie.url) {
        ImageRequest.Builder(context).data(url)
            .addHeader("Referer", movie.url)
            .addHeader("User-Agent", WebSettings.getDefaultUserAgent(context))
            .crossfade(true).build()
    }
    Box(modifier.background(appCard), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = appMuted.copy(alpha = .5f),
                modifier = Modifier.size(38.dp))
        } else {
            AsyncImage(
                model = image, imageLoader = loader, contentDescription = movie.title, contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { if (candidates.getOrNull(index) == url) index++ }
            )
        }
    }
}

@Volatile private var sharedPosterLoader: ImageLoader? = null

@Synchronized
internal fun posterImageLoader(context: android.content.Context): ImageLoader =
    sharedPosterLoader ?: ImageLoader.Builder(context.applicationContext)
        .okHttpClient {
            MirrorHttp.client.newBuilder().addNetworkInterceptor { chain ->
                if (!MirrorPoster.isPhoto(chain.request().url.toString())) {
                    throw java.io.IOException("Poster redirected to a placeholder")
                }
                chain.proceed(chain.request())
            }.build()
        }
        .build().also { sharedPosterLoader = it }
