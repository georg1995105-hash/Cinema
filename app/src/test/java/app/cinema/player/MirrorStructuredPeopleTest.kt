package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorStructuredPeopleTest {
    private val film = "https://my-film-mirror.example/films/drama/901-story.html"

    @Test fun retrievesCreditsFromSchemaOrgWhenVisibleCastIsMissing() {
        val html = """
            <article class="b-post">
              <h1 class="b-post__title">История</h1>
              <div class="b-post__description_text">Описание фильма.</div>
              <script type="application/ld+json">{
                "@context":"https://schema.org",
                "@type":"Movie",
                "url":"https://my-film-mirror.example/films/drama/901-story.html",
                "name":"История",
                "actor":[{"@type":"Person","name":"Актёр Первый",
                  "url":"/person/41.html","image":{"url":"/images/41.jpg"}},
                  {"@type":"Person","name":"Актриса Вторая"}],
                "director":{"@type":"Person","name":"Режиссёр Третий",
                  "url":"/person/12.html"}
              }</script>
            </article>
        """.trimIndent()
        val data = requireNotNull(MirrorHtmlMetadata.parse(film, html).info)
        assertEquals(listOf("Актёр Первый", "Актриса Вторая"), data.cast.map { it.name })
        assertEquals("https://my-film-mirror.example/person/41.html", data.cast.first().url)
        assertEquals("https://my-film-mirror.example/images/41.jpg", data.cast.first().photo)
        assertEquals(listOf("Режиссёр Третий"), data.directors.map { it.name })
        assertTrue("No franchise is also a finished franchise check", data.franchiseResolved)
    }

    @Test fun ignoresUnrelatedJsonLdFilmAndUnverifiedExternalProfile() {
        val html = """
          <article class="b-post"><h1 class="b-post__title">История</h1>
            <div class="b-post__description_text">Описание.</div>
            <script type="application/ld+json">{
              "@graph":[
                {"@type":"Movie",
                 "url":"https://my-film-mirror.example/films/drama/902-other.html",
                 "actor":[{"name":"Чужой Актёр"}]},
                {"@type":"Movie",
                 "url":"https://my-film-mirror.example/films/drama/901-story.html",
                 "director":[{"name":"Свой Режиссёр",
                              "url":"https://stranger.example/person/7"}]}
              ]
            }</script>
          </article>
        """.trimIndent()
        val data = requireNotNull(MirrorHtmlMetadata.parse(film, html).info)
        assertTrue(data.cast.isEmpty())
        assertEquals("Свой Режиссёр", data.directors.single().name)
        assertEquals("", data.directors.single().url)
    }

    @Test fun refusesTitleMismatchWhenStructuredDataHasNoFilmUrl() {
        val html = """
          <div class="b-post"><h1 class="b-post__title">История</h1>
            <script type="application/ld+json">
              {"@type":"Movie","name":"Другая картина",
               "actor":{"name":"Чужой Актёр"}}
            </script>
          </div>
        """.trimIndent()
        val data = requireNotNull(MirrorHtmlMetadata.parse(film, html).info)
        assertTrue(data.cast.isEmpty())
    }

    @Test fun separateFranchiseLinkStillRequiresResolution() {
        val html = """
          <div class="b-post"><h1 class="b-post__title">История</h1>
            <a class="b-post__franchise_link_title" href="/franchise/901">Части</a>
          </div>
        """.trimIndent()
        val data = requireNotNull(MirrorHtmlMetadata.parse(film, html).info)
        assertFalse(data.franchiseResolved)
    }
}
