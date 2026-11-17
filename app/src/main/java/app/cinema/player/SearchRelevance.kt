package app.cinema.player

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Reject generic page cards when a mirror ignores the submitted search query. */
internal object SearchRelevance {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
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
        val q = normalized(query)
        if (q.isBlank()) return movies
        val forms = listOf(q) + alternateQuery(q).map(::normalized)
        return movies.mapNotNull { movie ->
            val title = normalized(CatalogTitleCleaner.clean(movie.title, movie.details))
            val score = forms.mapIndexedNotNull { index, form ->
                val match = when {
                    title == form -> 0
                    title.startsWith("$form ") -> 2
                    title.contains(" $form ") || title.endsWith(" $form") -> 4
                    title.startsWith(form) && form.length >= 3 && form == q -> 6
                    else -> return@mapIndexedNotNull null
                }
                match + index
            }.minOrNull() ?: return@mapNotNull null
            score to movie
        }.sortedWith(compareBy<Pair<Int, CatalogMovie>> { it.first }
            .thenBy { it.second.title.length })
            .map { it.second }
            .distinctBy { MirrorPostId.fromUrl(it.url)?.let { id -> URI(it.url).authority + "/" + id } ?: it.url }
    }
}
