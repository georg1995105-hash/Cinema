package app.cinema.player

/**
 * The reference client reads a different initCDNSeriesEvents signature from
 * initCDNMoviesEvents. The extended series call includes two numeric selection
 * arguments and one unrelated argument before the final translator flags.
 * Treating the first three values after translator_id as flags corrupts AJAX.
 */
internal data class MirrorInitCdn(
    val isSeries: Boolean,
    val postId: String,
    val translatorId: String,
    val camrip: Int,
    val ads: Int,
    val director: Int
)

internal object MirrorInitCdnParser {
    private val call = Regex(
        """initCDN(Series|Movies)Events\s*\(([^)]{1,350})\)""",
        RegexOption.IGNORE_CASE
    )
    private val id = Regex("[0-9]{1,12}")

    fun parse(html: String): MirrorInitCdn? {
        for (match in call.findAll(html)) {
            val series = match.groupValues[1].equals("Series", ignoreCase = true)
            val args = match.groupValues[2].split(',').map {
                it.trim().trim('\'', '"').trim()
            }
            if (args.size < 5 || !args[0].matches(id) || !args[1].matches(id)) continue
            val flags = when {
                series && args.size >= 8 &&
                    args[2].matches(id) && args[3].matches(id) ->
                    listOf(args[4], args[6], args[7])
                else -> args.drop(2).take(3)
            }
            fun flag(value: String): Int = if (
                value == "1" || value.equals("true", ignoreCase = true)
            ) 1 else 0
            return MirrorInitCdn(series, args[0], args[1],
                flag(flags[0]), flag(flags[1]), flag(flags[2]))
        }
        return null
    }
}
