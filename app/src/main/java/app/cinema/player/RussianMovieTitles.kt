package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Look up actual Russian labels by IMDb ID in Wikidata in ONE small batch.
 * Do not machine-invent film titles, translate names word-for-word, or block
 * the first frame of the catalog while the optional Russian labels load.
 * This layer is independent of the metadata and streaming providers.
 */
internal object RussianMovieTitles {
    private val imdb = Regex("tt[0-9]{5,12}")
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val cache = mutableMapOf<String, String?>()

    internal fun parseBindings(data: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val rows = data.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyMap()
        for (i in 0 until minOf(rows.length(), 100)) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optJSONObject("imdb")?.optString("value").orEmpty()
            val title = row.optJSONObject("title")?.optString("value").orEmpty().trim()
            if (imdb.matches(id) && title.length in 2..150) {
                out.putIfAbsent(id, title)
            }
        }
        return out
    }

    suspend fun enrich(items: List<CatalogMovie>): List<CatalogMovie> = withContext(Dispatchers.IO) {
        val ids = items.mapNotNull { CinemetaCatalog.identity(it.url)?.second }
            .filter(imdb::matches).distinct().take(40)
        val pending = synchronized(cache) { ids.filterNot(cache::containsKey) }
        if (pending.isNotEmpty()) {
            try {
                val literals = pending.joinToString(" ") { "\"$it\"" }
                val sparql = """
                    PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                    SELECT ?imdb ?title WHERE {
                      VALUES ?imdb { $literals }
                      ?item wdt:P345 ?imdb .
                      ?item rdfs:label ?title .
                      FILTER(LANG(?title) = "ru")
                    } LIMIT 100
                """.trimIndent()
                val encoded = URLEncoder.encode(sparql, "UTF-8")
                val url = "https://query.wikidata.org/sparql?format=json&query=$encoded"
                val req = Request.Builder().url(url)
                    .header("Accept", "application/sparql-results+json")
                    .header("User-Agent", "CinemaAndroid/0.4 (film Russian labels; public Wikidata)")
                    .build()
                val labels = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful || resp.request.url.host != "query.wikidata.org")
                        emptyMap()
                    else {
                        val payload = resp.peekBody(1_000_000L).string()
                        parseBindings(JSONObject(payload))
                    }
                }
                synchronized(cache) {
                    pending.forEach { cache[it] = labels[it] }
                }
            } catch (_: Exception) {
                // English original title remains visible; a translation failure
                // never turns a valid movie catalog into a loading spinner.
            }
        }
        items.map { movie ->
            val id = CinemetaCatalog.identity(movie.url)?.second
            val title = id?.let { known -> synchronized(cache) { cache[known] } }
            if (title != null && title.isNotBlank()) movie.copy(title = title)
            else movie
        }
    }
}
