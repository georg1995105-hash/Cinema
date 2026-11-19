package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class LiftWebSourceTest {
    @Test fun officialWebPagesOnly() {
        assertTrue(LiftWebSource.allowedMainFrame("https://liftw.ws/"))
        assertTrue(LiftWebSource.allowedMainFrame("https://liftw.ws/search.html"))
        assertTrue(LiftWebSource.allowedMainFrame("https://liftw.ws/details.html?id=6190"))
        assertFalse(LiftWebSource.allowedMainFrame("http://liftw.ws/search.html"))
        assertFalse(LiftWebSource.allowedMainFrame("https://liftw.ws.evil.test/"))
        assertFalse(LiftWebSource.allowedMainFrame("https://other.example/"))
        assertFalse(LiftWebSource.allowedMainFrame("https://user@liftw.ws/"))
    }

    @Test fun movieSearchStartsAtFirstPartyPage() {
        assertEquals(LiftWebSource.HOME, LiftWebSource.startUrl(""))
        assertEquals(LiftWebSource.SEARCH, LiftWebSource.startUrl("Бригада"))
    }

    @Test fun filmTitleCannotEscapeJavascriptString() {
        val code = LiftWebSource.fillSearchScript("A\\\"; alert(1); //")
        assertTrue(code.contains("HTMLInputElement"))
        assertTrue(code.contains("dispatchEvent"))
        assertFalse(code.contains("call(field,A"))
        assertTrue(LiftWebSource.safeTitle("  Бригада  ").equals("Бригада"))
    }
}
