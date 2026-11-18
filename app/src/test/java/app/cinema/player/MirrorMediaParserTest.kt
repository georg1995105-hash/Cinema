package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorMediaParserTest {
    @Test fun extractsRealPostIdFromConventionalFilmUrls() {
        assertEquals("123456", MirrorPostId.fromUrl(
            "https://example.org/films/action/123456-film-title.html"))
        assertNull(MirrorPostId.fromUrl("https://example.org/films/action/random.html"))
        assertNull(MirrorPostId.fromUrl("http://example.org/films/123456-x.html"))
    }

    @Test fun loadsPageWithoutInlinePostIdWhenUrlContainsRealId() {
        val raw = org.json.JSONObject.quote(
            """{"id":"","series":false,"voices":[{"id":"12","name":"Дубляж"}]}"""
        )
        val page = MirrorMediaParser.pageFromWebView(
            raw,"https://example.org/films/action/123456-film-title.html"
        )
        assertEquals("123456",page?.id)
        assertEquals("12",page?.voices?.single()?.id)
    }

    @Test fun aScriptIdCanPlayWithoutInventingAVoiceName() {
        val page = MirrorMediaParser.pageFromWebView(
            """{"id":"41","defaultVoice":"0","flags":{"isCamrip":1}}""")
        assertEquals("0", page?.defaultVoice)
        assertTrue(page?.voices.orEmpty().isEmpty())
        assertEquals("1", MirrorPlaybackRequest.stream(requireNotNull(page), "0")["is_camrip"])
    }

    @Test fun rejectsChallengePageOrWrongFilmEvenWithAValidLookingUrl() {
        val url = "https://example.org/films/41-film.html"
        assertNull(MirrorMediaParser.pageFromWebView("""{"id":"42","defaultVoice":"5"}""", url))
        assertNull(MirrorMediaParser.pageFromWebView("""{"pageState":"protected","defaultVoice":"5"}""", url))
        assertNull(MirrorMediaParser.pageFromWebView("""{"id":"41","voices":[]}""", url))
    }

    @Test fun mapsSourceFailuresWithoutOfferingMedia() {
        val cases = mapOf(
            """{"__kinoStatus":401}""" to PlaybackFailure.AUTH_REQUIRED,
            """{"__kinoStatus":403}""" to PlaybackFailure.BLOCKED,
            """{"__kinoFailure":"timeout"}""" to PlaybackFailure.TIMEOUT,
            """{"__kinoFailure":"network"}""" to PlaybackFailure.NETWORK,
            """{"__kinoStatus":404}""" to PlaybackFailure.UNAVAILABLE,
            """{"success":0,"code":"premium_only"}""" to PlaybackFailure.PREMIUM,
            """{"success":0,"code":"drm_required","message":"DRM is required"}""" to PlaybackFailure.DRM,
            "<html>unexpected</html>" to PlaybackFailure.INVALID_RESPONSE
        )
        cases.forEach { (response, expected) ->
            val parsed = MirrorMediaParser.parseStreamInfo(response)
            assertEquals(response, expected, parsed.failure)
            assertTrue(parsed.qualities.isEmpty())
        }
    }

    @Test fun nonEmptyStreamsFieldWinsOverBlankOrUnplayableUrl() {
        val withBlankUrl = """{"success":1,"url":"","streams":"[720p]https://media.example/full.mp4"}"""
        assertEquals("https://media.example/full.mp4",
            MirrorMediaParser.parseStreamInfo(withBlankUrl).qualities.single().url)
        val withInvalidUrl = """{"success":1,"url":"not-a-video","streams":"[480p]https://media.example/backup.m3u8"}"""
        assertEquals("480p", MirrorMediaParser.parseStreamInfo(withInvalidUrl).qualities.single().label)
    }

    @Test fun directPremiumAndPromoLinksAreRejected() {
        assertTrue(MirrorMediaParser.parseQualityList("https://cdn.example/promo/demo.mp4").isEmpty())
        assertTrue(MirrorMediaParser.parseQualityList("https://cdn.example/premium/full.mp4").isEmpty())
    }

    @Test fun duplicateLabelsRetainAlternativeExactHlsPaths() {
        val a = "https://cdn.example/a.mp4:hls:manifest.m3u8"
        val result = MirrorMediaParser.parseQualityList("[720p]$a,[720p]https://cdn.example/b.m3u8").single()
        assertEquals(a, result.url)
        assertEquals(listOf("https://cdn.example/b.m3u8"), result.alternatives)
    }

    @Test fun acceptsNumericSuccessfulVideoResponses() {
        val raw = org.json.JSONObject.quote(
            """{"success":1,"url":"[720p]https://media.example/video.mp4"}"""
        )
        assertEquals("720p", MirrorMediaParser.parseStreamResponse(raw).first.single().label)
    }

    @Test fun acceptsStringAndBooleanSuccessfulResponses() {
        listOf("true", "\"1\"").forEach { success ->
            val raw = org.json.JSONObject.quote(
                """{"success":$success,"url":"[480p]https://media.example/video.mp4"}"""
            )
            assertEquals(1, MirrorMediaParser.parseStreamResponse(raw).first.size)
        }
    }

    @Test fun emptyErrorIsNotARejection() {
        val raw = org.json.JSONObject.quote(
            """{"success":1,"error":null,"url":"[720p]https://media.example/video.mp4"}"""
        )
        assertEquals(1, MirrorMediaParser.parseStreamResponse(raw).first.size)
    }

    @Test fun realErrorsAndNumericFailureDoNotExposeVideo() {
        val failed = org.json.JSONObject.quote(
            """{"success":0,"url":"[720p]https://media.example/video.mp4"}"""
        )
        val errored = org.json.JSONObject.quote(
            """{"error":"network","url":"[720p]https://media.example/video.mp4"}"""
        )
        assertTrue(MirrorMediaParser.parseStreamResponse(failed).first.isEmpty())
        assertTrue(MirrorMediaParser.parseStreamResponse(errored).first.isEmpty())
    }

    @Test fun parsesMediaResolutions() {
        val quality = MirrorMediaParser.parseQualityList(
            "[480p]https://media.example/a.mp4,[720p]https://media.example/b.mp4"
        )
        assertEquals(2, quality.size)
        assertEquals("720p", quality[0].label)
        assertEquals("https://media.example/b.mp4", quality[0].url)
    }

    @Test fun rejectsUntrustedAndInsecureMediaUrls() {
        assertTrue(MirrorMediaParser.parseQualityList("[720p]javascript:alert(1)").isEmpty())
        assertTrue(MirrorMediaParser.parseQualityList("[720p]http://media.example/video.mp4").isEmpty())
    }

    @Test fun ignoresPremiumMarkedQuality() {
        val quality = MirrorMediaParser.parseQualityList(
            "[720p]https://media.example/free.mp4," +
            "[<span class=\"pjs-prem-quality\">1080p</span>]https://media.example/premium.mp4"
        )
        assertEquals(1, quality.size)
        assertEquals("720p", quality.first().label)
    }

    @Test fun handlesAlternativeLinks() {
        val quality = MirrorMediaParser.parseQualityList(
            "[480p]https://media.example/first.m3u8 or https://media.example/second.mp4"
        )
        assertEquals("https://media.example/second.mp4", quality.single().url)
    }

    @Test fun keepsAlternateOpenMediaHosts() {
        val quality = MirrorMediaParser.parseQualityList(
            "[720p]https://free-a.example/a.mp4 or https://free-b.example/b.mp4"
        ).single()
        assertEquals("https://free-a.example/a.mp4", quality.url)
        assertEquals(listOf("https://free-b.example/b.mp4"), quality.alternatives)
    }

    @Test fun neverOffersPremiumLabeledAlternative() {
        val quality = MirrorMediaParser.parseQualityList(
            "[720p]https://free.example/main.mp4 or https://media.example/premium/trial.mp4"
        ).single()
        assertTrue(quality.alternatives.isEmpty())
    }

    @Test fun parsesRealExternalSubtitleTracks() {
        val tracks = MirrorMediaParser.parseSubtitleList(
            "[Русские]https://cdn.example/ru.vtt,[English]https://cdn.example/en.vtt"
        )
        assertEquals(2, tracks.size)
        assertEquals("ru", tracks[0].language)
        assertEquals("https://cdn.example/en.vtt", tracks[1].url)
    }

    @Test fun rejectsInsecureOrBogusSubtitleAddresses() {
        assertTrue(MirrorMediaParser.parseSubtitleList("[Русские]http://cdn.example/ru.vtt").isEmpty())
        assertTrue(MirrorMediaParser.parseSubtitleList("[Русские]javascript:alert(1)").isEmpty())
    }

    @Test fun subtitleNamesMustBePlainText() {
        assertTrue(MirrorMediaParser.parseSubtitleList(
            "[<script>]https://cdn.example/sub.vtt"
        ).isEmpty())
    }
}
