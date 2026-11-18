package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class MirrorStreamAlternative(
    val pageUrl: String,
    val player: MirrorMoviePage,
    val stream: MirrorStreamInfo
)

/**
 * A valid film page and voice list do NOT prove the stream is served by that
 * mirror. If the chosen mirror returns no video, try the same exact post,
 * translator and episode on a few other recognized origins.
 *
 * Each request is same-origin; the user's cookies and login are never copied
 * from one mirror to a different host.
 */
internal object MirrorStreamFailover {
    fun candidates(pageUrl: String, savedMirrors: List<String>): List<String> {
        if (MirrorPostId.fromUrl(pageUrl) == null) return emptyList()
        val origins = listOf("https://rezka.ag/") +
            savedMirrors + MirrorSitePolicy.builtInOrigins
        return origins.mapNotNull { MirrorFilmUrl.changeOrigin(pageUrl, it) }
            .filter { !SourceRules.sameOrigin(it, pageUrl) }
            .distinct()
            .take(7)
    }

    suspend fun find(
        pageUrl: String,
        savedMirrors: List<String>,
        translatorId: String,
        season: String,
        episode: String,
        userAgent: String
    ): MirrorStreamAlternative? = supervisorScope {
        if (!translatorId.matches(Regex("[0-9]{1,12}"))) return@supervisorScope null
        val candidates = candidates(pageUrl, savedMirrors)
        if (candidates.isEmpty()) return@supervisorScope null
        val queue = Channel<String>(Channel.UNLIMITED)
        candidates.forEach { queue.trySend(it) }
        queue.close()
        val results = Channel<MirrorStreamAlternative?>(Channel.UNLIMITED)
        val workers = List(minOf(2, candidates.size)) {
            launch(Dispatchers.IO) {
                for (candidate in queue) {
                    val next = withTimeoutOrNull(14_000L) {
                        val fetched = MirrorNetworkSource.film(
                            candidate, userAgent, savedMirrors
                        )
                        if (!MirrorFilmUrl.isAllowedResolved(
                                pageUrl, fetched.finalUrl, savedMirrors
                            )
                        ) return@withTimeoutOrNull null
                        val page = fetched.metadata?.player ?: return@withTimeoutOrNull null
                        if (!MirrorVoiceSelection.supports(page, translatorId))
                            return@withTimeoutOrNull null
                        val voice = page.voices.firstOrNull { it.id == translatorId }
                            ?: page.defaultFlags?.takeIf { it.id == translatorId }
                        if (voice?.requiresSubscription == true)
                            return@withTimeoutOrNull null
                        if (page.isSeries &&
                            (season.isBlank() || episode.isBlank()))
                            return@withTimeoutOrNull null
                        val fields = MirrorPlaybackRequest.stream(
                            page, translatorId, season, episode
                        )
                        val raw = MirrorNetworkSource.playback(
                            fetched.finalUrl, fields, userAgent
                        )
                        val streams = MirrorMediaParser.parseStreamInfo(raw)
                        if (streams.qualities.isEmpty()) null
                        else MirrorStreamAlternative(fetched.finalUrl, page, streams)
                    }
                    results.send(next)
                }
            }
        }
        try {
            repeat(candidates.size) {
                val alternative = results.receive()
                if (alternative != null) {
                    workers.forEach { it.cancel() }
                    return@supervisorScope alternative
                }
            }
            null
        } finally {
            workers.forEach { it.cancel() }
            results.close()
        }
    }
}
