package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorPersonSourceTest {
    private val page = "https://film-mirror.example/series/drama/1234-story.html"

    @Test fun acceptsActualPersonInfoPortrait() {
        val raw = """
            {"success":true,"person":{"name":"Саймон Бейкер",
              "name_alt":"Simon Baker","photo":"/uploads/people/52.jpg"}}
        """.trimIndent()
        assertEquals("https://film-mirror.example/uploads/people/52.jpg",
            MirrorPersonSource.photoFromResponse(raw, page, "Саймон Бейкер"))
    }

    @Test fun rejectsUnrelatedProfileAndPlaceholder() {
        assertNull(MirrorPersonSource.photoFromResponse(
            """{"success":true,"person":{"name":"Другой актёр","photo":"/people/wrong.jpg"}}""",
            page, "Саймон Бейкер"))
        assertNull(MirrorPersonSource.photoFromResponse(
            """{"success":true,"person":{"name":"Саймон Бейкер","photo":"nopersonphoto"}}""",
            page, "Саймон Бейкер"))
        assertNull(MirrorPersonSource.photoFromResponse(
            """{"success":false,"person":{"name":"Саймон Бейкер","photo":"/person.jpg"}}""",
            page, "Саймон Бейкер"))
    }

    @Test fun responseRequiresValidPhoto() {
        assertNull(MirrorPersonSource.photoFromResponse(
            """{"success":1,"person":{"name":"Саймон Бейкер","photo":"javascript:evil()"}}""",
            page, "Саймон Бейкер"))
        assertNull(MirrorPersonSource.photoFromResponse("not-json", page, "Саймон Бейкер"))
    }
}
