package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorCatalogMirrorsTest {
    @Test fun blockedKnownMirrorCanFallBackWithoutLosingSeriesPathOrQuery() {
        val origin = "https://omnirezka.tv/series/?filter=watching"
        assertEquals(
            listOf(
                "https://omnirezka.tv/series/?filter=watching",
                "https://rezka-ua.pub/series/?filter=watching",
                "https://hdrezka.sh/series/?filter=watching"
            ),
            MirrorCatalogMirrors.candidates(origin)
        )
    }

    @Test fun searchQueryIsNeverReencodedOrDroppedDuringFailover() {
        val original = MirrorCatalog.searchUrl("https://omnirezka.tv/", "бригада")
        val mirrors = MirrorCatalogMirrors.candidates(original)
        assertEquals(3, mirrors.size)
        assertTrue(mirrors.all { it.contains("q=%D0%B1%D1%80%D0%B8%D0%B3%D0%B0%D0%B4%D0%B0") })
        assertFalse(mirrors.any { it.contains("%25D0") })
    }

    @Test fun selectedWorkingMirrorIsTriedOnlyOnce() {
        assertEquals(
            listOf(
                "https://rezka-ua.pub/",
                "https://hdrezka.sh/"
            ),
            MirrorCatalogMirrors.candidates("https://rezka-ua.pub/")
        )
    }

    @Test fun customMirrorIsNotSilentlyRewrittenToAnUnrelatedDomain() {
        val custom = "https://private-mirror.example/search/?q=test"
        assertEquals(listOf(custom), MirrorCatalogMirrors.candidates(custom))
        assertTrue(MirrorCatalogMirrors.candidates("http://omnirezka.tv/").isEmpty())
        assertTrue(MirrorCatalogMirrors.candidates("https://user:secret@omnirezka.tv/").isEmpty())
    }

    @Test fun originalFilmLinkFromFallbackKeepsItsRealHost() {
        val url = "https://rezka-ua.pub/series/drama/8155-brigada-2002.html"
        assertTrue(MirrorSitePolicy.isRecognizedMirror(url))
        assertFalse(SourceRules.sameOrigin(url, "https://omnirezka.tv/"))
        assertEquals("8155", MirrorPostId.fromUrl(url))
    }
}
