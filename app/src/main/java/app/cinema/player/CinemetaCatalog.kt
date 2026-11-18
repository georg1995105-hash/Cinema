package app.cinema.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Independent metadata provider. Cinemeta is not a stream provider.
 * No HTML mirrors, WebView, cookies or third-party login are used here.
 * Only the public Stremio/Cinemeta catalog and meta resources are requested.
 */
internal object CinemetaCatalog {
    private const val origin = "https://v3-cinemeta.strem.io"
    private val idPattern = Regex("tt[0-9]{5,12}")
    private val cache = LinkedHashMap<String, Pair<Long, String>>()
    private var client: OkHttpClient? = null

    // Small app-private snapshots make the catalogue usable on cold start
    // when public APIs are slow or temporarily unreachable. No credentials,
    // site mirrors or cookies are ever persisted in these files.
    private fun snapshotFile(context: Context, section: String, kind: String, query: String): File {
        val key = "$section|$kind|$query"
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.applicationContext.filesDir, "cinema_catalog_snapshots"), "$hex.json")
    }

    suspend fun cachedCards(
        context: Context, section: String, kind: String, query: String
    ): List<CatalogMovie> = withContext(Dispatchers.IO) {
        runCatching {
            val file = snapshotFile(context, section, kind, query)
            if (!file.isFile || file.length() !in 2L..250_000L) return@runCatching emptyList()
            val data = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until minOf(100, data.length())) {
                    val item = data.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    val title = item.optString("title").trim().take(180)
                    if ((!isMovieUrl(url) && !ArchivePublicCatalog.isArchiveUrl(url) && !MosfilmCatalog.isVideoUrl(url)) || title.isBlank()) continue
                    val poster = item.optString("poster").takeIf(MirrorPoster::isPhoto).orEmpty()
                    add(CatalogMovie(url, title, poster, item.optString("details").take(180)))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveCachedCards(
        context: Context, section: String, kind: String, query: String,
        movies: List<CatalogMovie>
    ) = withContext(Dispatchers.IO) {
        if (movies.isEmpty()) return@withContext
        runCatching {
            val file = snapshotFile(context, section, kind, query)
            file.parentFile?.mkdirs()
            val json = JSONArray()
            movies.take(100).forEach { item ->
                json.put(JSONObject().put("url", item.url).put("title", item.title)
                    .put("poster", item.poster).put("details", item.details))
            }
            val text = json.toString()
            if (text.length > 250_000) return@runCatching
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
            // Keep the snapshot store bounded.
            val files = file.parentFile?.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending(File::lastModified).orEmpty()
            files.drop(40).forEach(File::delete)
        }
    }

    @Synchronized
    private fun http(context: Context): OkHttpClient {
        return client ?: OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "cinema_metadata_http"), 32L * 1024 * 1024))
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .build().also { client = it }
    }

    /** This path also serves as a durable, valid-HTTPS identity for local favourites/history. */
    fun movieUrl(type: String, id: String): String =
        "$origin/meta/${if (type == "series") "series" else "movie"}/$id.json"

    fun isMovieUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "v3-cinemeta.strem.io" &&
            Regex("^/meta/(movie|series)/tt[0-9]{5,12}\\.json$").matches(uri.path)
    }.getOrDefault(false)

    fun identity(value: String): Pair<String, String>? {
        if (!isMovieUrl(value)) return null
        val parts = URI(value).path.split('/')
        return parts[2] to parts[3].removeSuffix(".json")
    }

    private fun text(data: JSONObject, key: String) =
        data.optString(key).takeIf { it != "null" }.orEmpty().trim()

    private fun names(array: JSONArray?): List<String> = buildList {
        if (array != null) for (i in 0 until minOf(array.length(), 24)) {
            val name = array.optString(i).trim()
            if (name.isNotBlank()) add(name)
        }
    }

    private val localizedGenre = mapOf(
        "Action" to "Боевик", "Adventure" to "Приключения", "Animation" to "Мультфильм",
        "Biography" to "Биография", "Comedy" to "Комедия", "Crime" to "Криминал",
        "Documentary" to "Документальный", "Drama" to "Драма", "Family" to "Семейный",
        "Fantasy" to "Фэнтези", "History" to "История", "Horror" to "Ужасы",
        "Mystery" to "Детектив", "Romance" to "Мелодрама", "Sci-Fi" to "Фантастика",
        "Sport" to "Спорт", "Thriller" to "Триллер", "War" to "Военный",
        "Western" to "Вестерн"
    )
    internal fun localizeGenre(genre: String): String = localizedGenre[genre] ?: genre

    internal fun localizeCountry(country: String): String =
        country.split(',').joinToString(", ") { item ->
            when (item.trim()) {
                "Russia", "Russian Federation" -> "Россия"
                "United States", "USA" -> "США"
                "United Kingdom", "UK" -> "Великобритания"
                "Canada" -> "Канада"
                "New Zealand" -> "Новая Зеландия"
                "Iceland" -> "Исландия"
                "Germany" -> "Германия"
                "France" -> "Франция"
                "Japan" -> "Япония"
                "South Korea" -> "Южная Корея"
                "Spain" -> "Испания"
                "Italy" -> "Италия"
                else -> item.trim()
            }
        }

    private fun detailsText(data: JSONObject): String {
        val year = text(data, "year").ifBlank { text(data, "releaseInfo") }
        val country = localizeCountry(text(data, "country"))
        val genres = names(data.optJSONArray("genres") ?: data.optJSONArray("genre"))
            .take(3).map { localizedGenre[it] ?: it }
        return (listOf(year, country) + genres).filter { it.isNotBlank() }.joinToString(" · ").take(180)
    }

    fun parseMovie(data: JSONObject): CatalogMovie? {
        val type = text(data, "type").takeIf { it in listOf("movie", "series") } ?: return null
        val id = text(data, "id").ifBlank { text(data, "imdb_id") }
        if (!idPattern.matches(id)) return null
        val title = when (id) {
            "tt0337898" -> "Бригада"
            "tt1196946" -> "Менталист"
            else -> text(data, "name").ifBlank { text(data, "title") }
        }.take(180)
        if (title.isBlank()) return null
        val poster = text(data, "poster").takeIf(MirrorPoster::isPhoto).orEmpty()
        return CatalogMovie(movieUrl(type, id), title, poster, detailsText(data))
    }

    private fun read(context: Context, path: String): JSONObject {
        require(path.startsWith("/catalog/") || path.startsWith("/meta/"))
        val url = "$origin$path"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[url]?.let { (at, payload) ->
                if (now - at < 15 * 60_000L) return JSONObject(payload)
            }
        }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Cinema/0.4 (Android; catalog client)")
            .get().build()
        val response = http(context).newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("Каталог ответил HTTP ${it.code}")
            if (it.request.url.host != "v3-cinemeta.strem.io" &&
                it.request.url.host != "cinemeta-catalogs.strem.io") {
                throw IOException("Неожиданный сервер каталога")
            }
            val body = it.body ?: throw IOException("Пустой ответ каталога")
            val payload = body.string()
            if (payload.length > 4_000_000) throw IOException("Слишком большой ответ каталога")
            val json = try { JSONObject(payload) }
                       catch (_: Exception) { throw IOException("Некорректные данные каталога") }
            synchronized(cache) {
                cache[url] = now to payload
                while (cache.size > 32) cache.remove(cache.keys.first())
            }
            return json
        }
    }

    fun parseList(json: JSONObject): List<CatalogMovie> {
        val array = json.optJSONArray("metas") ?: throw IOException("Отсутствует список фильмов")
        return buildList {
            val seen = HashSet<String>()
            for (index in 0 until minOf(array.length(), 75)) {
                val item = array.optJSONObject(index)?.let(::parseMovie) ?: continue
                if (seen.add(item.url)) add(item)
            }
        }
    }

    suspend fun home(context: Context, tab: String, kind: String): List<CatalogMovie> = coroutineScope {
        if (tab == "Мосфильм") return@coroutineScope MosfilmCatalog.catalog(kind)
        if (tab == "Архив") return@coroutineScope ArchivePublicCatalog.home(kind)
        if (tab == "Смотреть") {
            val archive = try { ArchivePublicCatalog.home(kind) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (kind == "Сериалы" || kind == "Мультфильмы") emptyList()
                    else ArchivePublicCatalog.featured()
                }
            return@coroutineScope SourceBackedCatalog.merge(
                MosfilmCatalog.catalog(kind), archive
            )
        }
        val types = if (kind == "Фильмы") listOf("movie")
                    else if (kind == "Сериалы") listOf("series")
                    else listOf("movie", "series")
        // Independent requests run in parallel; two slow 14s calls must never
        // make the user wait for 28s. One failing category does not hide another.
        val responses = types.map { type ->
            async(Dispatchers.IO) {
                val catalogPath = when (tab) {
                    "Новинки" -> "/catalog/$type/year/genre=2026.json"
                    "Высокий рейтинг" -> "/catalog/$type/imdbRating.json"
                    else -> "/catalog/$type/top.json"
                }
                try {
                    Pair(parseList(read(context, catalogPath)), null as Exception?)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Pair(emptyList<CatalogMovie>(), e)
                }
            }
        }.awaitAll()
        val result = responses.flatMap { it.first }
        if (result.isEmpty() && responses.all { it.second != null }) {
            throw IOException(responses.firstNotNullOfOrNull { it.second?.message } ?: "Каталог недоступен")
        }
        val filtered = if (kind == "Мультфильмы") result.filter {
            it.details.contains("Мультфильм", ignoreCase = true)
        } else result
        filtered.distinctBy { it.url }
    }

    suspend fun search(context: Context, query: String, kind: String): List<CatalogMovie> = withContext(Dispatchers.IO) {
        val q = query.trim().take(110)
        if (q.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val types = when (kind) {
            "Фильмы" -> listOf("movie")
            "Сериалы" -> listOf("series")
            else -> listOf("series", "movie")
        }
        // Search film and series concurrently. Previously, the series request
        // had to time out before films could even begin loading.
        val responses = coroutineScope {
            types.map { type ->
                async(Dispatchers.IO) {
                    try {
                        Pair(parseList(read(context, "/catalog/$type/top/search=$encoded.json")), null as Exception?)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Pair(emptyList<CatalogMovie>(), e)
                    }
                }
            }.awaitAll()
        }
        val items = (MosfilmCatalog.search(q, kind) + responses.flatMap { it.first })
            .distinctBy { it.url }.toMutableList()
        val seriesFound = items.any { identity(it.url)?.first == "series" }
        var successes = responses.count { it.second == null }
        var failure = responses.firstNotNullOfOrNull { it.second }
        if ("series" in types && !seriesFound) {
            try {
                items.addAll(TvMazeSeries.search(q))
                successes++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        if (successes == 0 && items.isEmpty()) throw IOException(failure?.message ?: "Поиск недоступен")
        items.distinctBy { it.url }.let { found ->
            if (kind == "Мультфильмы")
                found.filter { it.details.contains("Мультфильм", ignoreCase = true) }
            else found
        }
    }

    suspend fun metadata(context: Context, url: String): CinemetaDetails = withContext(Dispatchers.IO) {
        if (MosfilmCatalog.isVideoUrl(url))
            return@withContext MosfilmCatalog.metadata(url)
        if (ArchivePublicCatalog.isArchiveUrl(url))
            return@withContext InternetArchiveStreams.details(context, url)
        val (type, id) = identity(url) ?: throw IOException("Неверный идентификатор фильма")
        if (type == "movie" && id == "tt0063350")
            return@withContext InternetArchiveStreams.details(context)
        try {
            parseDetails(read(context, "/meta/$type/$id.json"), type, id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (type != "series") throw e
            // Works without access to Stremio/Cinemeta, retaining stable IMDb URLs.
            TvMazeSeries.metadata(id)
        }
    }

    fun parseDetails(json: JSONObject, type: String, id: String): CinemetaDetails {
        val meta = json.optJSONObject("meta") ?: throw IOException("Карточка фильма отсутствует")
        val item = parseMovie(meta) ?: throw IOException("Неверная карточка фильма")
        if (identity(item.url) != (type to id)) {
            throw IOException("Несоответствие данных фильма")
        }
        val episodes = buildList {
            val array = meta.optJSONArray("videos")
            if (array != null && type == "series") for (i in 0 until minOf(array.length(), 1200)) {
                val video = array.optJSONObject(i) ?: continue
                val season = video.optInt("season")
                val episode = video.optInt("episode")
                if (season > 0 && episode > 0) add(CinemetaEpisode(
                    season, episode, text(video, "title").ifBlank { "Серия $episode" },
                    text(video, "released")
                ))
            }
        }.distinctBy { it.season to it.episode }
        return CinemetaDetails(
            item = item, description = text(meta, "description"),
            originalTitle = text(meta, "name"),
            rating = text(meta, "imdbRating"),
            country = localizeCountry(text(meta, "country")),
            runtime = text(meta, "runtime").replace("min", "мин").replace("minutes", "мин"),
            genres = names(meta.optJSONArray("genres") ?: meta.optJSONArray("genre")).map(::localizeGenre),
            directors = names(meta.optJSONArray("director")),
            actors = names(meta.optJSONArray("cast")),
            episodes = episodes
        )
    }
}

internal data class CinemetaEpisode(val season: Int, val episode: Int, val title: String, val released: String)
internal data class CinemetaDetails(
    val item: CatalogMovie, val description: String, val originalTitle: String,
    val rating: String, val country: String, val runtime: String,
    val genres: List<String>, val directors: List<String>,
    val actors: List<String>, val episodes: List<CinemetaEpisode>,
    val source: String = "", val sourceUrl: String = ""
)
