package app.cinema.player

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** The full public results contain artwork that autocomplete does not include. */
internal object PublicSearch {
    private const val maxBytes = 1024L * 1024L
    // The search page must use the same source cookies, network headers and
    // Android session as film metadata and player AJAX. A raw OkHttp client can
    // receive HTTP 403 on the very mirror which opened in the app's WebView.
    private val client = MirrorHttp.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun read(source: String, query: String, userAgent: String): List<CatalogMovie> =
        withContext(Dispatchers.IO) {
            val variants = (listOf(query) + SearchRelevance.alternateQuery(query)).distinct()
            for (variant in variants) {
                val cards = runCatching {
                    val url = MirrorCatalog.searchUrl(source, variant)
                    val origin = MirrorAccountGateway.origin(url) ?: return@runCatching emptyList()
                    val request = Request.Builder().url(url)
                        .header("Referer", "$origin/")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .apply {
                            MirrorSitePolicy.requestHeaders(url, userAgent).forEach { (key, value) ->
                                header(key, value)
                            }
                        }
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList()
                        response.peekBody(maxBytes + 1).use { html ->
                            if (html.contentLength() > maxBytes) return@use emptyList()
                            parse(html.string(), url)
                        }
                    }
                }.getOrDefault(emptyList())
                val matches = SearchRelevance.find(cards, query)
                // A verified film URL and title are a real result even if the
                // source omitted artwork. Poster loading has its own fallbacks.
                if (matches.isNotEmpty()) return@withContext matches
            }
            emptyList()
        }

    /**
     * Each mirror is the same site, but may be blocked independently.
     * Never infer "no results" from a 403 or a page of login/challenge HTML.
     * Only an actual title-matched list is returned to the UI.
     */
    suspend fun readWithFallback(source: String, query: String, userAgent: String): List<CatalogMovie> =
        supervisorScope {
            val urls = MirrorCatalogMirrors.candidates(source)
                .mapNotNull(MirrorCatalogMirrors::origin)
            if (urls.isEmpty()) return@supervisorScope emptyList()
            val replies = Channel<List<CatalogMovie>>(Channel.UNLIMITED)
            val workers = urls.mapIndexed { index, origin ->
                launch {
                    if (index > 0) delay(index * 900L)
                    replies.send(read(origin, query, userAgent))
                }
            }
            try {
                repeat(urls.size) {
                    val matches = replies.receive()
                    if (matches.isNotEmpty()) return@supervisorScope matches
                }
                emptyList()
            } finally {
                workers.forEach { it.cancel() }
                replies.close()
            }
        }

    internal fun parse(html: String, pageUrl: String): List<CatalogMovie> {
        val doc = Jsoup.parse(html, pageUrl)
        val results = doc.select(".b-content__search_wrapper, .b-content__inline_inner_mainprobar")
            .firstOrNull() ?: return emptyList()
        return results.select(".b-content__inline_item, .b-content__list_item").take(96)
            .mapNotNull { item ->
                val link = item.selectFirst(".b-content__inline_item-link a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href")
                if (!SourceRules.sameOrigin(pageUrl, url) || MirrorPostId.fromUrl(url) == null) return@mapNotNull null
                val details = item.selectFirst(".b-content__inline_item-link > div")?.text().orEmpty().take(110)
                val title = CatalogTitleCleaner.clean(link.text(), details).take(130)
                if (title.isBlank()) return@mapNotNull null
                val images = item.select(".b-content__inline_item-cover img").flatMap { image ->
                    listOf("data-src", "data-original", "data-lazy-src", "src").map { attribute ->
                        image.attr(attribute).takeIf(String::isNotBlank)?.let { value ->
                            runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull()
                        }.orEmpty()
                    }
                }.filter(MirrorPoster::isPhoto).distinct().take(8)
                CatalogMovie(url, title, images.firstOrNull().orEmpty(), details, images.drop(1))
            }.distinctBy { MirrorPostId.fromUrl(it.url) }
    }
}
