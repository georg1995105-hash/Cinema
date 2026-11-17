package app.cinema.player

/** Numeric order of seasons/episodes as exposed by the active mirror, without guessing missing ones. */
internal object EpisodeNavigator {
    fun ordered(episodes: List<MirrorEpisode>): List<MirrorEpisode> =
        episodes.distinctBy { it.season to it.id }.sortedWith(
            compareBy<MirrorEpisode>({ it.season.toIntOrNull() ?: Int.MAX_VALUE },
                { it.id.toIntOrNull() ?: Int.MAX_VALUE })
        )

    fun next(episodes: List<MirrorEpisode>, currentSeason: String, currentEpisode: String): MirrorEpisode? {
        val all = ordered(episodes)
        val position = all.indexOfFirst { it.season == currentSeason && it.id == currentEpisode }
        return if (position < 0) null else all.getOrNull(position + 1)
    }

    fun previous(episodes: List<MirrorEpisode>, currentSeason: String, currentEpisode: String): MirrorEpisode? {
        val all = ordered(episodes)
        val position = all.indexOfFirst { it.season == currentSeason && it.id == currentEpisode }
        return if (position <= 0) null else all.getOrNull(position - 1)
    }
}
