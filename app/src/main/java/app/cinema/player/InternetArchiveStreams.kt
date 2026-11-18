package app.cinema.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Conservative legal stream provider. Metadata is checked on the provider,
 * and a real byte-range GET must return playable MP4 bytes before the option is offered.
 * Metadata API provides per-item license checks, and each MP4 candidate must
 * pass a real byte-range test. A catalog item is NOT necessarily playable.
 * Publisher-supplied public-domain claims can still be incorrect.
 */
internal object InternetArchiveStreams {
    private const val archiveId = "NightOfTheLivingDead1968-Restored"
    private const val imdbId = "tt0063350"
    private val mp4 = Regex("(?i)\\.(mp4|m4v)$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(13, TimeUnit.SECONDS)
        .build()

    data class Video(val title: String, val url: String)

    private fun verifiedMetadata(id: String): JSONObject {
        ArchivePublicCatalog.url(id) // validates the entire path component
        val request = Request.Builder()
            .url("https://archive.org/metadata/$id")
            .header("Accept", "application/json").build()
        val data = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful ||
                response.request.url.host != "archive.org")
                throw IOException("Архив недоступен: HTTP ${response.code}")
            val payload = response.peekBody(3_000_001L).string()
            if (payload.length > 3_000_000) throw IOException("Недопустимый размер метаданных")
            JSONObject(payload)
        }
        val info = data.optJSONObject("metadata")
            ?: throw IOException("Нет метаданных архивного фильма")
        val license = info.optString("licenseurl")
        if (!ArchivePublicCatalog.isDeclaredFree(license))
            throw IOException("Нет подтверждённой отметки Public Domain/CC0")
        return data
    }

    suspend fun catalog(context: Context, kind: String): List<CatalogMovie> =
        ArchivePublicCatalog.home(kind)

    fun canCheck(url: String): Boolean =
        ArchivePublicCatalog.isArchiveUrl(url) ||
            CinemetaCatalog.identity(url) == ("movie" to imdbId)

    suspend fun details(context: Context, url: String): CinemetaDetails = withContext(Dispatchers.IO) {
        val id = ArchivePublicCatalog.identifier(url)
            ?: if (CinemetaCatalog.identity(url) == ("movie" to imdbId)) archiveId
               else throw IOException("Этот фильм не относится к доступному архиву")
        val data = verifiedMetadata(id)
        val info = data.getJSONObject("metadata")
        val legacy = id == archiveId
        val title = if (legacy) "Ночь живых мертвецов" else
            Jsoup.parse(info.optString("title")).text().take(180).ifBlank { "Архивный фильм" }
        val original = if (legacy) "Night of the Living Dead" else
            Jsoup.parse(info.optString("title")).text().take(180)
        val year = info.optString("year").takeIf {
            it.matches(Regex("[0-9]{4}"))
        } ?: info.optString("date").take(4).takeIf {
            it.matches(Regex("[0-9]{4}"))
        }.orEmpty()
        val creator = info.opt("creator")
        val director = when (creator) {
            is org.json.JSONArray -> (0 until minOf(creator.length(), 12))
                .map { creator.optString(it) }.filter(String::isNotBlank)
            is String -> creator.take(200).takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            else -> emptyList()
        }
        val item = CatalogMovie(
            if (legacy) CinemetaCatalog.movieUrl("movie", imdbId) else ArchivePublicCatalog.url(id),
            title, "https://archive.org/services/img/$id",
            listOf(year, "Архивный фильм").filter(String::isNotBlank).joinToString(" · ")
        )
        CinemetaDetails(
            item = item,
            description = Jsoup.parse(info.optString("description")).text().take(6000),
            originalTitle = original,
            rating = "",
            country = "",
            runtime = "",
            genres = emptyList(),
            directors = director,
            actors = emptyList(),
            episodes = emptyList(),
            source = "Internet Archive · Public Domain/CC0 (указано загрузившим)",
            sourceUrl = "https://archive.org/details/$id"
        )
    }

    // Preserve the previous single-film identity for existing bookmarks/history.
    suspend fun details(context: Context): CinemetaDetails =
        details(context, CinemetaCatalog.movieUrl("movie", imdbId))

    suspend fun available(context: Context, url: String): List<Video> = withContext(Dispatchers.IO) {
        val id = ArchivePublicCatalog.identifier(url)
            ?: if (CinemetaCatalog.identity(url) == ("movie" to imdbId)) archiveId
               else return@withContext emptyList()
        val data = verifiedMetadata(id)
        val published = data.optJSONArray("files") ?: return@withContext emptyList()
        val availableFiles = buildList {
            for (i in 0 until published.length()) {
                val entry = published.optJSONObject(i) ?: continue
                val name = entry.optString("name")
                val size = entry.optLong("size", 0L)
                if (!mp4.containsMatchIn(name) ||
                    name.contains("preview", ignoreCase = true) ||
                    name.contains("sample", ignoreCase = true) ||
                    size !in 10_000_000L..5_000_000_000L ||
                    entry.optString("private").isNotBlank()) continue
                add(name to size)
            }
        }.distinctBy { it.first }
            .sortedBy { it.second }
            .take(4)
        coroutineScope {
            availableFiles.map { (name, size) ->
                async(Dispatchers.IO) {
                    val escaped = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val streamUrl = "https://archive.org/download/$id/$escaped"
                    if (isPlayableMp4(streamUrl)) Video("MP4 · ${size / 1_000_000} МБ", streamUrl)
                    else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun isPlayableMp4(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-63")
            .header("Accept-Encoding", "identity")
            .get().build()
        client.newCall(request).execute().use { response ->
            val host = response.request.url.host
            if (host != "archive.org" && !host.endsWith(".archive.org")) return@use false
            if (response.code != 206) return@use false
            if (response.header("Content-Range")?.startsWith("bytes 0-") != true) return@use false
            val prefix = ByteArray(16)
            val bytes = response.body?.byteStream()?.use { it.read(prefix) } ?: return@use false
            bytes >= 8 && String(prefix.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp"
        }
    }.getOrDefault(false)
}
