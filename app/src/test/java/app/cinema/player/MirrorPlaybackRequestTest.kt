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
    @Test fun seriesHasEpisodeParametersWithoutMovieFlags() {
        val page=MirrorMoviePage("222",true,listOf(MirrorVoice("2","Озвучка")),emptyList())
        val fields=MirrorPlaybackRequest.stream(page,"2","1","4")
        assertEquals("get_stream",fields["action"])
        assertEquals("1",fields["season"])
        assertEquals("4",fields["episode"])
        assertFalse(fields.containsKey("is_ads"))
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
