package app.cinema.player

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Independent HTML adapter: metadata remains available if WebView scripts stall. */
internal object MirrorHtmlMetadata {
    data class Result(val info: FilmInfo?, val player: MirrorMoviePage?, val failure: String = "")

    fun parse(filmUrl: String, html: String): Result {
        if (!SourceRules.isValidHttpsUrl(filmUrl)) return Result(null, null, "Неверное зеркало.")
        if (html.length > 1_800_000) return Result(null, null, "Слишком большой ответ зеркала.")
        val document = Jsoup.parse(html, filmUrl)
        val title = document.selectFirst(".b-post__title h1, h1.b-post__title, h1[itemprop=name], .b-post__title")
            ?.text()?.trim().orEmpty()
        val contentFound = document.selectFirst(".b-post__title, .b-post__infotable, .b-post__description_text") != null
        if (!contentFound || title.isBlank()) {
            val raw = (document.title() + " " + document.body().text().take(600)).lowercase()
            val reason = when {
                listOf("captcha","verify you are human","checking your browser","anubis",
                    "проверка браузера").any(raw::contains) ->
                    "Зеркало требует проверку браузера."
                "premium" in raw || "премиум" in raw -> "Зеркало открыло страницу Premium вместо фильма."
                "войти в аккаунт" in raw || "authorization required" in raw ->
                    "Зеркало требует входа в аккаунт."
                else -> "Зеркало не предоставило страницу фильма."
            }
            return Result(null, null, reason)
        }
        val expectedId = MirrorPostId.fromUrl(filmUrl)
        if (expectedId == null) return Result(null, null, "Нет подтверждённого ID фильма.")

        fun absolute(raw: String): String {
            if (raw.isBlank()) return ""
            return runCatching {
                val result = URI(filmUrl).resolve(raw.trim()).toString()
                result.takeIf(SourceRules::isValidHttpsUrl).orEmpty()
            }.getOrDefault("")
        }
        fun poster(element: Element?): String {
            if (element == null) return ""
            val image = if (element.tagName() == "img") element else element.selectFirst("img")
            val src = listOf("data-src", "data-original", "data-lazy-src", "src")
                .firstNotNullOfOrNull { image?.attr(it)?.takeIf(String::isNotBlank) }.orEmpty()
            return absolute(src).takeIf(MirrorPoster::isPhoto).orEmpty()
        }
        val posters = listOfNotNull(
            document.selectFirst(".b-post__poster img"),
            document.selectFirst(".b-post__cover img"),
            document.selectFirst("meta[property=og:image]")
        ).mapNotNull {
            val value = if (it.tagName() == "meta") absolute(it.attr("content")) else poster(it)
            value.takeIf(MirrorPoster::isPhoto)
        }.distinct().take(8)
        val descriptionNodes = document.select(
            ".b-post__description_text, .b-post__description [itemprop=description], .b-post__description > p"
        )
        val description = (descriptionNodes.toList() +
            listOfNotNull(document.selectFirst(".b-post__description")))
            .firstNotNullOfOrNull { entry ->
                val clean = entry.clone()
                clean.select("script, style, noscript, [data-ad], .premium-promo").remove()
                FilmTextSanitizer.description(clean.text()).takeIf(String::isNotBlank)
            }.orEmpty()
        val facts = mutableListOf<Pair<String, String>>()
        val actors = linkedMapOf<String, FilmPerson>()
        val directors = linkedMapOf<String, FilmPerson>()
        fun person(entry: Element, role: String) {
            val link = if (entry.tagName() == "a") entry else entry.selectFirst("a[href]")
            val name = (entry.selectFirst("[itemprop=name]")?.text()
                ?: link?.text() ?: entry.text()).trim().take(90)
            if (name.length !in 2..90) return
            val url = absolute(link?.attr("href").orEmpty())
                .takeIf { SourceRules.sameOrigin(filmUrl, it) }.orEmpty()
            val value = FilmPerson(name, poster(entry), url)
            if (role == "director") directors.putIfAbsent(name.lowercase(), value)
            else actors.putIfAbsent(name.lowercase(), value)
        }
        for (row in document.select(".b-post__infotable tr, .b-post__info tr")) {
            val cells = row.children().filter { it.tagName() in listOf("th", "td") }
            if (cells.size < 2) continue
            val label = cells[0].text().trim().trimEnd(':')
            val body = cells[1]
            val value = body.text().trim()
            if (label.isNotBlank() && value.isNotBlank() && value.length < 165 &&
                listOf("дата выхода","страна","режисс","жанр","время","возраст","в качестве","оригинальное")
                    .any { label.lowercase().contains(it) }) facts.add(label to value)
            val role = when {
                label.startsWith("режисс", true) -> "director"
                label.startsWith("в ролях", true) || label.startsWith("в главных ролях", true) ||
                    label.startsWith("актёр", true) || label.startsWith("актер", true) -> "actor"
                else -> ""
            }
            if (role.isNotBlank()) {
                val names = body.select("a[href]")
                if (names.isNotEmpty()) names.take(30).forEach { person(it, role) }
                else value.split(',', ';').take(20).map(String::trim)
                    .filter { it.length in 2..90 }.forEach {
                        val entry = FilmPerson(it, "")
                        if (role == "director") directors.putIfAbsent(it.lowercase(), entry)
                        else actors.putIfAbsent(it.lowercase(), entry)
                    }
            }
        }
        document.select(".b-post__actors a[href], .b-post__actors_item a[href], [itemprop=actor] a[href]")
            .take(25).forEach { person(it,"actor") }
        document.select(".b-post__directors a[href], [itemprop=director] a[href]")
            .take(12).forEach { person(it,"director") }
        val init = Regex(
            """initCDN(Series|Movies)Events\s*\(\s*['"]?(\d{1,12})['"]?\s*,\s*['"]?(\d{1,12})""",
            RegexOption.IGNORE_CASE
        ).find(html)
        val initId = init?.groupValues?.getOrNull(2).orEmpty()
        val selectedId = init?.groupValues?.getOrNull(3).orEmpty()
        val voices = linkedMapOf<String, MirrorVoice>()
        for (entry in document.select("#translators-list li, .b-translator__item, [data-translator_id], [data-translator-id], [data-translation-id]")) {
            val inner = entry.selectFirst(
                "[data-translator_id], [data-translator-id], [data-translation-id], [data-id]")
            val id = listOf("data-translator_id","data-translator-id","data-translation-id","data-id")
                .firstNotNullOfOrNull { key ->
                    entry.attr(key).takeIf(String::isNotBlank)
                        ?: inner?.attr(key)?.takeIf(String::isNotBlank)
                }.orEmpty()
            if (!id.matches(Regex("[0-9]{1,12}"))) continue
            val name = entry.attr("data-title").ifBlank {
                inner?.attr("data-title").orEmpty()
            }.ifBlank { entry.text() }.trim().take(75)
            if (name.isBlank()) continue
            val requiresSubscription = entry.hasClass("b-translator__premium") ||
                entry.attr("data-premium") == "1" || entry.attr("data-access") == "premium"
            voices.putIfAbsent(id, MirrorVoice(id, name,
                entry.attr("data-camrip").toIntOrNull()?.coerceIn(0,1) ?: 0,
                entry.attr("data-ads").toIntOrNull()?.coerceIn(0,1) ?: 0,
                entry.attr("data-director").toIntOrNull()?.coerceIn(0,1) ?: 0,
                requiresSubscription))
        }
        val selected = document.selectFirst("#translators-list .active[data-translator_id], .b-translator__item.active[data-translator_id]")
            ?.attr("data-translator_id").orEmpty()
        val defaultVoice = selected.takeIf { it in voices }.orEmpty().ifBlank { selectedId }
        val seasons = document.select(".b-simple_season__item[data-tab_id], .b-simple_season__item[data-season_id]")
        val soleSeason = if (seasons.size == 1)
            seasons.first()?.attr("data-tab_id").orEmpty().ifBlank {
                seasons.first()?.attr("data-season_id").orEmpty()
            } else ""
        val episodes = document.select(".b-simple_episode__item[data-episode_id], .b-simple_episode__item[data-episode-id]")
            .mapNotNull { item ->
                val id = item.attr("data-episode_id").ifBlank { item.attr("data-episode-id") }
                val season = item.attr("data-season_id").ifBlank {
                    item.closest("[data-season_id]")?.attr("data-season_id").orEmpty()
                }.ifBlank {
                    item.closest("[id^=simple-episodes-list-]")?.id()
                        ?.removePrefix("simple-episodes-list-").orEmpty()
                }.ifBlank { soleSeason }
                if (!season.matches(Regex("[0-9]{1,4}")) ||
                    !id.matches(Regex("[0-9]{1,4}"))) null
                else MirrorEpisode(season, id, item.text().ifBlank { "Серия $id" })
            }.distinctBy { it.season to it.id }.take(500)
        val franchise = FranchiseParts.validate(document.select(".b-post__partcontent_item")
            .mapNotNull { row ->
                val link = row.selectFirst(".title > a[href], .title a[href]") ?: return@mapNotNull null
                FranchisePartInput(absolute(link.attr("href")),link.text(),
                    poster(row),row.selectFirst(".year")?.text().orEmpty(),
                    row.selectFirst(".rating")?.text().orEmpty(),
                    row.selectFirst(".num")?.text()?.toIntOrNull() ?: 0)
            }, filmUrl)
        val related = document.select(
            ".b-post__related .b-content__inline_item, .b-post__similar .b-content__inline_item"
        ).mapNotNull { item ->
            val a = item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = absolute(a.attr("href"))
            if (!SourceRules.sameOrigin(filmUrl,url) || MirrorPostId.fromUrl(url) == expectedId ||
                MirrorPostId.fromUrl(url) == null) return@mapNotNull null
            CatalogMovie(url, a.text().trim().take(140), poster(item), "")
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }.take(15)
        val info = FilmInfo(
            originalTitle = document.selectFirst(".b-post__origtitle, .b-post__subtitle, .b-post__title_original")?.text().orEmpty(),
            description = description,
            facts = facts.distinctBy { it.first }.take(18),
            rating = document.selectFirst(".b-post__rating, .b-post__rating_tables")?.text().orEmpty().take(120),
            cast = actors.values.take(20),
            related = related,
            franchise = franchise,
            franchiseResolved = franchise.isNotEmpty(),
            directors = directors.values.take(12),
            voices = voices.values.toList(),
            title = CatalogTitleCleaner.clean(title),
            posters = posters,
            portraitsResolved = true,
            defaultVoice = defaultVoice,
            episodes = episodes
        )
        // Only trust source-provided player metadata matching the requested film.
        val player = if ((initId.isBlank() || initId == expectedId) &&
            (voices.isNotEmpty() || defaultVoice.isNotBlank())) {
            MirrorMoviePage(expectedId,
                init?.groupValues?.getOrNull(1)?.equals("Series",true) == true ||
                    URI(filmUrl).path.startsWith("/series/"),
                voices.values.toList(),episodes,defaultVoice=defaultVoice)
        } else null
        return Result(info, player)
    }
}
