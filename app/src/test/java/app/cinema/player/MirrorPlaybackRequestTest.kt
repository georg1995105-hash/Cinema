package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class MirrorPlaybackRequestTest {
    @Test fun usesTranslatorFlagsProvidedByMirrorForMovies() {
        val movie=MirrorMoviePage(
            id="123456",isSeries=false,
            voices=listOf(MirrorVoice("31","Дубляж",isCamrip=1,isAds=0,isDirector=1)),
            episodes=emptyList(),favs="AbC123"
        )
        val payload=MirrorPlaybackRequest.stream(movie,"31")
        assertEquals("get_movie",payload["action"])
        assertEquals("1",payload["is_camrip"])
        assertEquals("0",payload["is_ads"])
        assertEquals("1",payload["is_director"])
        assertEquals("AbC123",payload["favs"])
    }
    @Test fun seriesHasTranslatorFlagsJustLikeTheReferenceApk() {
        val page = MirrorMoviePage("222", true,
            listOf(MirrorVoice("2", "Перевод", isCamrip=1, isAds=1, isDirector=0,
                favs="perVoice42")), emptyList(), favs="staleFilmValue")
        val fields = MirrorPlaybackRequest.stream(page, "2", "1", "4")
        assertEquals("get_stream", fields["action"])
        assertEquals("1", fields["season"])
        assertEquals("4", fields["episode"])
        assertEquals("1", fields["is_camrip"])
        assertEquals("1", fields["is_ads"])
        assertEquals("0", fields["is_director"])
        assertEquals("perVoice42", fields["favs"])
        val episodeQuery = MirrorPlaybackRequest.episodes(page, "2")
        assertEquals("get_episodes", episodeQuery["action"])
        assertEquals("1", episodeQuery["is_camrip"])
        assertEquals("perVoice42", episodeQuery["favs"])
        assertFalse(episodeQuery.containsKey("season"))
    }

    @Test fun selectingAnotherTranslatorDoesNotLeakPreviousTranslatorsFavs() {
        val page = MirrorMoviePage("42", true, listOf(
            MirrorVoice("1", "Первая", favs="first"),
            MirrorVoice("2", "Вторая", favs="second")
        ), emptyList())
        assertEquals("first", MirrorPlaybackRequest.stream(page, "1", "1", "1")["favs"])
        assertEquals("second", MirrorPlaybackRequest.stream(page, "2", "1", "1")["favs"])
    }
    @Test fun rejectsNonNumericIdsInsteadOfGuessing() {
        val movie=MirrorMoviePage("",false,emptyList(),emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            MirrorPlaybackRequest.stream(movie,"0")
        }
    }
    @Test fun parsesFlagsFromActualPageResponse() {
        val raw=org.json.JSONObject.quote(
            """{"id":"123","series":false,"voices":[{"id":"17","name":"Перевод","isCamrip":1,"isAds":1,"isDirector":0}]}"""
        )
        val voice=MirrorMediaParser.pageFromWebView(raw)?.voices?.single()
        assertEquals(1,voice?.isCamrip)
        assertEquals(1,voice?.isAds)
    }
}
