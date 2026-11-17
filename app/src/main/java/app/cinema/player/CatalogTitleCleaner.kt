package app.cinema.player

internal object CatalogTitleCleaner {
    private val visibleDetails = Regex(
        """\s+(?:18|19|20)\d{2}(?:\s*[-–—]\s*(?:\.\.\.|…|\d{4}))?\s*,\s*[^,]+(?:,.*)?$"""
    )

    fun clean(raw: String, details: String = ""): String {
        val title = raw.replace(Regex("""\s+"""), " ").trim()
        val suffix = details.replace(Regex("""\s+"""), " ").trim()
        val stripped = if (suffix.isNotBlank() && title.endsWith(" ${suffix}", true)) {
            title.dropLast(suffix.length).trimEnd()
        } else title.replace(visibleDetails, "").trim()
        return stripped.ifBlank { title }
    }
}
