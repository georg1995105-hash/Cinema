package app.cinema.player

/** Blocking a navigational attempt never means discarding current film metadata. */
internal object FilmNavigationPolicy {
    data class Decision(val allow: Boolean, val failureMessage: String = "")

    fun decide(filmUrl: String, requestedUrl: String): Decision {
        if (MirrorPostId.canOpen(filmUrl, requestedUrl)) return Decision(true)
        val login = SourceRules.sameOrigin(filmUrl, requestedUrl) &&
            runCatching { java.net.URI(requestedUrl).path.orEmpty().contains("login", true) }
                .getOrDefault(false)
        return Decision(
            allow = false,
            failureMessage = if (login) "Сайт требует входа в аккаунт."
                else "Зеркало перенаправляет на другую страницу. Данные фильма недоступны."
        )
    }
}
