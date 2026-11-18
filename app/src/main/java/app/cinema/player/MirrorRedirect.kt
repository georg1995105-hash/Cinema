package app.cinema.player

import java.net.URI

/** Convert a Rezka mirror redirect back into the exact film URL the user opened. */
internal object MirrorRedirect {
    fun resolveFilmUrl(
        originalFilmUrl: String,
        fromUrl: String,
        location: String?,
        savedMirrors: List<String> = emptyList()
    ): String? {
        if (location.isNullOrBlank() || MirrorPostId.fromUrl(originalFilmUrl) == null) return null
        val destination = runCatching { URI(fromUrl).resolve(location).toString() }.getOrNull()
            ?: return null
        if (!SourceRules.isValidHttpsUrl(destination)) return null

        val expectedId = MirrorPostId.fromUrl(originalFilmUrl) ?: return null
        val destinationId = MirrorPostId.fromUrl(destination)
        if (destinationId != null) {
            if (destinationId != expectedId) return null
            return destination.takeIf {
                SourceRules.sameOrigin(fromUrl, it) ||
                    MirrorSitePolicy.isAllowedMirror(it, savedMirrors)
            }
        }

        val path = runCatching { URI(destination).path.orEmpty().lowercase() }.getOrDefault("")
        if (Regex("""/(?:login|account|premium|subscribe|authorization)(?:/|$)""")
                .containsMatchIn(path)) return null
        if (!MirrorSitePolicy.isAllowedMirror(destination, savedMirrors)) return null

        return MirrorFilmUrl.changeOrigin(originalFilmUrl, destination)
    }
}
