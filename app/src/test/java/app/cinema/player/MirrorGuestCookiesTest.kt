package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorGuestCookiesTest {
    @Test fun guestStreamDoesNotCarryAccountLoginCookies() {
        val actual = MirrorGuestCookies.fromStoredCookies(listOf(
            "dle_user_id=123; dle_password=private; hdmbbs=0; techaro.lol-anubis-auth=guestChallenge",
            "some_other_tracking=abc"
        ))
        assertEquals("hdmbbs=1; techaro.lol-anubis-auth=guestChallenge", actual)
        assertFalse(actual.contains("dle_user_id"))
        assertFalse(actual.contains("dle_password"))
    }

    @Test fun customUserMirrorGetsGuestCookieForFilmAndAjax() {
        val page = "https://my-film-mirror.example/series/drama/901-story.html"
        assertTrue(MirrorGuestCookies.isGuestFilmRequest(
            page, "https://my-film-mirror.example/"))
        assertTrue(MirrorGuestCookies.isGuestFilmRequest(
            "https://my-film-mirror.example/ajax/get_cdn_series/", page))
        assertFalse(MirrorGuestCookies.isGuestFilmRequest(
            "https://unrelated.example/ajax/get_cdn_series/", page))
        assertFalse(MirrorGuestCookies.isGuestFilmRequest(
            "https://my-film-mirror.example/unrelated/", "https://my-film-mirror.example/"))
    }

    @Test fun guestProfileWorksWithoutAccountOrChallenge() {
        assertEquals("hdmbbs=1", MirrorGuestCookies.fromStoredCookies(emptyList()))
    }
}
