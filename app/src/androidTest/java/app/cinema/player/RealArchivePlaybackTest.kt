package app.cinema.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real remote Media3 decoding test; no embedded MP4, HTTP mocks, or fake
 * successful playback. It fails if the player cannot advance its position.
 * This is not a user-device test; providers and ISPs may differ.
 */
class RealArchivePlaybackTest {
    @Test fun publicDomainFilmActuallyAdvancesInMedia3() = runBlocking {
        withTimeout(120_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val verified = InternetArchiveStreams.available(context,
                CinemetaCatalog.movieUrl("movie", "tt0063350"))
            assertTrue("Archive must expose verified MP4 variants", verified.isNotEmpty())
            val source = verified.last().url
            val player = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(source))
                    prepare()
                    playWhenReady = true
                }
            }
            try {
                var ready = false
                var playingPosition = 0L
                repeat(160) {
                    val progress = withContext(Dispatchers.Main) {
                        val error = player.playerError
                        if (error != null)
                            throw AssertionError("Remote MP4 failed: ${error.errorCodeName}", error)
                        Triple(player.playbackState, player.currentPosition, player.isPlaying)
                    }
                    ready = ready || progress.first == Player.STATE_READY
                    playingPosition = maxOf(playingPosition, progress.second)
                    if (ready && playingPosition >= 900 && progress.third)
                        return@withTimeout
                    delay(450)
                }
                throw AssertionError("Real Media3 player did not play >900ms: " +
                    "ready=$ready, position=$playingPosition")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { player.release() }
            }
        }
    }
}
