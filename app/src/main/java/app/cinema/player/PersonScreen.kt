package app.cinema.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Filmography from the actor page when linked; fallback searches their name in the mirror. */
@Composable
internal fun PersonScreen(
    person: FilmPerson,
    source: String?,
    cardsPerRow: Int,
    onBack: () -> Unit,
    onOpen: (CatalogMovie) -> Unit,
    onBrowse: (String) -> Unit,
    onAddSource: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeading(person.name, "Фильмография", onBack)
        Spacer(Modifier.height(12.dp))
        if (source == null) {
            EmptyPanel("Выберите зеркало", "Фильмы актёра загружаются из каталога.",
                Icons.Outlined.Person)
        } else {
            val url = person.url.ifBlank { MirrorCatalog.searchUrl(source, person.name) }
            CatalogGrid(
                source = source,
                section = "Фильмография",
                kind = "Все",
                cardsPerRow = cardsPerRow,
                onOpen = onOpen,
                onBrowse = onBrowse,
                onAddSource = onAddSource,
                requestOverride = url
            )
        }
    }
}
