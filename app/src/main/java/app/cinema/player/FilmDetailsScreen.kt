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
    val voices: List<MirrorVoice> = emptyList()
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
           var photo=img ? absolute(img.getAttribute('data-src') || img.getAttribute('data-original') ||
                                   img.getAttribute('data-lazy-src') || img.getAttribute('data-image') ||
                                   img.getAttribute('src') || img.currentSrc) : '';
           if(!photo) {
             var visual=e.querySelector('[style*="background-image"]');
             var css=visual?.getAttribute('style') || '';
             var found=css.match(/url\((['"]?)(.*?)\1\)/);
             if(found) photo=absolute(found[2]);
           }
           var anchor=e.matches('a[href]')?e:(e.querySelector('a[href]')||e.closest('a[href]'));
           var href=anchor?.getAttribute('href') || e.getAttribute('data-href') ||
                    e.getAttribute('data-url') || '';
           var personUrl=href?absolute(href):'';
           if(!photo && personUrl && window.__kinoActorPhotos) {
             photo = window.__kinoActorPhotos[personUrl] || '';
           }
           var group=e.closest('.b-post__actors, .b-post__directors, [data-role], .persons-list-holder');
           var label=((group?.className||'')+' '+(group?.getAttribute('data-role')||'')).toLowerCase();
           var role=e.matches('.b-post__actors_item') || /actor|акт[её]р/.test(label) ?
             'actor' : /director|режисс/.test(label) ? 'director' : 'unknown';
           if(n && n.length<95) people.push({name:n,photo:photo,url:personUrl,role:role});
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
         var franchise=window.__kinoFranchiseParts || [];
         var directors=[];
         document.querySelectorAll('.b-post__infotable tr,.b-post__info tr').forEach(function(tr){
           var td=tr.querySelectorAll('td');
           if(td.length<2 || !/режисс/i.test(norm(td[0].textContent)))return;
           var links=Array.from(td[1].querySelectorAll('a[href]'));
           if(links.length){
             links.slice(0,12).forEach(function(a){
               var name=norm(a.textContent);
               if(name && name.length<90) {
                 var address=absolute(a.getAttribute('href'));
                 directors.push({name:name,url:address,
                   photo:(window.__kinoActorPhotos||{})[address]||''});
               }
             });
           }else{
             norm(td[1].textContent).split(/,|;/).slice(0,10).forEach(function(value){
               var name=norm(value);
               if(name && name.length<90)directors.push({name:name,url:'',photo:''});
             });
           }
         });
         var voices=Array.from(document.querySelectorAll(
           '#translators-list li[data-translator_id],.b-translator__item[data-translator_id]'
         )).map(function(item){
           return {id:item.getAttribute('data-translator_id'),
                   name:norm(item.getAttribute('title')||item.textContent)};
         }).filter(function(item){return item.id&&item.name}).slice(0,45);
         return JSON.stringify({
           originalTitle:txt('.b-post__origtitle, .b-post__subtitle, .b-post__title_original'),
           description:description.substring(0,1800),
           rating:txt('.b-post__rating, .b-post__rating_tables, .b-post__ratings'),
           facts:facts.slice(0,18),
           people:people.slice(0,18),
           directors:directors.slice(0,15),
           voices:voices,
           related:similar.slice(0,15),
           franchise:franchise.slice(0,50),
           franchiseDone:window.__kinoFranchiseDone === true
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
             return response.text();
           })
           .then(function(html){
             if(!html || html.length>1700000)return;
             var doc = new DOMParser().parseFromString(html,'text/html');
             var found = read(doc,endpoint.href);
             if(found.length>0)window.__kinoFranchiseParts=found.slice(0,50);
           }).catch(function(){}).finally(function(){
             clearTimeout(abort);
             window.__kinoFranchiseDone=true;
           });
         return 'loading';
       })();
    """.trimIndent()

    /**
     * The film page often lists actors as text-only links. Fetch a few same-origin actor
     * profiles within the existing browser session to find the *actual* portrait.
     * A profile without a valid image stays without a photo; no identity guessing.
     */
    val actorProfilePhotosJavascript = """
      (function() {
        if (window.__kinoActorPhotosStarted) return 'existing';
        window.__kinoActorPhotosStarted = true;
        window.__kinoActorPhotos = window.__kinoActorPhotos || {};
        var cards = Array.from(document.querySelectorAll(
          '.b-post__people_item, .b-post__actors_item, .b-post__person_item, .persons-list-holder .person-name-item'
        )).slice(0, 16);
        // Director portraits live on their own linked profile pages, not in the cast list.
        document.querySelectorAll('.b-post__infotable tr, .b-post__info tr').forEach(function(row) {
          var td=row.querySelectorAll('td');
          if(td.length>=2 && /режисс/i.test((td[0].textContent||'').trim())) {
            td[1].querySelectorAll('a[href]').forEach(function(anchor){cards.push(anchor);});
          }
        });
        cards=cards.slice(0,24);
        var targets = cards.map(function(e) {
          var a = e.matches('a[href]') ? e : (e.querySelector('a[href]') || e.closest('a[href]'));
          var href = a?.getAttribute('href') || e.getAttribute('data-href') || e.getAttribute('data-url');
          var url;
          try { url = new URL(href, document.baseURI); } catch (err) { return null; }
          if(url.origin !== location.origin) return null;
          var name = (a?.textContent || e.textContent || '').replace(/\s+/g,' ').trim().toLowerCase();
          if(!name) return null;
          return {url:url.href,name:name};
        }).filter(Boolean).filter(function(v, i, all) {
          return all.findIndex(function(a){return a.url===v.url})===i;
        });
        var active = 0;
        function valid(address) {
          try {
            var u = new URL(address, location.href);
            return u.protocol==='https:' &&
              !/(?:logo|blank|placeholder|no[-_]?photo|default[-_]?image|spacer|favicon|\/pixel)/i.test(u.pathname);
          } catch(err) { return false; }
        }
        function imageOf(node, docUrl) {
          if(!node) return '';
          var values = [node.getAttribute('data-src'),node.getAttribute('data-original'),
                        node.getAttribute('data-lazy-src'),node.getAttribute('src'),
                        node.getAttribute('content')];
          var background=node.getAttribute('style')||'';
          var css=background.match(/background-image\s*:\s*url\(\s*['"]?([^)'"]+)/i);
          if(css) values.unshift(css[1]);
          for(var n=0;n<values.length;n++) {
            if(!values[n])continue;
            try {
              var candidate=new URL(values[n],docUrl).href;
              if(valid(candidate))return candidate;
            } catch(err){}
          }
          return '';
        }
        function photoFromProfile(doc, base, personName) {
          var title=(doc.title||'').replace(/\s+/g,' ').toLowerCase();
          var normalized=personName.replace(/[ё]/g,'е');
          if(!title.replace(/[ё]/g,'е').includes(normalized))return '';
          var selectors=[
             '.b-person__photo img','.b-person__poster img','.b-person__image img',
             '.b-person__info img','.b-content__person img','.b-content__person_photo img',
             '.b-profile__photo img','[itemprop="image"]',
             '.b-post__poster img','.b-person img'
          ];
          for(var i=0;i<selectors.length;i++) {
            var photo=imageOf(doc.querySelector(selectors[i]),base);
            if(photo)return photo;
          }
          var meta=doc.querySelector('meta[property="og:image"]');
          return imageOf(meta,base);
        }
        var index=0;
        async function worker(){
          while(index<targets.length) {
            var person=targets[index++];
            if(window.__kinoActorPhotos[person.url])continue;
            var controller=new AbortController();
            var cancel=setTimeout(function(){controller.abort()},3200);
            try {
              var response=await fetch(person.url,{
                credentials:'same-origin',signal:controller.signal,
                headers:{'Accept':'text/html'}
              });
              if(!response.ok || !response.url.startsWith(location.origin))continue;
              var html=await response.text();
              if(html.length>850000)continue;
              var doc=new DOMParser().parseFromString(html,'text/html');
              var photo=photoFromProfile(doc,response.url,person.name);
              if(photo)window.__kinoActorPhotos[person.url]=photo;
            } catch(err){} finally{clearTimeout(cancel);}
          }
        }
        Promise.all([worker(),worker(),worker()]).then(function() {
          window.__kinoActorPhotosDone=true;
        });
        return 'started';
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
                val normalized = key.lowercase()
                val interesting = listOf("дата выхода", "страна", "режисс", "жанр",
                    "время", "возраст", "в качестве", "оригинальное")
                if (interesting.any { normalized.contains(it) } &&
                    key.isNotBlank() && value.isNotBlank() && value.length < 165)
                    add(key to value)
            }
        }
        val peopleArray = data.optJSONArray("people")
        val peopleCredits = buildList {
            if (peopleArray != null) for (i in 0 until minOf(peopleArray.length(), 16)) {
                val item = peopleArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val photo = item.optString("photo").takeIf(SourceRules::isValidHttpsUrl).orEmpty()
                val personUrl = item.optString("url").takeIf { url ->
                    if (!SourceRules.isValidHttpsUrl(url)) return@takeIf false
                    val host = URI(url).host?.lowercase() ?: return@takeIf false
                    val origin = URI(source).host?.lowercase() ?: return@takeIf false
                    host == origin || host.endsWith(".$origin")
                }.orEmpty()
                if (name.isNotBlank())
                    add(FilmPerson(name.take(85), photo, personUrl) to item.optString("role", "unknown"))
            }
        }.distinctBy { it.first.name.lowercase() }
        val directorArray=data.optJSONArray("directors")
        val directors=buildList {
            if(directorArray!=null)for(i in 0 until minOf(directorArray.length(),15)){
                val item=directorArray.optJSONObject(i) ?: continue
                val name=item.optString("name").trim()
                if(name.length !in 2..90)continue
                val url=item.optString("url").takeIf { value ->
                    SourceRules.isValidHttpsUrl(value) && runCatching {
                        URI(value).host.equals(URI(source).host,ignoreCase=true)
                    }.getOrDefault(false)
                }.orEmpty()
                val photo = item.optString("photo").takeIf(SourceRules::isValidHttpsUrl).orEmpty()
                add(FilmPerson(name,photo,url))
            }
        }.distinctBy { it.name }
        val people = FilmPersonRoles.actorCredits(peopleCredits, directors)
        val voiceArray=data.optJSONArray("voices")
        val voices=buildList {
            if(voiceArray!=null)for(i in 0 until minOf(voiceArray.length(),45)){
                val item=voiceArray.optJSONObject(i) ?: continue
                val id=item.optString("id")
                val name=item.optString("name").trim()
                if(id.matches(Regex("[0-9]{1,10}")) && name.length in 2..80)
                    add(MirrorVoice(id,name))
            }
        }.distinctBy { it.id }
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
            description = data.optString("description").trim().take(1800),
            facts = facts,
            rating = data.optString("rating").replace(Regex("\\s+"), " ").trim().take(130),
            cast = people,
            related = related,
            franchise = parts,
            franchiseResolved = data.optBoolean("franchiseDone",false),
            directors = directors,
            voices = voices
        )
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilmDetails(
    movie: CatalogMovie,
    source: String,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    savedVoice: String,
    onWatch: (String, String) -> Unit,
    onRelated: (CatalogMovie) -> Unit,
    onPerson: (FilmPerson) -> Unit
) {
    val context = LocalContext.current
    var info by remember(movie.url) { mutableStateOf(FilmInfoMemory.get(movie.url)) }
    // A franchise cache hit is not evidence that late-loading actor/director photos resolved.
    var loadDone by remember(movie.url) { mutableStateOf(false) }
    var showWatchMenu by remember(movie.url) { mutableStateOf(false) }
    var voiceId by remember(movie.url) { mutableStateOf(savedVoice) }
    LaunchedEffect(info?.voices) {
        val available = info?.voices.orEmpty()
        if (available.isNotEmpty() && available.none { it.id == voiceId })
            voiceId = available.first().id
    }
    val webView = if (!loadDone) remember(context, movie.url) {
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
                    // Keep this same-origin session alive briefly to resolve real actor profile portraits.
                    view.evaluateJavascript(FilmPageParser.franchiseJavascript) {}
                    view.evaluateJavascript(FilmPageParser.actorProfilePhotosJavascript) {}
                    val delays = listOf(550L, 1700L, 3400L, 6200L, 8500L)
                    delays.forEachIndexed { index, delay ->
                        handler.postDelayed({
                            if (view.url != url || loadDone) return@postDelayed
                            view.evaluateJavascript(FilmPageParser.javascript) { raw ->
                                if (loadDone) return@evaluateJavascript
                                val parsed = FilmPageParser.parse(raw, source)
                                if (parsed != null &&
                                    (parsed.description.isNotBlank() || parsed.facts.isNotEmpty() ||
                                        parsed.cast.isNotEmpty())) {
                                    val merged = if(parsed.franchise.isEmpty() &&
                                        info?.franchise?.isNotEmpty() == true)
                                        parsed.copy(franchise=info!!.franchise) else parsed
                                    if (info == null ||
                                        merged.franchise.size > (info?.franchise?.size ?: 0) ||
                                        merged.cast.count { it.photo.isNotBlank() } >=
                                            (info?.cast?.count { it.photo.isNotBlank() } ?: 0)) {
                                        info = merged
                                        FilmInfoMemory.put(movie.url, merged)
                                    }
                                }
                                if (index == delays.lastIndex || (parsed?.franchiseResolved == true &&
                                    parsed.cast.isNotEmpty() &&
                                    parsed.cast.all { it.photo.isNotBlank() || it.url.isBlank() }))
                                    loadDone = true
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 730.dp
        val tabletPosterWidth = (maxWidth * .35f).coerceIn(250.dp, 420.dp)
        if (webView != null) AndroidView(factory = { webView }, modifier = Modifier.size(2.dp).alpha(.01f))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) {
                Box(Modifier.width(tabletPosterWidth).fillMaxHeight()
                    .clip(RoundedCornerShape(17.dp)).background(appCard),
                    contentAlignment = Alignment.TopCenter) {
                    if (movie.poster.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(movie.poster)
                                .addHeader("Referer", source).build(),
                            contentDescription = movie.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Outlined.Movie, contentDescription = null,
                            tint = appMuted, modifier = Modifier.size(52.dp))
                    }
                }
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
                        Box(Modifier.fillMaxWidth().height(360.dp)
                            .clip(RoundedCornerShape(16.dp)).background(appCard),
                            contentAlignment = Alignment.Center) {
                            if (movie.poster.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(movie.poster)
                                        .addHeader("Referer", source).build(),
                                    contentDescription = movie.title, contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else Icon(Icons.Outlined.Movie, contentDescription = null,
                                tint = appMuted, modifier = Modifier.size(44.dp))
                        }
                    }
                    Text(movie.title, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    if (!info?.originalTitle.isNullOrBlank())
                        Text(info!!.originalTitle, color = appMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    if (info?.rating?.isNotBlank() == true)
                        Text(info!!.rating, color = appBlue,
                            style = MaterialTheme.typography.bodyMedium)
                    if (movie.details.isNotBlank())
                        Text(movie.details, color = appMuted,
                            style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showWatchMenu = true },
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
                                        source = source,
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
                                    ActorPortrait(director, source, Modifier.fillMaxSize())
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
            if (info?.franchise?.isNotEmpty() == true) {
                item {
                    Text("Связанные фильмы и части франшизы", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    info!!.franchise.forEach { part ->
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
        if (showWatchMenu) {
            ModalBottomSheet(
                onDismissRequest = { showWatchMenu = false },
                containerColor = appPanel,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Просмотр", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("Источник", color = appMuted,
                        style = MaterialTheme.typography.labelLarge)
                    Surface(color = appCard, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Public, contentDescription = null, tint = appBlue)
                            Spacer(Modifier.width(9.dp))
                            Text(runCatching { URI(source).host }.getOrDefault("Выбранное зеркало"),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (info?.voices?.isNotEmpty() == true) {
                        Text("Озвучка", color = appMuted,
                            style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            info!!.voices.forEach { voice ->
                                FilterChip(
                                    selected = voiceId == voice.id,
                                    onClick = { voiceId = voice.id },
                                    label = { Text(voice.name) },
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    } else {
                        Text("Озвучку можно выбрать внутри плеера после загрузки фильма.",
                            color = appMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = appMuted.copy(alpha = .15f))
                    Button(onClick = {
                        showWatchMenu = false
                        onWatch(movie.url,voiceId)
                    }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Смотреть")
                    }
                    Spacer(Modifier.height(7.dp))
                }
            }
        }
        }
    }
}
