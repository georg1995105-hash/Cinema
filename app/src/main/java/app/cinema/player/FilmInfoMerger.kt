package app.cinema.player

/** Never erase an existing film field because a later WebView snapshot is partial. */
internal object FilmInfoMerger {
    fun merge(previous: FilmInfo?, latest: FilmInfo): FilmInfo {
        if (previous == null) return latest
        fun people(old: List<FilmPerson>, incoming: List<FilmPerson>): List<FilmPerson> {
            val merged = linkedMapOf<String, FilmPerson>()
            for (entry in old + incoming) {
                val key = entry.name.trim().lowercase()
                val existing = merged[key]
                merged[key] = entry.copy(
                    photo = entry.photo.ifBlank { existing?.photo.orEmpty() },
                    url = entry.url.ifBlank { existing?.url.orEmpty() }
                )
            }
            return merged.values.take(30)
        }
        val facts = linkedMapOf<String, Pair<String, String>>()
        for (entry in previous.facts + latest.facts)
            facts[entry.first.trim().lowercase()] = entry
        return latest.copy(
            originalTitle = latest.originalTitle.ifBlank { previous.originalTitle },
            title = latest.title.ifBlank { previous.title },
            description = latest.description.ifBlank { previous.description },
            facts = facts.values.toList(),
            rating = latest.rating.ifBlank { previous.rating },
            cast = people(previous.cast, latest.cast),
            directors = people(previous.directors, latest.directors),
            related = (latest.related + previous.related).distinctBy { it.url }.take(15),
            franchise = (latest.franchise + previous.franchise).distinctBy { it.url }.take(50),
            franchiseResolved = previous.franchiseResolved || latest.franchiseResolved,
            voices = (latest.voices + previous.voices).distinctBy { it.id }.take(45),
            posters = (latest.posters + previous.posters).distinct().take(10)
        )
    }
}
