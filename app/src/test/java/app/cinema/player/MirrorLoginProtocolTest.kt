package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorLoginProtocolTest {
    @Test fun acceptsVerifiedResponseOnly() {
        assertTrue(MirrorLoginProtocol.decode(200, """{"success":true}""").success)
    }
    @Test fun rejectsInvalidCredentials() {
        val result = MirrorLoginProtocol.decode(200, """{"success":false,"message":"Неверные данные"}""")
        assertFalse(result.success)
        assertTrue(result.message.contains("Неверные"))
    }
    @Test fun rejectsRedirect() {
        assertFalse(MirrorLoginProtocol.decode(302, "").success)
    }
    @Test fun rejectsMalformedResponse() {
        assertFalse(MirrorLoginProtocol.decode(200, "<html>ok</html>").success)
    }
    @Test fun stripsMarkupFromServerMessage() {
        val result = MirrorLoginProtocol.decode(200, """{"success":false,"message":"<b>Ошибка</b>"}""")
        assertEquals("Ошибка", result.message)
    }
    @Test fun onlyUsesHttpsOrigins() {
        assertEquals("https://mirror.example",
            MirrorAccountGateway.origin("https://mirror.example/films/a"))
        assertNull(MirrorAccountGateway.origin("http://mirror.example"))
    }
}
