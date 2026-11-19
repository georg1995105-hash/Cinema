package app.cinema.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Ensures the primary legacy film Watch action uses Lift, never a stale mirror player. */
class ManualWatchSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryWatchRoutesToLiftWithoutAutostartingMirrorStream() {
        val url = "https://rezka.example/films/action/123-film.html"
        val movie = CatalogMovie(url, "Тестовый фильм", "", "2026")
        FilmInfoMemory.put(url, FilmInfo(
            originalTitle = "", description = "Сюжет тестового фильма",
            facts = listOf("Страна" to "Россия"), rating = "",
            cast = emptyList(), related = emptyList(),
            franchiseResolved = true,
            voices = listOf(MirrorVoice("11", "Стандартная"),
                MirrorVoice("27", "Дубляж")),
            title = "Тестовый фильм",
            portraitsResolved = true,
            defaultVoice = "11"
        ))
        var selected: String? = null
        var openedLift = false
        compose.setContent {
            MaterialTheme {
                FilmDetails(
                    movie = movie,
                    source = "https://rezka.example",
                    sources = listOf("https://rezka.example"),
                    isFavourite = false,
                    onToggleFavourite = {},
                    onBack = {},
                    onBrowse = {},
                    savedSelection = null,
                    onWatch = { request -> selected = request.voice },
                    onLift = { openedLift = true },
                    onRelated = {},
                    onMetadata = {},
                    onPerson = {},
                    verifiedPlayer = MirrorMoviePage(
                        id = "123",
                        isSeries = false,
                        voices = listOf(MirrorVoice("11", "Стандартная")),
                        episodes = emptyList(),
                        defaultVoice = "11"
                    )
                )
            }
        }
        compose.onNodeWithText("Смотреть в Lift").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(openedLift)
            assertNull("Legacy mirror must not auto-start", selected)
        }
    }
}
