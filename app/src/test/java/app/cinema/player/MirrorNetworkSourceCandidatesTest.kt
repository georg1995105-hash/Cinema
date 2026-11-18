package app.cinema.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorNetworkSourceCandidatesTest {
    @Test fun referenceMirrorPoolIsNotTruncatedAtTen() {
        val film = "https://rezka-ua.pub/films/fiction/30651-film.html"
        val candidates = MirrorNetworkSource.candidateUrls(
            film,
            listOf("https://hdrezka.club/", "https://hdrezka.kim/")
        )

        assertTrue(candidates.size > 10)
        assertTrue(candidates.contains(
            "https://rezka.ag/films/fiction/30651-film.html"
        ))
        assertTrue(candidates.contains(
            "https://hdrzk.org/films/fiction/30651-film.html"
        ))
        assertTrue(candidates.contains(
            "https://omnirezka.tv/films/fiction/30651-film.html"
        ))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test fun mirrorsFromReferenceApkAreRecognized() {
        listOf(
            "https://hdrzk.org/",
            "https://omnirezka.tv/",
            "https://hello-rezka.tv/",
            "https://rezka.pub/",
            "https://rezkery.com/",
            "https://rezka-kz.tv/",
            "https://rezka.ag/"
        ).forEach {
            assertTrue("Expected reference mirror: $it",
                MirrorSitePolicy.isRecognizedMirror(it))
        }
    }
}
