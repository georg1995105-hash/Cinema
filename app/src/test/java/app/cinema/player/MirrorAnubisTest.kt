package app.cinema.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun parsesChallengeAndSolvesReferencePowShape() {
        val html = """
            <html><head>
            <script id="anubis_base_prefix" type="application/json">"/guard"</script>
            <script id="anubis_challenge" type="application/json">
            {
              "challenge":{"id":"abc-123","randomData":"seed"},
              "rules":{"algorithm":"fast","difficulty":1}
            }
            </script>
            </head></html>
        """.trimIndent()
        val challenge = MirrorAnubis.parse(html)
        assertNotNull(challenge)
        challenge!!
        assertEquals("abc-123", challenge.id)
        assertEquals("seed", challenge.randomData)
        assertEquals(1, challenge.difficulty)
        assertEquals("/guard", challenge.basePrefix)
        assertEquals("fast", challenge.algorithm)

        val solution = MirrorAnubis.solve(challenge)
        assertNotNull(solution)
        solution!!
        assertTrue(solution.hash.startsWith("0"))

        val pass = MirrorAnubis.passChallengeUrl(film, challenge, solution).orEmpty()
        assertTrue(pass.startsWith(
            "https://rezka-ua.pub/guard/.within.website/x/cmd/anubis/api/pass-challenge?"
        ))
        assertTrue(pass.contains("id=abc-123"))
        assertTrue(pass.contains("redir=https%3A%2F%2Frezka-ua.pub%2Ffilms%2Ffiction%2F30651-film.html"))
    }

    @Test fun parsesAssignmentFallbackUsedByOlderChallengePages() {
        val html = """
            <script>
            var anubis_base_prefix = '';
            var anubis_challenge = {
              "challenge":{"id":"id2","randomData":"payload"},
              "rules":{"difficulty":1,"algorithm":"SHA-256"}
            };
            </script>
        """.trimIndent()
        val challenge = MirrorAnubis.parse(html)
        assertNotNull(challenge)
        assertEquals("", challenge!!.basePrefix)
        assertEquals("id2", challenge.id)
    }

}
