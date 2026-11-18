package app.cinema.player

/** A film must have a manually selected, source-provided translation before playback. */
internal object WatchChoicePolicy {
    fun validChoice(voices: List<MirrorVoice>, id: String): Boolean =
        id.isNotBlank() && voices.any { it.id == id }

    fun episodesFor(episodes: List<MirrorEpisode>, season: String): List<MirrorEpisode> =
        episodes.filter { it.season == season }
}
