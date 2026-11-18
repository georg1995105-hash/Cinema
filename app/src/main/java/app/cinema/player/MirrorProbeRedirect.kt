package app.cinema.player

import java.net.URI

/** Follow only public HTTPS mirror redirects during a connectivity probe. */
internal object MirrorProbeRedirect {
    fun next(current: String, location: String?): String? {
        if (location.isNullOrBlank() || !SourceRules.isValidHttpsUrl(current))
            return null
        val destination = runCatching {
            URI(current).resolve(location.trim()).toString()
        }.getOrNull() ?: return null
        if (!SourceRules.isValidHttpsUrl(destination)) return null
        val path = runCatching { URI(destination).path.orEmpty() }.getOrDefault("")
        if (Regex("/(?:login|account|premium|subscribe|authorization)(?:/|$)",
                RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
        return destination.takeIf {
            SourceRules.sameOrigin(current, it) ||
                MirrorSitePolicy.isRecognizedMirror(it)
        }
    }
}
