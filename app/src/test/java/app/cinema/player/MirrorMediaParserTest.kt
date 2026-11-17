package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorMediaParserTest {
    @Test fun retryablePageWithoutPostIdHasClearReason() {
        val raw = org.json.JSONObject.quote("""{"id":"","pageState":"missing-metadata"}""")
        assertNull(MirrorMediaParser.pageFromWebView(raw))
        assertTrue(MirrorMediaParser.pageFailureReason(raw).contains("не предоставила"))
    }

    @Test fun legitimatePageWithIdGetsVoiceFallback() {
        val raw = org.json.JSONObject.quote("""{"id":"12345","series":false,"voice":"4","pageState":"ready"}""")
        val page = MirrorMediaParser.pageFromWebView(raw)
        assertEquals("12345",page?.id)
        assertEquals("4",page?.voices?.singleOrNull()?.id)
    }

    @Test fun neverInventsDefaultVoiceForMissingIds() {
        assertTrue(MirrorMediaParser.parseVoices(null, "").isEmpty())
        assertTrue(MirrorMediaParser.parseVoices(org.json.JSONArray(), "").isEmpty())
        assertEquals("9", MirrorMediaParser.parseVoices(null, "9").single().id)
    }
    @Test fun rejectsPrematureInventedVoiceZero() {
        val early = org.json.JSONObject.quote("""{"id":"40","voice":"0","voices":[]}""")
        assertFalse(MirrorMediaParser.hasRealVoiceMetadata(early))
        val ready = org.json.JSONObject.quote("""{"id":"40","voice":"0","voiceFromScript":true,"voices":[]}""")
        assertTrue(MirrorMediaParser.hasRealVoiceMetadata(ready))
        val list = org.json.JSONObject.quote("""{"id":"40","voice":"0","voices":[{"id":"0","name":"Оригинал"}]}""")
        assertTrue(MirrorMediaParser.hasRealVoiceMetadata(list))
    }

    @Test fun acceptsNumericSuccessfulVideoResponses() {
        val raw = org.json.JSONObject.quote(
            """{"success":1,"url":"[720p]https://media.example/video.mp4"}"""
        )
        assertEquals("720p", MirrorMediaParser.parseStreamResponse(raw).first.single().label)
    }

    @Test fun acceptsStringAndBooleanSuccessfulResponses() {
        listOf("true", "\\"1\\"").forEach { success ->
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
