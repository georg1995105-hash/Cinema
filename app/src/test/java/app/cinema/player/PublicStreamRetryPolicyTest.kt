package app.cinema.player

import org.junit.Assert.*
import org.junit.Test

class PublicStreamRetryPolicyTest {
    @Test fun recognizesShortPreviewWithoutCallingItFullVideo() {
        assertTrue(PublicStreamRetryPolicy.isShortPreview(45_000L))
        assertTrue(PublicStreamRetryPolicy.isShortPreview(150_000L))
        assertFalse(PublicStreamRetryPolicy.isShortPreview(0L))
        assertFalse(PublicStreamRetryPolicy.isShortPreview(2_700_000L))
    }

    @Test fun previewNeverPollutesWatchHistory() {
        assertFalse(PublicStreamRetryPolicy.shouldSaveProgress(45_000L, 13_000L))
        assertFalse(PublicStreamRetryPolicy.shouldSaveProgress(150_000L, 40_000L))
        assertFalse(PublicStreamRetryPolicy.shouldSaveProgress(2_700_000L, 0L))
        assertTrue(PublicStreamRetryPolicy.shouldSaveProgress(2_700_000L, 42_000L))
    }
}
