package app.cinema.player

/** Prevent an early empty WebView snapshot from completing film details. */
internal object FilmInfoReadiness {
    fun hasContent(data: FilmInfo): Boolean =
        data.description.isNotBlank() || data.facts.isNotEmpty() ||
        data.cast.isNotEmpty() || data.directors.isNotEmpty() ||
        data.voices.isNotEmpty() || data.related.isNotEmpty() ||
        data.franchise.isNotEmpty()

    fun canFinish(data: FilmInfo): Boolean =
        hasContent(data) && data.franchiseResolved &&
        data.cast.all { it.photo.isNotBlank() || it.url.isBlank() } &&
        data.directors.all { it.photo.isNotBlank() || it.url.isBlank() }
}
