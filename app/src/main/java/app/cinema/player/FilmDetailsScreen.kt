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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

internal data class FilmPerson(val name: String, val photo: String)
internal data class FilmInfo(
    val originalTitle: String,
    val description: String,
    val facts: List<Pair<String,String>>,
    val rating: String,
    val cast: List<FilmPerson>,
    val related: List<CatalogMovie>
)

internal object FilmInfoMemory {
    private val values = object : LinkedHashMap<String, FilmInfo>(20, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FilmInfo>?): Boolean =
            size > 20
    }
    @Synchronized fun get(url: String): FilmInfo? = values[url]
    @Synchronized fun put(url: String, info: FilmInfo) { values[url] = info }
}

internal object FilmPageParser {
    val javascript = """
       (function() {
         function norm(s) {return (s || '').replace(/\s+/g,' ').trim();}
         function txt(s) {var e=document.querySelector(s);return e?norm(e.textContent):'';}
         function absolute(s) {try {return new URL(s,document.baseURI).href;} catch(e) {return '';}}
         var facts=[];
         document.querySelectorAll('.b-post__infotable tr, .b-post__info tr').forEach(function(tr) {
           var cells=tr.querySelectorAll('td');
           if (cells.length<2) return;
           var label=norm(cells[0].textContent).replace(/:$/,'');
           var value=norm(cells[1].textContent);
           if (label && value && value.length<220) facts.push([label,value]);
         });
         var details=document.querySelector('.b-post__description_text, .b-post__description, [itemprop="description"]');
         var description=details ? norm(details.textContent) : '';
         if (!description) {
           var meta=document.querySelector('meta[name="description"], meta[property="og:description"]');
           if(meta) description=norm(meta.getAttribute('content'));
         }
         var people=[];
         document.querySelectorAll('.b-post__people_item, .b-post__actors_item, .b-post__person_item, .persons-list-holder .person-name-item').forEach(function(e){
           var img=e.querySelector('img');
           var n=norm(e.querySelector('a')?.textContent || img?.getAttribute('alt') || e.textContent);
           var photo=img ? absolute(img.getAttribute('data-src') || img.getAttribute('src')) : '';
           if(n && n.length<95) people.push({name:n,photo:photo});
         });
         var similar=[];
         document.querySelectorAll('.b-content__inline_item, .b-sidelist__item').forEach(function(item) {
           var a=item.querySelector('a[href]');
           var img=item.querySelector('img');
           if(!a)return;
           var name=norm(a.getAttribute('title') || item.querySelector('.b-content__inline_item-link')?.textContent || a.textContent);
           if(!name || name.length>130)return;
           var photo=img?absolute(img.getAttribute('data-src') || img.getAttribute('src')):'';
           similar.push({title:name,url:absolute(a.getAttribute('href')),poster:photo,details:''});
         });
         return JSON.stringify({
           originalTitle:txt('.b-post__origtitle, .b-post__subtitle, .b-post__title_original'),
           description:description.substring(0,1800),
           rating:txt('.b-post__rating, .b-post__rating_tables, .b-post__ratings'),
           facts:facts.slice(0,18),
           people:people.slice(0,18),
           related:similar.slice(0,15)
         });
       })();
    """.trimIndent()

    fun parse(raw: String, source: String): FilmInfo? = runCatching {
        val decoded = JSONTokener(raw).nextValue() as? String ?: return@runCatching null
        val data = JSONObject(decoded)
        val factsArray = data.optJSONArray("facts")
        val facts = buildList {
            if (factsArray != null) for (i in 0 until minOf(20, factsArray.length())) {
                val row = factsArray.optJSONArray(i) ?: continue
                val key = row.optString(0).trim().take(45)
                val value = row.optString(1).trim().take(220)
                if (key.isNotBlank() && value.isNotBlank()) add(key to value)
            }
        }
        val peopleArray = data.optJSONArray("people")
        val people = buildList {
            if (peopleArray != null) for (i in 0 until minOf(peopleArray.length(), 16)) {
                val item = peopleArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val photo = item.optString("photo").takeIf(SourceRules::isValidHttpsUrl).orEmpty()
                if (name.isNotBlank()) add(FilmPerson(name.take(85), photo))
            }
        }.distinctBy { it.name }
        val relatedArray = data.optJSONArray("related")
        val originalHost = URI(source).host?.lowercase() ?: return@runCatching null
        val related = buildList {
            if (relatedArray != null) for (i in 0 until minOf(relatedArray.length(), 12)) {
                val item = relatedArray.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (!SourceRules.isValidHttpsUrl(url)) continue
                val host = URI(url).host?.lowercase() ?: continue
                if (host != originalHost && !host.endsWith(".$originalHost")) continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val poster = item.optString("poster").takeIf(SourceRules::isValidHttpsUrl).orEmpty()
                add(CatalogMovie(url, title.take(130), poster, ""))
            }
        }.distinctBy { it.url }
        FilmInfo(
            originalTitle = data.optString("originalTitle").trim().take(130),
            description = data.optString("description").trim().take(1800),
            facts = facts,
            rating = data.optString("rating").replace(Regex("\\s+"), " ").trim().take(130),
            cast = people,
            related = related
        )
    }.getOrNull()
}

@Composable
internal fun FilmDetails(
    movie: CatalogMovie,
    source: String,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    onWatch: (String) -> Unit,
    onRelated: (CatalogMovie) -> Unit
) {
    val context = LocalContext.current
    var info by remember(movie.url) { mutableStateOf(FilmInfoMemory.get(movie.url)) }
    var loadDone by remember(movie.url) { mutableStateOf(info != null) }
    val webView = if (info == null && !loadDone) remember(context, movie.url) {
        val handler = Handler(Looper.getMainLooper())
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val host = request.url.host ?: return true
                    val home = URI(source).host ?: return true
                    return request.url.scheme != "https" ||
                        !(host.equals(home, true) || host.endsWith(".$home", true))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    listOf(400L, 1400L, 3000L).forEachIndexed { index, delay ->
                        handler.postDelayed({
                            if (view.url != url || loadDone) return@postDelayed
                            view.evaluateJavascript(FilmPageParser.javascript) { raw ->
                                val parsed = FilmPageParser.parse(raw, source)
                                if (parsed != null &&
                                    (parsed.description.isNotBlank() || parsed.facts.isNotEmpty() ||
                                        parsed.cast.isNotEmpty())) {
                                    info = parsed
                                    FilmInfoMemory.put(movie.url, parsed)
                                    loadDone = true
                                } else if (index == 2) {
                                    loadDone = true
                                }
                            }
                        }, delay)
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadDone = true
                }
            }
            setTag(handler)
            loadUrl(movie.url)
        }
    } else null
    DisposableEffect(webView) {
        onDispose {
            (webView?.tag as? Handler)?.removeCallbacksAndMessages(null)
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    val primary = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxSize()) {
        if (webView != null) AndroidView(factory = { webView }, modifier = Modifier.size(2.dp).alpha(.01f))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = appBlue)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("О фильме", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(142.dp).aspectRatio(.70f)
                        .clip(RoundedCornerShape(16.dp)).background(appCard),
                        contentAlignment = Alignment.Center) {
                        if (movie.poster.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(movie.poster)
                                    .addHeader("Referer", source).build(),
                                contentDescription = movie.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Outlined.Movie, contentDescription = null,
                                modifier = Modifier.size(42.dp), tint = appMuted)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(movie.title, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        if (!info?.originalTitle.isNullOrBlank()) {
                            Text(info!!.originalTitle, style = MaterialTheme.typography.bodyMedium,
                                color = appMuted)
                        }
                        if (info?.rating?.isNotBlank() == true) {
                            Text(info!!.rating, color = appBlue,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (movie.details.isNotBlank()) {
                            Text(movie.details, color = appMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(3.dp))
                        Button(
                            onClick = { onWatch(movie.url) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text("Смотреть")
                        }
                        OutlinedButton(
                            onClick = onToggleFavourite,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                if (isFavourite) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (isFavourite) "Сохранено" else "В избранное")
                        }
                    }
                }
            }
            if (info?.description?.isNotBlank() == true) {
                item {
                    Text("Описание", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    Text(info!!.description, color = primary.copy(alpha = .88f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (info?.facts?.isNotEmpty() == true) {
                item {
                    Text("Информация", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    info!!.facts.take(12).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label, color = appMuted, modifier = Modifier.width(115.dp),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(value, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (info?.cast?.isNotEmpty() == true) {
                item {
                    Text("В ролях", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(info!!.cast, key = { it.name }) { person ->
                            Column(Modifier.width(92.dp)) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(.80f)
                                        .clip(RoundedCornerShape(12.dp)).background(appCard),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (person.photo.isNotBlank()) {
                                        AsyncImage(model = person.photo, contentDescription = person.name,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else Icon(Icons.Outlined.Person, contentDescription = null,
                                        tint = appMuted, modifier = Modifier.size(38.dp))
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(person.name, maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (info?.related?.isNotEmpty() == true) {
                item {
                    Text("Смотрите также", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(info!!.related, key = { it.url }) { related ->
                            Box(Modifier.width(124.dp)) {
                                FilmPoster(related, source) { onRelated(related) }
                            }
                        }
                    }
                }
            }
            if (info == null && !loadDone) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Загружаем сведения о фильме", color = appMuted)
                    }
                }
            } else if (info == null) {
                item {
                    Text("Подробные сведения на выбранном зеркале сейчас недоступны.",
                        color = appMuted)
                }
            }
        }
    }
}
