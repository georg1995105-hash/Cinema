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

    @Test fun guestProfileWorksWithoutAccountOrChallenge() {
        assertEquals("hdmbbs=1", MirrorGuestCookies.fromStoredCookies(emptyList()))
    }
}
