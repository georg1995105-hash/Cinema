package app.cinema.player

/** Metadata descriptions must be actual summaries, not site advertising/SEO. */
internal object FilmTextSanitizer {
    fun description(raw: String): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return ""
        val advertising = Regex(
            "(?i)(смотреть.{0,65}(онлайн|бесплатно)|без регистрации|приятного просмотра|в хорошем качестве совершенно бесплатно)"
        )
        // No invented description: if this is mostly promotional metadata, omit it.
        if (advertising.containsMatchIn(text) && text.length < 250) return ""
        return text.take(1800)
    }
}
