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
    @Test fun sameOriginRedirectIsSafeButForeignDomainIsNot() {
        assertTrue(MirrorAccountGateway.isSafeRedirect("https://mirror.example", "/user/"))
        assertFalse(MirrorAccountGateway.isSafeRedirect("https://mirror.example", "https://other.example/"))
        assertFalse(MirrorAccountGateway.isSafeRedirect("https://mirror.example", "http://mirror.example/"))
        assertFalse(MirrorAccountGateway.isSafeRedirect("https://mirror.example", "//other.example/"))
    }
    @Test fun redirectCanRetryOnlyInOwnBrowserSession() {
        assertTrue(MirrorLoginProtocol.decode(302, "").retryInBrowser)
        assertTrue(MirrorLoginProtocol.decode(403, "").retryInBrowser)
        assertFalse(MirrorLoginProtocol.decode(401, "").retryInBrowser)
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
