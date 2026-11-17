package app.cinema.player

/** Keep actual story descriptions, not mirror advertising or premium promotions. */
internal object FilmTextSanitizer {
    private val whitespace = Regex("\\s+")
    private val promotions = listOf(
        Regex("(?iu)подписка\\s+(?:на\\s+)?(?:hdrezka|хдрезка|rezka)\\s*premium"),
        Regex("(?iu)\\b(?:hdrezka|хдрезка|rezka)\\s*premium\\b"),
        Regex("(?iu)смотреть\\s+(?:фильм|сериал|мультфильм).{0,90}\\bонлайн\\b"),
        Regex("(?iu)\\bсовершенно\\s+бесплатно\\b.{0,50}\\bбез\\s+регистрации\\b"),
        Regex("(?iu)\\bприятного\\s+просмотра\\b"),
        Regex("(?iu)\\b3500\\s+переведенн\\w*\\s+релизов\\b")
    )
    fun description(raw: String): String {
        val text = raw.replace(whitespace, " ").trim()
        if (text.isBlank() || promotions.any { it.containsMatchIn(text) }) return ""
        return text.take(1800)
    }
}
