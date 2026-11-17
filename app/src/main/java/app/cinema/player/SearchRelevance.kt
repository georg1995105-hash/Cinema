package app.cinema.player

import java.text.Normalizer
import java.util.Locale

/** Reject generic page cards when a mirror ignores the submitted search query. */
internal object SearchRelevance {
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    fun normalized(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(separators, " ").trim()

    fun find(movies: List<CatalogMovie>, query: String): List<CatalogMovie> {
        val q = normalized(query)
        if (q.isBlank()) return movies
        val words = q.split(' ').filter { it.isNotBlank() }
        return movies.mapNotNull { movie ->
            val title = normalized(CatalogTitleCleaner.clean(movie.title))
            if (title.isEmpty()) return@mapNotNull null
            val score = when {
                title == q -> 0
                title.startsWith("$q ") -> 1
                title.contains(" $q ") || title.endsWith(" $q") -> 2
                title.contains(q) -> 3
                words.size > 1 && words.all { word ->
                    title.split(' ').any { it == word || it.startsWith(word) }
                } -> 4
                // The speech recognizer can return an inflected Russian title:
                // "найди Бригаду" vs the catalogue's "Бригада".
                words.size == 1 && words[0].length >= 5 &&
                    title.split(' ').firstOrNull()?.let { first ->
                        first.length == q.length && first.dropLast(1) == q.dropLast(1)
                    } == true -> 5
                else -> return@mapNotNull null
            }
            score to movie
        }.sortedWith(compareBy<Pair<Int,CatalogMovie>> { it.first }
            .thenBy { it.second.title.length }).map { it.second }.distinctBy { it.url }
    }
}
