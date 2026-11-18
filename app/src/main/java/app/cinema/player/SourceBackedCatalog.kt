package app.cinema.player

/**
 * Home and search must not offer metadata-only cards as watchable movies.
 * These identities have an actual playback integration, although the
 * publisher may still deny a stream in the current device/region.
 */
internal object SourceBackedCatalog {
    fun isPlaybackCandidate(url: String): Boolean =
        MosfilmCatalog.isVideoUrl(url) ||
            ArchivePublicCatalog.isArchiveUrl(url) ||
            InternetArchiveStreams.canCheck(url)

    fun initial(tab: String, kind: String, query: String = ""): List<CatalogMovie> {
        val studio = if (query.isBlank()) MosfilmCatalog.catalog(kind)
            else MosfilmCatalog.search(query, kind)
        val archive = if (kind == "Сериалы" || kind == "Мультфильмы") emptyList()
            else ArchivePublicCatalog.featured().filter {
                query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)
            }
        return when {
            query.isNotBlank() -> merge(studio, archive)
            tab == "Мосфильм" -> studio
            tab == "Архив" -> archive
            else -> merge(studio, archive)
        }
    }

    /**
     * Cache and metadata-service results cannot remove supported films or
     * resurrect a stale catalogue full of titles without video providers.
     */
    fun merge(primary: List<CatalogMovie>, incoming: List<CatalogMovie>): List<CatalogMovie> =
        (primary + incoming)
            .filter { isPlaybackCandidate(it.url) }
            .distinctBy { it.url }
}
