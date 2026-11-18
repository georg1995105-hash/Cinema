package app.cinema.player

/** Playback must respect an explicit voice chosen in the film sheet. */
internal object MirrorVoiceSelection {
    fun choose(page: MirrorMoviePage, previouslySelected: String?): String {
        val id = previouslySelected.orEmpty()
        if (id.isBlank()) return ""
        return id.takeIf { candidate ->
            candidate == page.defaultVoice || page.voices.any { it.id == candidate }
        }.orEmpty()
    }
}
