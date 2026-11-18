package app.cinema.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
            // Home is native: it must never embed the Lift website by default.
            compose.onNodeWithText("Популярное").assertIsDisplayed()
            compose.onNodeWithText("Lift · Смотреть").assertDoesNotExist()
            compose.onNode(hasText("Поиск") and hasClickAction()).performClick()
            compose.onNodeWithText("Недавние запросы").assertIsDisplayed()
            compose.onNodeWithText("Бригада").performClick()
            compose.onNodeWithText("Фильтры").assertIsDisplayed()

            compose.onNode(hasText("Моё") and hasClickAction()).performClick()
            compose.onNodeWithText("Просмотров пока нет").assertIsDisplayed()
            compose.onNodeWithText("Избранное").performClick()
            compose.onNodeWithText("Избранное пока пусто").assertIsDisplayed()

            compose.onNode(hasText("Настройки") and hasClickAction()).performClick()
            compose.onNodeWithText("Каталог работает без зеркал и авторизации.", substring = true)
                .assertIsDisplayed()
            compose.onNode(hasText("Настройки") and hasClickAction()).performClick()
            compose.onNodeWithText("Внешний вид").performClick()
            compose.onNodeWithText("Тёмная тема").assertIsDisplayed()
            compose.onNode(hasText("Настройки") and hasClickAction()).performClick()
            compose.onNodeWithText("Плеер").performClick()
            compose.onNodeWithText("Шаг перемотки").assertIsDisplayed()
            compose.onNode(hasText("Главная") and hasClickAction()).performClick()
            // Returning from settings must show the native catalog.
            compose.onNodeWithText("Популярное").assertIsDisplayed()
            compose.onNodeWithText("Lift · Смотреть").assertDoesNotExist()
        } finally {
            store.clearSearchHistory()
        }
    }
}
