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

    @Test fun nativeAnubisPassRetainsVerificationCookieWithoutLeakingLogin() {
        val film = "https://hdrzk.org/films/fiction/1234-movie.html"
        val pass = "https://hdrzk.org/.within.website/x/cmd/anubis/api/pass-challenge"
        val stored = listOf(
            "hdmbbs=0; techaro.lol-anubis-cookie-verification=challengeA; PHPSESSID=session",
            "dle_user_id=123; techaro.lol-anubis-auth=oldAuth",
            "techaro.lol-anubis-cookie-verification-7af=challengeB; dle_hash=private"
        )
        val header = MirrorGuestCookies.cookieHeaderFor(pass, film, stored)
        assertTrue(header.contains("hdmbbs=1"))
        assertTrue(header.contains("techaro.lol-anubis-cookie-verification=challengeA"))
        assertTrue(header.contains("techaro.lol-anubis-cookie-verification-7af=challengeB"))
        assertTrue(header.contains("techaro.lol-anubis-auth=oldAuth"))
        assertFalse(header.contains("PHPSESSID"))
        assertFalse(header.contains("dle_user_id"))
        assertFalse(header.contains("dle_hash"))
        assertEquals("", MirrorGuestCookies.cookieHeaderFor(
            "https://other.example/.within.website/x/cmd/anubis/api/pass-challenge",
            film, stored))
    }

    @Test fun anubisCookieMatchingRejectsPrefixLookalikes() {
        val header = MirrorGuestCookies.fromStoredCookies(listOf(
            "techaro.lol-anubis-cookie-verificationFake=bad; " +
                "techaro.lol-anubis-authFake=bad; " +
                "techaro.lol-anubis-cookie-verification=good"
        ))
        assertEquals("hdmbbs=1; techaro.lol-anubis-cookie-verification=good", header)
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
