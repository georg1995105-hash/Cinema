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
        // The reference client's film parser reads the cast from
        // div.persons-list-holder, not only from the information table.
        // These sections are used by film pages where the table has no actor rows.
        for (holder in document.select("div.persons-list-holder")) {
            val heading = (
                holder.selectFirst("span.inline h2, h2")?.text().orEmpty() +
                    " " + holder.attr("data-role") + " " + holder.attr("data-id")
                ).trim().lowercase()
            val role = when {
                "режисс" in heading || "director" in heading -> "director"
                "в ролях" in heading || "актёр" in heading ||
                    "актер" in heading || "actor" in heading -> "actor"
                else -> continue
            }
            val items = holder.select("span.item").takeIf { it.isNotEmpty() }
                ?: holder.select("span.person-name-item")
            for (entry in items.take(35)) {
                val label = entry.selectFirst("span.person-name-item") ?: entry
                val name = label.text().trim().take(90)
                if (name.length !in 2..90) continue
                val photoAttribute = entry.attr("data-photo")
                    .ifBlank { label.attr("data-photo") }
                    .ifBlank { entry.selectFirst("[data-photo]")?.attr("data-photo").orEmpty() }
                    .trim()
                val photo = photoAttribute.takeUnless {
                    it.isBlank() || it.equals("null", true) ||
                        it.contains("nopersonphoto", ignoreCase = true)
                }?.let(::absolute)?.takeIf(MirrorPoster::isPhoto).orEmpty()
                val link = entry.selectFirst("a[href]")?.attr("href").orEmpty()
                val url = absolute(link).takeIf {
                    SourceRules.sameOrigin(filmUrl, it)
                }.orEmpty()
                val value = FilmPerson(name, photo, url)
                if (role == "director") directors.putIfAbsent(name.lowercase(), value)
                else actors.putIfAbsent(name.lowercase(), value)
            }
        }
        // A real mirror may provide complete credits in schema.org JSON-LD
        // even when the visible cast table has not been rendered.
        val structured = MirrorStructuredPeople.parse(document, filmUrl, title, expectedId)
        fun mergePerson(dest: MutableMap<String, FilmPerson>, incoming: FilmPerson) {
            val key = incoming.name.trim().lowercase().replace('ё', 'е')
            val old = dest[key]
            if (old == null) dest[key] = incoming
            else dest[key] = old.copy(
                photo = old.photo.ifBlank { incoming.photo },
                url = old.url.ifBlank { incoming.url }
            )
        }
        structured.actors.forEach { mergePerson(actors, it) }
        structured.directors.forEach { mergePerson(directors, it) }
        val imdbId = document.selectFirst(
            "span.imdb a[href], .imdb a[href], a[href*=imdb.com/title/]"
        )?.let { Regex("""tt\d{5,12}""", RegexOption.IGNORE_CASE)
            .find(it.attr("href").ifBlank { it.text() })?.value }.orEmpty()
        val kinopoiskId = document.selectFirst(
            "span.kp a[href], .kp a[href], a[href*=kinopoisk.ru/film/]"
        )?.let { Regex("""/film/(\d{1,12})""", RegexOption.IGNORE_CASE)
            .find(it.attr("href").ifBlank { it.text() })?.groupValues?.getOrNull(1) }.orEmpty()
        val init = MirrorInitCdnParser.parse(html)
        val initId = init?.postId.orEmpty()
        val selectedId = init?.translatorId.orEmpty()
        val initCamrip = init?.camrip ?: 0
        val initAds = init?.ads ?: 0
        val initDirector = init?.director ?: 0
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
            val requiresSubscription = listOfNotNull(entry, inner).any { node ->
                node.hasClass("b-prem_translator") ||
                    node.hasClass("b-translator__premium") ||
                    node.attr("data-premium") == "1" ||
                    node.attr("data-is-premium") == "1" ||
                    node.attr("data-access").equals("premium", true)
            }
            fun sourceFlag(vararg names: String, defaultValue: Int): Int {
                for (key in names) {
                    val raw = entry.attr(key).ifBlank { inner?.attr(key).orEmpty() }
                    raw.toIntOrNull()?.takeIf { it in 0..1 }?.let { return it }
                }
                return if (id == selectedId) defaultValue else 0
            }
            voices.putIfAbsent(id, MirrorVoice(id, name,
                sourceFlag("data-camrip", "data-is-camrip", "data-is_camrip",
                    defaultValue = initCamrip),
                sourceFlag("data-ads", "data-is-ads", "data-is_ads",
                    defaultValue = initAds),
                sourceFlag("data-director", "data-is-director", "data-is_director",
                    defaultValue = initDirector),
                requiresSubscription,
                MirrorMediaParser.sanitizeFavs(
                    entry.attr("data-favs").ifBlank { inner?.attr("data-favs").orEmpty() }
                )))
        }
        val selected = document.selectFirst("#translators-list .active[data-translator_id], .b-translator__item.active[data-translator_id]")
            ?.attr("data-translator_id").orEmpty()
        val defaultVoice = selected.takeIf { it in voices }.orEmpty().ifBlank { selectedId }
        if (defaultVoice.matches(Regex("[0-9]{1,12}")) && defaultVoice !in voices) {
            // Some pages expose the valid translator only through initCDN*Events.
            // Keep that source-provided ID usable instead of disabling playback.
            voices[defaultVoice] = MirrorVoice(
                defaultVoice, "По умолчанию", initCamrip, initAds, initDirector
            )
        }
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
        val runtimeValue = document.selectFirst(
            ".b-post [itemprop=duration], .b-post__infotable [itemprop=duration], meta[property=video:duration]"
        )?.let { node ->
            node.attr("content").ifBlank { node.attr("datetime") }.ifBlank { node.text() }
        }.orEmpty().ifBlank {
            facts.firstOrNull { (label, _) ->
                Regex("""(?iu)^(время|длительность|продолжительность|хронометраж)""")
                    .containsMatchIn(label.trim())
            }?.second.orEmpty()
        }
        val expectedDurationMs = MirrorPreview.expectedDuration(runtimeValue)
        val unresolvedProfiles = (actors.values + directors.values).any {
            it.photo.isBlank() && it.url.isNotBlank()
        }
        val info = FilmInfo(
            originalTitle = document.selectFirst(".b-post__origtitle, .b-post__subtitle, .b-post__title_original")?.text().orEmpty(),
            description = description,
            facts = facts.distinctBy { it.first }.take(18),
            rating = document.selectFirst(".b-post__rating, .b-post__rating_tables")?.text().orEmpty().take(120),
            cast = actors.values.take(20),
            related = related,
            franchise = franchise,
            // No franchise section is a resolved state, not an endless wait.
            // A separate franchise URL still needs WebView to load its parts.
            franchiseResolved = franchise.isNotEmpty() ||
                document.selectFirst(
                    "a.b-post__franchise_link_title[href], .b-post__franchise_link_title[href]"
                ) == null,
            directors = directors.values.take(12),
            voices = voices.values.toList(),
            title = CatalogTitleCleaner.clean(title),
            posters = posters,
            portraitsResolved = !unresolvedProfiles,
            defaultVoice = defaultVoice,
            episodes = episodes,
            kinopoiskId = kinopoiskId,
            imdbId = imdbId
        )
        // Preserve source-provided request state even when the native HTTP
        // metadata parser finishes before the WebView's page scripts execute.
        val favs = document.selectFirst("#ctrl_favs, input[name=ctrl_favs], #favs, input[name=favs]")?.attr("value").orEmpty()
            .ifBlank { document.selectFirst("[data-favs]")?.attr("data-favs").orEmpty() }
            .takeIf { it.matches(Regex("[A-Za-z0-9_+=/-]{1,256}")) }.orEmpty()
        // Only trust source-provided player metadata matching the requested film.
        val player = if ((initId.isBlank() || initId == expectedId) &&
            (voices.isNotEmpty() || defaultVoice.isNotBlank())) {
            MirrorMoviePage(
                expectedId,
                init?.isSeries == true ||
                    URI(filmUrl).path.startsWith("/series/"),
                voices.values.toList(),
                episodes,
                favs = favs,
                defaultVoice = defaultVoice,
                defaultFlags = voices[defaultVoice],
                expectedDurationMs = expectedDurationMs
            )
        } else null
        return Result(info, player)
    }
}
