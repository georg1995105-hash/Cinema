package app.cinema.player

import java.net.URI

/** Change only the HTTPS origin while retaining this exact film's path and ID. */
internal object MirrorFilmUrl {
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
