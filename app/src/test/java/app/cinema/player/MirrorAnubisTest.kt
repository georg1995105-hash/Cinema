package app.cinema.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorAnubisTest {
    private val film = "https://rezka-ua.pub/films/fiction/30651-film.html"

    @Test fun acceptsOnlySameOriginAnubisServicePages() {
        assertTrue(MirrorAnubis.isChallengeUrl(
            film,
            "https://rezka-ua.pub/.within.website/x/cmd/anubis/api/pass-challenge?id=1"
        ))
        assertTrue(MirrorAnubis.isChallengeUrl(
            film,
            "https://rezka-ua.pub/prefix/.within.website/x/cmd/anubis/static/js/main.mjs"
        ))
        assertFalse(MirrorAnubis.isChallengeUrl(
            film,
            "https://evil.example/.within.website/x/cmd/anubis/api/pass-challenge"
        ))
        assertFalse(MirrorAnubis.isChallengeUrl(
            film,
            "https://rezka-ua.pub/account/login"
        ))
    }

    @Test fun recognizesReferenceClientChallengeMarkers() {
        assertTrue(MirrorAnubis.isChallengeDocument(
            "<script>window.anubis_challenge={};</script>"
        ))
        assertTrue(MirrorAnubis.isChallengeDocument(
            "Set-Cookie: techaro.lol-anubis-auth=token"
        ))
        assertFalse(MirrorAnubis.isChallengeDocument(
            "<html><h1>Мстители: Финал</h1></html>"
        ))
    }

    @Test fun navigationPolicyAllowsOnlyTheMirrorVerificationPath() {
        assertTrue(FilmNavigationPolicy.decide(
            film,
            "https://rezka-ua.pub/.within.website/x/cmd/anubis/api/pass-challenge"
        ).allow)
        assertFalse(FilmNavigationPolicy.decide(
            film,
            "https://evil.example/.within.website/x/cmd/anubis/api/pass-challenge"
        ).allow)
    }
}
