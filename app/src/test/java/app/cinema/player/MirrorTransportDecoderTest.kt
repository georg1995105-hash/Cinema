package app.cinema.player

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MirrorTransportDecoderTest {
    @Test fun keepsAlreadyDecodedPlaylistUnchanged() {
        val item = "[720p]https://video.example/movie.mp4"
        assertEquals(item, MirrorTransportDecoder.decode(item))
    }

    @Test fun removesValidShortPunctuationSalt() {
        val plain = "[720p]https://video.example/movie.mp4"
        val data = Base64.getEncoder().encodeToString(plain.toByteArray())
        val token = Base64.getEncoder().encodeToString("@#".toByteArray())
        val encoded = "#h" + data.take(8) + "//_//" + token + data.drop(8)
        assertEquals(plain, MirrorTransportDecoder.decode(encoded))
    }

    @Test fun refusesUnknownSaltInsteadOfGeneratingUntrustedLink() {
        assertEquals("", MirrorTransportDecoder.decode("#hAAAA//_//notRecognizedBBBB"))
    }

    @Test fun acceptsHttpsProtocolRelativeMediaLinks() {
        val parsed = MirrorMediaParser.parseQualityList(
            "[720p]//cdn.example/video.mp4,[480p]https://cdn.example/low.mp4"
        )
        assertEquals("https://cdn.example/video.mp4", parsed[0].url)
    }
}
