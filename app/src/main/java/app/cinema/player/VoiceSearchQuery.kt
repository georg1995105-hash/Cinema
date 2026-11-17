package app.cinema.player

/** Remove spoken assistant commands; do not alter meaningful film titles. */
internal object VoiceSearchQuery {
    fun clean(raw: String): String =
        raw.trim()
            .replace(Regex("(?i)^(пожалуйста\\s+)?(найди|найдите|покажи|покажите|ищи|поиск)\\s+(мне\\s+)?"), "")
            .replace(Regex("(?i)^(фильм|сериал|мультфильм|аниме)\\s+"), "")
            .trim().trim('.', '!', '?', ',', ' ')
            .take(120)
}
