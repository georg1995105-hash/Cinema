package app.cinema.player

/**
 * Encode only the selected mirror's own publicly supplied translator parameters.
 * No fabricated entitlement, Premium token or third-party proxy.
 */
internal object MirrorPlaybackRequest {
    fun stream(
        movie: MirrorMoviePage,
        translatorId: String,
        season: String = "",
        episode: String = ""
    ): Map<String, String> {
        require(movie.id.matches(Regex("[0-9]{1,12}")))
        require(translatorId.matches(Regex("[0-9]{1,12}")))
        val fields = linkedMapOf(
            "id" to movie.id,
            "translator_id" to translatorId,
            "action" to if (movie.isSeries) "get_stream" else "get_movie"
        )
        movie.favs.takeIf { it.isNotBlank() }?.let { fields["favs"] = it }
        if (movie.isSeries) {
            require(season.matches(Regex("[0-9]{1,4}")) &&
                episode.matches(Regex("[0-9]{1,4}")))
            fields["season"] = season
            fields["episode"] = episode
        } else {
            val selected = movie.voices.firstOrNull { it.id == translatorId }
            fields["is_camrip"] = (selected?.isCamrip ?: 0).toString()
            fields["is_ads"] = (selected?.isAds ?: 0).toString()
            fields["is_director"] = (selected?.isDirector ?: 0).toString()
        }
        return fields
    }
}
