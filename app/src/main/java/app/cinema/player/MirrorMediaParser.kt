package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.Base64

internal data class MirrorVoice(val id: String, val name: String)
internal data class MirrorEpisode(val season: String, val id: String, val label: String)
internal data class MirrorQuality(val label: String, val url: String, val alternatives: List<String> = emptyList())
internal data class MirrorSubtitle(val title: String, val url: String, val language: String)
internal data class MirrorStreamInfo(
    val qualities: List<MirrorQuality>, val subtitles: List<MirrorSubtitle>, val error: String
)
internal data class MirrorMoviePage(
    val id: String,
    val isSeries: Boolean,
    val voices: List<MirrorVoice>,
    val episodes: List<MirrorEpisode>
)

/**
 * Parsing of ordinary player metadata and streams returned to an accessible site session.
 * This does not decrypt DRM or grant access to restricted/premium content.
 */
internal object MirrorMediaParser {
    fun objectFromWebView(value: String): JSONObject? = runCatching {
        when (val raw = JSONTokener(value).nextValue()) {
            is JSONObject -> raw
            is String -> JSONObject(raw)
            else -> null
        }
    }.getOrNull()

    fun pageFromWebView(raw: String): MirrorMoviePage? {
        val json = objectFromWebView(raw) ?: return null
        val id = json.optString("id").trim()
        if (!id.matches(Regex("[0-9]{1,12}"))) return null
        val voices = parseVoices(json.optJSONArray("voices"), json.optString("voice"))
        return MirrorMoviePage(
            id,
            json.optBoolean("series"),
            voices,
            parseEpisodes(json.optJSONArray("episodes"))
        )
    }

    fun parseVoices(input: JSONArray?, fallback: String): List<MirrorVoice> {
        val list = mutableListOf<MirrorVoice>()
        if (input != null) for (index in 0 until minOf(input.length(), 80)) {
            val item = input.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().take(75)
            if (id.matches(Regex("[0-9]{1,12}")) && name.isNotBlank() &&
                list.none { it.id == id }) list += MirrorVoice(id, name)
        }
        if (list.isEmpty()) {
            val id = fallback.trim().takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: "0"
            list += MirrorVoice(id, "Озвучка по умолчанию")
        }
        return list
    }

    fun parseEpisodes(json: JSONArray?): List<MirrorEpisode> {
        val found = mutableListOf<MirrorEpisode>()
        if (json != null) for (i in 0 until minOf(json.length(), 500)) {
            val item = json.optJSONObject(i) ?: continue
            val season = item.optString("season").trim()
            val episode = item.optString("episode").trim()
            val label = item.optString("label").trim().take(90)
            if (!season.matches(Regex("[0-9]{1,4}")) ||
                !episode.matches(Regex("[0-9]{1,4}"))) continue
            if (found.none { it.season == season && it.id == episode }) {
                found += MirrorEpisode(season, episode, label.ifBlank { "Серия $episode" })
            }
        }
        return found
    }

    fun parseStreamResponse(raw: String): Pair<List<MirrorQuality>, String> {
        val json = objectFromWebView(raw) ?: return emptyList<MirrorQuality>() to "Ответ сервера не распознан"
        if (json.has("error")) {
            return emptyList<MirrorQuality>() to "Не удалось получить видео от зеркала"
        }
        if (json.has("success") && !json.optBoolean("success")) {
            val reason = json.optString("message").take(180)
            return emptyList<MirrorQuality>() to
                (reason.ifBlank { "Источник не предоставил видеопоток" })
        }
        val streamText = when (val url = json.opt("url")) {
            is String -> url
            is JSONObject -> url.optString("url")
            else -> json.optString("streams")
        }
        val parsed = parseQualityList(streamText)
        return if (parsed.isEmpty()) {
            emptyList<MirrorQuality>() to "Доступный видеопоток не найден для выбранной озвучки и серии"
        } else parsed to ""
    }

    fun parseStreamInfo(raw: String): MirrorStreamInfo {
        val (qualities, error) = parseStreamResponse(raw)
        val json = objectFromWebView(raw)
        val subtitles = parseSubtitleList(
            json?.optString("subtitle").orEmpty().ifBlank {
                json?.optString("subtitles").orEmpty()
            }
        )
        return MirrorStreamInfo(qualities, subtitles, error)
    }

    /** Regular, already accessible subtitle URLs from a site's stream response. */
    fun parseSubtitleList(value: String): List<MirrorSubtitle> {
        if (value.isBlank()) return emptyList()
        val output = mutableListOf<MirrorSubtitle>()
        val pattern = Regex("""\[([^\]]{1,70})\](https://[^,\s]+)""")
        for (match in pattern.findAll(value)) {
            val title = match.groupValues[1].trim()
            val uri = match.groupValues[2].trim()
            if (title.isBlank() || title.contains('<') || !SourceRules.isValidHttpsUrl(uri)) continue
            val language = when {
                title.contains("рус", ignoreCase = true) ||
                    title.equals("ru", ignoreCase = true) ||
                    title.equals("rus", ignoreCase = true) -> "ru"
                title.contains("анг", ignoreCase = true) ||
                    title.contains("engl", ignoreCase = true) ||
                    title.equals("en", ignoreCase = true) -> "en"
                title.contains("укр", ignoreCase = true) ||
                    title.equals("uk", ignoreCase = true) -> "uk"
                else -> "und"
            }
            if (output.none { it.url == uri }) output += MirrorSubtitle(title, uri, language)
            if (output.size >= 12) break
        }
        return output
    }

    fun parseQualityList(data: String): List<MirrorQuality> {
        val expanded = unpackPlainUrlList(data.trim())
        if (expanded.isBlank()) return emptyList()
        val matches = Regex("""\[([^\]]{1,180})\]([^\[]+)""").findAll(expanded)
        val output = mutableListOf<MirrorQuality>()
        for (match in matches) {
            val tag = match.groupValues[1]
            // Premium trial/placeholder labels must never be treated as ordinary video.
            if (tag.contains("prem-quality", true) || tag.contains("prem-icon", true)) continue
            val label = tag.replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\s+"), " ").trim().take(40)
            if (label.isBlank()) continue
            val alternatives = match.groupValues[2].trim().trimEnd(',')
                .split(" or ", ignoreCase = true)
            // Prefer a normal MP4 link where available. Media3 also accepts real HLS URLs.
            val candidates = alternatives.mapNotNull { normalizeMediaUrl(it) }.filterNot {
                val lower = it.lowercase()
                listOf("/premium/", "/promo/", "/preroll/", "/advert/", "premium-promo",
                    "ad-preview", "premium_ad").any(lower::contains)
            }
            val ordered = candidates.distinct().sortedBy { source ->
                when {
                    source.endsWith(".mp4", true) || ".mp4?" in source.lowercase() -> 0
                    ".m3u8" in source.lowercase() -> 1
                    else -> 2
                }
            }
            val chosen = ordered.firstOrNull()
            if (chosen != null && output.none { it.label == label }) {
                output += MirrorQuality(label, chosen, ordered.drop(1))
            }
        }
        if (output.isEmpty()) {
            normalizeMediaUrl(expanded)?.let { output += MirrorQuality("Авто", it) }
        }
        return output.sortedByDescending {
            Regex("""(\d{3,4})""").find(it.label)?.value?.toIntOrNull() ?: 0
        }
    }

    private fun normalizeMediaUrl(raw: String): String? {
        var url = raw.trim().trimEnd(',', ';').trim()
        if (url.endsWith(":hls:manifest.m3u8")) {
            // CDN's variant suffix is not a valid HLS file path; the base URL is MP4.
            url = url.removeSuffix(":hls:manifest.m3u8")
        }
        return runCatching {
            val parsed = URI(url)
            if (parsed.scheme != "https" || parsed.host.isNullOrBlank() ||
                parsed.userInfo != null || parsed.fragment != null) null else url
        }.getOrNull()
    }

    private fun unpackPlainUrlList(data: String): String {
        if (!data.startsWith("#h")) return data
        // This is only the legacy non-cryptographic transport encoding of URL strings,
        // not DRM, entitlement checks or a means of acquiring restricted quality.
        var encoded = data.removePrefix("#h")
        val known = listOf(
            "IyMjI14hISMjIUBA", "QEBAQEAhIyMhXl5e",
            "JCQhIUAkJEBeIUAjJCRA", "JCQjISFAIyFAIyM=", "Xl5eIUAjIyEhIyM="
        )
        repeat(48) {
            val index = encoded.indexOf("//_//")
            if (index < 0) return@repeat
            val remaining = encoded.substring(index + 5)
            val salt = known.firstOrNull(remaining::startsWith)
            // Unrecognized layouts are not guessed: avoid malformed URLs.
            if (salt == null) return ""
            encoded = encoded.substring(0, index) + remaining.drop(salt.length)
        }
        return runCatching {
            val restored = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            if (restored.startsWith("[") || restored.startsWith("https://")) restored else ""
        }.getOrDefault("")
    }
}
