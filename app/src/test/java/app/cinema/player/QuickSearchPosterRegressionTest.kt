package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class QuickSearchPosterRegressionTest {
    @Test fun missingPosterUsesSameOriginFilmPageWithTimeout() {
        val js=MirrorQuickSearch.javascript("бригада")
        assertTrue(js.contains("fetch(card.url"))
        assertTrue(js.contains("!sameOrigin(response.url)"))
        assertTrue(js.contains("controller.abort()"))
        assertTrue(js.contains("slice(0,8)"))
        assertTrue(js.contains("meta[property="))
        assertTrue(js.contains("b-post__cover img"))
    }
    @Test fun unsafeTextIsQuotedAsData() {
        val js=MirrorQuickSearch.javascript("бригада');alert(1);//")
        assertTrue(js.contains("var q = "))
        assertTrue(js.contains("encodeURIComponent(q)"))
        assertFalse(js.contains("fetch('https://"))
    }
}
