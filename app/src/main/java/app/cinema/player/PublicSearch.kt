package app.cinema.player

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** The full public results contain artwork that autocomplete does not include. */
internal object PublicSearch {
    private const val maxBytes = 1024L * 1024L
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun read(source: String, query: String, userAgent: String): List<CatalogMovie> =
        withContext(Dispatchers.IO) {
            val variants = (listOf(query) + SearchRelevance.alternateQuery(query)).distinct()
            for (variant in variants) {
                val cards = runCatching {
                    val url = MirrorCatalog.searchUrl(source, variant)
                    val request = Request.Builder().url(url).header("User-Agent", userAgent)
                        .header("Accept", "text/html").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList()
                        val html = response.peekBody(maxBytes + 1)
                        if (html.contentLength() > maxBytes) return@use emptyList()
                        parse(html.string(), url)
                    }
                }.getOrDefault(emptyList())
                val matches = SearchRelevance.find(cards, query)
                if (matches.any { it.poster.isNotBlank() }) return@withContext matches
            }
            emptyList()
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
