package app.cinema.player

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.CookieManager
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
import org.json.JSONObject
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

internal data class FilmPerson(val name: String, val photo: String, val url: String = "")
internal data class FilmInfo(
    val originalTitle: String,
    val description: String,
    val facts: List<Pair<String,String>>,
    val rating: String,
    val cast: List<FilmPerson>,
    val related: List<CatalogMovie>,
    val franchise: List<CatalogMovie> = emptyList(),
    val franchiseResolved: Boolean = false,
    val directors: List<FilmPerson> = emptyList(),
    val voices: List<MirrorVoice> = emptyList(),
    val title: String = "",
    val posters: List<String> = emptyList(),
    val portraitsResolved: Boolean = true,
    val defaultVoice: String = "",
    val episodes: List<MirrorEpisode> = emptyList()
)

internal object FilmInfoMemory {
    private data class Entry(val info: FilmInfo, val timestamp: Long)
    private val values = object : LinkedHashMap<String, Entry>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > 24
    }
    @Synchronized fun get(url: String): FilmInfo? = values[url]
        ?.takeIf { android.os.SystemClock.elapsedRealtime() - it.timestamp < 5 * 60 * 1000 }
        ?.info

    @Synchronized fun put(url: String, info: FilmInfo) {
        values[url] = Entry(info, android.os.SystemClock.elapsedRealtime())
    }

}

internal object FilmPageParser {
    val javascript = """
       (function() {
         function norm(s) {return (typeof s === 'string' ? s : '').replace(/\s+/g,' ').trim();}
         ${MirrorVoiceDiscovery.javascript}
         ${MirrorVoiceDiscovery.episodesJavascript}
         ${MirrorPoster.javascript}
         function txt(s) {var e=document.querySelector(s);return e?norm(e.textContent):'';}
         function absolute(s) {try {return new URL(s,document.baseURI).href;} catch(e) {return '';}}
         var facts=[];
         document.querySelectorAll('.b-post__infotable tr, .b-post__info tr').forEach(function(tr) {
           var cells=tr.cells;
           if (!cells || cells.length<2) return;
           var label=norm(cells[0].textContent).replace(/:$/,'');
           var value=norm(cells[1].textContent);
           if (label && value && value.length<220) facts.push([label,value]);
         });
         var descriptions=[];
         var people = [];
         function addDescription(value) {
           var text = norm(typeof value === 'string' ? value : '');
           if(text && text.length <= 8000 && !descriptions.includes(text)) descriptions.push(text);
         }
         document.querySelectorAll('.b-post__description_text, .b-post__description [itemprop="description"]').forEach(function(node) {
           var copy = node.cloneNode(true);
           copy.querySelectorAll('script,style,noscript,.b-post__description_title,[data-ad],.premium-promo').forEach(function(e) {e.remove()});
           addDescription(copy.textContent);
         });
         if (!descriptions.length) {
           var section = document.querySelector('.b-post__description');
           section?.querySelectorAll('p').forEach(function(paragraph){addDescription(paragraph.textContent)});
           if (!descriptions.length && section) addDescription(section.textContent);
         }
         var localTitle = norm(document.querySelector('.b-post__title h1, h1.b-post__title, h1[itemprop="name"], .b-post__title')?.textContent);
         var id = function(url) { return new URL(url,location.href).pathname.match(/\/(\d{1,12})-[^/]+\.html$/i)?.[1]; };
         document.querySelectorAll('script[type="application/ld+json"]').forEach(function(script) {
           try {
             var json = JSON.parse(script.textContent);
             (Array.isArray(json) ? json : [json]).flatMap(function(entry) {
               return Array.isArray(entry?.['@graph']) ? entry['@graph'] : [entry];
             }).forEach(function(row) {
               var types = Array.isArray(row?.['@type']) ? row['@type'] : [row?.['@type']];
               if (!types.some(function(t){return /^(Movie|TVSeries|TVSeason)$/i.test(t || '')})) return;
               var address = row.url || row.mainEntityOfPage?.['@id'];
               if (address) {
                 var u = new URL(address,location.href);
                 if(u.origin !== location.origin || !id(u.href) || id(u.href) !== id(location.href)) return;
               } else if (!localTitle || norm(row.name).toLowerCase() !== localTitle.toLowerCase()) return;
               addDescription(row.description);
               [['actor', row.actor || row.actors], ['director', row.director]].forEach(function(pair) {
                 var list = Array.isArray(pair[1]) ? pair[1] : [pair[1]];
                 list.slice(0,24).forEach(function(entry) {
                   if (entry && typeof entry === 'object') {
                     addPerson(pair[0], entry.name, entry.url || entry['@id'], entry.image?.url || entry.image);
                   }
                 });
               });
             });
           } catch(e) {}
         });
         function addPerson(role, rawName, address, image) {
           var name = norm(rawName);
           if (!name || name.length >= 95) return;
           var url = typeof address === 'string' && address ? absolute(address) : '';
           var photo = typeof image === 'string' && image ? absolute(image) : '';
           var previous = people.find(function(entry) { return entry.role === role &&
             entry.name.toLowerCase().replace(/ё/g,'е') === name.toLowerCase().replace(/ё/g,'е'); });
           photo = photo || window.__kinoPortraits?.images[url] || '';
           if (previous) {
             previous.url = previous.url || url;
             previous.photo = previous.photo || photo;
           } else people.push({name:name,url:url,photo:photo,role:role});
         }
         function person(node, role) {
           var entry = node.closest('.b-post__people_item, .b-post__actors_item, .b-post__person_item, .person-name-item, [itemprop~="actor"], [itemprop~="actors"], [itemprop~="director"]') || node;
           var anchor = node.matches('a[href]') ? node : entry.querySelector('a[href]');
           var label = entry.querySelector('[itemprop~="name"], .person-name') ||
             anchor?.querySelector('[itemprop~="name"]') || anchor || entry;
           var name = label.getAttribute('content') || label.textContent || entry.querySelector('img')?.getAttribute('alt');
           var photo = kinoPosters(entry,document.baseURI)[0] || '';
           addPerson(role, name, anchor?.getAttribute('href'), photo);
         }
         document.querySelectorAll('.b-post__infotable tr, .b-post__info tr').forEach(function(row) {
           var cells = row.cells;
           if (!cells || cells.length < 2) return;
           var label = norm(cells[0].textContent);
           var role = /^(?:в ролях|в главных ролях|акт[её]р)/i.test(label) ? 'actor' : /^режисс/i.test(label) ? 'director' : '';
           if (!role) return;
           var entries = cells[1].querySelectorAll('a[href], [itemprop~="actor"], [itemprop~="actors"], [itemprop~="director"]');
           if (entries.length) entries.forEach(function(item){person(item,role)});
           else norm(cells[1].textContent).split(/,|;/).slice(0,12)
             .forEach(function(name){addPerson(role,name,'','')});
         });
         document.querySelectorAll(
           '.b-post__actors a[href], .b-post__actors_item a[href], a.b-post__actors_item[href], ' +
           '.b-post [itemprop~="actor"], .b-post [itemprop~="actors"], .b-post__infotable [itemprop~="actor"], ' +
           '.b-post__infotable [itemprop~="actors"], .persons-list-holder [itemprop~="actor"], .persons-list-holder [itemprop~="actors"]'
         ).forEach(function(node){person(node,'actor')});
         document.querySelectorAll('.b-post__directors a[href], .b-post [itemprop~="director"], .b-post__infotable [itemprop~="director"]')
           .forEach(function(node){person(node,'director')});
         var profiles = people.slice().sort(function(a,b) {return (a.role !== "actor") - (b.role !== "actor")}).filter(function(p) {
           if (p.photo || !p.url) return false;
           var u = new URL(p.url);
           return u.origin === location.origin && /\/(?:person|persons|actors)\//i.test(u.pathname);
         }).filter(function(p, index, all) {
           return all.findIndex(function(other){return other.url === p.url}) === index;
         }).slice(0, 4);
         if (profiles.length && !window.__kinoPortraits) {
           var portraits = window.__kinoPortraits = {images:{}, done:false};
           var controller = new AbortController();
           var timeout = setTimeout(function(){controller.abort()}, 2600);
           Promise.all(profiles.map(async function(profile) {
              try {
                var response = await fetch(profile.url, {credentials:'same-origin',signal:controller.signal});
                var from = new URL(profile.url);
                var final = new URL(response.url);
                if (!response.ok || from.origin !== final.origin || from.pathname !== final.pathname) return;
                var html = await response.text();
                if (html.length > 750000) return;
                var doc = new DOMParser().parseFromString(html, 'text/html');
                var title = norm(doc.querySelector('h1, .b-person__name')?.textContent);
                if (title.toLowerCase().replace(/ё/g,'е') !== profile.name.toLowerCase().replace(/ё/g,'е')) return;
                var portrait = doc.querySelector('.b-person__photo, .b-person__poster, .b-person__image, .b-person [itemprop~="image"], [itemtype$="/Person"] [itemprop~="image"]');
                var image = kinoPosters(portrait, response.url)[0];
                if (image) portraits.images[profile.url] = image;
              } catch (e) {}
           })).finally(function(){clearTimeout(timeout); portraits.done = true});
         }
         var similar = [];
         document.querySelectorAll('.b-post__related .b-content__inline_item, .b-post__related_item, .b-post__similar .b-content__inline_item').forEach(function(item) {
           var link = item.querySelector('.b-content__inline_item-link a[href], .b-post__related_item_title a[href], a[href]');
           if (!link) return;
           var address = absolute(link.getAttribute('href'));
           var title = norm(link.textContent || link.getAttribute('title'));
           if (!id(address) || id(address) === id(location.href) || !title) return;
           var posters = kinoPosters(item.querySelector('.b-content__inline_item-cover') || item,document.baseURI);
           similar.push({title:title,url:address,posters:posters});
         });
         var franchise=window.__kinoFranchiseParts || [];
         return JSON.stringify({
           browserCheck:/captcha|verify you are human|checking your browser|проверяем.{0,30}не бот/i.test(document.title || ''),
           originalTitle:txt('.b-post__origtitle, .b-post__subtitle, .b-post__title_original'),
           title:localTitle,
           posters:kinoFilmPosters(document,document.baseURI),
           descriptions:descriptions.slice(0,12),
           rating:txt('.b-post__rating, .b-post__rating_tables, .b-post__ratings'),
           facts:facts.slice(0,18),
           people:people.slice(0,60),
           voices:kinoPlayerMetadata().voices,
           defaultVoice:kinoPlayerMetadata().defaultVoice,
           episodes:kinoEpisodes(document),
           related:similar.slice(0,15),
           franchise:franchise.slice(0,50),
           franchiseDone:window.__kinoFranchiseDone === true,
           portraitsDone:!window.__kinoPortraits || window.__kinoPortraits.done
         });
       })();
    """.trimIndent()

    /**
     * Franchises are NOT identified by heading text. HDRezka uses a separate franchise
     * link and .b-post__partcontent_item rows (number, title, year, rating).
     * Fetch at most one same-origin HTML page using the current WebView session.
     */
    val franchiseJavascript = """
       (function() {
         if (window.__kinoFranchiseStarted) return 'already-started';
         if (document.readyState === 'loading' && !document.querySelector('.b-post__franchise_link_title, .b-post__partcontent_item')) return 'waiting-for-markup';
         window.__kinoFranchiseStarted = true;
         window.__kinoFranchiseParts = [];
         window.__kinoFranchiseDone = false;
         function text(node) { return (node?.textContent || '').replace(/\s+/g, ' ').trim(); }
         function read(doc, base) {
           var values = [];
           doc.querySelectorAll('.b-post__partcontent_item').forEach(function(row) {
             var a=row.querySelector('.title > a[href], .title a[href]');
             var title=text(a || row.querySelector('.title'));
             if (!title || title.length>140) return;
             var href=a?.getAttribute('href') ||
                (row.classList.contains('current') ? location.href : '');
             if (!href) return;
             var u;
             try { u=new URL(href, base); } catch(e) { return; }
             if (u.protocol!=='https:' || u.origin!==location.origin) return;
             var position=parseInt(text(row.querySelector('.num')),10);
             var year=text(row.querySelector('.year')).match(/\b(?:19|20)\d{2}\b/);
             var rating=text(row.querySelector('.rating')).replace(',','.');
             var posterNode=row.querySelector('img');
             var img=posterNode?.getAttribute('data-src') || posterNode?.getAttribute('src') || '';
             var poster='';
             if(img) { try {var p=new URL(img,base); if(p.protocol==='https:')poster=p.href;}catch(e){} }
             values.push({
               url:u.href,title:title,poster:poster,
               year:year?year[0]:'',rating:rating,number:Number.isFinite(position)?position:0
             });
           });
           return values;
         }
         window.__kinoFranchiseParts = read(document,location.href);
         var link = document.querySelector(
           'a.b-post__franchise_link_title[href],.b-post__franchise_link_title[href]'
         );
         if (window.__kinoFranchiseParts.length>0 || !link) {
           window.__kinoFranchiseDone=true;
           return 'inline-or-none';
         }
         var endpoint;
         try {endpoint = new URL(link.getAttribute('href'),document.baseURI);}catch(e) {
           window.__kinoFranchiseDone=true;return 'invalid-url';
         }
         if(endpoint.protocol!=='https:' || endpoint.origin!==location.origin) {
           window.__kinoFranchiseDone=true;return 'foreign-url';
         }
         var controller=new AbortController();
         var abort=setTimeout(function(){controller.abort()},5700);
         fetch(endpoint.href,{credentials:'same-origin',signal:controller.signal,
           headers:{Accept:'text/html'}})
           .then(function(response){
             if(!response.ok || new URL(response.url).origin!==location.origin) return null;
             return response.text().then(function(text){return {text:text,url:response.url}});
           })
           .then(function(html){
             if(!html || html.text.length>1700000)return;
             var doc = new DOMParser().parseFromString(html.text,'text/html');
             var found = read(doc,html.url);
             if(found.length>0)window.__kinoFranchiseParts=found.slice(0,50);
           }).catch(function(){}).finally(function(){
             clearTimeout(abort);
             window.__kinoFranchiseDone=true;
           });
         return 'loading';
       })();
    """.trimIndent()

    fun parse(raw: String, source: String): FilmInfo? = runCatching {
        val data = MirrorMediaParser.objectFromWebView(raw) ?: return@runCatching null
        val factsArray = data.optJSONArray("facts")
        val facts = buildList {
            if (factsArray != null) for (i in 0 until minOf(20, factsArray.length())) {
                val row = factsArray.optJSONArray(i) ?: continue
                val key = row.optString(0).trim().take(45)
                val value = row.optString(1).trim().take(220)
                val normalized = key.lowercase()
                val interesting = listOf("дата выхода", "страна", "режисс", "жанр",
                    "время", "возраст", "в качестве", "оригинальное")
                if (interesting.any { normalized.contains(it) } &&
                    key.isNotBlank() && value.isNotBlank() && value.length < 165)
                    add(key to value)
            }
        }
        val peopleArray = data.optJSONArray("people")
        val credits = buildList {
            if (peopleArray != null) for (index in 0 until minOf(peopleArray.length(), 60)) {
                val item = peopleArray.optJSONObject(index) ?: continue
                val name = item.optString("name").trim().take(85)
                val role = item.optString("role")
                if (name.isBlank() || role !in setOf("actor", "director")) continue
                val photo = item.optString("photo").takeIf(MirrorPoster::isPhoto).orEmpty()
                val url = item.optString("url").takeIf { SourceRules.sameOrigin(source, it) }.orEmpty()
                add(FilmPerson(name, photo, url) to role)
            }
        }
        val directors = credits.filter { it.second == "director" }.map { it.first }.distinctBy { it.name }
        val people = FilmPersonRoles.actorCredits(credits, directors).take(20)
        val related = MirrorCatalog.parse(JSONObject.quote(
            JSONObject().put("cards", data.optJSONArray("related") ?: org.json.JSONArray()).toString()
        ), source)
        val partArray=data.optJSONArray("franchise")
        val items=buildList {
            if(partArray!=null)for(i in 0 until minOf(50,partArray.length())){
                val part=partArray.optJSONObject(i) ?: continue
                add(FranchisePartInput(
                    url=part.optString("url"),
                    title=part.optString("title"),
                    poster=part.optString("poster"),
                    year=part.optString("year").ifBlank { part.optString("details") },
                    rating=part.optString("rating"),
                    number=part.optInt("number",0)
                ))
            }
        }
        val parts=FranchiseParts.validate(items,source)
        FilmInfo(
            originalTitle = data.optString("originalTitle").trim().take(130),
            description = buildList {
                val candidates = data.optJSONArray("descriptions")
                if (candidates != null) for (i in 0 until minOf(candidates.length(), 12))
                    add(candidates.optString(i))
                add(data.optString("description"))
            }.map(FilmTextSanitizer::description).firstOrNull { it.isNotBlank() }.orEmpty(),
            facts = facts,
            rating = data.optString("rating").replace(Regex("\\s+"), " ").trim().take(130),
            cast = people,
            related = related,
            franchise = parts,
            franchiseResolved = data.optBoolean("franchiseDone",false),
            directors = directors,
            voices = MirrorMediaParser.parseVoices(data.optJSONArray("voices")),
            defaultVoice = data.optString("defaultVoice").takeIf {
                it.matches(Regex("[0-9]{1,12}"))
            }.orEmpty(),
            episodes = MirrorMediaParser.parseEpisodes(data.optJSONArray("episodes")),
            title = CatalogTitleCleaner.clean(data.optString("title")),
            posters = MirrorCatalog.readPosters(data),
            portraitsResolved = data.optBoolean("portraitsDone", true)
        )
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilmDetails(
    movie: CatalogMovie,
    source: String,
    sources: List<String>,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    onBrowse: (String) -> Unit,
    savedSelection: PlaybackSelection?,
    onWatch: (String, String, String, String) -> Unit,
    onRelated: (CatalogMovie) -> Unit,
    onMetadata: (CatalogMovie) -> Unit,
    onPerson: (FilmPerson) -> Unit
) {
    val context = LocalContext.current
    var info by remember(movie.url) { mutableStateOf(FilmInfoMemory.get(movie.url)) }
    var loadDone by remember(movie.url) {
        mutableStateOf(info?.let { FilmInfoReadiness.canFinish(it) && it.portraitsResolved } == true)
    }
    var loadError by remember(movie.url) { mutableStateOf("") }
    var resolvedFilmUrl by remember(movie.url) { mutableStateOf(movie.url) }
    var resolvedPlayer by remember(movie.url) { mutableStateOf<MirrorMoviePage?>(null) }
    var voiceAvailability by remember(movie.url) {
        mutableStateOf<Map<String, VoiceAvailability>>(emptyMap())
    }
    // A real HTTP session can deliver metadata even when the hidden WebView stalls.
    // It is independent of the WebView parser, but shares Android's mirror cookies.
    LaunchedEffect(movie.url) {
        val result = MirrorNetworkSource.resolveFilm(
            movie.url, sources, WebSettings.getDefaultUserAgent(context))
        if (MirrorPostId.isSameFilm(movie.url, result.finalUrl)) {
            resolvedFilmUrl = result.finalUrl
        }
        result.metadata?.player?.let { resolvedPlayer = it }
        val parsed = result.metadata?.info
        if (parsed != null && FilmInfoReadiness.hasContent(parsed)) {
            val merged = FilmInfoMerger.merge(info, parsed)
            info = merged
            FilmInfoMemory.put(movie.url, merged)
            if (FilmInfoReadiness.canFinish(merged) && merged.portraitsResolved)
                loadDone = true
        } else if (info == null && result.reason.isNotBlank()) {
            // Keep the actual HTTP failure, even while WebView retries in parallel.
            loadError = result.reason
        }
    }
    LaunchedEffect(movie.url) {
        if (!loadDone) {
            delay(9500)
            if (info == null && loadError.isBlank())
                loadError = "Зеркало не предоставило сведения о фильме."
            loadDone = true
        }
    }
    var showWatchMenu by remember(movie.url) { mutableStateOf(false) }
    var chosenVoice by remember(movie.url) { mutableStateOf("") }
    var chosenSeason by remember(movie.url) { mutableStateOf("") }
    var chosenEpisode by remember(movie.url) { mutableStateOf("") }
    val webView = if (!loadDone) remember(context, movie.url, resolvedFilmUrl) {
        val handler = Handler(Looper.getMainLooper())
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                private var scanningUrl: String? = null

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val decision = FilmNavigationPolicy.decide(resolvedFilmUrl, request.url.toString())
                    if (!decision.allow && info == null && loadError.isBlank())
                        loadError = decision.failureMessage
                    // Block unrelated navigation, but do NOT destroy the original film page.
                    return !decision.allow
                }

                private fun scan(view: WebView, pageUrl: String) {
                    if (loadDone || scanningUrl == pageUrl || !MirrorPostId.canOpen(resolvedFilmUrl, pageUrl)) return
                    scanningUrl = pageUrl
                    val timings = listOf(100L, 480L, 1200L, 2450L, 4400L, 6800L)
                    timings.forEachIndexed { index, wait ->
                        handler.postDelayed({
                            if (loadDone || view.url != pageUrl) return@postDelayed
                            view.evaluateJavascript(FilmPageParser.franchiseJavascript) {}
                            view.evaluateJavascript(FilmPageParser.javascript) { raw ->
                                if (loadDone || view.url != pageUrl) return@evaluateJavascript
                                val parsed = FilmPageParser.parse(raw, pageUrl)
                                if (parsed != null && FilmInfoReadiness.hasContent(parsed)) {
                                    val merged = FilmInfoMerger.merge(info, parsed)
                                    info = merged
                                    FilmInfoMemory.put(movie.url, merged)
                                    if (resolvedPlayer == null) {
                                        val pageId = MirrorPostId.fromUrl(pageUrl)
                                        val fallbackVoices = merged.voices
                                        val fallbackDefault = merged.defaultVoice
                                        if (pageId != null &&
                                            (fallbackVoices.isNotEmpty() || fallbackDefault.isNotBlank())) {
                                            val fallbackFlags = fallbackVoices.firstOrNull {
                                                it.id == fallbackDefault
                                            }
                                            resolvedPlayer = MirrorMoviePage(
                                                id = pageId,
                                                isSeries = merged.episodes.isNotEmpty() ||
                                                    runCatching { URI(pageUrl).path.startsWith("/series/") }
                                                        .getOrDefault(false),
                                                voices = fallbackVoices,
                                                episodes = merged.episodes,
                                                defaultVoice = fallbackDefault,
                                                defaultFlags = fallbackFlags
                                            )
                                        }
                                    }
                                    if (FilmInfoReadiness.canFinish(merged) &&
                                        merged.portraitsResolved &&
                                        (merged.cast.isNotEmpty() || merged.directors.isNotEmpty() ||
                                            index >= 4)) {
                                        loadDone = true
                                    }
                                }
                                if (index == timings.lastIndex) {
                                    if (info == null && MirrorMediaParser.objectFromWebView(raw)
                                            ?.optBoolean("browserCheck") == true) {
                                        loadError = "Зеркало запросило проверку браузера. Откройте сайт в настройках источника."
                                    }
                                    loadDone = true
                                }
                            }
                        }, wait)
                    }
                }

                override fun onPageCommitVisible(view: WebView, url: String) = scan(view, url)
                override fun onPageFinished(view: WebView, url: String) = scan(view, url)

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    if (request.isForMainFrame) {
                        if (info == null)
                            loadError = "Источник вернул HTTP ${response.statusCode}."
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        if (info == null)
                            loadError = "Не удалось загрузить страницу фильма."
                    }
                }
            }
            setTag(handler)
            loadUrl(resolvedFilmUrl)
        }
    } else null
    DisposableEffect(webView) {
        onDispose {
            (webView?.tag as? Handler)?.removeCallbacksAndMessages(null)
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    val current = info
    val sourceDefaultVoice = resolvedPlayer?.defaultVoice.orEmpty()
        .ifBlank { current?.defaultVoice.orEmpty() }
    val effectiveVoices = remember(current?.voices, resolvedPlayer?.voices, sourceDefaultVoice) {
        val values = linkedMapOf<String, MirrorVoice>()
        (resolvedPlayer?.voices.orEmpty() + current?.voices.orEmpty()).forEach {
            if (it.id.isNotBlank()) values.putIfAbsent(it.id, it)
        }
        if (sourceDefaultVoice.matches(Regex("[0-9]{1,12}")) &&
            sourceDefaultVoice !in values) {
            values[sourceDefaultVoice] = resolvedPlayer?.defaultFlags
                ?.takeIf { it.id == sourceDefaultVoice }
                ?.copy(name = "По умолчанию")
                ?: MirrorVoice(sourceDefaultVoice, "По умолчанию")
        }
        values.values.toList()
    }
    LaunchedEffect(showWatchMenu, resolvedFilmUrl, effectiveVoices, current?.episodes, resolvedPlayer) {
        if (!showWatchMenu || effectiveVoices.isEmpty()) return@LaunchedEffect
        val filmId = MirrorPostId.fromUrl(resolvedFilmUrl) ?: return@LaunchedEffect
        val episodes = current?.episodes.orEmpty().ifEmpty { resolvedPlayer?.episodes.orEmpty() }
        val isSeries = resolvedPlayer?.isSeries == true || episodes.isNotEmpty() ||
            runCatching { URI(resolvedFilmUrl).path.startsWith("/series/") }.getOrDefault(false)
        val baselineSeason = savedSelection?.season?.takeIf { saved ->
            episodes.any { it.season == saved }
        } ?: episodes.firstOrNull()?.season.orEmpty()
        val baselineEpisode = savedSelection?.episode?.takeIf { saved ->
            episodes.any { it.season == baselineSeason && it.id == saved }
        } ?: episodes.firstOrNull { it.season == baselineSeason }?.id.orEmpty()
        if (isSeries && (baselineSeason.isBlank() || baselineEpisode.isBlank()))
            return@LaunchedEffect

        val page = (resolvedPlayer ?: MirrorMoviePage(
            id = filmId,
            isSeries = isSeries,
            voices = effectiveVoices,
            episodes = episodes,
            defaultVoice = sourceDefaultVoice
        )).copy(voices = effectiveVoices, episodes = episodes)

        var statuses = effectiveVoices.filter { it.requiresSubscription }
            .associate { it.id to VoiceAvailability.SUBSCRIPTION }
        voiceAvailability = statuses
        val toProbe = effectiveVoices.filterNot { it.requiresSubscription }.take(12)
        for (chunk in toProbe.chunked(3)) {
            val checked = coroutineScope {
                chunk.map { voice ->
                    async {
                        val fields = runCatching {
                            MirrorPlaybackRequest.stream(
                                page, voice.id,
                                if (isSeries) baselineSeason else "",
                                if (isSeries) baselineEpisode else ""
                            )
                        }.getOrNull() ?: return@async voice.id to null
                        val raw = MirrorNetworkSource.playback(
                            resolvedFilmUrl, fields, WebSettings.getDefaultUserAgent(context)
                        )
                        val stream = MirrorMediaParser.parseStreamInfo(raw)
                        val availability = when {
                            stream.qualities.isNotEmpty() -> VoiceAvailability.AVAILABLE
                            else -> stream.failure?.voiceAvailability()
                        }
                        voice.id to availability
                    }
                }.awaitAll()
            }
            statuses = statuses + checked.mapNotNull { (id, status) ->
                status?.let { id to it }
            }.toMap()
            voiceAvailability = statuses
        }
    }
    val pictures = (current?.posters.orEmpty() + movie.poster + movie.posterAlternatives)
        .filter(MirrorPoster::isPhoto).distinct()
    val visibleMovie = movie.copy(
        title = current?.title.orEmpty().ifBlank { CatalogTitleCleaner.clean(movie.title, movie.details) },
        poster = pictures.firstOrNull().orEmpty(),
        posterAlternatives = pictures.drop(1)
    )
    LaunchedEffect(visibleMovie) {
        if (visibleMovie != movie) onMetadata(visibleMovie)
    }

    val primary = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 730.dp
        val tabletPosterWidth = (maxWidth * .35f).coerceIn(250.dp, 420.dp)
        if (webView != null) AndroidView(factory = { webView }, modifier = Modifier.size(2.dp).alpha(.01f))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) {
                MoviePosterImage(
                    visibleMovie,
                    Modifier.width(tabletPosterWidth).fillMaxHeight().clip(RoundedCornerShape(17.dp)),
                    ContentScale.Fit
                )
            }
            LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    if (!wide) {
                        MoviePosterImage(
                            visibleMovie,
                            Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp)),
                            ContentScale.Fit
                        )
                    }
                    Text(visibleMovie.title, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    if (!info?.originalTitle.isNullOrBlank())
                        Text(info!!.originalTitle, color = appMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    if (movie.details.isNotBlank())
                        Text(movie.details, color = appMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            chosenVoice = savedSelection?.voice?.takeIf { id ->
                                effectiveVoices.any { it.id == id }
                            } ?: sourceDefaultVoice.takeIf { id ->
                                effectiveVoices.any { it.id == id }
                            }.orEmpty().ifBlank {
                                effectiveVoices.singleOrNull()?.id.orEmpty()
                            }
                            chosenSeason = savedSelection?.season.orEmpty()
                            chosenEpisode = savedSelection?.episode.orEmpty()
                            showWatchMenu = true
                        },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Смотреть")
                        }
                        OutlinedIconButton(onClick = onToggleFavourite) {
                            Icon(if (isFavourite) Icons.Outlined.BookmarkAdded
                                else Icons.Outlined.BookmarkAdd,
                                contentDescription = if (isFavourite) "Удалить из избранного"
                                    else "В избранное", tint = appBlue)
                        }
                    }
                }
            }
            val meaningfulDescription = info?.description.orEmpty()
            if (meaningfulDescription.isNotBlank()) {
                item {
                    Text("Описание", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    Text(meaningfulDescription, color = primary.copy(alpha = .88f),
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
                            var expanded by remember(label, value) { mutableStateOf(false) }
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis
                            )
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
                            Column(Modifier.width(92.dp).clickable { onPerson(person) }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(.80f)
                                        .clip(RoundedCornerShape(12.dp)).background(appCard),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ActorPortrait(
                                        person = person,
                                        source = MirrorAccountGateway.origin(resolvedFilmUrl) ?: source,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(person.name, maxLines = 2,
                                    color = appBlue,
                                    style = MaterialTheme.typography.bodySmall,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (info?.directors?.isNotEmpty() == true) {
                item {
                    Text("Режиссёры", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(info!!.directors, key = { it.name }) { director ->
                            Column(Modifier.width(92.dp).clickable { onPerson(director) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.80f)
                                    .clip(RoundedCornerShape(12.dp)).background(appCard),
                                    contentAlignment = Alignment.Center) {
                                    ActorPortrait(
                                        director,
                                        MirrorAccountGateway.origin(resolvedFilmUrl) ?: source,
                                        Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(director.name, color = appBlue,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            val otherParts = info?.franchise.orEmpty().filterNot { MirrorPostId.isSameFilm(it.url, movie.url) }
            if (otherParts.isNotEmpty()) {
                item {
                    Text("Связанные фильмы и части франшизы", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    otherParts.forEach { part ->
                        Surface(
                            onClick = { onRelated(part) },
                            color = appPanel,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Movie, contentDescription = null,
                                    tint = appBlue, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(part.title, maxLines = 2)
                                    if (part.details.isNotBlank()) Text(part.details,
                                        color = appMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                                    tint = appMuted)
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
                                FilmPoster(related) { onRelated(related) }
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
            } else if (info == null || (loadDone && info?.description.isNullOrBlank())) {
                item {
                    Text(
                        if (info == null) loadError.ifBlank {
                            "Подробные сведения на выбранном зеркале сейчас недоступны."
                        } else "Описание не предоставлено выбранным зеркалом.",
                        color = appMuted
                    )
                    TextButton(onClick = { onBrowse(resolvedFilmUrl) }) { Text("Открыть страницу на сайте") }
                }
            }
        }
        if (showWatchMenu) {
            ModalBottomSheet(
                onDismissRequest = { showWatchMenu = false },
                containerColor = appPanel,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            ) {
                val availableVoices = effectiveVoices
                val voiceSelected = availableVoices.firstOrNull { it.id == chosenVoice }
                val pageEpisodes = current?.episodes.orEmpty().ifEmpty {
                    resolvedPlayer?.episodes.orEmpty()
                }
                val showEpisodes = voiceSelected != null && pageEpisodes.isNotEmpty()
                val seasons = if (showEpisodes) pageEpisodes.map { it.season }.distinct()
                    else emptyList()
                val currentSeason = chosenSeason.takeIf { it in seasons }
                    ?: seasons.firstOrNull().orEmpty()
                val currentEpisodes = pageEpisodes.filter { showEpisodes && it.season == currentSeason }
                val currentEpisode = chosenEpisode.takeIf { id ->
                    currentEpisodes.any { it.id == id }
                } ?: currentEpisodes.firstOrNull()?.id.orEmpty()
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Источник", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Surface(
                        color = if (resolvedPlayer != null) appBlue.copy(alpha = .18f) else appCard,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Rezka",
                                color = if (resolvedPlayer != null) appBlue else appMuted,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                if (resolvedPlayer != null) "Доступен" else "Проверяем…",
                                color = appMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Text("Озвучка", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (availableVoices.isEmpty()) {
                        Text(if (!loadDone) "Ищем озвучки…" else
                            "Зеркало не предоставило список озвучек.",
                            color = appMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            availableVoices.forEach { candidate ->
                                FilterChip(
                                    selected = chosenVoice == candidate.id,
                                    onClick = {
                                        if (chosenVoice != candidate.id) {
                                            chosenVoice = candidate.id
                                            chosenSeason = ""
                                            chosenEpisode = ""
                                        }
                                    },
                                    label = {
                                        val status = voiceAvailability[candidate.id]
                                        Text(
                                            candidate.name + when (status) {
                                                VoiceAvailability.AVAILABLE -> " · доступна"
                                                VoiceAvailability.SUBSCRIPTION -> " · подписка"
                                                VoiceAvailability.PREVIEW -> " · ролик"
                                                VoiceAvailability.UNAVAILABLE -> " · недоступна"
                                                null -> " · проверяем"
                                            },
                                            maxLines = 2
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = if (
                                            voiceAvailability[candidate.id] == VoiceAvailability.AVAILABLE
                                        ) appBlue.copy(alpha = 0.26f) else appBlue.copy(alpha = 0.14f),
                                        selectedLabelColor = appBlue
                                    )
                                )
                            }
                        }
                    }
                    if (showEpisodes) {
                        Text("Сезон", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            seasons.forEach { value ->
                                FilterChip(
                                    selected = value == currentSeason,
                                    onClick = { chosenSeason = value; chosenEpisode = "" },
                                    label = { Text("S" + value) }
                                )
                            }
                        }
                        Text("Серия", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            currentEpisodes.forEach { entry ->
                                FilterChip(
                                    selected = entry.id == currentEpisode,
                                    onClick = { chosenSeason = currentSeason; chosenEpisode = entry.id },
                                    label = { Text("E" + entry.id) }
                                )
                            }
                        }
                    }
                    if (voiceSelected?.requiresSubscription == true) {
                        Text("Для этой озвучки источник может потребовать подписку.",
                            color = appMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (voiceSelected != null) {
                                    showWatchMenu = false
                                    onWatch(resolvedFilmUrl, voiceSelected.id,
                                        if (showEpisodes) currentSeason else "",
                                        if (showEpisodes) currentEpisode else "")
                                }
                            },
                            enabled = WatchChoicePolicy.validChoice(availableVoices, chosenVoice) &&
                                voiceAvailability[chosenVoice].let { status ->
                                    status == null || status == VoiceAvailability.AVAILABLE
                                },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(5.dp))
                            Text("Смотреть")
                        }
                        OutlinedButton(
                            onClick = { showWatchMenu = false; onBrowse(resolvedFilmUrl) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("На сайте") }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        }
    }
}
