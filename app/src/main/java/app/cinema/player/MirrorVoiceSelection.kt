package app.cinema.player

/** Playback must respect an explicit voice chosen in the film sheet. */
internal object MirrorVoiceSelection {
    fun supports(page: MirrorMoviePage, voiceId: String?): Boolean {
        val id = voiceId.orEmpty()
        if (id.isBlank()) return false
        return id == page.defaultVoice || page.voices.any { it.id == id }
    }

    fun choose(page: MirrorMoviePage, previouslySelected: String?): String {
        val id = previouslySelected.orEmpty()
        return id.takeIf { supports(page, it) }.orEmpty()
    }
}
