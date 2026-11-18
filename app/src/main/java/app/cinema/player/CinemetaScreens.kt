package app.cinema.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Public metadata UI; it does not assume that a catalog hit means a playable stream.
 * All states distinguish offline/source errors from a genuine empty result.
 */
@Composable
internal fun CinemetaGrid(
    tab: String, kind: String, cardsPerRow: Int,
    onOpen: (CatalogMovie) -> Unit,
    searchText: String = "",
    movieFilter: (CatalogMovie) -> Boolean = { true },
    loadContent: Boolean = true
) {
    val context = LocalContext.current
    var refresh by remember(tab, kind, searchText) { mutableIntStateOf(0) }
    var movies by remember(tab, kind, searchText) { mutableStateOf<List<CatalogMovie>>(emptyList()) }
    var loading by remember(tab, kind, searchText) { mutableStateOf(true) }
    var error by remember(tab, kind, searchText) { mutableStateOf("") }

    LaunchedEffect(tab, kind, searchText, refresh, loadContent) {
        if (!loadContent) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val items = if (searchText.isBlank())
                CinemetaCatalog.home(context, tab, kind)
            else CinemetaCatalog.search(context, searchText, kind)
            movies = items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Нет связи с каталогом: ${e.message ?: "ошибка сети"}"
        } finally { loading = false }
    }

    val visible = remember(movies, movieFilter) { movies.filter(movieFilter) }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600)
        cardsPerRow.coerceIn(2, 8) else cardsPerRow.coerceIn(2, 3)
    Box(Modifier.fillMaxSize()) {
        when {
            visible.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${visible.size} результатов", style = MaterialTheme.typography.bodySmall,
                        color = appMuted, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { refresh++ }) { Text("Обновить") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(17.dp)
                ) {
                    items(visible, key = { it.url }) { movie ->
                        FilmPoster(movie) { onOpen(movie) }
                    }
                }
            }
            loading && loadContent -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(Modifier.size(32.dp), color = appBlue)
                Spacer(Modifier.height(12.dp))
                Text("Загружаем каталог", color = appMuted)
            }
            error.isNotBlank() -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = appMuted,
                    modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text("Каталог временно недоступен", fontWeight = FontWeight.SemiBold)
                Text(error, color = appMuted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { refresh++ }) { Text("Повторить") }
            }
            !loadContent -> Unit
            else -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (searchText.isBlank()) "Нет материалов в этой категории"
                    else "По запросу ничего не найдено", color = appMuted)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { refresh++ }) { Text("Обновить") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CinemetaFilmDetails(
    movie: CatalogMovie, isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    onPlay: (String) -> Unit
) {
    val context = LocalContext.current
    var details by remember(movie.url) { mutableStateOf<CinemetaDetails?>(null) }
    var loading by remember(movie.url) { mutableStateOf(true) }
    var error by remember(movie.url) { mutableStateOf("") }
    var streams by remember(movie.url) { mutableStateOf<List<InternetArchiveStreams.Video>>(emptyList()) }
    var checking by remember(movie.url) { mutableStateOf(false) }
    var checkFailure by remember(movie.url) { mutableStateOf("") }
    var retry by remember(movie.url) { mutableIntStateOf(0) }
    val couldHaveArchive = CinemetaCatalog.identity(movie.url) == ("movie" to "tt0063350")
    LaunchedEffect(movie.url, retry) {
        loading = true
        error = ""
        try {
            details = CinemetaCatalog.metadata(context, movie.url)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Не удалось получить сведения: ${e.message.orEmpty()}" }
        finally { loading = false }
    }
    LaunchedEffect(movie.url, retry) {
        if (!couldHaveArchive) return@LaunchedEffect
        checking = true
        streams = emptyList()
        checkFailure = ""
        try {
            streams = InternetArchiveStreams.available(context, movie.url)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { checkFailure = "Источник не ответил: ${e.message.orEmpty()}" }
        finally { checking = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
            }
            Text("О фильме", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleFavourite) {
                Icon(if (isFavourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Избранное", tint = appBlue)
            }
        }
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        if (isTablet) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MoviePosterImage(movie, Modifier.width(190.dp).aspectRatio(.70f)
                    .border(1.dp, appPanel, RoundedCornerShape(14.dp)), ContentScale.Crop)
                DetailsBody(movie, details, loading, error, streams, checking, checkFailure,
                    couldHaveArchive, onPlay, { retry++ }, Modifier.weight(1f))
            }
        } else {
            MoviePosterImage(movie, Modifier.width(148.dp).aspectRatio(.70f)
                .align(Alignment.CenterHorizontally), ContentScale.Crop)
            Spacer(Modifier.height(12.dp))
            DetailsBody(movie, details, loading, error, streams, checking, checkFailure,
                couldHaveArchive, onPlay, { retry++ }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(
    movie: CatalogMovie, info: CinemetaDetails?, loading: Boolean, error: String,
    streams: List<InternetArchiveStreams.Video>, checking: Boolean, checkFailure: String,
    couldHaveArchive: Boolean, onPlay: (String) -> Unit, retry: () -> Unit,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(movie.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (movie.details.isNotBlank()) Text(movie.details, color = appMuted)
        if (loading && info == null) CircularProgressIndicator(Modifier.size(24.dp))
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = retry) { Text("Повторить") }
        }
        if (info != null) {
            if (info.rating.isNotBlank()) Text("IMDb: ${info.rating}", color = appBlue,
                fontWeight = FontWeight.Medium)
            if (info.description.isNotBlank()) {
                Text("Описание", style = MaterialTheme.typography.titleMedium)
                Text(info.description)
            }
            val facts = listOf(
                "Оригинальное название" to info.originalTitle,
                "Страна" to info.country,
                "Длительность" to info.runtime,
                "Жанры" to info.genres.joinToString(", "),
                "Режиссёр" to info.directors.joinToString(", ")
            ).filter { it.second.isNotBlank() }
            facts.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.width(158.dp), color = appMuted,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(value, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (info.actors.isNotEmpty()) {
                Text("В ролях", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    info.actors.take(16).forEach { name ->
                        Column(Modifier.width(94.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ActorPortrait(
                                person = FilmPerson(name, ""),
                                source = "",
                                modifier = Modifier.width(84.dp).height(108.dp)
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (info.episodes.isNotEmpty()) {
                Text("Сезоны и серии", style = MaterialTheme.typography.titleMedium)
                val bySeason = info.episodes.groupBy { it.season }.toSortedMap()
                bySeason.forEach { (season, episodes) ->
                    Text("Сезон $season • ${episodes.size} серий", color = appBlue)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        episodes.sortedBy { it.episode }.forEach {
                            Surface(shape = RoundedCornerShape(10.dp), color = appPanel) {
                                Text(it.episode.toString(), modifier = Modifier.padding(
                                    horizontal = 12.dp, vertical = 8.dp), color = appMuted)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = appMuted.copy(alpha = .2f))
        Text("Просмотр", style = MaterialTheme.typography.titleMedium)
        if (checking) Text("Проверяем реальный видеопоток…", color = appMuted)
        else if (streams.isNotEmpty()) {
            Text("Internet Archive • общественное достояние", color = appMuted)
            streams.forEach { video ->
                Button(onClick = { onPlay(video.url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Смотреть — ${video.title}")
                }
            }
        } else {
            Text(if (checkFailure.isNotBlank()) checkFailure
                else "Подтверждённый источник видео пока недоступен. Сведения и каталог работают независимо.",
                color = appMuted)
            if (couldHaveArchive) OutlinedButton(onClick = retry) { Text("Проверить снова") }
        }
    }
}
