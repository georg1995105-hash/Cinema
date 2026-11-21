package app.cinema.player

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryHistoryCoverTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deleteCrossStaysVisibleAndRemovesHistoryEntry() {
        val prefs = compose.activity.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)
        val originalHistory = prefs.getString("watch_history", null)
        val originalIndex = prefs.getString("movie_index_v1", null)
        val url = "https://liftw.ws/details.html?id=383&name=Test"
        val title = "Тестовый фильм"
        try {
            val store = LocalStore(compose.activity)
            store.rememberMovie(CatalogMovie(url, title, "https://img.liftw.ws/cover", ""))
            store.saveHistory(listOf(WatchedVideo(url, 0L)))
            compose.activityRule.scenario.recreate()

            compose.onNode(hasText("Моё") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Удалить из истории: $title")
                .assertIsDisplayed().performClick()
            compose.onNodeWithText("История пока пуста").assertIsDisplayed()
            assertTrue(LocalStore(compose.activity).history().isEmpty())
        } finally {
            val edit = prefs.edit()
            if (originalHistory == null) edit.remove("watch_history")
            else edit.putString("watch_history", originalHistory)
            if (originalIndex == null) edit.remove("movie_index_v1")
            else edit.putString("movie_index_v1", originalIndex)
            edit.commit()
        }
    }

    @Test fun reopeningWithoutPosterDoesNotErasePreviouslySavedCover() {
        val prefs = compose.activity.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)
        val originalIndex = prefs.getString("movie_index_v1", null)
        val url = "https://liftw.ws/details.html?id=384&name=Second"
        val photo = "https://img.liftw.ws/valid-photo-token"
        try {
            val store = LocalStore(compose.activity)
            store.rememberMovie(CatalogMovie(url, "Второй фильм", photo, ""))
            store.rememberMovie(CatalogMovie(url, "Второй фильм", "", ""))
            assertEquals(photo, store.savedMovie(url)?.poster)
            store.rememberMovie(CatalogMovie(url, "Фильм Lift №384", "", ""))
            assertEquals("Второй фильм", store.savedMovie(url)?.title)
            assertEquals(photo, store.savedMovie(url)?.poster)
        } finally {
            val edit = prefs.edit()
            if (originalIndex == null) edit.remove("movie_index_v1")
            else edit.putString("movie_index_v1", originalIndex)
            edit.commit()
        }
    }
}
