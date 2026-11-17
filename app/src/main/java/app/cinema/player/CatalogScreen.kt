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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject
import org.json.JSONTokener
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
        val decoded = JSONTokener(result).nextValue() as? String ?: return@runCatching emptyList()
        val array = JSONObject(decoded).optJSONArray("cards") ?: return@runCatching emptyList()
        val origin = URI(source).host?.lowercase() ?: return@runCatching emptyList()
        val list = mutableListOf<CatalogMovie>()
        val seen = mutableSetOf<String>()
        for (i in 0 until minOf(array.length(), 96)) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!SourceRules.isValidHttpsUrl(url)) continue
            val host = URI(url).host?.lowercase() ?: continue
            if (host != origin && !host.endsWith("." + origin)) continue
            val title = item.optString("title").trim().take(130)
            if (title.isBlank() || !seen.add(url)) continue
            val pictures = buildList {
                val primary = item.optString("poster").trim()
                if (SourceRules.isValidHttpsUrl(primary)) add(primary)
                val raw = item.optJSONArray("posters")
                if (raw != null) for (n in 0 until minOf(raw.length(), 12)) {
                    val link = raw.optString(n).trim()
                    if (SourceRules.isValidHttpsUrl(link) && link !in this) add(link)
                }
            }
            val poster = pictures.firstOrNull().orEmpty()
            val details = item.optString("details").replace(Regex("\\s+"), " ").trim().take(110)
            list.add(CatalogMovie(url, title, poster, details, pictures.drop(1)))
        }
        list
    }.getOrDefault(emptyList())

    val javascript = """
        (function() {
          var nodes = Array.from(document.querySelectorAll(
            '.b-content__inline_item, .b-content__list_item, .b-content__item'
          )).slice(0,96);
          var cards = nodes.map(function(item) {
            var a = item.querySelector('.b-content__inline_item-link a[href]') ||
                    item.querySelector('a.b-content__inline_item-link[href]') ||
                    item.querySelector('a[href*="/films/"], a[href*="/series/"], a[href*="/cartoons/"], a[href*="/animation/"]') ||
                    item.querySelector('a[href]');
            if (!a) return null;
            var link = item.querySelector('.b-content__inline_item-link');
            var img = item.querySelector('.b-content__inline_item-cover img') || item.querySelector('img');
            var cover = item.querySelector('.b-content__inline_item-cover');
            var name = (a.getAttribute('title') ||
                item.querySelector('.b-content__inline_item-link')?.textContent ||
                img?.getAttribute('alt') || a.textContent || '').replace(/\s+/g,' ').trim();
            function absolute(s) {
              try { return new URL(s,document.baseURI).href; } catch(e) { return ''; }
            }
            var posters = [];
            function add(s) {
              if (!s) return;
              var u=absolute(s.trim());
              if (!u.startsWith('https://') || /(?:placeholder|spacer|blank\.|data:image|\/1x1\.)/i.test(u)) return;
              if(posters.indexOf(u)===-1)posters.push(u);
            }
            function srcset(text) {
              if(!text)return;
              text.split(',').reverse().forEach(function(entry) {
                 add((entry||'').trim().split(/\s+/)[0]);
              });
            }
            if(img) {
              ['data-src','data-original','data-lazy-src','data-image','data-url',
                'data-srcset','srcset','src'].forEach(function(key) {
                 var v=img.getAttribute(key);
                 if(key.indexOf('srcset')>=0)srcset(v); else add(v);
              });
              add(img.currentSrc);
              var picture=img.closest('picture');
              if(picture)Array.from(picture.querySelectorAll('source')).forEach(function(e) {
                 srcset(e.getAttribute('srcset')||e.getAttribute('data-srcset'));
              });
            }
            if(cover) {
              ['data-src','data-original','data-image','data-bg','data-background','data-cover']
                .forEach(function(key){ add(cover.getAttribute(key)); });
              var css=cover.style.backgroundImage || getComputedStyle(cover).backgroundImage || '';
              var found=css.match(/url\(['"]?(.*?)['"]?\)/i);
              if(found)add(found[1]);
              var noscript=cover.querySelector('noscript');
              if(noscript) {
                var n=noscript.textContent.match(/src=['"]([^'"]+)/i);
                if(n)add(n[1]);
              }
            }
            var data = link ? link.querySelector('div') : null;
            return {url:absolute(a.getAttribute('href')),title:name,poster:posters[0]||'',
                    posters:posters.slice(0,8),
                    details:data ? (data.textContent || '').trim() : ''};
          }).filter(function(x){return x && x.title && x.url;});
          return JSON.stringify({cards:cards});
        })();
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
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
    var pending by remember(requestUrl, attempt) { mutableStateOf(movies.isEmpty()) }
    var problem by remember(requestUrl, attempt) { mutableStateOf("") }
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
    LaunchedEffect(requestUrl, attempt, loadContent) {
        if (loadContent && pending) {
            delay(9000)
            if (pending) recoverEmptyCatalog(
                "Источник не завершил загрузку каталога. Проверьте доступность зеркала."
            )
        }
    }
    val browser = if (loadContent && pending && movies.isEmpty()) remember(context, requestUrl, attempt) {
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
            val host = URI(source).host.lowercase()
            val handler = Handler(Looper.getMainLooper())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    val candidateHost = uri.host?.lowercase() ?: return true
                    return uri.scheme != "https" || (candidateHost != host && !candidateHost.endsWith("." + host))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        pending = false
                        problem = "Не удалось загрузить каталог. Проверьте соединение."
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    val delays = listOf(300L, 1100L, 2600L, 4800L)
                    delays.forEachIndexed { index, delayMs ->
                        handler.postDelayed({
                            if (view.url != url || !pending) return@postDelayed
                            view.evaluateJavascript(MirrorCatalog.javascript) { raw ->
                                if (!pending || view.url != url) return@evaluateJavascript
                                val extracted = MirrorCatalog.parse(raw, source)
                                if (extracted.isNotEmpty()) {
                                    movies = extracted
                                    CatalogMemory.put(cacheKey, extracted)
                                    pending = false
                                    problem = ""
                                } else if (index == delays.lastIndex) {
                                    recoverEmptyCatalog(
                                        "Источник не предоставил карточки или открыл проверку доступа."
                                    )
                                }
                            }
                        }, delayMs)
                    }
                }
            }
            setTag(handler)
            loadUrl(requestUrl)
        }
    } else null
    DisposableEffect(browser) {
        onDispose {
            (browser?.tag as? Handler)?.removeCallbacksAndMessages(null)
            browser?.stopLoading()
            browser?.destroy()
        }
    }
    val visibleMovies = movies.filter(movieFilter)
    Box(Modifier.fillMaxSize()) {
        // The hidden view reads the ordinary page over the user's current network and VPN.
        if (browser != null) AndroidView(factory = { browser }, modifier = Modifier.size(2.dp).alpha(0.01f))
        when {
            movies.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (useUnfiltered) "Общий каталог • " else "") +
                        visibleMovies.size.toString() + " результатов", color = appMuted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { CatalogMemory.invalidate(cacheKey); attempt++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null,
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Обновить")
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(visibleMovies, key = { it.url }) { movie ->
                        FilmPoster(movie, source) { onOpen(movie) }
                    }
                }
            }
            pending -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = appBlue, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(15.dp))
                Text("Загружаем фильмы", color = appMuted)
            }
            else -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = appMuted,
                    modifier = Modifier.size(39.dp))
                Spacer(Modifier.height(14.dp))
                Text("Не удалось получить карточки",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(7.dp))
                Text(problem, color = appMuted)
                Spacer(Modifier.height(15.dp))
                Button(onClick = { CatalogMemory.invalidate(cacheKey); attempt++ }) { Text("Повторить") }
                TextButton(onClick = { onBrowse(source) }) { Text("Открыть зеркало") }
            }
        }
    }
}

@Composable
internal fun FilmPoster(movie: CatalogMovie, source: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val candidates = remember(movie.poster, movie.posterAlternatives) {
        (listOf(movie.poster) + movie.posterAlternatives).filter(SourceRules::isValidHttpsUrl).distinct()
    }
    var imageIndex by remember(movie.url, candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(imageIndex)
    val image = remember(url, source) {
        val builder = ImageRequest.Builder(context)
            .data(url)
            .addHeader("Referer", source.trimEnd('/') + "/")
            .addHeader("User-Agent", WebSettings.getDefaultUserAgent(context))
        // Coil does not share WebView's authenticated cookie jar automatically.
        // Never attach account cookies to third-party image/CDN hosts.
        if (url != null && runCatching {
                URI(url).host.equals(URI(source).host, ignoreCase = true)
            }.getOrDefault(false)) {
            CookieManager.getInstance().getCookie(source)?.let { cookie ->
                if (cookie.isNotBlank()) builder.addHeader("Cookie", cookie)
            }
        }
        builder.crossfade(true).build()
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.70f).clip(RoundedCornerShape(14.dp))
            .background(appCard), contentAlignment = Alignment.Center) {
            if (url != null) {
                AsyncImage(
                    model = image, contentDescription = movie.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    onError = {
                        if (imageIndex < candidates.lastIndex) imageIndex++
                    }
                )
            } else {
                Icon(Icons.Outlined.Movie, contentDescription = null,
                    tint = appMuted.copy(alpha = .5f), modifier = Modifier.size(38.dp))
            }
        }
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
