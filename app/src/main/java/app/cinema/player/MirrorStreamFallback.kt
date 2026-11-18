package app.cinema.player

/**
 * A native HTTP response is not proof of a paid restriction until the ordinary
 * site WebView session was tried. Both use the same film, translator and
 * same-origin endpoint; no extra rights or IDs are added to the request.
 */
internal object MirrorStreamFallback {
    fun shouldTryBrowser(primary: MirrorStreamInfo): Boolean =
        primary.qualities.isEmpty()

    fun choose(primary: MirrorStreamInfo, browser: MirrorStreamInfo?): MirrorStreamInfo {
        if (browser == null) return primary
        if (browser.qualities.isNotEmpty()) return browser
        // When one path is blocked or incorrectly decoded and the other
        // returns an explicit access error, show the more informative result.
        if (primary.failure in setOf(
                PlaybackFailure.INVALID_RESPONSE,
                PlaybackFailure.NETWORK,
                PlaybackFailure.TIMEOUT,
                PlaybackFailure.REDIRECT,
                PlaybackFailure.UNAVAILABLE
            ) && browser.failure in setOf(
                PlaybackFailure.AUTH_REQUIRED,
                PlaybackFailure.PREMIUM,
                PlaybackFailure.DRM,
                PlaybackFailure.BLOCKED,
                PlaybackFailure.PREVIEW
            )
        ) return browser
        return primary
    }
}
