package app.cinema.player

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Recover source-provided film credits from schema.org JSON-LD when a mirror
 * omits the ordinary Rezka cast table. Never match an unrelated film entry.
 */
internal object MirrorStructuredPeople {
    data class Credits(val actors: List<FilmPerson>, val directors: List<FilmPerson>)

    fun parse(document: Document, filmUrl: String, filmTitle: String, filmId: String): Credits {
        val actors = linkedMapOf<String, FilmPerson>()
        val directors = linkedMapOf<String, FilmPerson>()

        fun absolute(raw: String): String = runCatching {
            val url = URI(filmUrl).resolve(raw.trim()).toString()
            url.takeIf(SourceRules::isValidHttpsUrl).orEmpty()
        }.getOrDefault("")

        fun clean(value: String): String = value.trim()
            .replace(Regex("\\s+"), " ").lowercase().replace('ё', 'е')

        fun values(raw: Any?): List<Any> = when (raw) {
            is JSONArray -> (0 until minOf(raw.length(), 60))
                .mapNotNull { raw.opt(it) }.filter { it != JSONObject.NULL }
            null, JSONObject.NULL -> emptyList()
            else -> listOf(raw)
        }

        fun collect(raw: Any?, dest: MutableMap<String, FilmPerson>) {
            for (item in values(raw)) {
                val node = item as? JSONObject
                val name = (node?.optString("name") ?: (item as? String).orEmpty())
                    .trim().replace(Regex("\\s+"), " ").take(90)
                if (name.length !in 2..90) continue
                val address = node?.optString("url").orEmpty().ifBlank {
                    node?.optString("@id").orEmpty()
                }
                val url = absolute(address).takeIf {
                    it.isNotBlank() && SourceRules.sameOrigin(filmUrl, it)
                }.orEmpty()
                val image = node?.opt("image")
                val imageUrl = when (image) {
                    is String -> image
                    is JSONObject -> image.optString("url").ifBlank { image.optString("contentUrl") }
                    else -> ""
                }
                val photo = absolute(imageUrl).takeIf(MirrorPoster::isPhoto).orEmpty()
                val key = clean(name)
                val previous = dest[key]
                dest[key] = FilmPerson(name,
                    previous?.photo.orEmpty().ifBlank { photo },
                    previous?.url.orEmpty().ifBlank { url })
            }
        }

        val rows = mutableListOf<JSONObject>()
        fun flatten(raw: Any?) {
            when (raw) {
                is JSONArray -> (0 until minOf(raw.length(), 40)).forEach { index ->
                    val node = raw.opt(index)
                    if (node is JSONObject) {
                        rows.add(node)
                        val graph = node.optJSONArray("@graph")
                        if (graph != null) (0 until minOf(graph.length(), 50)).forEach { i ->
                            graph.optJSONObject(i)?.let(rows::add)
                        }
                    }
                }
                is JSONObject -> {
                    rows.add(raw)
                    raw.optJSONArray("@graph")?.let { graph ->
                        (0 until minOf(graph.length(), 50)).forEach { i ->
                            graph.optJSONObject(i)?.let(rows::add)
                        }
                    }
                }
            }
        }
        document.select("script[type=application/ld+json]").take(12).forEach { script ->
            val json = script.data().ifBlank { script.html() }.trim()
            if (json.length in 2..200_000) runCatching {
                flatten(JSONTokener(json).nextValue())
            }
        }

        for (row in rows.take(120)) {
            val types = values(row.opt("@type")).mapNotNull { it as? String }
                .map { it.substringAfterLast('/') }
            if (types.none { it in setOf("Movie", "TVSeries", "TVSeason", "Episode") })
                continue
            val page = when (val raw = row.opt("mainEntityOfPage")) {
                is JSONObject -> raw.optString("@id").ifBlank { raw.optString("url") }
                is String -> raw
                else -> ""
            }
            val address = row.optString("url").ifBlank { page }
            if (address.isNotBlank()) {
                val target = absolute(address)
                if (!SourceRules.sameOrigin(filmUrl, target) ||
                    MirrorPostId.fromUrl(target) != filmId) continue
            } else if (clean(row.optString("name")) != clean(filmTitle)) {
                continue
            }
            collect(row.opt("actor"), actors)
            collect(row.opt("actors"), actors)
            collect(row.opt("director"), directors)
        }
        return Credits(actors.values.take(30), directors.values.take(15))
    }
}
