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
            val base = URI("https", null, target.host,
                if (target.port == 443) -1 else target.port,
                movie.rawPath, movie.rawQuery, null)
            val output = base.toString()
            output.takeIf { SourceRules.isValidHttpsUrl(it) &&
                MirrorPostId.fromUrl(it) == MirrorPostId.fromUrl(filmUrl) }
        }.getOrNull()
    }
}
