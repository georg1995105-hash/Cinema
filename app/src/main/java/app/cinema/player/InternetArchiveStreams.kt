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
 * The single approved title is a verified Public Domain Mark 1.0 archive item;
 * general titles and episodes are NOT silently mapped to an unrelated video.
 */
internal object InternetArchiveStreams {
    private const val archiveId = "NightOfTheLivingDead1968-Restored"
    private const val imdbId = "tt0063350"
    private const val publicDomainMark = "creativecommons.org/publicdomain/mark/1.0/"
    private val videoFiles = listOf(
        "Notld1968Restoration-desktop.mp4" to "MP4 • высокое качество",
        "Notld1968Restoration-desktop_512kb.mp4" to "MP4 • экономный вариант"
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(13, TimeUnit.SECONDS)
        .build()

    data class Video(val title: String, val url: String)

    private fun verifiedMetadata(): JSONObject {
        val request = Request.Builder()
            .url("https://archive.org/metadata/$archiveId")
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
        if (!license.startsWith("https://$publicDomainMark") &&
            !license.startsWith("http://$publicDomainMark"))
            throw IOException("Не подтверждена лицензия на свободный просмотр")
        return data
    }

    /** A short, verified playable-content tab; it must not pretend to be a
     * general catalogue of commercial films, or offer a dead Watch button. */
    suspend fun catalog(context: Context, kind: String): List<CatalogMovie> = withContext(Dispatchers.IO) {
        if (kind == "Сериалы" || kind == "Мультфильмы") return@withContext emptyList()
        val data = verifiedMetadata()
        val files = data.optJSONArray("files") ?: return@withContext emptyList()
        var hasPublishedMp4 = false
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            if (entry.optString("name") in videoFiles.map { it.first } &&
                entry.optLong("size") > 10_000_000 && entry.optString("private").isBlank()) {
                hasPublishedMp4 = true
                break
            }
        }
        if (!hasPublishedMp4) return@withContext emptyList()
        listOf(CatalogMovie(
            CinemetaCatalog.movieUrl("movie", imdbId),
            "Ночь живых мертвецов",
            "https://archive.org/services/img/$archiveId",
            "1968 · США · Ужасы · свободный просмотр"
        ))
    }

    suspend fun details(context: Context): CinemetaDetails = withContext(Dispatchers.IO) {
        val data = verifiedMetadata()
        val info = data.getJSONObject("metadata")
        val item = CatalogMovie(
            CinemetaCatalog.movieUrl("movie", imdbId),
            "Ночь живых мертвецов",
            "https://archive.org/services/img/$archiveId",
            "1968 · США · Ужасы · свободный просмотр"
        )
        CinemetaDetails(
            item = item,
            description = Jsoup.parse(info.optString("description")).text().take(5000),
            originalTitle = "Night of the Living Dead",
            rating = "",
            country = "США",
            runtime = "",
            genres = listOf("Ужасы"),
            directors = emptyList(),
            actors = emptyList(),
            episodes = emptyList(),
            source = "Internet Archive · Public Domain Mark 1.0",
            sourceUrl = "https://archive.org/details/$archiveId"
        )
    }

    suspend fun available(context: Context, url: String): List<Video> = withContext(Dispatchers.IO) {
        val identity = CinemetaCatalog.identity(url)
        if (identity != ("movie" to imdbId)) return@withContext emptyList()
        val data = verifiedMetadata()
        val published = data.optJSONArray("files") ?: return@withContext emptyList()
        val availableFiles = mutableSetOf<String>()
        for (i in 0 until published.length()) {
            val entry = published.optJSONObject(i) ?: continue
            if (entry.optLong("size") > 10_000_000 &&
                entry.optString("private").isBlank()) {
                availableFiles.add(entry.optString("name"))
            }
        }
        // Both verified MP4 variants are probed concurrently. A slow or
        // unavailable variant must not block showing an already playable one.
        coroutineScope {
            videoFiles.filter { it.first in availableFiles }.map { (name, label) ->
                async(Dispatchers.IO) {
                    val escaped = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val streamUrl = "https://archive.org/download/$archiveId/$escaped"
                    if (isPlayableMp4(streamUrl)) Video(label, streamUrl) else null
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
