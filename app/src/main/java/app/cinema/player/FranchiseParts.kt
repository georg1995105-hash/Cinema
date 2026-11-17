package app.cinema.player

import java.net.URI

internal data class FranchisePartInput(
    val url: String,
    val title: String,
    val poster: String = "",
    val year: String = "",
    val rating: String = "",
    val number: Int = 0
)

/** Only the explicit entries of a mirror's franchise list; never infer membership by title. */
internal object FranchiseParts {
    fun validate(items: List<FranchisePartInput>, source: String): List<CatalogMovie> {
        val sourceHost = runCatching { URI(source).host?.lowercase() }.getOrNull()
            ?: return emptyList()
        return items.asSequence()
            .filter { SourceRules.isValidHttpsUrl(it.url) }
            .filter {
                val host = runCatching { URI(it.url).host?.lowercase() }.getOrNull()
                host == sourceHost || host?.endsWith(".$sourceHost") == true
            }
            .filter { it.title.trim().length in 2..140 }
            .sortedWith(compareBy<FranchisePartInput> { if (it.number > 0) it.number else Int.MAX_VALUE })
            .distinctBy { it.url }
            .take(50)
            .map { item ->
                val year = item.year.takeIf { it.matches(Regex("(19|20)\\d{2}")) }
                val rating = item.rating.toDoubleOrNull()
                    ?.takeIf { it in 0.0..10.0 }
                    ?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }
                val details = listOfNotNull(year, rating).joinToString(" · ")
                CatalogMovie(
                    url = item.url,
                    title = item.title.trim().take(140),
                    poster = item.poster.takeIf(SourceRules::isValidHttpsUrl).orEmpty(),
                    details = details
                )
            }.toList()
    }
}
