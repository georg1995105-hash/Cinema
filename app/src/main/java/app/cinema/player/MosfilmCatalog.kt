package app.cinema.player

import java.net.URI

/**
 * Manually verified full-length films published by Mosfilm itself (official
 * RUTUBE account 25963146). RUTUBE explicitly documents third-party embeds.
 * We do NOT automate RUTUBE scraping, extract stream URLs, bypass DRM, or
 * alter the official embedded player. Videos remain hosted by the studio.
 *
 * Discovery is a finite, licensed-source collection, not a claim that every
 * film or serial in the Cinema metadata catalog can be streamed.
 */
internal object MosfilmCatalog {
    internal data class Film(
        val id: String,
        val title: String,
        val year: Int,
        val genre: String,
        val seconds: Int,
        val poster: String,
        val director: String = "",
        val synopsis: String = ""
    )
    private val films: List<Film> = listOf(
        Film("dcd9dcbf08d177d51656c01dd7bd75b4", "Красная палатка", 1969, "Драма", 9417,
            "https://pic.rtbcdn.ru/video/2026-11-06/59/ef/59ef40716a18b896b1252c2720648776.jpg",
            "Михаил Калатозов",
            "После крушения дирижабля «Италия» в арктических льдах участники экспедиции пытаются выжить, пока спасатели готовят поисковую операцию."),
        Film("1a7baf7b3eb80925774b0174b1e2c0f9", "Бриллиантовая рука", 1968, "Комедия", 5973,
            "https://pic.rtbcdn.ru/video/e0/81/e081dd88129632333a2db734f0fc083e.jpg",
            "Леонид Гайдай",
            "Во время заграничного путешествия Семён Семёныч случайно оказывается втянут в операцию контрабандистов."),
        Film("de108b940b59e5e9d361077328ca9201", "Иван Васильевич меняет профессию", 1973, "Комедия", 5274,
            "https://pic.rtbcdn.ru/video/20/bf/20bf211ef9fc2f4fb098d2293a8c3f7e.jpg",
            "Леонид Гайдай",
            "Инженер Шурик строит машину времени: Иван Грозный попадает в современную Москву, а управдом Бунша — в эпоху царя."),
        Film("fcb693c38528938caa3b70101b7c58bf", "Операция «Ы» и другие приключения Шурика", 1965, "Комедия", 5743,
            "https://pic.rtbcdn.ru/video/ff/bd/ffbd828e5fe5e3c6a1349ed3110c2c8c.jpg", "Леонид Гайдай",
            "Три истории о приключениях студента Шурика и его встречах с изобретательными мошенниками и хулиганами."),
        Film("2959f7a99504290930cc68951bd2f5ce", "Кавказская пленница", 1966, "Комедия", 4909,
            "https://pic.rtbcdn.ru/video/2b/5d/2b5d729e80b798262a487614acaf13f9.jpg", "Леонид Гайдай",
            "Шурик приезжает на Кавказ собирать фольклор и пытается спасти Нину, похищенную по распоряжению местного начальника."),
        Film("68781d890618ea50933ec020fd5060bf", "Любовь и голуби", 1984, "Комедия", 6317,
            "https://pic.rtbcdn.ru/video/2024-11-25/30/c6/30c6653b1cf7f2ab43d774d84ee572e8.jpg",
            "Владимир Меньшов",
            "Рабочий Василий Кузякин во время отпуска увлекается другой женщиной, но по-настоящему скучает по семье и дому."),
        Film("e6bb01652353356ca0333f34a957353c", "Берегись автомобиля", 1966, "Комедия", 5580,
            "https://pic.rtbcdn.ru/video/2024-09-27/67/6a/676ac01e0c00d1d8cdd1dcf2ede8379d.jpg", "Эльдар Рязанов"),
        Film("74ef826bbe750918b3baac15c118f1a8", "Солярис", 1972, "Фантастика", 10025,
            "https://pic.rtbcdn.ru/video/2025-11-21/98/d6/98d61187cac7ee73088eec70205231b3.jpg", "Андрей Тарковский"),
        Film("7358ddc35dd78d6f136be8e99f8be457", "Девчата", 1961, "Комедия", 5769,
            "https://pic.rtbcdn.ru/video/2026-04-07/10/d0/10d01bad36b78d662a446d6fc27e5607.jpg", "Юрий Чулюкин"),
        Film("2489d73325e7135837a6663027535d61", "Карнавальная ночь", 1956, "Комедия", 4567,
            "https://pic.rtbcdn.ru/video/2025-12-26/79/c2/79c2d2eb3570e0799e2304fe86bc84a5.jpg", "Эльдар Рязанов"),
        Film("517a03c96cb712b66c3490e8c5460271", "Земля Санникова", 1973, "Приключения", 5605,
            "https://pic.rtbcdn.ru/video/2026-08-27/80/bc/80bc37d1250afd387cf6d4ac97c8faf7.jpg"),
        Film("b13ef664275a06c4dc6e46337cc52719", "Деловые люди", 1962, "Комедия", 5026,
            "https://pic.rtbcdn.ru/video/2026-10-12/7f/71/7f71b129e63056813cec114dfd465295.jpg", "Леонид Гайдай"),
        Film("e76bfe1f071d9a938e284cb359523a24", "Иваново детство", 1962, "Драма", 5726,
            "https://pic.rtbcdn.ru/video/2026-05-08/4e/22/4e22c959775a4849dec9cc46b64031f4.jpg", "Андрей Тарковский"),
        Film("f9fddf8e570a62c744b20459f7ba9209", "По прозвищу «Зверь»", 1990, "Боевик", 5252,
            "https://pic.rtbcdn.ru/video/2026-07-18/2c/4e/2c4e2c5a4ab2064221c6bce7da83d931.jpg", "Александр Муратов"),
        Film("01a787842a4825f7d1ad5b32ef884949", "Гранатовый браслет", 1964, "Драма", 5408,
            "https://pic.rtbcdn.ru/video/2026-09-25/2b/2a/2b2a833ce9e6e4af688436fb87f4c82a.jpg", "Абрам Роом"),
        Film("90bd6d6e5386629a5acb4ee79ae5a5e9", "Гори, гори, моя звезда", 1969, "Трагикомедия", 5642,
            "https://pic.rtbcdn.ru/video/2026-09-11/7a/55/7a55a29a03425fade51ce765495fa255.jpg", "Александр Митта"),
        Film("591b87fe05726931c18aeb7d55bcd109", "Внимание, черепаха!", 1969, "Комедия", 5121,
            "https://pic.rtbcdn.ru/video/2026-08-14/fe/8b/fe8bc427de83d5ad51e807f360d68050.jpg", "Ролан Быков"),
        Film("22949ecd4e36c4abe450c4309529d927", "Взлёт", 1979, "Драма", 8259,
            "https://pic.rtbcdn.ru/video/2026-07-30/e5/09/e50932b41d9ea94b1b81317d4f3a9454.jpg", "Савва Кулиш"),
        Film("e965b253d09f6359d71ea3e328740503", "Дядя Ваня", 1970, "Драма", 6166,
            "https://pic.rtbcdn.ru/video/2026-07-22/0c/22/0c2244f2c9f1a389a94bb31647aa6fe7.jpg", "Андрей Кончаловский"),
        Film("2b20fbd25503fc60231ec9577ebc2748", "Первый учитель", 1965, "Драма", 5955,
            "https://pic.rtbcdn.ru/video/2026-07-22/e0/f7/e0f7f53c0ad7e0d1a23bb7b50dcb1366.jpg", "Андрей Кончаловский"),
        Film("0648fd5f7438d672b72c5d50c0b8cf2b", "Ещё раз про любовь", 1968, "Мелодрама", 5730,
            "https://pic.rtbcdn.ru/video/2026-06-05/ce/3d/ce3d7db8c4bd9a02b54930b573fed800.jpg", "Георгий Натансон"),
        Film("7ac9df1b7bc81d726262bc363bece056", "Дайте жалобную книгу", 1964, "Комедия", 5374,
            "https://pic.rtbcdn.ru/video/2026-05-22/e7/74/e77441287f6d2d76916cde6c107c726f.jpg", "Эльдар Рязанов"),
        Film("5e4eb41dc56647c92b6a50111c4b413c", "Жизнь прекрасна", 1980, "Драма", 5956,
            "https://pic.rtbcdn.ru/video/2026-04-17/66/20/66201510b04b6f4fdab2bcf26c8fe52c.jpg", "Григорий Чухрай"),
        Film("70861fbae89e4d29f2dc35dad1f29f56", "Степь", 1977, "Драма", 7937,
            "https://pic.rtbcdn.ru/video/2026-04-03/82/d3/82d3f203ea1e7183e238ef998e0be734.jpg", "Сергей Бондарчук"),
        Film("96ecbcc1770f422243fe37ddd3dbb186", "Лунная радуга", 1983, "Фантастика", 5358,
            "https://pic.rtbcdn.ru/video/2026-02-17/dc/77/dc772d77c471bf417761e3e2bf0f7070.jpg", "Андрей Ермаш"),
        Film("c3f9a356e9e04ac607b4d7d295648eab", "Москва слезам не верит · 1 серия", 1979, "Драма", 4007,
            "https://pic.rtbcdn.ru/video/2025-04-25/67/10/6710885a10b435323171ba4773ddefaf.jpg", "Владимир Меньшов"),
        Film("993a315cc6dbb860b88d1b1171e85d3b", "Москва слезам не верит · 2 серия", 1979, "Драма", 4895,
            "https://pic.rtbcdn.ru/video/2026-01-19/3c/bc/3cbc2877e2fb4cc5e1ef8256e9a1c576.jpg", "Владимир Меньшов"),
        Film("9b8a20607f7a35bde58aaf51fd9d8348", "Служебный роман · 1 серия", 1977, "Комедия", 5040,
            "https://pic.rtbcdn.ru/video/96/95/96959752e2a8bb42ce5155db9bed5f1c.jpg", "Эльдар Рязанов"),
        Film("2d0899d6388926b7caa66a09e9073572", "Ирония судьбы · 1 серия", 1976, "Комедия", 5992,
            "https://pic.rtbcdn.ru/video/2026-01-23/a4/7e/a47ed2e73b5cc4a70ea5491fb8673395.jpg", "Эльдар Рязанов"),
        Film("83e55d3104776e47517b7bbab318e018", "Ирония судьбы · 2 серия", 1976, "Комедия", 5065,
            "https://pic.rtbcdn.ru/video/2025-12-16/90/a7/90a7e066ff6fc3387ca2e71af8f97fb0.jpg", "Эльдар Рязанов")
    )
    private val byId = films.associateBy(Film::id)
    private val idPattern = Regex("[0-9a-f]{32}")

    fun videoUrl(id: String): String {
        require(idPattern.matches(id) && byId.containsKey(id))
        return "https://rutube.ru/video/$id/"
    }
    fun videoId(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "rutube.ru" || uri.query != null ||
            uri.fragment != null || uri.userInfo != null) return@runCatching null
        val id = uri.path.removePrefix("/video/").removeSuffix("/")
        if (!uri.path.startsWith("/video/") || !idPattern.matches(id) || id !in byId) null else id
    }.getOrNull()

    fun isVideoUrl(url: String): Boolean = videoId(url) != null

    fun embedUrl(url: String): String? {
        val id = videoId(url) ?: return null
        return "https://rutube.ru/play/embed/$id"
    }

    fun isEmbedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val id = uri.path.removePrefix("/play/embed/").removeSuffix("/")
        uri.scheme == "https" && uri.host == "rutube.ru" &&
            uri.query == null && uri.fragment == null && uri.userInfo == null &&
            uri.path.startsWith("/play/embed/") && idPattern.matches(id) && id in byId
    }.getOrDefault(false)

    fun catalog(kind: String): List<CatalogMovie> = if (kind == "Сериалы" || kind == "Мультфильмы")
        emptyList() else films.map { film ->
        CatalogMovie(videoUrl(film.id), film.title, film.poster,
            "${film.year} · ${film.genre} · Мосфильм")
    }

    fun search(query: String, kind: String): List<CatalogMovie> {
        if (kind == "Сериалы" || kind == "Мультфильмы") return emptyList()
        val normalized = query.lowercase().trim().replace('ё','е')
        if (normalized.isBlank()) return emptyList()
        return catalog("Фильмы").filter { it.title.lowercase().replace('ё','е').contains(normalized) }
    }

    fun metadata(url: String): CinemetaDetails {
        val film = byId[videoId(url)] ?: throw IllegalArgumentException("Фильм не найден в проверенной подборке")
        val item = CatalogMovie(url, film.title, film.poster, "${film.year} · ${film.genre} · Мосфильм")
        return CinemetaDetails(
            item = item, description = film.synopsis, originalTitle = film.title,
            rating = "", country = "СССР",
            runtime = "${film.seconds / 60} мин", genres = listOf(film.genre),
            directors = film.director.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
            actors = emptyList(), episodes = emptyList(),
            source = "Мосфильм · официальный RUTUBE", sourceUrl = videoUrl(film.id)
        )
    }
}
