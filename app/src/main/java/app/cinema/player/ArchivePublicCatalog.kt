package app.cinema.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Genuine film discovery on Internet Archive, not a single hardcoded title.
 * Search results are only *candidates*. License and MP4 bytes are checked
 * AGAIN before displaying any Watch action; metadata does not mean playable.
 *
 * Internet Archive cautions that uploader-provided licenses are not verified.
 * This client only accepts Public Domain Mark 1.0 / CC0 1.0 claims and does
 * not assert that all listed editions are unrestricted in every jurisdiction.
 */
internal object ArchivePublicCatalog {
    private val idPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{2,99}")
    private val pd = Regex("^https?://creativecommons\\.org/publicdomain/(?:mark|zero)/1\\.0/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)
    private val query = "mediatype:movies AND collection:feature_films AND (licenseurl:http*mark* OR licenseurl:http*zero*)"
    private val badTitle = Regex("^(?:public domain movies|feature films|movie collection)$|(?:nudist|naked witch|erotic|grindhouse|sex madness)", RegexOption.IGNORE_CASE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .build()

    fun isArchiveUrl(raw: String): Boolean = identifier(raw) != null

    fun identifier(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host != "archive.org" || uri.userInfo != null ||
            uri.query != null || uri.fragment != null) return@runCatching null
        val id = uri.path.removePrefix("/details/")
        if (!uri.path.startsWith("/details/") || !idPattern.matches(id) || ".." in id)
            null else id
    }.getOrNull()

    fun url(id: String): String {
        require(idPattern.matches(id) && ".." !in id)
        return "https://archive.org/details/$id"
    }

    fun isDeclaredFree(license: String): Boolean = pd.matches(license.trim())

    // A handful of inspected records remain visible while the discovery API
    // is unavailable. They are still checked for actual MP4 on the film page.
    fun featured(): List<CatalogMovie> = listOf(
        CatalogMovie(
            CinemetaCatalog.movieUrl("movie", "tt0063350"),
            "Ночь живых мертвецов",
            "https://archive.org/services/img/NightOfTheLivingDead1968-Restored",
            "1968 · Ужасы · архивный фильм"
        ),
        CatalogMovie(
            url("Sita_Sings_the_Blues"), "Сита поёт блюз",
            "https://archive.org/services/img/Sita_Sings_the_Blues",
            "2008 · Анимация · архивный фильм"
        ),
        CatalogMovie(
            url("AsYouLikeIt1936"), "Как вам это понравится",
            "https://archive.org/services/img/AsYouLikeIt1936",
            "1936 · Драма · архивный фильм"
        )
    )

    internal fun parseSearch(data: JSONObject): List<CatalogMovie> {
        val docs = data.optJSONObject("response")?.optJSONArray("docs")
            ?: throw IOException("Internet Archive не вернул список фильмов")
        return buildList {
            val known = HashSet<String>()
            for (i in 0 until minOf(docs.length(), 80)) {
                val item = docs.optJSONObject(i) ?: continue
                val id = item.optString("identifier")
                if (!idPattern.matches(id) || ".." in id) continue
                if (!isDeclaredFree(item.optString("licenseurl"))) continue
                val name = Jsoup.parse(item.optString("title")).text().trim().take(160)
                if (name.length < 3 || badTitle.containsMatchIn(name)) continue
                val year = item.optString("year").takeIf { it.matches(Regex("[0-9]{4}")) }
                val details = listOfNotNull(year, "Архивный фильм").joinToString(" · ")
                val target = url(id)
                if (known.add(target)) add(CatalogMovie(
                    target, name, "https://archive.org/services/img/$id", details
                ))
            }
        }
    }

    @Volatile private var discoveryCache: Pair<Long, List<CatalogMovie>>? = null

    // Several keystrokes in search must not trigger one expensive Archive
    // request each. A cached index is only a discovery aid; actual MP4
    // availability is checked afresh on the film screen.
    @Synchronized
    private fun discover(): List<CatalogMovie> {
        val now = System.currentTimeMillis()
        discoveryCache?.let { (time, cards) ->
            if (now - time in 0L..300_000L) return cards
        }
        val queryUrl = HttpUrl.Builder().scheme("https").host("archive.org")
            .addPathSegment("advancedsearch.php")
            .addQueryParameter("q", query)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "licenseurl")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("rows", "55")
            .addQueryParameter("sort[]", "downloads desc")
            .addQueryParameter("output", "json")
            .build()
        val request = Request.Builder().url(queryUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "Cinema/0.4 (Android; public domain catalog)")
            .get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.request.url.host != "archive.org")
                throw IOException("Internet Archive: HTTP ${response.code}")
            val body = response.peekBody(2_000_001L).string()
            if (body.length > 2_000_000) throw IOException("Превышен размер каталога")
            return parseSearch(JSONObject(body)).also { cards ->
                discoveryCache = System.currentTimeMillis() to cards
            }
        }
    }

    suspend fun home(kind: String): List<CatalogMovie> = withContext(Dispatchers.IO) {
        if (kind == "Сериалы" || kind == "Мультфильмы") return@withContext emptyList()
        val initial = featured()
        // Propagate a network failure instead of silently replacing a healthy
        // persisted catalog with only three fallback records.
        // CinemetaGrid already preserves cached/featured cards on errors.
        val found = discover()
        (initial + found).distinctBy { it.url }.take(50)
    }

    /** Direct API probe for instrumentation; does not use baseline fallback. */
    suspend fun liveDiscovery(): List<CatalogMovie> = withContext(Dispatchers.IO) { discover() }
}
