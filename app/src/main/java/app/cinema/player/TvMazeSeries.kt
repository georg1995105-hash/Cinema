package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TVmaze's free public API: https://www.tvmaze.com/api
 * Metadata is CC BY-SA 4.0; attribute TVmaze and preserve share-alike terms.
 * This is an independent fallback for SERIES metadata, never a stream source.
 * No keys, mirror addresses, cookies or account information are sent.
 */
internal object TvMazeSeries {
    private const val host = "api.tvmaze.com"
    const val attributionUrl = "https://www.tvmaze.com/api"
    private val imdbId = Regex("tt[0-9]{5,12}")
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun get(path: String): String {
        require(path.startsWith("/search/shows?") || path.startsWith("/lookup/shows?") ||
            Regex("^/shows/[0-9]+/(episodes|cast|crew)$").matches(path))
        client.newCall(Request.Builder().url("https://$host$path")
            .header("Accept", "application/json")
            .header("User-Agent", "Cinema/0.4 (TVmaze metadata client)")
            .build()).execute().use { response ->
            if (response.request.url.host != host || response.request.url.scheme != "https")
                throw IOException("Неожиданный сервер TVmaze")
            if (!response.isSuccessful) throw IOException("TVmaze: HTTP ${response.code}")
            val body = response.peekBody(2L * 1024 * 1024 + 1).string()
            if (body.length > 2_000_000) throw IOException("Слишком большой ответ TVmaze")
            return body
        }
    }

    internal fun parseSearch(json: JSONArray): List<CatalogMovie> = buildList {
        val seen = HashSet<String>()
        for (index in 0 until minOf(json.length(), 25)) {
            val show = json.optJSONObject(index)?.optJSONObject("show") ?: continue
            val imdb = show.optJSONObject("externals")?.optString("imdb").orEmpty()
            if (!imdbId.matches(imdb)) continue
            val name = show.optString("name").trim().take(150)
            if (name.isBlank()) continue
            val url = CinemetaCatalog.movieUrl("series", imdb)
            if (!seen.add(url)) continue
            val image = show.optJSONObject("image")
            val poster = (image?.optString("original").orEmpty()
                .takeIf(MirrorPoster::isPhoto)
                ?: image?.optString("medium").orEmpty()
                    .takeIf(MirrorPoster::isPhoto)).orEmpty()
            val premiere = show.optString("premiered").take(4)
            val genres = show.optJSONArray("genres")
            val details = buildList {
                if (premiere.matches(Regex("[0-9]{4}"))) add(premiere)
                if (genres != null) for (i in 0 until minOf(genres.length(), 3)) {
                    val genre = genres.optString(i)
                    if (genre.isNotBlank()) add(CinemetaCatalog.localizeGenre(genre))
                }
            }.joinToString(" · ")
            add(CatalogMovie(url, name, poster, details))
        }
    }

    suspend fun search(query: String): List<CatalogMovie> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim().take(110), "UTF-8")
        parseSearch(JSONArray(get("/search/shows?q=$encoded")))
    }

    suspend fun metadata(imdb: String): CinemetaDetails = withContext(Dispatchers.IO) {
        require(imdbId.matches(imdb))
        // Lookup redirects to the matching series. The final host is checked in get().
        val show = JSONObject(get("/lookup/shows?imdb=$imdb"))
        val confirmed = show.optJSONObject("externals")?.optString("imdb").orEmpty()
        if (confirmed != imdb) throw IOException("TVmaze не подтвердил IMDb-идентификатор")
        val showId = show.optInt("id")
        if (showId <= 0) throw IOException("Неизвестный сериал TVmaze")
        val image = show.optJSONObject("image")
        val poster = (image?.optString("original").orEmpty()
            .takeIf(MirrorPoster::isPhoto) ?: image?.optString("medium").orEmpty()
                .takeIf(MirrorPoster::isPhoto)).orEmpty()
        val name = show.optString("name").take(150)
        if (name.isBlank()) throw IOException("TVmaze вернул сериал без названия")
        val premiere = show.optString("premiered").take(4)
        val genres = show.optJSONArray("genres").let { arr ->
            if (arr == null) emptyList() else (0 until minOf(16, arr.length()))
                .map { arr.optString(it) }.filter(String::isNotBlank)
                .map(CinemetaCatalog::localizeGenre)
        }
        // Episodes, cast and crew are independent resources. Requesting them
        // sequentially previously tripled worst-case wait on poor networks.
        val (eps, cast, crew) = coroutineScope {
            val episodesJob = async(Dispatchers.IO) {
                JSONArray(get("/shows/$showId/episodes"))
            }
            val castJob = async(Dispatchers.IO) {
                runCatching { JSONArray(get("/shows/$showId/cast")) }.getOrElse { JSONArray() }
            }
            val crewJob = async(Dispatchers.IO) {
                runCatching { JSONArray(get("/shows/$showId/crew")) }.getOrElse { JSONArray() }
            }
            Triple(episodesJob.await(), castJob.await(), crewJob.await())
        }
        val episodes = buildList {
            for (i in 0 until minOf(1000, eps.length())) {
                val ep = eps.optJSONObject(i) ?: continue
                val season = ep.optInt("season")
                val episode = ep.optInt("number")
                if (season <= 0 || episode <= 0) continue
                add(CinemetaEpisode(season, episode,
                    ep.optString("name").take(180).ifBlank { "Серия $episode" },
                    ep.optString("airdate")))
            }
        }.distinctBy { it.season to it.episode }

        val actors = buildList {
            for (i in 0 until minOf(24, cast.length())) {
                val value = cast.optJSONObject(i)?.optJSONObject("person")
                    ?.optString("name").orEmpty().trim()
                if (value.isNotBlank() && value !in this) add(value)
            }
        }
        val directors = buildList {
            for (i in 0 until minOf(48, crew.length())) {
                val member = crew.optJSONObject(i) ?: continue
                if (member.optString("type") != "Director") continue
                val value = member.optJSONObject("person")?.optString("name").orEmpty().trim()
                if (value.isNotBlank() && value !in this) add(value)
            }
        }
        val summary = Jsoup.parse(show.optString("summary")).text().take(5000)
        val networkCountry = show.optJSONObject("network")?.optJSONObject("country")
            ?.optString("name").orEmpty()
        val streamingCountry = show.optJSONObject("webChannel")?.optJSONObject("country")
            ?.optString("name").orEmpty()
        val sourceUrl = show.optString("url").takeIf {
            it.startsWith("https://www.tvmaze.com/shows/")
        } ?: attributionUrl
        val movieUrl = CinemetaCatalog.movieUrl("series", imdb)
        CinemetaDetails(
            item = CatalogMovie(movieUrl, name, poster,
                (listOf(premiere) + genres.take(3)).filter(String::isNotBlank).joinToString(" · ")),
            description = summary,
            originalTitle = name,
            rating = show.optJSONObject("rating")?.optString("average").orEmpty().let {
                if (it == "null") "" else it
            },
            country = CinemetaCatalog.localizeCountry(networkCountry.ifBlank { streamingCountry }),
            runtime = show.optString("runtime").takeIf { it != "null" }?.let { "$it мин" }.orEmpty(),
            genres = genres,
            directors = directors,
            actors = actors,
            episodes = episodes,
            source = "TVmaze · CC BY-SA 4.0",
            sourceUrl = sourceUrl
        )
    }
}
