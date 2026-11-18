package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorStreamFailoverTest {
    @Test fun fallbackPrioritizesSavedMirrorsAndChecksBroaderPool() {
        val film = "https://hdrezka.club/series/drama/123-mentalist.html"
        val options = MirrorStreamFailover.candidates(
            film, listOf("https://hdrezka.website/", "https://hdrezka.sh/")
        )
        assertTrue(options.isNotEmpty())
        assertEquals("https://hdrezka.website/series/drama/123-mentalist.html", options.first())
        assertEquals("https://hdrezka.sh/series/drama/123-mentalist.html", options[1])
        assertTrue(options.contains("https://rezka.ag/series/drama/123-mentalist.html"))
        assertTrue(options.contains(
            "https://hdrezka.website/series/drama/123-mentalist.html"
        ))
        assertTrue(options.all { MirrorPostId.fromUrl(it) == "123" })
        assertTrue(options.none { SourceRules.sameOrigin(it, film) })
        assertEquals(options.size, options.distinct().size)
        assertTrue(options.size <= 12)
        assertTrue("More than the former seven should be checked", options.size > 7)
    }

    @Test fun invalidPageDoesNotTriggerCrossMirrorRequests() {
        assertTrue(MirrorStreamFailover.candidates(
            "https://hdrezka.club/no-film", listOf("https://rezka.ag/")
        ).isEmpty())
    }
}
