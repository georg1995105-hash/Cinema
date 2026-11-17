package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class FranchiseSourceSelectorsTest {
    /** Guard against regressions to the heading-only parser that never displayed franchises. */
    @Test fun usesSiteNativeFranchiseLinkAndPartRows() {
        assertTrue(FilmPageParser.franchiseJavascript.contains("b-post__franchise_link_title"))
        assertTrue(FilmPageParser.franchiseJavascript.contains("b-post__partcontent_item"))
    }

    @Test fun remoteFetchKeepsSameOriginAndCompletes() {
        assertTrue(FilmPageParser.franchiseJavascript.contains("endpoint.origin!==location.origin"))
        assertTrue(FilmPageParser.franchiseJavascript.contains("window.__kinoFranchiseDone=true"))
    }
}
