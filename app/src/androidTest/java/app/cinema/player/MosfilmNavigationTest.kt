package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** The home screen must be native; website navigation is never embedded by default. */
class NativeHomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeCinemaCatalogIsFirstHomeTab() {
        compose.onNodeWithText("Популярное").assertIsDisplayed()
        compose.onNodeWithText("Главная").assertIsDisplayed()
        compose.onNodeWithText("Lift · Смотреть").assertDoesNotExist()
    }
}
