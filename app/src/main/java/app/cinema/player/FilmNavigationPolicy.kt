package app.cinema.player

/** Blocking a navigational attempt never means discarding current film metadata. */
internal object FilmNavigationPolicy {
    data class Decision(val allow: Boolean, val failureMessage: String = "")

    fun decide(
        filmUrl: String,
        requestedUrl: String,
        mirrors: List<String> = emptyList()
    ): Decision {
        if (MirrorPostId.canOpen(filmUrl, requestedUrl)) return Decision(true)
        val samePost = MirrorPostId.fromUrl(filmUrl) != null &&
            MirrorPostId.fromUrl(filmUrl) == MirrorPostId.fromUrl(requestedUrl)
        if (samePost && MirrorSitePolicy.isAllowedMirror(requestedUrl, mirrors))
            return Decision(true)
        val login = runCatching {
            java.net.URI(requestedUrl).path.orEmpty().contains("login", true)
        }.getOrDefault(false)
        return Decision(
            allow = false,
            failureMessage = if (login) "Сайт требует входа в аккаунт."
                else "Переход не относится к выбранному фильму."
        )
    }
}
