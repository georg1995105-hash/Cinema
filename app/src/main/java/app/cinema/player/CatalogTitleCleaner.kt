package app.cinema.player

internal object CatalogTitleCleaner {
    fun clean(raw: String): String {
        val title = raw.replace(Regex("\\s+"), " ").trim()
        val cleaned = title.replace(
            Regex("\\s+(?:19|20)\\d{2}\\s*,\\s*[^,]+(?:,.*)?$"), ""
        ).trim()
        return cleaned.ifBlank { title }
    }
}
