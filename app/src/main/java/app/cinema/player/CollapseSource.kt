package app.cinema.player

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class CollapseSubtitle(val url: String, val name: String)

internal data class CollapseStream(
    val hls: String = "",
    val dash: String = "",
    val audioNames: List<String> = emptyList(),
    val audioOrder: List<Int> = emptyList(),
    val subtitles: List<CollapseSubtitle> = emptyList()
)

internal data class CollapseEpisode(
    val season: Int,
    val episode: String,
    val id: Long,
    val videoKey: Long?,
    val stream: CollapseStream
)

internal data class CollapseMedia(
    val id: Long,
    val title: String,
    val poster: String,
    val source: CollapseStream?,
    val episodes: List<CollapseEpisode>
) {
    fun playable(): Boolean = source?.let { it.hls.isNotBlank() || it.dash.isNotBlank() } == true ||
        episodes.any { it.stream.hls.isNotBlank() || it.stream.dash.isNotBlank() }

    fun episode(season: String, episode: String): CollapseEpisode? {
        val s = season.toIntOrNull()
        return episodes.firstOrNull { it.season == s && it.episode == episode }
            ?: episodes.firstOrNull { it.season == s }
            ?: episodes.firstOrNull()
    }

    fun stream(season: String, episode: String): CollapseStream? =
        if (episodes.isEmpty()) source else episode(season, episode)?.stream

    fun voices(season: String = "", episode: String = ""): List<MirrorVoice> {
        val names = stream(season, episode)?.audioNames.orEmpty()
        if (names.isEmpty()) {
            val available = stream(season, episode)
            return if (available != null && (available.hls.isNotBlank() || available.dash.isNotBlank()))
                listOf(MirrorVoice("track:0", "Standard")) else emptyList()
        }
        return names.mapIndexedNotNull { index, raw ->
            val name = raw.trim().take(80)
            name.takeIf(String::isNotBlank)?.let { MirrorVoice("track:$index", it) }
        }
    }

    fun mirrorEpisodes(): List<MirrorEpisode> = episodes.map {
        MirrorEpisode(
            it.season.toString(),
            it.episode,
            it.episode.toIntOrNull()?.let { number -> "Серия $number" } ?: it.episode
        )
    }.distinctBy { it.season to it.id }
}

internal data class CollapseResult(
    val media: CollapseMedia? = null,
    val reason: String = "",
    val endpoint: String = ""
)

internal object ExternalFilmIdResolver {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun imdb(
        title: String,
        originalTitle: String,
        details: String,
        userAgent: String
    ): String = withContext(Dispatchers.IO) {
        val year = Regex("""\b(?:19|20)\d{2}\b""").find(details)?.value.orEmpty()
        val queries = listOf(originalTitle, title)
            .map(String::trim).filter(String::isNotBlank).distinct()
        for (query in queries) {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val url = "https://v2.sg.media-imdb.com/suggestion/t/$encoded.json"
            val id = runCatching {
                val request = Request.Builder().url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Referer", "https://www.imdb.com/")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use ""
                    val body = response.body?.string().orEmpty()
                    if (body.length > 900_000) return@use ""
                    chooseImdb(body, query, year)
                }
            }.getOrDefault("")
            if (id.isNotBlank()) return@withContext id
        }
        ""
    }

    internal fun chooseImdb(raw: String, query: String, year: String): String = runCatching {
        val rows = JSONObject(raw).optJSONArray("d") ?: return@runCatching ""
        val target = query.trim().lowercase()
        var bestId = ""
        var bestScore = Int.MIN_VALUE
        for (i in 0 until minOf(rows.length(), 20)) {
            val item = rows.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (!id.matches(Regex("tt[0-9]{5,12}", RegexOption.IGNORE_CASE))) continue
            val label = item.optString("l").trim().lowercase()
            val itemYear = item.optInt("y", 0).takeIf { it > 0 }?.toString().orEmpty()
            val titleScore = when {
                label == target -> 8
                label.length >= 4 && target.length >= 4 &&
                    (label.contains(target) || target.contains(label)) -> 4
                else -> 0
            }
            val yearMatches = year.isBlank() || (itemYear.isNotBlank() && itemYear == year)
            if (titleScore == 0 || !yearMatches) continue
            var score = titleScore
            if (year.isNotBlank()) score += 5
            val type = item.optString("q").lowercase()
            if ("feature" in type || "movie" in type || "tv" in type) score += 1
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        val threshold = if (year.isNotBlank()) 9 else 8
        bestId.takeIf { bestScore >= threshold }.orEmpty()
    }.getOrDefault("")
}

internal object CollapseSource {
    private data class Cache(val at: Long, val result: CollapseResult)

    private val cache = ConcurrentHashMap<String, Cache>()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        kinopoiskId: String,
        imdbId: String,
        userAgent: String
    ): CollapseResult = withContext(Dispatchers.IO) {
        val kp = Regex("""\d+""").find(kinopoiskId)?.value.orEmpty()
        val imdb = Regex("""tt\d{5,12}""", RegexOption.IGNORE_CASE)
            .find(imdbId)?.value.orEmpty()
        val key = "$kp|$imdb"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000L }
            ?.let { return@withContext it.result }

        val endpoints = buildList {
            if (kp.isNotBlank()) add("https://api.domem.ws/embed/kp/$kp")
            if (imdb.isNotBlank()) add("https://api.domem.ws/embed/imdb/$imdb")
        }
        if (endpoints.isEmpty())
            return@withContext CollapseResult(reason = "Нет IMDb/Кинопоиск ID.")

        var lastReason = "Collapse не предоставил поток."
        for (endpoint in endpoints) {
            val result = runCatching {
                val request = Request.Builder().url(endpoint)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body
                    val body = if (responseBody == null) "" else boundedText(
                        responseBody.byteStream(), 2_200_000
                    ) ?: return@use CollapseResult(
                        reason = "Collapse вернул слишком большой ответ.",
                        endpoint = endpoint
                    )
                    responseFailure(response.code, body, endpoint)?.let { return@use it }
                    val media = parseHtml(body)
                    if (media?.playable() == true)
                        CollapseResult(media = media, endpoint = endpoint)
                    else CollapseResult(
                        reason = "Collapse не предоставил доступный HLS/DASH.",
                        endpoint = endpoint
                    )
                }
            }.getOrElse {
                CollapseResult(reason = "Collapse не ответил.", endpoint = endpoint)
            }
            if (result.media != null) {
                cache[key] = Cache(System.currentTimeMillis(), result)
                return@withContext result
            }
            lastReason = result.reason
        }
        CollapseResult(reason = lastReason)
    }

    /** Reads at most maxBytes + 1 so an error page cannot cause an unbounded allocation. */
    internal fun boundedText(input: InputStream, maxBytes: Int): String? {
        val output = ByteArrayOutputStream(minOf(maxBytes + 1, 64 * 1024))
        val buffer = ByteArray(8192)
        var remaining = maxBytes + 1
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        val bytes = output.toByteArray()
        if (bytes.size > maxBytes) return null
        return String(bytes, Charsets.UTF_8)
    }

    internal fun responseFailure(code: Int, body: String, endpoint: String): CollapseResult? {
        val lower = body.lowercase()
        if ("видео недоступно для вашего региона" in lower ||
            "video is not available for your region" in lower) {
            return CollapseResult(
                reason = "Collapse недоступен в текущем регионе.", endpoint = endpoint)
        }
        if (code !in 200..299) return CollapseResult(
            reason = "Collapse: HTTP $code.", endpoint = endpoint)
        return null
    }

    internal fun parseHtml(html: String): CollapseMedia? {
        val marker = html.indexOf("makePlayer(", ignoreCase = true)
        if (marker < 0) return null
        val objectStart = html.indexOf('{', marker)
        if (objectStart < 0) return null
        val config = balanced(html, objectStart) ?: return null
        val id = scalar(config, "id")?.filter(Char::isDigit)?.toLongOrNull() ?: 0L
        val title = scalar(config, "title").orEmpty()
        val poster = scalar(config, "poster").orEmpty()
        val source = objectValue(config, "source")?.let(::parseStream)
        val playlist = objectValue(config, "playlist")
        val episodes = playlist?.let(::parsePlaylist).orEmpty()
        return CollapseMedia(id, title, poster, source, episodes)
    }

    private fun parsePlaylist(raw: String): List<CollapseEpisode> {
        val seasonsRaw = arrayValue(raw, "seasons") ?: return emptyList()
        val array = runCatching { JSONArray(seasonsRaw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<CollapseEpisode>()
        for (i in 0 until array.length()) {
            val season = array.optJSONObject(i) ?: continue
            if (season.optBoolean("blocked", false)) continue
            val seasonNumber = season.optInt("season", 0)
            if (seasonNumber <= 0) continue
            val episodes = season.optJSONArray("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val item = episodes.optJSONObject(j) ?: continue
                parseEpisode(seasonNumber, item)?.let(result::add)
            }
        }
        return result.sortedWith(
            compareBy<CollapseEpisode> { it.season }
                .thenBy { it.episode.toIntOrNull() ?: Int.MAX_VALUE }
        )
    }

    private fun parseEpisode(season: Int, item: JSONObject): CollapseEpisode? {
        val episode = item.optString("episode", "0").trim()
        val id = item.optLong("id", 0L)
        val videoKey = item.optLong("videoKey", 0L).takeIf { it != 0L }
        val stream = CollapseStream(
            hls = validMediaUrl(item.optString("hls")),
            dash = validMediaUrl(item.optString("dash")),
            audioNames = stringArray(item.optJSONObject("audio")?.optJSONArray("names")),
            audioOrder = intArray(item.optJSONObject("audio")?.optJSONArray("order")),
            subtitles = subtitles(item.optJSONArray("cc"))
        )
        if (episode.isBlank() || (stream.hls.isBlank() && stream.dash.isBlank())) return null
        return CollapseEpisode(season, episode, id, videoKey, stream)
    }

    private fun parseStream(raw: String): CollapseStream {
        val audio = objectValue(raw, "audio")
        return CollapseStream(
            hls = validMediaUrl(scalar(raw, "hls").orEmpty()),
            dash = validMediaUrl(scalar(raw, "dash").orEmpty()),
            audioNames = audio?.let { arrayValue(it, "names") }
                ?.let(::stringArray).orEmpty(),
            audioOrder = audio?.let { arrayValue(it, "order") }
                ?.let(::intArray).orEmpty(),
            subtitles = arrayValue(raw, "cc")?.let(::subtitlesFromRaw).orEmpty()
        )
    }

    private fun validMediaUrl(value: String): String =
        value.replace("\\/", "/").trim().takeIf {
            runCatching {
                val uri = java.net.URI(it)
                uri.scheme == "https" && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }.orEmpty()

    private fun subtitles(array: JSONArray?): List<CollapseSubtitle> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until minOf(array.length(), 20)) {
                val item = array.optJSONObject(i) ?: continue
                val url = validMediaUrl(item.optString("url"))
                if (url.isBlank()) continue
                add(CollapseSubtitle(
                    url,
                    item.optString("name").ifBlank { "Субтитры " + (i + 1) }
                ))
            }
        }
    }

    private fun subtitlesFromRaw(raw: String): List<CollapseSubtitle> =
        subtitles(runCatching { JSONArray(raw) }.getOrNull())

    private fun stringArray(raw: String): List<String> =
        runCatching { stringArray(JSONArray(raw)) }.getOrElse {
            Regex("""["']([^"']+)["']""")
                .findAll(raw).map { it.groupValues[1] }.toList()
        }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), 40)).mapNotNull {
            array.optString(it).trim().takeIf(String::isNotBlank)
        }
    }

    private fun intArray(raw: String): List<Int> =
        runCatching { intArray(JSONArray(raw)) }.getOrDefault(emptyList())

    private fun intArray(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), 40)).map { array.optInt(it, it) }
    }

    private fun scalar(raw: String, key: String): String? {
        val text = value(raw, key)?.trim() ?: return null
        if (text.startsWith('"') || text.startsWith('\'')) {
            val quote = text.first()
            val end = quotedEnd(text, 0, quote)
            if (end > 0) {
                val quoted = text.substring(0, end + 1)
                if (quote == '"') {
                    return runCatching { JSONArray("[$quoted]").getString(0) }
                        .getOrDefault(quoted.substring(1, quoted.length - 1))
                }
                return quoted.substring(1, quoted.length - 1)
                    .replace("\\'", "'").replace("\\/", "/")
            }
        }
        return text.substringBefore(',').substringBefore('}').trim()
    }

    private fun objectValue(raw: String, key: String): String? =
        value(raw, key)?.trim()?.takeIf { it.startsWith('{') }?.let { balanced(it, 0) }

    private fun arrayValue(raw: String, key: String): String? =
        value(raw, key)?.trim()?.takeIf { it.startsWith('[') }?.let { balanced(it, 0) }

    private fun value(raw: String, key: String): String? {
        val escaped = Regex.escape(key)
        val patterns = listOf(
            Regex("""["']""" + escaped + """["']\s*:\s*"""),
            Regex("""\b""" + escaped + """\b\s*:\s*""")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(raw) } ?: return null
        return raw.substring(match.range.last + 1)
    }

    private fun balanced(raw: String, start: Int): String? {
        if (start !in raw.indices || raw[start] !in charArrayOf('{', '[')) return null
        val open = raw[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                continue
            }
            if (ch == open) depth++
            if (ch == close) {
                depth--
                if (depth == 0) return raw.substring(start, i + 1)
            }
        }
        return null
    }

    private fun quotedEnd(raw: String, start: Int, quote: Char): Int {
        var escaped = false
        for (i in start + 1 until raw.length) {
            val ch = raw[i]
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == quote) return i
        }
        return -1
    }
}
