package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

internal data class MirrorVoice(
    val id: String, val name: String,
    val isCamrip: Int = 0, val isAds: Int = 0, val isDirector: Int = 0
)
internal data class MirrorEpisode(val season: String, val id: String, val label: String)
internal data class MirrorQuality(val label: String, val url: String, val alternatives: List<String> = emptyList())
internal data class MirrorSubtitle(val title: String, val url: String, val language: String)
internal enum class PlaybackFailure(val text: String) {
    NETWORK("Не удалось связаться с зеркалом."),
    TIMEOUT("Зеркало вовремя не ответило на запрос потока."),
    BLOCKED("Зеркало ограничило доступ или запросило проверку."),
    AUTH_REQUIRED("Для просмотра войдите в аккаунт выбранного зеркала."),
    PREMIUM("Источник требует платную подписку."),
    DRM("Источник требует DRM."),
    UNAVAILABLE("Источник не предоставил поток для этой озвучки и серии."),
    INVALID_RESPONSE("Формат ответа плеера не распознан."),
    REDIRECT("Запрос плеера перенаправлен на другой адрес.")
}

internal data class MirrorStreamInfo(
    val qualities: List<MirrorQuality>,
    val subtitles: List<MirrorSubtitle> = emptyList(),
    val failure: PlaybackFailure? = null
)

internal data class MirrorMoviePage(
    val id: String,
    val isSeries: Boolean,
    val voices: List<MirrorVoice>,
    val episodes: List<MirrorEpisode>,
    val favs: String = "",
    val defaultVoice: String = "",
    val defaultFlags: MirrorVoice? = null
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

    fun pageFailureReason(raw: String): String {
        val metadata = objectFromWebView(raw)
        return when (metadata?.optString("pageState")) {
            "protected" -> PlaybackFailure.BLOCKED.text
            "login" -> PlaybackFailure.AUTH_REQUIRED.text
            "premium" -> PlaybackFailure.PREMIUM.text
            "missing-metadata" -> "Страница фильма открылась без необходимых сведений плеера."
            else -> "Не удалось прочитать сведения плеера на странице фильма."
        }
    }

    fun pageFromWebView(raw: String, pageUrl: String? = null): MirrorMoviePage? {
        val json = objectFromWebView(raw) ?: return null
        if (json.optString("pageState") in setOf("protected", "login", "premium")) return null
        val urlId = pageUrl?.let(MirrorPostId::fromUrl)
        val pageId = json.optString("id").trim().takeIf { it.matches(numericId) }
        if (urlId != null && pageId != null && pageId != urlId) return null
        val id = pageId ?: urlId ?: return null
        val voices = parseVoices(json.optJSONArray("voices"))
        val defaultVoice = json.optString("defaultVoice").takeIf { it.matches(numericId) }.orEmpty()
        if (voices.isEmpty() && defaultVoice.isEmpty()) return null
        val flags = json.optJSONObject("flags")
        return MirrorMoviePage(
            id, json.optBoolean("series"), voices,
            parseEpisodes(json.optJSONArray("episodes")),
            json.optString("favs").takeIf { it.matches(Regex("[A-Za-z0-9_+=/-]{1,256}")) }.orEmpty(),
            defaultVoice,
            flags?.let { voiceWithFlags(defaultVoice, "", it) }
        )
    }

    private val numericId = Regex("[0-9]{1,12}")

    private fun voiceWithFlags(id: String, name: String, item: JSONObject): MirrorVoice =
        MirrorVoice(id, name, item.optInt("isCamrip").coerceIn(0, 1),
            item.optInt("isAds").coerceIn(0, 1), item.optInt("isDirector").coerceIn(0, 1))

    fun parseVoices(input: JSONArray?): List<MirrorVoice> = buildList {
        if (input != null) for (index in 0 until minOf(input.length(), 80)) {
            val item = input.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim().take(75)
            if (id.matches(numericId) && name.isNotBlank() && none { it.id == id }) {
                add(voiceWithFlags(id, name, item))
            }
        }
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
        val result = parseStreamInfo(raw)
        return result.qualities to result.failure?.text.orEmpty()
    }

    fun responseFailure(json: JSONObject): PlaybackFailure? {
        when (json.optString("__kinoFailure")) {
            "network" -> return PlaybackFailure.NETWORK
            "timeout" -> return PlaybackFailure.TIMEOUT
            "redirect" -> return PlaybackFailure.REDIRECT
            "invalid" -> return PlaybackFailure.INVALID_RESPONSE
            "blocked" -> return PlaybackFailure.BLOCKED
        }
        val status = json.optInt("__kinoStatus")
        val code = listOf("code", "error", "message").joinToString(" ") { json.optString(it) }
        return when {
            status == 401 || contains(code, "auth_required|login_required|авторизуй|войдите|войти в аккаунт") ->
                PlaybackFailure.AUTH_REQUIRED
            status == 402 || contains(code, "premium|премиум|платн.{0,8} подписк") -> PlaybackFailure.PREMIUM
            contains(code, "(?:^|[^a-z0-9])drm(?:$|[^a-z0-9])") -> PlaybackFailure.DRM
            status in setOf(403, 429, 451) || contains(code, "captcha|access.denied|доступ запрещ[её]н") ->
                PlaybackFailure.BLOCKED
            status == 404 || status == 410 -> PlaybackFailure.UNAVAILABLE
            status >= 400 || json.optString("error").equals("network", true) -> PlaybackFailure.NETWORK
            else -> null
        }
    }

    private fun contains(text: String, pattern: String) =
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun parseStreamInfo(raw: String): MirrorStreamInfo {
        val json = objectFromWebView(raw)
            ?: return MirrorStreamInfo(emptyList(), failure = PlaybackFailure.INVALID_RESPONSE)
        responseFailure(json)?.let { return MirrorStreamInfo(emptyList(), failure = it) }
        val error = json.optString("error").trim()
        val success = json.optString("success").trim()
        if ((error.isNotEmpty() && error !in setOf("false", "0", "null")) ||
            (success.isNotEmpty() && success !in setOf("true", "1", "null"))) {
            return MirrorStreamInfo(emptyList(), failure = PlaybackFailure.UNAVAILABLE)
        }
        val streamText = when (val url = json.opt("url")) {
            is String -> url
            is JSONObject -> url.optString("url")
            else -> json.optString("streams")
        }
        val qualities = parseQualityList(streamText)
        if (qualities.isEmpty()) {
            val failure = when {
                contains(streamText, "prem-quality|prem-icon|/premium/|premium-promo|premium_ad") -> PlaybackFailure.PREMIUM
                streamText.isBlank() && !json.has("url") && !json.has("streams") && !json.has("success") -> PlaybackFailure.INVALID_RESPONSE
                streamText.isBlank() -> PlaybackFailure.UNAVAILABLE
                else -> PlaybackFailure.INVALID_RESPONSE
            }
            return MirrorStreamInfo(emptyList(), failure = failure)
        }
        return MirrorStreamInfo(qualities, parseSubtitleList(
            json.optString("subtitle").ifBlank { json.optString("subtitles") }
        ))
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
        val expanded = MirrorTransportDecoder.decode(data.trim())
        if (expanded.isBlank()) return emptyList()
        val matches = Regex("""\[([^\]]{1,180})\]([^\[]+)""").findAll(expanded)
        val qualities = linkedMapOf<String, MutableList<String>>()
        for (match in matches) {
            val tag = match.groupValues[1]
            if (contains(tag, "prem-quality|prem-icon|premium|премиум")) continue
            val label = tag.replace(Regex("<[^>]+>"), "").replace(Regex("""\s+"""), " ").trim().take(40)
            if (label.isBlank()) continue
            val candidates = match.groupValues[2].trim().trimEnd(',').split(Regex("""\s+or\s+""", RegexOption.IGNORE_CASE))
                .mapNotNull(::normalizeMediaUrl)
            if (candidates.isNotEmpty()) qualities.getOrPut(label) { mutableListOf() }.addAll(candidates)
        }
        if (qualities.isEmpty()) normalizeMediaUrl(expanded)?.let { qualities["Исходное"] = mutableListOf(it) }
        return qualities.map { (label, candidates) ->
            val ordered = candidates.distinct().sortedBy {
                when {
                    it.substringBefore('?').endsWith(".mp4", true) -> 0
                    it.substringBefore('?').endsWith(".m3u8", true) -> 1
                    else -> 2
                }
            }
            MirrorQuality(label, ordered.first(), ordered.drop(1))
        }.sortedByDescending { Regex("""(\d{3,4})""").find(it.label)?.value?.toIntOrNull() ?: 0 }
    }

    private fun normalizeMediaUrl(raw: String): String? {
        var url = raw.trim().trimEnd(',', ';').trim()
        if (url.startsWith("//") && !url.startsWith("///"))
            url = "https:" + url
        if (contains(url.substringBefore('?'), "/(?:premium|promo|preroll|advert)/|premium-promo|ad-preview|premium_ad")) return null
        return runCatching {
            val parsed = URI(url)
            if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank() ||
                parsed.userInfo != null || parsed.fragment != null) null else url
        }.getOrNull()
    }


}
