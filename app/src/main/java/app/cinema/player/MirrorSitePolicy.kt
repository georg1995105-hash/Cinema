package app.cinema.player

import java.net.URI

/**
 * Network behavior shared by the native Rezka transport and the hidden WebView.
 * The built-in hosts and headers mirror the behavior of the user's reference client.
 */
internal object MirrorSitePolicy {
    val builtInOrigins = listOf(
        "https://rezka-ua.pub/",
        "https://hdrezka.me/",
        "https://standby-rezka.tv/"
    )

    private val dynamicHdRezka = Regex("""^hdrezka[a-z0-9-]*\.[a-z]{2,12}$""", RegexOption.IGNORE_CASE)
    private val dynamicRezkaUa = Regex("""^rezka-ua\.[a-z]{2,12}$""", RegexOption.IGNORE_CASE)

    fun isRecognizedMirror(url: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(url)) return false
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return host == "standby-rezka.tv" ||
            dynamicHdRezka.matches(host) ||
            dynamicRezkaUa.matches(host)
    }

    fun isAllowedMirror(url: String, savedMirrors: List<String>): Boolean =
        isRecognizedMirror(url) || savedMirrors.any { SourceRules.sameOrigin(it, url) }

    fun requestHeaders(url: String, userAgent: String): Map<String, String> {
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return buildMap {
            put("User-Agent", userAgent)
            put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            put("X-Hdrezka-Android-App", "1")
            put("X-Hdrezka-Android-App-Version", "2.2.5")
            if (host == "standby-rezka.tv") put("Host", "hdrezka.me")
        }
    }

    fun webViewHeaders(url: String): Map<String, String> =
        requestHeaders(url, "").filterKeys { it != "User-Agent" && it != "Host" }

    fun cookieScopes(url: String): List<String> {
        if (!SourceRules.isValidHttpsUrl(url)) return emptyList()
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return if (host == "standby-rezka.tv")
            listOf(url, "https://hdrezka.me/")
        else listOf(url)
    }
}
