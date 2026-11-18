package app.cinema.player

import java.net.URI

/**
 * Rezka mirrors can protect ordinary film pages with Anubis.
 * These URLs are service pages on the same mirror, not film redirects.
 *
 * We intentionally let Android WebView execute the site's own challenge code.
 * The resulting cookie is stored by WebView's CookieManager and is then reused
 * by MirrorHttp for the native HTML/AJAX requests.
 */
internal object MirrorAnubis {
    private const val servicePath = "/.within.website/x/cmd/anubis/"

    fun isChallengeUrl(pageUrl: String, candidateUrl: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(pageUrl) ||
            !SourceRules.isValidHttpsUrl(candidateUrl) ||
            !SourceRules.sameOrigin(pageUrl, candidateUrl)) return false
        val path = runCatching { URI(candidateUrl).path.orEmpty() }.getOrDefault("")
        return path.contains(servicePath, ignoreCase = true)
    }

    fun isChallengeDocument(raw: String): Boolean {
        if (raw.isBlank()) return false
        val head = raw.take(160_000)
        return listOf(
            "anubis_challenge",
            "techaro.lol-anubis",
            "/.within.website/x/cmd/anubis/",
            "id=\"anubis_challenge\""
        ).any { head.contains(it, ignoreCase = true) }
    }
}
