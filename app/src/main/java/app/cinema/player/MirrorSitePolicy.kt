package app.cinema.player

import java.net.URI

/**
 * Network behavior shared by the native Rezka transport and the hidden WebView.
 * The built-in hosts and headers mirror the behavior of the user's reference client.
 */
internal object MirrorSitePolicy {
    /**
     * Public mirror set from the reference APK (v2.2.5), plus its standby host.
     * Keep the order: earlier entries are preferred when no user mirror works.
     */
    val builtInOrigins = listOf(
        "https://hdrezka.mn/",
        "https://hdrzk.org/",
        "https://hdrezka-home.tv/",
        "https://hdrezka.website/",
        "https://hdrezka.sh/",
        "https://omnirezka.tv/",
        "https://rezka-ua.pub/",
        "https://hdrezka.ag/",
        "https://hdrezka.name/",
        "https://hello-rezka.tv/",
        "https://rezka.pub/",
        "https://hdrezka.kim/",
        "https://rezkery.com/",
        "https://hdrezka-fly.net/",
        "https://hdrezka-sonic.net/",
        "https://rezka-kz.tv/",
        "https://hdrezka.club/",
        "https://hdrezka.hk/",
        "https://rezka-ua.net/",
        "https://rezka-ua.in/",
        "https://rezka-ua.co/",
        "https://rezka.ag/",
        "https://hdrezka.me/",
        "https://standby-rezka.tv/"
    )

    private val dynamicHdRezka = Regex("""^hdrezka[a-z0-9-]*\.[a-z]{2,12}$""", RegexOption.IGNORE_CASE)
    private val dynamicRezkaUa = Regex("""^rezka-ua\.[a-z]{2,12}$""", RegexOption.IGNORE_CASE)

    fun isRecognizedMirror(url: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(url)) return false
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        val knownHosts = builtInOrigins.mapNotNull {
            runCatching { URI(it).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        }.toSet()
        return host in knownHosts ||
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

    fun mergeCookieHeaders(headers: List<String>): String {
        val values = linkedMapOf<String, String>()
        // Later process lower-priority shared scopes first; the local scope wins duplicates.
        headers.asReversed().forEach { header ->
            header.split(';').map(String::trim).forEach { part ->
                val split = part.indexOf('=')
                if (split <= 0) return@forEach
                val name = part.substring(0, split).trim()
                val value = part.substring(split + 1).trim()
                if (name.isNotBlank()) values[name] = value
            }
        }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    fun sessionScopes(url: String): List<String> {
        if (!SourceRules.isValidHttpsUrl(url)) return emptyList()
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return if (host == "standby-rezka.tv" || host == "hdrezka.me")
            listOf("https://standby-rezka.tv/", "https://hdrezka.me/")
        else listOf(url)
    }

    fun cookieScopes(url: String): List<String> {
        if (!SourceRules.isValidHttpsUrl(url)) return emptyList()
        val host = runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }
            .getOrDefault("")
        return if (host == "standby-rezka.tv")
            listOf(url, "https://hdrezka.me/")
        else listOf(url)
    }
}
