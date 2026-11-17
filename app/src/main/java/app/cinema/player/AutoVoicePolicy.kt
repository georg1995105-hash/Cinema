package app.cinema.player

/** Attempts only distinct translation IDs published by the selected mirror. */
internal object AutoVoicePolicy {
    private const val MAX_VOICES = 8
    private val restricted = Regex("(?i)(premium|премиум|подписк)")

    fun next(
        available: List<MirrorVoice>,
        current: String,
        previouslyTried: Collection<String>
    ): MirrorVoice? {
        if (previouslyTried.size + (if (current.isBlank() || current in previouslyTried) 0 else 1)
            >= MAX_VOICES) return null
        val excluded = previouslyTried.toSet() + current
        return available.firstOrNull {
            it.id !in excluded && !restricted.containsMatchIn(it.name)
        }
    }
}
