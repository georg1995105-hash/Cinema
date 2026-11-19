package app.cinema.player

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Reject generic page cards when a mirror ignores the submitted search query. */
internal object SearchRelevance {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val yearSuffix = Regex("""(?:^|\\s)((?:18|19|20)\\d{2})\\s*$""")
    private val yearInDetails = Regex("""\\b(?:18|19|20)\\d{2}\\b""")
    fun queryWithoutYear(raw: String): String =
        raw.trim().replace(yearSuffix, "").trim().ifEmpty { raw.trim() }
    fun normalized(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(separators, " ").trim()

    fun alternateQuery(raw: String): List<String> {
        val query = raw.trim()
        val last = query.substringAfterLast(' ')
        if (!last.matches(Regex("(?iu)[а-яё]{4,}[ую]"))) return emptyList()
        val ending = if (last.endsWith("ю", true)) "я" else "а"
        return listOf(query.dropLast(1) + ending)
    }

    fun find(movies: List<CatalogMovie>, query: String): List<CatalogMovie> {
        val requestedYear = yearSuffix.find(query.trim())?.groupValues?.get(1)?.toIntOrNull()
        val q = normalized(queryWithoutYear(query))
        if (q.isBlank()) return movies
        val forms = listOf(q) + alternateQuery(q).map(::normalized)
        val ranked = movies.mapNotNull { movie ->
            if (requestedYear != null) {
                val titleYear = yearInDetails.find(movie.title)?.value?.toIntOrNull()
                val metadataYear = yearInDetails.find(movie.details)?.value?.toIntOrNull()
                if ((metadataYear ?: titleYear) != requestedYear) return@mapNotNull null
            }
            val score = (listOf(movie.title) + movie.aliases).mapIndexedNotNull { aliasIndex, candidate ->
                val title = normalized(CatalogTitleCleaner.clean(candidate, movie.details))
                val match = forms.mapIndexedNotNull { index, form ->
                    val relevance = when {
                        title == form -> 0
                        title.startsWith(form + " ") -> 2
                        title.contains(" " + form + " ") || title.endsWith(" " + form) -> 4
                        title.startsWith(form) && form.length >= 3 && form == q -> 6
                        else -> return@mapIndexedNotNull null
                    }
                    relevance + index
                }.minOrNull() ?: return@mapIndexedNotNull null
                match + if (aliasIndex == 0) 0 else 1
            }.minOrNull() ?: return@mapNotNull null
            score to movie
        }
        val wholeWordMatches = ranked.any { it.first < 6 }
        return ranked.filter { !wholeWordMatches || it.first < 6 }
            .sortedWith(compareBy<Pair<Int, CatalogMovie>> { it.first }
            .thenBy { it.second.title.length })
            .map { it.second }
            .distinctBy { MirrorPostId.fromUrl(it.url)?.let { id -> URI(it.url).authority + "/" + id } ?: it.url }
    }
}
