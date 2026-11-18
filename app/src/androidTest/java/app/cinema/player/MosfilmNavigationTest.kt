package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        // Details on phones are scrollable. Make the *actual* watch action
        // visible before pressing it; the studio label may be below the fold.
        compose.onNode(hasText("Смотреть") and hasClickAction())
            .performScrollTo().performClick()
        compose.onNodeWithText("Мосфильм · RUTUBE").assertIsDisplayed()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("О фильме").assertIsDisplayed()
    }
}
