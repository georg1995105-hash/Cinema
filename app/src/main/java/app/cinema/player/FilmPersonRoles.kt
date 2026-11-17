package app.cinema.player

internal object FilmPersonRoles {
    private fun normalized(name: String): String =
        name.trim().lowercase().replace('ё','е').replace(Regex("\\s+")," ")

    fun actorCredits(
        candidates: List<Pair<FilmPerson,String>>,
        directors: List<FilmPerson>
    ): List<FilmPerson> {
        val directorNames=directors.map { normalized(it.name) }.toSet()
        return candidates.filter { (person,role) ->
            role == "actor" ||
                (role != "director" && normalized(person.name) !in directorNames)
        }.map { it.first }.distinctBy { normalized(it.name) }
    }
}
