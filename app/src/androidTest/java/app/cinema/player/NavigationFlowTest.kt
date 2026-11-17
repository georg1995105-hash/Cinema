package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainTabsOpenAndRecentSearchCanBeReused() {
        val store = LocalStore(compose.activity)
        store.saveSearch("Бригада")
        try {
            compose.onNodeWithText("Добавьте зеркало").assertIsDisplayed()
            compose.onNodeWithContentDescription("Поиск").performClick()
            compose.onNodeWithText("Недавние запросы").assertIsDisplayed()
            compose.onNodeWithText("Бригада").performClick()
            compose.onNodeWithText("Добавьте зеркало").assertIsDisplayed()
            compose.onNodeWithText("Обновить").assertDoesNotExist()

            compose.onNodeWithContentDescription("Моё").performClick()
            compose.onNodeWithText("Просмотров пока нет").assertIsDisplayed()
            compose.onNodeWithText("Избранное").performClick()
            compose.onNodeWithText("Избранное пока пусто").assertIsDisplayed()

            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Источники и зеркала").performClick()
            compose.onNodeWithText("Адрес сайта").assertIsDisplayed()
            compose.onNodeWithContentDescription("Главная").performClick()
            compose.onNodeWithText("Добавьте зеркало").assertIsDisplayed()
        } finally {
            store.clearSearchHistory()
        }
    }
}
