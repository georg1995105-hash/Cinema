package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * UI route to a real publisher's embedded playback screen (not just a mock
 * Watch button). Does NOT assert real video frames or audio playback.
 */
class MosfilmNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun officialFilmCardOpensWatchAndEmbeddedPlayer() {
        compose.onNodeWithText("Красная палатка").performClick()
        compose.onNodeWithText("О фильме").assertIsDisplayed()
        compose.onNodeWithText("Киноконцерн «Мосфильм» · официальный плеер RUTUBE")
            .assertIsDisplayed()
        compose.onNode(hasText("Смотреть") and hasClickAction()).performClick()
        compose.onNodeWithText("Мосфильм · RUTUBE").assertIsDisplayed()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("О фильме").assertIsDisplayed()
    }
}
