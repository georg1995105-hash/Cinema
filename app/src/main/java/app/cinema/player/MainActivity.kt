package app.cinema.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import java.net.URI

internal val appBackground = Color(0xFF13181C)
internal val appPanel = Color(0xFF1D2429)
internal val appCard = Color(0xFF262D33)
internal val appBlue = Color(0xFF80C5E7)
internal val appMuted = Color(0xFFADB8C0)
private val appWhite = Color(0xFFF4F7F8)

internal enum class Page { HOME, SEARCH, SECTIONS, LIBRARY, SETTINGS, SOURCES, PLAYER, APPEARANCE, ABOUT, TEST }
private data class Destination(val page: Page, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination(Page.HOME, "Главная", Icons.Outlined.Home),
    Destination(Page.SEARCH, "Поиск", Icons.Outlined.Search),
    Destination(Page.SECTIONS, "Разделы", Icons.Outlined.GridView),
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
        if (activeSource !in newItems) {
            activeSource = null
            local.saveActiveSource(null)
        }
    }
    fun back() {
        page = if (page in listOf(Page.SOURCES, Page.PLAYER, Page.APPEARANCE, Page.ABOUT, Page.TEST))
            Page.SETTINGS else Page.HOME
    }
    playing?.let { url ->
        PlayerPane(
            url = url,
            startPositionMs = history.firstOrNull { it.url == url }?.positionMs ?: 0,
            seekSeconds = local.seekSeconds(),
            onClose = { playing = null },
            onSave = { saveProgress(url, it) }
        )
        return
    }
    BackHandler(enabled = page != Page.HOME) { back() }

    Scaffold(
        containerColor = appBackground,
        bottomBar = {
            BoxWithConstraints(
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
                                Page.SOURCES, Page.PLAYER, Page.APPEARANCE, Page.ABOUT, Page.TEST
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
            Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = if (tablet) 30.dp else 16.dp, vertical = 12.dp)
        ) {
            when (page) {
                Page.HOME -> HomeScreen()
                Page.SEARCH -> SearchScreen(history, favourites) { playing = it }
                Page.SECTIONS -> SectionsScreen()
                Page.LIBRARY -> LibraryScreen(history, favourites, { playing = it },
                    ::toggleFavourite, { url ->
                        history.removeAll { it.url == url }
                        local.saveHistory(history.toList())
                    })
                Page.SETTINGS -> SettingsScreen { page = it }
                Page.SOURCES -> SourcesScreen(sources, activeSource, ::saveSources,
                    { selected -> activeSource = selected; local.saveActiveSource(selected) }, ::back)
                Page.PLAYER -> PlayerSettingsScreen(local, ::back) { page = Page.TEST }
                Page.APPEARANCE -> AppearanceScreen(::back)
                Page.ABOUT -> AboutScreen(::back)
                Page.TEST -> VideoTestScreen(::back) { playing = it }
            }
        }
    }
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

private val feeds = listOf(
    "Горячие", "Смотрят сейчас", "Новинки", "Последние поступления", "Популярное", "В ожидании"
)
private val genres = listOf("Все", "Фильмы", "Сериалы", "Мультфильмы", "Аниме")

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
private fun HomeScreen() {
    var feed by rememberSaveable { mutableStateOf(feeds.first()) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            contentPadding = PaddingValues(start = 2.dp, end = 20.dp)
        ) {
            items(feeds) { section ->
                val active = feed == section
                Column(
                    Modifier.clickable { feed = section }
                        .padding(top = 15.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        section,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) appWhite else appMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(13.dp))
                    Box(
                        Modifier.fillMaxWidth().height(3.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (active) appBlue else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(color = appMuted.copy(alpha = .13f))
        // Content will be populated by a catalogue provider. Do not show fake posters.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = appMuted.copy(alpha = .55f),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Каталог пока пуст",
                    style = MaterialTheme.typography.titleMedium,
                    color = appWhite,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Фильмы появятся после подключения источника.",
                    color = appMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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
private fun SearchScreen(history: List<WatchedVideo>, favourites: List<String>, play: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matched = (history.map { it.url } + favourites).distinct()
        .filter { query.isNotBlank() && it.contains(query.trim(), ignoreCase = true) }
    Column {
        ScreenHeading("Поиск", "Поиск в каталоге появится после подключения источника")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(query, { query = it }, label = { Text("Название фильма или сериала") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(18.dp))
        if (matched.isEmpty()) {
            EmptyPanel(if (query.isBlank()) "Что будем смотреть?" else "Ничего не найдено",
                "Сейчас доступен поиск только по сохранённым видеоссылкам.",
                Icons.Outlined.Search)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(matched) { url ->
                    VideoRow(url, history.firstOrNull { it.url == url }?.positionMs, play)
                }
            }
        }
    }
}

@Composable
private fun SectionsScreen() {
    var category by remember { mutableStateOf(genres.first()) }
    Column {
        ScreenHeading("Разделы", "Категории фильмов и сериалов")
        Spacer(Modifier.height(22.dp))
        SimpleTabs(genres, category) { category = it }
        Spacer(Modifier.height(18.dp))
        EmptyPanel("Раздел «" + category + "» пуст",
            "Фильмы появятся после подключения каталога.",
            Icons.Outlined.GridView)
    }
}

private fun displayName(url: String): String =
    runCatching { "Видео · " + (URI(url).host ?: "сохранённое") }.getOrDefault("Видео")

@Composable
private fun VideoRow(
    url: String, position: Long?, play: (String) -> Unit,
    fav: Boolean = false, toggle: (() -> Unit)? = null, remove: (() -> Unit)? = null
) {
    Surface(color = appCard, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp))
                .background(appPanel), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = appBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(displayName(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (position != null) "Продолжить с " + (position / 60000) + " мин"
                    else "Сохранённое видео",
                    color = appMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (toggle != null) IconButton(onClick = toggle) {
                Icon(if (fav) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (fav) "Убрать из избранного" else "В избранное",
                    tint = if (fav) appBlue else appMuted)
            }
            if (remove != null) IconButton(onClick = remove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = appMuted)
            }
            FilledIconButton(onClick = { play(url) },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = appBlue)) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Смотреть", tint = appBackground)
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    history: List<WatchedVideo>, favourites: List<String>, play: (String) -> Unit,
    toggle: (String) -> Unit, remove: (String) -> Unit
) {
    var selected by remember { mutableStateOf("История") }
    Column {
        ScreenHeading("Моё", "История и избранное хранятся только на устройстве")
        Spacer(Modifier.height(20.dp))
        SimpleTabs(listOf("История", "Избранное"), selected) { selected = it }
        Spacer(Modifier.height(16.dp))
        val visible = if (selected == "История") history.map { it.url } else favourites
        if (visible.isEmpty()) {
            EmptyPanel(if (selected == "История") "Просмотров пока нет" else "Избранное пока пусто",
                if (selected == "История") "Здесь появятся просмотренные видео и место остановки."
                else "Видео можно сохранить в избранное из истории.",
                if (selected == "История") Icons.Outlined.History else Icons.Outlined.Bookmarks)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it }) { url ->
                    VideoRow(url, history.firstOrNull { it.url == url }?.positionMs, play,
                        fav = url in favourites, toggle = { toggle(url) },
                        remove = if (selected == "История") ({ remove(url) }) else null)
                }
            }
        }
    }
}
