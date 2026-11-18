package app.cinema.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapseSourceTest {
    @Test fun parsesMovieMakePlayerAndAudioTracks() {
        val html = """
          <html><body><script>
          makePlayer({
            id:843650,
            title:"Мстители: Финал",
            poster:"https://cdn.example/poster.jpg",
            source:{
              hls:"https://cdn.example/movie/master.m3u8",
              dash:"https://cdn.example/movie/manifest.mpd",
              audio:{names:["Дубляж","Original"],order:[0,1]},
              cc:[{"url":"https://cdn.example/subs/ru.vtt","name":"Русские"}]
            }
          });
          </script></body></html>
        """
        val media = CollapseSource.parseHtml(html)
        assertNotNull(media)
        requireNotNull(media)
        assertTrue(media.playable())
        assertEquals("https://cdn.example/movie/master.m3u8", media.source!!.hls)
        assertEquals(listOf("track:0","track:1"), media.voices().map { it.id })
        assertEquals(listOf("Дубляж","Original"), media.voices().map { it.name })
        assertEquals("https://cdn.example/subs/ru.vtt", media.source!!.subtitles.single().url)
    }

    @Test fun parsesSeriesPlaylistIntoSeasonsEpisodesAndTracks() {
        val html = """
          <script>
          makePlayer({
            id:777,
            title:"Сериал",
            playlist:{
              seasons:[
                {"season":1,"blocked":false,"episodes":[
                  {"episode":"1","id":101,"videoKey":201,
                   "hls":"https://cdn.example/s1e1.m3u8",
                   "audio":{"names":["LostFilm","Original"],"order":[0,1]},"cc":[]},
                  {"episode":"2","id":102,"videoKey":202,
                   "hls":"https://cdn.example/s1e2.m3u8",
                   "audio":{"names":["LostFilm"],"order":[0]},"cc":[]}
                ]},
                {"season":2,"blocked":true,"episodes":[
                  {"episode":"1","id":201,"hls":"https://cdn.example/blocked.m3u8"}
                ]}
              ]
            }
          });
          </script>
        """
        val media = requireNotNull(CollapseSource.parseHtml(html))
        assertTrue(media.playable())
        assertEquals(2, media.episodes.size)
        assertEquals(listOf("1","2"), media.mirrorEpisodes().map { it.id })
        assertEquals(listOf("track:0","track:1"), media.voices("1","1").map { it.id })
        assertEquals("https://cdn.example/s1e2.m3u8", media.stream("1","2")!!.hls)
    }

    @Test fun imdbSuggestionPrefersMatchingTitleAndYear() {
        val raw = """
          {"d":[
            {"id":"tt0000001","l":"Avengers: Endgame","y":2018,"q":"feature"},
            {"id":"tt4154796","l":"Avengers: Endgame","y":2019,"q":"feature"},
            {"id":"tt9999999","l":"Other","y":2019,"q":"feature"}
          ]}
        """
        assertEquals(
            "tt4154796",
            ExternalFilmIdResolver.chooseImdb(raw, "Avengers: Endgame", "2019")
        )
    }

    @Test fun imdbSuggestionRejectsWrongYearAndUnrelatedTitles() {
        val wrongYear = """
          {"d":[
            {"id":"tt4154796","l":"Avengers: Endgame","y":2018,"q":"feature"},
            {"id":"tt9999999","l":"Completely Different","y":2019,"q":"feature"}
          ]}
        """
        assertEquals(
            "",
            ExternalFilmIdResolver.chooseImdb(wrongYear, "Avengers: Endgame", "2019")
        )

        val unrelated = """
          {"d":[{"id":"tt9999999","l":"Completely Different","y":2019,"q":"feature"}]}
        """
        assertEquals(
            "",
            ExternalFilmIdResolver.chooseImdb(unrelated, "Avengers: Endgame", "2019")
        )
    }
}
