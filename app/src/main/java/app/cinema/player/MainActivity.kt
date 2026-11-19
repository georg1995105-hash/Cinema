package app.cinema.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.net.URI

internal val appBackground = Color(0xFF13181C)
internal val appPanel = Color(0xFF1D2429)
internal val appCard = Color(0xFF262D33)
internal val appBlue = Color(0xFF80C5E7)
internal val appMuted = Color(0xFFADB8C0)
private val appWhite = Color(0xFFF4F7F8)

internal enum class Page { HOME, SEARCH, LIBRARY, SETTINGS, SOURCES, PLAYER, APPEARANCE, ABOUT, TEST, UPDATES, SITE, MOVIE, FILM_PLAYER, PERSON, ACCOUNT, LIFT }
private data class Destination(val page: Page, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination(Page.HOME, "Главная", Icons.Outlined.Home),
    Destination(Page.SEARCH, "Поиск", Icons.Outlined.Search),
    Destination(Page.LIBRARY, "Моё", Icons.Outlined.Bookmarks),
    Destination(Page.SETTINGS, "Настройки", Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(19, 24, 28)
        window.navigationBarColor = android.graphics.Color.rgb(19, 24, 28)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = appBackground, surface = appCard,
                    primary = appBlue, onBackground = appWhite, onSurface = appWhite
                )
            ) { CinemaApp() }
        }
    }
}

@Composable
private fun CinemaApp() {
    val context = LocalContext.current
    val local = remember { LocalStore(context.applicationContext) }
    val history = remember { mutableStateListOf<WatchedVideo>().apply { addAll(local.history()) } }
    val favourites = remember { mutableStateListOf<String>().apply { addAll(local.favourites()) } }
    val sources = remember { mutableStateListOf<String>().apply { addAll(local.sources()) } }
    var activeSource by remember { mutableStateOf(local.activeSource()) }
    var page by remember { mutableStateOf(Page.HOME) }
    var playing by remember { mutableStateOf<String?>(null) }
    var playingMovieKey by remember { mutableStateOf<String?>(null) }
    var liftTitle by remember { mutableStateOf("") }
    var liftMovieKey by remember { mutableStateOf<String?>(null) }
    var liftResumeUrl by remember { mutableStateOf("") }
    var liftReturn by remember { mutableStateOf(Page.MOVIE) }
    var browsingSource by remember { mutableStateOf<String?>(null) }
    var playbackStorageUrl by remember { mutableStateOf<String?>(null) }
    var selectedFilm by remember { mutableStateOf<CatalogMovie?>(null) }
    var movieBack by remember { mutableStateOf(Page.HOME) }
    var selectedPerson by remember { mutableStateOf<FilmPerson?>(null) }
    var previousFilm by remember { mutableStateOf<CatalogMovie?>(null) }
    var browseReturn by remember { mutableStateOf(Page.HOME) }
    val tablet = LocalConfiguration.current.screenWidthDp >= 600

    fun saveProgress(url: String, position: Long) {
        history.removeAll { it.url == url }
        history.add(0, WatchedVideo(url, position))
        local.saveHistory(history.toList())
    }
    fun toggleFavourite(url: String) {
        if (url in favourites) favourites.remove(url) else favourites.add(0, url)
        local.saveFavourites(favourites.toList())
    }
    fun saveSources(newItems: List<String>) {
        sources.clear()
        sources.addAll(newItems)
        local.saveSources(newItems)
        if (activeSource !in newItems && !MirrorSitePolicy.isRecognizedMirror(activeSource.orEmpty())) {
            activeSource = null
            local.saveActiveSource(null)
        }
    }
    fun back() {
        if (page == Page.SITE) { page = browseReturn; return }
        if (page == Page.LIFT) { page = liftReturn; return }
        if (page == Page.MOVIE) { page = movieBack; return }
        if (page == Page.PERSON) { selectedFilm = previousFilm; page = Page.MOVIE; return }
        if (page == Page.FILM_PLAYER) {
            playbackStorageUrl = null
            page = Page.MOVIE
            return
        }
        page = if (page in listOf(Page.SOURCES, Page.PLAYER, Page.APPEARANCE, Page.ABOUT, Page.TEST, Page.UPDATES, Page.ACCOUNT))
            Page.SETTINGS else Page.HOME
    }
    playing?.let { url ->
        PlayerPane(
            url = url,
            startPositionMs = history.firstOrNull { it.url == (playingMovieKey ?: url) }?.positionMs ?: 0,
            seekSeconds = local.seekSeconds(),
            onClose = { playing = null; playingMovieKey = null },
            holdSpeed = local.holdSpeed(),
            onSave = { saveProgress(playingMovieKey ?: url, it) }
        )
        return
    }
    BackHandler(enabled = page != Page.HOME) { back() }
    LaunchedEffect(Unit) { AppUpdates.check(context) }

    Scaffold(
        containerColor = appBackground,
        bottomBar = {
            if (page != Page.SITE && page != Page.MOVIE && page != Page.FILM_PLAYER && page != Page.PERSON && page != Page.LIFT) BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val width = if (maxWidth > 640.dp) 616.dp else maxWidth - 24.dp
                NavigationBar(
                    modifier = Modifier.width(width).clip(RoundedCornerShape(22.dp)),
                    containerColor = appPanel,
                    tonalElevation = 0.dp
                ) {
                    destinations.forEach { destination ->
                        val selected = page == destination.page ||
                            (destination.page == Page.SETTINGS && page in listOf(
                                Page.SOURCES, Page.PLAYER, Page.APPEARANCE, Page.ABOUT, Page.TEST, Page.UPDATES, Page.ACCOUNT
                            ))
                        NavigationBarItem(
                            selected = selected,
                            onClick = { page = destination.page },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = { Text(destination.label, maxLines = 1) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = appBlue,
                                selectedTextColor = appWhite,
                                indicatorColor = appBlue.copy(alpha = .15f),
                                unselectedIconColor = appMuted,
                                unselectedTextColor = appMuted
                            )
                        )
                    }
                }
            }
        }
    ) { insets ->
        Box(
            if (page == Page.FILM_PLAYER || page == Page.LIFT) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = if (tablet) 30.dp else 16.dp, vertical = 12.dp)
        ) {
            when (page) {
                Page.HOME -> HomeScreen(
                    activeSource = activeSource,
                    cardsPerRow = local.cardsPerRow(),
                    onOpenFilm = { local.rememberMovie(it); selectedFilm = it; movieBack = Page.HOME; page = Page.MOVIE },
                    onBrowse = { browsingSource = it; browseReturn = Page.HOME; page = Page.SITE },
                    onAddSource = { page = Page.SOURCES }
                )
                Page.SEARCH -> SearchScreen(
                    local = local,
                    source = activeSource,
                    cardsPerRow = local.cardsPerRow(),
                    onOpen = { local.rememberMovie(it); selectedFilm = it; movieBack = Page.SEARCH; page = Page.MOVIE },
                    onAddSource = { page = Page.SOURCES },
                    onBrowse = { browsingSource = it; browseReturn = Page.SEARCH; page = Page.SITE }
                )
                Page.LIBRARY -> LibraryScreen(
                    local = local,
                    history = history,
                    favourites = favourites,
                    cardsPerRow = local.cardsPerRow(),
                    onOpen = { film ->
                        local.rememberMovie(film)
                        selectedFilm = film
                        movieBack = Page.LIBRARY
                        page = Page.MOVIE
                    },
                    onResume = { film ->
                        local.rememberMovie(film)
                        selectedFilm = film
                        movieBack = Page.LIBRARY
                        if (CinemetaCatalog.isMovieUrl(film.url) || ArchivePublicCatalog.isArchiveUrl(film.url)) {
                            val previousLift = local.liftVisitedUrl(film.url)
                            if (previousLift != null) {
                                liftTitle = film.title
                                liftMovieKey = film.url
                                liftResumeUrl = previousLift
                                liftReturn = Page.MOVIE
                                page = Page.LIFT
                            } else {
                                page = Page.MOVIE
                            }
                        } else {
                            playbackStorageUrl = film.url
                            browsingSource = activeSource?.let {
                                MirrorFilmUrl.changeOrigin(film.url, it)
                            } ?: film.url
                            page = Page.FILM_PLAYER
                        }
                    },
                    toggle = ::toggleFavourite,
                    remove = { url ->
                        history.removeAll { it.url == url }
                        local.removeLiftVisit(url)
                        local.saveHistory(history.toList())
                    }
                )
                Page.SETTINGS -> SettingsScreen(
                    open = { page = it },
                    onAccount = { page = Page.ACCOUNT }
                )
                Page.ACCOUNT -> MirrorAccountScreen(
                    sources = sources,
                    selected = activeSource,
                    onSelect = { selected ->
                        activeSource = selected
                        local.saveActiveSource(selected)
                    },
                    onSources = { page = Page.SOURCES },
                    onRegistration = { mirror ->
                        browsingSource = MirrorAccountGateway.origin(mirror)?.plus("/index.php?do=register")
                        browseReturn = Page.ACCOUNT
                        page = Page.SITE
                    },
                    onBack = ::back
                )
                Page.SOURCES -> SourcesScreen(sources, activeSource, ::saveSources,
                    { selected -> activeSource = selected; local.saveActiveSource(selected) }, ::back,
                    onBrowse = { browsingSource = it; browseReturn = Page.SOURCES; page = Page.SITE })
                Page.PLAYER -> PlayerSettingsScreen(local, ::back) { page = Page.TEST }
                Page.APPEARANCE -> AppearanceScreen(local, ::back)
                Page.UPDATES -> UpdatesScreen()
                Page.LIFT -> LiftWebScreen(
                    onClose = { page = liftReturn },
                    title = liftTitle,
                    resumeUrl = liftResumeUrl,
                    onFilmPageVisited = { detailUrl ->
                        val movieUrl = liftMovieKey
                        if (movieUrl != null) {
                            local.saveLiftVisit(movieUrl, detailUrl)
                            saveProgress(movieUrl, 0L)
                        }
                    },
                    expectedYear = LiftMatchPolicy.yearFromDetails(
                        selectedFilm?.details.orEmpty()
                    )
                )
                Page.SITE -> BrowserScreen(
                    browsingSource ?: activeSource.orEmpty(),
                    onBack = { page = browseReturn },
                    accountMode = browseReturn == Page.ACCOUNT,
                    readFilmMetadata = browseReturn == Page.MOVIE
                )
                Page.FILM_PLAYER -> {
                    val playbackUrl = browsingSource ?: selectedFilm?.url.orEmpty()
                    val storageUrl = playbackStorageUrl ?: selectedFilm?.url.orEmpty()
                    val saved = local.playbackSelection(storageUrl)
                    val startPosition = saved?.positionMs
                        ?: history.firstOrNull { it.url == storageUrl }?.positionMs ?: 0L
                    val closePlayer: () -> Unit = {
                        playbackStorageUrl = null
                        page = Page.MOVIE
                    }
                    if (saved?.provider == "collapse" &&
                        (saved.kinopoiskId.isNotBlank() || saved.imdbId.isNotBlank())) {
                        CollapseNativePlayer(
                            filmTitle = selectedFilm?.title.orEmpty(),
                            kinopoiskId = saved.kinopoiskId,
                            imdbId = saved.imdbId,
                            startPositionMs = startPosition,
                            seekSeconds = local.seekSeconds(),
                            holdSpeed = local.holdSpeed(),
                            savedSelection = saved,
                            onSaveSelection = { selection ->
                                local.savePlaybackSelection(storageUrl, selection)
                            },
                            onSave = { ms ->
                                if (storageUrl.isNotBlank()) saveProgress(storageUrl, ms)
                            },
                            onClose = closePlayer
                        )
                    } else {
                        MirrorNativePlayer(
                            filmUrl = playbackUrl,
                            filmTitle = selectedFilm?.title.orEmpty(),
                            mirrors = sources.toList(),
                            startPositionMs = startPosition,
                            seekSeconds = local.seekSeconds(),
                            holdSpeed = local.holdSpeed(),
                            savedSelection = saved,
                            onSaveSelection = { selection ->
                                local.savePlaybackSelection(
                                    storageUrl,
                                    selection.copy(
                                        provider = "rezka",
                                        kinopoiskId = saved?.kinopoiskId.orEmpty(),
                                        imdbId = saved?.imdbId.orEmpty()
                                    )
                                )
                            },
                            onSave = { ms ->
                                if (storageUrl.isNotBlank()) saveProgress(storageUrl, ms)
                            },
                            onClose = closePlayer
                        )
                    }
                }
                Page.MOVIE -> selectedFilm?.let { movie ->
                    if (CinemetaCatalog.isMovieUrl(movie.url) || ArchivePublicCatalog.isArchiveUrl(movie.url)) {
                        CinemetaFilmDetails(
                            movie = movie,
                            isFavourite = movie.url in favourites,
                            onToggleFavourite = { toggleFavourite(movie.url) },
                            onBack = { page = movieBack },
                            onPlay = { verifiedStreamUrl ->
                                playingMovieKey = movie.url
                                playing = verifiedStreamUrl
                            },
                            onLift = { resume ->
                                liftTitle = movie.title
                                liftMovieKey = movie.url
                                liftResumeUrl = if (resume) local.liftVisitedUrl(movie.url).orEmpty() else ""
                                liftReturn = Page.MOVIE
                                page = Page.LIFT
                            }
                        )
                    } else FilmDetails(
                        movie = movie,
                        source = activeSource ?: MirrorAccountGateway.origin(movie.url).orEmpty(),
                        sources = sources.toList(),
                        isFavourite = movie.url in favourites,
                        onToggleFavourite = { toggleFavourite(movie.url) },
                        onBack = { page = movieBack },
                        onBrowse = { url ->
                            browsingSource = url
                            browseReturn = Page.MOVIE
                            page = Page.SITE
                        },
                        savedSelection = local.playbackSelection(movie.url),
                        onLift = {
                            liftTitle = movie.title
                            liftMovieKey = movie.url
                            liftResumeUrl = ""
                            liftReturn = Page.MOVIE
                            page = Page.LIFT
                        },
                        onWatch = { request ->
                            if (request.voice.isNotBlank()) {
                                val storageUrl = movie.url
                                val previous = local.playbackSelection(storageUrl) ?: PlaybackSelection()
                                val changed = previous.provider != request.provider ||
                                    previous.voice != request.voice ||
                                    (request.season.isNotBlank() && previous.season != request.season) ||
                                    (request.episode.isNotBlank() && previous.episode != request.episode)
                                local.savePlaybackSelection(
                                    storageUrl,
                                    previous.copy(
                                        voice = request.voice,
                                        season = request.season.ifBlank {
                                            if (changed) "" else previous.season
                                        },
                                        episode = request.episode.ifBlank {
                                            if (changed) "" else previous.episode
                                        },
                                        positionMs = if (changed) 0L else previous.positionMs,
                                        provider = request.provider,
                                        kinopoiskId = request.kinopoiskId,
                                        imdbId = request.imdbId
                                    )
                                )
                                playbackStorageUrl = storageUrl
                                browsingSource = request.filmUrl
                                page = Page.FILM_PLAYER
                            }
                        },
                        onRelated = { next -> local.rememberMovie(next); selectedFilm = next },
                        onMetadata = { refined -> local.rememberMovie(refined); selectedFilm = refined },
                        onPerson = { person ->
                            selectedPerson = person
                            previousFilm = selectedFilm
                            page = Page.PERSON
                        }
                    )
                }
                Page.PERSON -> selectedPerson?.let { person ->
                    PersonScreen(
                        person = person,
                        source = activeSource,
                        cardsPerRow = local.cardsPerRow(),
                        onBack = { selectedFilm = previousFilm; page = Page.MOVIE },
                        onOpen = { film ->
                            local.rememberMovie(film)
                            selectedFilm = film
                            movieBack = Page.PERSON
                            page = Page.MOVIE
                        },
                        onBrowse = { browsingSource = it; browseReturn = Page.PERSON; page = Page.SITE },
                        onAddSource = { page = Page.SOURCES }
                    )
                }
                Page.ABOUT -> AboutScreen(::back)
                Page.TEST -> VideoTestScreen(::back) { playingMovieKey = null; playing = it }
            }
        }
    }
    UpdateNotice()
}

@Composable
internal fun ScreenHeading(title: String, subtitle: String? = null, back: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            IconButton(onClick = back) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = appBlue)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = appMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val feeds = listOf("Популярное", "Новинки", "Высокий рейтинг")
private val contentKinds = listOf("Все", "Фильмы", "Сериалы", "Мультфильмы")

@Composable
internal fun SimpleTabs(items: List<String>, selected: String, onSelected: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { text ->
            val active = text == selected
            FilterChip(
                selected = active, onClick = { onSelected(text) },
                label = { Text(text) },
                shape = RoundedCornerShape(11.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = appCard, labelColor = appMuted,
                    selectedContainerColor = appBlue.copy(alpha = .19f),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = active,
                    borderColor = Color.Transparent, selectedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun HomeScreen(
    activeSource: String?,
    cardsPerRow: Int,
    onOpenFilm: (CatalogMovie) -> Unit,
    onBrowse: (String) -> Unit,
    onAddSource: () -> Unit
) {
    var kind by rememberSaveable { mutableStateOf(contentKinds.first()) }
    var options by remember { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = 0, pageCount = { feeds.size })
    val tabList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(pager.currentPage) {
        if (tabList.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage }) {
            tabList.animateScrollToItem(pager.currentPage)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                state = tabList,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 12.dp)
            ) {
                items(feeds.size) { index ->
                    val active = pager.currentPage == index
                    Column(
                        Modifier.width(IntrinsicSize.Max)
                            .clickable {
                                scope.launch { pager.animateScrollToPage(index) }
                            }
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            feeds[index],
                            color = if (active) appWhite else appMuted,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier.fillMaxWidth().height(3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (active) appBlue else Color.Transparent)
                        )
                    }
                }
            }
            Box {
                Surface(
                    color = appPanel,
                    shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    TextButton(
                        onClick = { options = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            kind,
                            maxLines = 1,
                            color = appBlue,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = "Выбрать тип контента",
                            tint = appBlue
                        )
                    }
                }
                DropdownMenu(
                    expanded = options,
                    onDismissRequest = { options = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = appPanel,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(min = 180.dp)
                ) {
                    contentKinds.forEach { item ->
                        val selected = kind == item
                        DropdownMenuItem(
                            text = {
                                Text(
                                    item,
                                    color = if (selected) appBlue else appWhite,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (selected) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = appBlue
                                    )
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            },
                            onClick = { kind = item; options = false },
                            colors = MenuDefaults.itemColors(
                                textColor = if (selected) appBlue else appWhite,
                                leadingIconColor = appBlue
                            )
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = appMuted.copy(alpha = .13f))
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { index -> feeds[index] }
        ) { index ->
            CinemetaGrid(
                tab = feeds[index],
                kind = kind,
                cardsPerRow = cardsPerRow,
                onOpen = onOpenFilm,
                loadContent = pager.currentPage == index
            )
        }
    }
}

@Composable
internal fun EmptyPanel(title: String, explanation: String, icon: ImageVector) {
    Surface(color = appPanel, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = appBlue,
                modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(explanation, color = appMuted)
        }
    }
}



@Composable
private fun LibraryScreen(
    local: LocalStore,
    history: List<WatchedVideo>,
    favourites: List<String>,
    cardsPerRow: Int,
    onOpen: (CatalogMovie) -> Unit,
    onResume: (CatalogMovie) -> Unit,
    toggle: (String) -> Unit,
    remove: (String) -> Unit
) {
    var selected by remember { mutableStateOf("История") }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val columns = if (isTablet) cardsPerRow.coerceIn(3,8)
        else cardsPerRow.coerceIn(2,3)
    var metadataRevision by remember { mutableIntStateOf(0) }
    val list = if (selected == "История") history.map { it.url } else favourites
    val missing = (history.map { it.url } + favourites).distinct()
        .filter { local.savedMovie(it) == null }.take(16)
    val visible = list

    Column(Modifier.fillMaxSize()) {
        if (missing.isNotEmpty()) {
            RecoverLibraryCards(missing, local) { metadataRevision++ }
        }
        ScreenHeading("Моё")
        Spacer(Modifier.height(14.dp))
        SimpleTabs(listOf("История", "Избранное"), selected) { selected = it }
        Spacer(Modifier.height(10.dp))
        if (visible.isEmpty()) {
            EmptyPanel(
                if (selected == "История") "Просмотров пока нет" else "Избранное пока пусто",
                if (selected == "История") "Продолжение просмотра появится здесь."
                else "Добавьте фильм в избранное из его карточки.",
                if (selected == "История") Icons.Outlined.History else Icons.Outlined.Bookmarks
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(visible, key = { it }) { url ->
                    // Read revision so a recovered title/poster repaints its existing grid cell.
                    val revision = metadataRevision
                    val film = local.savedMovie(url) ?: CatalogMovie(
                        url, if (revision >= 0) "Загружаем карточку…" else "", "", "")
                    val position = history.firstOrNull { it.url == url }?.positionMs
                    val origin = runCatching {
                        val uri = URI(url)
                        uri.scheme + "://" + uri.authority
                    }.getOrDefault(url)
                    Column(Modifier.fillMaxWidth()) {
                        FilmPoster(film) { onOpen(film) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (selected == "История") {
                                val seconds = (position ?: 0L) / 1000L
                                Text(
                                    if (seconds > 0) {
                                        val minute = seconds / 60
                                        val second = (seconds % 60).toString().padStart(2,'0')
                                        "$minute:$second"
                                    } else if (local.liftVisitedUrl(url) != null) "В Lift"
                                    else "Начало",
                                    color = appMuted,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onResume(film) },
                                        modifier = Modifier.size(33.dp)) {
                                        Icon(Icons.Outlined.PlayArrow,
                                            contentDescription = "Продолжить", tint = appBlue)
                                    }
                                    IconButton(onClick = { remove(url) },
                                        modifier = Modifier.size(33.dp)) {
                                        Icon(Icons.Outlined.Close,
                                            contentDescription = "Удалить из истории", tint = appMuted)
                                    }
                                }
                            } else {
                                Text("Избранное", color = appMuted,
                                    style = MaterialTheme.typography.labelSmall)
                                IconButton(onClick = { toggle(url) },
                                    modifier = Modifier.size(33.dp)) {
                                    Icon(Icons.Outlined.BookmarkRemove,
                                        contentDescription = "Убрать из избранного", tint = appBlue)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
