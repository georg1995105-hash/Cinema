package app.cinema.player

import java.util.Calendar

/** Stremio catalog routes use skip as the number of skipped items (100 per page). */
internal object CatalogPages {
    private const val size = 100
    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    fun path(tab: String, type: String, page: Int, year: Int = currentYear()): String {
        require(type == "movie" || type == "series")
        require(page in 0..10)
        val catalog = when (tab) {
            "Новинки" -> "year"
            "Высокий рейтинг" -> "imdbRating"
            else -> "top"
        }
        val extras = buildList {
            if (catalog == "year") add("genre=" + year)
            if (page > 0) add("skip=" + (page * size))
        }
        return "/catalog/" + type + "/" + catalog +
            (if (extras.isEmpty()) "" else "/" + extras.joinToString("&")) + ".json"
    }
}
