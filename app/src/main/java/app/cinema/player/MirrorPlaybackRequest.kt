package app.cinema.player

/**
 * Rezka's own CDN form is assembled from the chosen translator, not inferred
 * from the film type. The reference client's base request contains three
 * translator flags for both films and series, and passes favs from Voice.
 * These are source-supplied fields; they do not grant premium entitlements.
 */
internal object MirrorPlaybackRequest {
    private val idPattern = Regex("[0-9]{1,12}")
    private val episodePattern = Regex("[0-9]{1,4}")

    private fun base(
        movie: MirrorMoviePage,
        translatorId: String,
        action: String
    ): LinkedHashMap<String, String> {
        require(movie.id.matches(idPattern))
        require(translatorId.matches(idPattern))
        val selected = movie.voices.firstOrNull { it.id == translatorId }
            ?: movie.defaultFlags?.takeIf { it.id == translatorId }
        val fields = linkedMapOf(
            "id" to movie.id,
            "translator_id" to translatorId,
            "action" to action,
            "is_camrip" to (selected?.isCamrip ?: 0).toString(),
            "is_ads" to (selected?.isAds ?: 0).toString(),
            "is_director" to (selected?.isDirector ?: 0).toString()
        )
        val favs = selected?.favs.orEmpty().ifBlank { movie.favs }
        MirrorMediaParser.sanitizeFavs(favs).takeIf(String::isNotEmpty)?.let {
            fields["favs"] = it
        }
        return fields
    }

    fun episodes(movie: MirrorMoviePage, translatorId: String): Map<String, String> {
        require(movie.isSeries)
        return base(movie, translatorId, "get_episodes")
    }

    fun stream(
        movie: MirrorMoviePage,
        translatorId: String,
        season: String = "",
        episode: String = ""
    ): Map<String, String> {
        val fields = base(movie, translatorId,
            if (movie.isSeries) "get_stream" else "get_movie")
        if (movie.isSeries) {
            require(season.matches(episodePattern) && episode.matches(episodePattern))
            fields["season"] = season
            fields["episode"] = episode
        }
        return fields
    }
}
