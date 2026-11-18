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

    @Test fun sourceAccountSessionIsNotSilentlyDowngradedToGuest() {
        val film = "https://hdrzk.org/series/123-show.html"
        val cookies = listOf(
            "dle_user_id=123; dle_password=sourceSession; hdmbbs=1"
        )
        assertTrue(MirrorGuestCookies.hasAccountSession(cookies))
        val header = MirrorGuestCookies.cookieHeaderFor(
            "https://hdrzk.org/ajax/get_cdn_series/", film, cookies)
        assertTrue(header.contains("dle_user_id=123"))
        assertTrue(header.contains("dle_password=sourceSession"))
        assertEquals("", MirrorGuestCookies.cookieHeaderFor(
            "https://unrelated.example/ajax/get_cdn_series/", film, cookies))
    }

    @Test fun anonymousMirrorStillUsesGuestProfileAndChallenge() {
        val film = "https://hdrzk.org/series/123-show.html"
        val cookies = listOf("hdmbbs=0; techaro.lol-anubis-auth=ok")
        assertFalse(MirrorGuestCookies.hasAccountSession(cookies))
        assertEquals("hdmbbs=1; techaro.lol-anubis-auth=ok",
            MirrorGuestCookies.cookieHeaderFor(
                "https://hdrzk.org/ajax/get_cdn_series/", film, cookies))
        assertFalse(MirrorGuestCookies.hasAccountSession(listOf(
            "unrelated_dle_user_id=123; dle_password=sourceSession")))
        assertFalse(MirrorGuestCookies.hasAccountSession(listOf(
            "dle_user_id=; dle_password=sourceSession")))
    }


    @Test fun loginKeepsMirrorSessionCookiesWithoutGuestDowngrade() {
        val source = "https://hdrzk.org/"
        val login = "https://hdrzk.org/ajax/login/"
        val cookies = listOf("PHPSESSID=csrf_session; hdmbbs=0")
        assertFalse(MirrorGuestCookies.isGuestFilmRequest(login, source))
        val header = MirrorGuestCookies.cookieHeaderFor(login, source, cookies)
        assertTrue(header.contains("PHPSESSID=csrf_session"))
        assertTrue(header.contains("hdmbbs=0"))
        assertFalse(header.contains("dle_password"))
        assertEquals("", MirrorGuestCookies.cookieHeaderFor(
            "https://different.example/ajax/login/", source, cookies))
    }

}
