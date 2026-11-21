package app.cinema.player

import java.net.URI
import java.net.URLDecoder

/**
 * The embedded Lift browser can report a visited film page, not a measured video
 * position. Keep those entries in Cinema's local library without claiming playback.
 */
internal object LiftHistoryPolicy {
    fun cardFromVisit(url: String, pageMetadata: CatalogMovie? = null): CatalogMovie? {
        if (!LiftMatchPolicy.safeDetailsUrl(url)) return null
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        val id = Regex("""(?:^|&)id=([0-9]{1,12})(?:&|$)""")
            .find(query)?.groupValues?.getOrNull(1) ?: return null
        val encodedName = query.split('&').firstOrNull { it.startsWith("name=") }
            ?.substringAfter("name=").orEmpty()
        val name = runCatching { URLDecoder.decode(encodedName, "UTF-8") }
            .getOrDefault("").replace(Regex("""\s+"""), " ").trim().take(130)
        // A reliable film title in the first-party URL beats a generic page title.
        val pageName = pageMetadata?.takeIf { it.url == url }?.title.orEmpty()
            .replace(Regex("""\s+"""), " ").trim().take(130)
        val title = name.ifBlank { pageName }.ifBlank { "Фильм Lift №$id" }
        val poster = pageMetadata?.takeIf { it.url == url }?.poster
            ?.takeIf(MirrorPoster::isPhoto).orEmpty()
        return CatalogMovie(url = url, title = title, poster = poster, details = "")
    }

    /**
     * Only the initially matched Lift page belongs to the originating catalog
     * film. Further in-browser navigation must create independent history entries.
     */
    fun historyKey(sourceMovieUrl: String?, firstDetailsUrl: String?, visitedDetailsUrl: String): String? {
        if (!LiftMatchPolicy.safeDetailsUrl(visitedDetailsUrl)) return null
        return sourceMovieUrl?.takeIf(SourceRules::isValidHttpsUrl)
            ?.takeIf { firstDetailsUrl.isNullOrBlank() || firstDetailsUrl == visitedDetailsUrl }
            ?: visitedDetailsUrl
    }
}
