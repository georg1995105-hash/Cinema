package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** Checks the official Lift web surface is mounted inside Cinema.
 * This test DOES NOT verify real video frames or region authorization.
 */
class LiftNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun officialLiftWebViewIsFirstHomeTab() {
        compose.onNodeWithText("Lift · Смотреть").assertIsDisplayed()
        compose.onNodeWithText("Популярное").assertIsDisplayed()
    }
}
