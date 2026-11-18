package app.cinema.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Exercises the manual Watch sheet, not just the parser model. */
class ManualWatchSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mainWatchOpensSelectionAndStartsOnlyAfterExplicitVoiceChoice() {
        val url = "https://rezka.example/films/action/123-film.html"
        val movie = CatalogMovie(url, "Тестовый фильм", "", "2026")
        FilmInfoMemory.put(url, FilmInfo(
            originalTitle = "", description = "Сюжет тестового фильма",
            facts = listOf("Страна" to "Россия"), rating = "",
            cast = emptyList(), related = emptyList(),
            franchiseResolved = true,
            voices = listOf(MirrorVoice("11","Стандартная"),
                MirrorVoice("27","Дубляж")),
            title = "Тестовый фильм",
            portraitsResolved = true,
            defaultVoice = "11"
        ))
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                FilmDetails(
                    movie = movie,
                    source = "https://rezka.example",
                    sources = listOf("https://rezka.example", "https://another.example"),
                    isFavourite = false,
                    onToggleFavourite = {},
                    onBack = {},
                    onBrowse = {},
                    savedSelection = null,
                    onWatch = { request -> selected = request.voice },
                    onRelated = {},
                    onMetadata = {},
                    onPerson = {}
                )
            }
        }
        compose.onNodeWithText("Смотреть").performScrollTo().performClick()
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithText("Источник").assertExists()
        compose.onNodeWithText("Озвучка").assertExists()
        compose.onNodeWithText("Дубляж", substring = true).performClick()
        compose.runOnIdle { assertNull(selected) }
        compose.onAllNodesWithText("Смотреть").onLast().performClick()
        compose.runOnIdle { assertEquals("27",selected) }
    }
}
