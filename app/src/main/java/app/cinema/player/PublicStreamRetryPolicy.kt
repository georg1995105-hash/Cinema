package app.cinema.player

/**
 * A film/series preview must not be stored as progress of a complete movie.
 * Duration by itself cannot prove premium status; it only triggers bounded retries.
 */
internal object PublicStreamRetryPolicy {
    private const val previewMaxDurationMs = 150_000L

    fun isShortPreview(durationMs: Long): Boolean =
        durationMs in 1L..previewMaxDurationMs

    fun shouldSaveProgress(durationMs: Long, positionMs: Long): Boolean =
        durationMs > previewMaxDurationMs && positionMs > 0L
}
