package app.cinema.player

import java.net.URI

/** Change only the HTTPS origin while retaining this exact film's path and ID. */
internal object MirrorFilmUrl {
    fun isAllowedResolved(filmUrl: String, resolvedUrl: String, mirrors: List<String>): Boolean {
        if (!SourceRules.isValidHttpsUrl(filmUrl) || !SourceRules.isValidHttpsUrl(resolvedUrl))
            return false
        val expectedId = MirrorPostId.fromUrl(filmUrl) ?: return false
        if (MirrorPostId.fromUrl(resolvedUrl) != expectedId) return false
        return (listOf(filmUrl) + mirrors).any { SourceRules.sameOrigin(it, resolvedUrl) }
    }

    fun changeOrigin(filmUrl: String, mirror: String): String? {
        if (!SourceRules.isValidHttpsUrl(filmUrl) ||
            !SourceRules.isValidHttpsUrl(mirror)) return null
        if (MirrorPostId.fromUrl(filmUrl) == null) return null
        return runCatching {
            val movie = URI(filmUrl)
            val target = URI(mirror)
            val authority = target.rawAuthority ?: return@runCatching null
            val suffix = buildString {
                append(movie.rawPath)
                movie.rawQuery?.let { append('?').append(it) }
            }
            // rawPath/rawQuery are already encoded. Rebuilding through URI's component
            // constructor would encode '%' again (for example %20 -> %2520).
            val output = "https://$authority$suffix"
            output.takeIf { SourceRules.isValidHttpsUrl(it) &&
                MirrorPostId.fromUrl(it) == MirrorPostId.fromUrl(filmUrl) }
        }.getOrNull()
    }
}
