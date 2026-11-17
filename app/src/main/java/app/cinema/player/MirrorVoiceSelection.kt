package app.cinema.player

/** Do not assume that the page has any translation buttons. */
internal object MirrorVoiceSelection {
    fun choose(page: MirrorMoviePage, previouslySelected: String?): String {
        val saved = previouslySelected.orEmpty().takeIf { id ->
            id.isNotBlank() && (id == page.defaultVoice || page.voices.any { it.id == id })
        }
        if (saved != null) return saved
        val hasFreeAlternative = page.voices.any { !it.requiresSubscription }
        val default = page.defaultVoice.takeIf { id ->
            id.isNotBlank() && !(hasFreeAlternative &&
                page.voices.any { it.id == id && it.requiresSubscription })
        }
        return default ?: page.voices.firstOrNull { !it.requiresSubscription }?.id
            ?: page.voices.firstOrNull()?.id.orEmpty()
    }
}
