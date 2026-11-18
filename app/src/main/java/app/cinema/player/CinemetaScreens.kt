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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
            val cached = CinemetaCatalog.cachedCards(context, tab, kind, searchText)
                .filterNot { it.url.startsWith("https://rutube.ru/") }
            if (cached.isNotEmpty()) {
                movies = cached
                loading = false
            }
            val items = if (searchText.isBlank())
                CinemetaCatalog.home(context, tab, kind)
            else CinemetaCatalog.search(context, searchText, kind)
            movies = items
            loading = false
            CinemetaCatalog.saveCachedCards(context, tab, kind, searchText, items)
            val localized = RussianMovieTitles.enrich(items)
            movies = localized
            CinemetaCatalog.saveCachedCards(context, tab, kind, searchText, localized)
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
                    Text(
                        when {
                            error.isNotBlank() -> "Сохранённые результаты · нет связи"
                            loading -> "${visible.size} результатов · обновляем"
                            else -> "${visible.size} результатов"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error.isNotBlank()) MaterialTheme.colorScheme.error else appMuted,
                        modifier = Modifier.weight(1f)
                    )
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
                        CinemetaFilmPoster(movie) { onOpen(movie) }
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
    onPlay: (String) -> Unit,
    onLift: () -> Unit
) {
    val context = LocalContext.current
    var details by remember(movie.url) { mutableStateOf<CinemetaDetails?>(null) }
    var loading by remember(movie.url) { mutableStateOf(true) }
    var error by remember(movie.url) { mutableStateOf("") }
    var streams by remember(movie.url) { mutableStateOf<List<InternetArchiveStreams.Video>>(emptyList()) }
    var checking by remember(movie.url) { mutableStateOf(false) }
    var checkFailure by remember(movie.url) { mutableStateOf("") }
    var retry by remember(movie.url) { mutableIntStateOf(0) }
    val couldHaveArchive = InternetArchiveStreams.canCheck(movie.url)
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
                PublicMoviePoster(movie, Modifier.width(190.dp).aspectRatio(.70f)
                    .border(1.dp, appPanel, RoundedCornerShape(14.dp)))
                DetailsBody(movie, details, loading, error, streams, checking, checkFailure,
                    couldHaveArchive, onPlay, onLift, { retry++ }, Modifier.weight(1f))
            }
        } else {
            PublicMoviePoster(movie, Modifier.width(148.dp).aspectRatio(.70f)
                .align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            DetailsBody(movie, details, loading, error, streams, checking, checkFailure,
                couldHaveArchive, onPlay, onLift, { retry++ }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(
    movie: CatalogMovie, info: CinemetaDetails?, loading: Boolean, error: String,
    streams: List<InternetArchiveStreams.Video>, checking: Boolean, checkFailure: String,
    couldHaveArchive: Boolean,
    onPlay: (String) -> Unit, onLift: () -> Unit, retry: () -> Unit,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(movie.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (movie.details.isNotBlank()) Text(movie.details, color = appMuted)
        Button(onClick = onLift, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Смотреть в Lift")
        }
        Text("Откроется поиск фильма в Lift. Доступность видео зависит от сервиса и региона.",
            color = appMuted, style = MaterialTheme.typography.bodySmall)
        // Watch must be directly visible under the film title, not buried
        // below synopsis, credits and a long list of metadata.

        if (loading && info == null) CircularProgressIndicator(Modifier.size(24.dp))
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = retry) { Text("Повторить") }
        }
        if (info != null) {
            if (info.source.isNotBlank()) {
                val uriHandler = LocalUriHandler.current
                Text(
                    text = "Источник сведений: ${info.source}",
                    color = appBlue,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable {
                        uriHandler.openUri(info.sourceUrl.ifBlank { TvMazeSeries.attributionUrl })
                    }
                )
            }
            if (info.rating.isNotBlank()) Text(
                (if (info.source.startsWith("TVmaze")) "Рейтинг TVmaze: " else "IMDb: ") + info.rating,
                color = appBlue, fontWeight = FontWeight.Medium
            )
            if (info.description.isNotBlank()) {
                Text("Описание", style = MaterialTheme.typography.titleMedium)
                CinemaSynopsis(info.description)
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
                Text("Сведения об эпизодах — не ссылки на просмотр",
                    style = MaterialTheme.typography.bodySmall, color = appMuted)
                val bySeason = info.episodes.groupBy { it.season }.toSortedMap()
                bySeason.forEach { (season, episodes) ->
                    val count = episodes.map { it.episode }.distinct().size
                    Text("Сезон $season · $count серий", color = appBlue,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider(color = appMuted.copy(alpha = .2f))
        Text("Просмотр", style = MaterialTheme.typography.titleMedium)
        if (checking) Text("Проверяем реальный видеопоток…", color = appMuted)
        else if (streams.isNotEmpty()) {
            var selecting by remember(movie.url) { mutableStateOf(false) }
            Text("Internet Archive • доступный архивный фильм", color = appMuted)
            Button(onClick = {
                if (streams.size == 1) onPlay(streams.first().url)
                else selecting = true
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Смотреть")
            }
            if (selecting) {
                AlertDialog(
                    onDismissRequest = { selecting = false },
                    title = { Text("Выберите качество") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            streams.forEach { video ->
                                TextButton(
                                    onClick = {
                                        selecting = false
                                        onPlay(video.url)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(video.title) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selecting = false }) { Text("Отмена") }
                    }
                )
            }
        } else {
            Text(if (checkFailure.isNotBlank()) checkFailure
                else "Прямого подтверждённого MP4 здесь нет. Используйте поиск в Lift выше.",
                color = appMuted)
            if (couldHaveArchive) OutlinedButton(onClick = retry) { Text("Проверить снова") }
        }
    }
}

/**
 * Do not reuse MirrorHttp for independent poster hosts. It disables HTTPS
 * redirects and injects mirror cookies; public image CDNs require neither.
 */
@Composable
private fun PublicMoviePoster(movie: CatalogMovie, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(movie.url, movie.poster) { mutableStateOf(false) }
    Box(modifier.background(appCard).clip(RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center) {
        if (movie.poster.isBlank() || failed) {
            Icon(Icons.Outlined.Movie, contentDescription = null,
                tint = appMuted, modifier = Modifier.size(38.dp))
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(movie.poster)
                    .crossfade(true).build(),
                contentDescription = movie.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onError = { failed = true }
            )
        }
    }
}

@Composable
private fun CinemetaFilmPoster(movie: CatalogMovie, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        PublicMoviePoster(movie, Modifier.fillMaxWidth().aspectRatio(.70f))
        Spacer(Modifier.height(7.dp))
        Text(movie.title, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (movie.details.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(movie.details, color = appMuted, maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Ellipsis)
        }
    }
}
