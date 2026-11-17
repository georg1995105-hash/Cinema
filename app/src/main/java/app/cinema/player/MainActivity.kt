package app.cinema.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val background = Color(0xFF101317)
private val surface = Color(0xFF1A2026)
private val accent = Color(0xFF95B8C8)
private val tabs = listOf("Главная", "История", "Источники", "Настройки")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 23)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 23)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = background,
                    surface = surface,
                    primary = accent,
                    onBackground = Color(0xFFF4F6F8)
                )
            ) {
                CinemaApp()
            }
        }
    }
}

@Composable
private fun CinemaApp() {
    val context = LocalContext.current
    val local = remember { LocalStore(context.applicationContext) }
    val sources = remember { mutableStateListOf<String>().apply { addAll(local.sources()) } }
    val history = remember { mutableStateListOf<WatchedVideo>().apply { addAll(local.history()) } }
    var selectedTab by remember { mutableIntStateOf(0) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    val tablet = LocalConfiguration.current.screenWidthDp >= 600

    fun storeProgress(url: String, pos: Long) {
        history.removeAll { it.url == url }
        history.add(0, WatchedVideo(url, pos))
        local.saveHistory(history.toList())
    }
    fun playVideo(url: String) {
        if (SourceRules.isValidHttpsUrl(url)) playerUrl = url
    }

    if (playerUrl != null) {
        val currentUrl = playerUrl!!
        PlayerPane(
            url = currentUrl,
            startPositionMs = history.firstOrNull { it.url == currentUrl }?.positionMs ?: 0,
            onClose = { playerUrl = null },
            onSave = { pos -> storeProgress(currentUrl, pos) }
        )
        return
    }

    Scaffold(
        containerColor = background,
        bottomBar = {
            if (!tablet) {
                NavigationBar(containerColor = surface) {
                    tabs.forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            icon = { Text(listOf("⌂", "◷", "◎", "⚙")[i]) },
                            label = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { insets ->
        Row(Modifier.fillMaxSize().padding(insets)) {
            if (tablet) {
                Column(
                    Modifier
                        .width(205.dp)
                        .fillMaxHeight()
                        .background(surface)
                        .padding(16.dp)
                ) {
                    Text(
                        "Cinema",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                    tabs.forEachIndexed { i, title ->
                        NavigationDrawerItem(
                            label = { Text(title) },
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            modifier = Modifier.padding(vertical = 3.dp),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Гостевой режим", style = MaterialTheme.typography.bodySmall, color = accent)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                when (selectedTab) {
                    0 -> HomeTab(onPlay = ::playVideo)
                    1 -> HistoryTab(history, onPlay = ::playVideo, onRemove = { url ->
                        history.removeAll { it.url == url }
                        local.saveHistory(history.toList())
                    })
                    2 -> SourcesTab(sources, onChange = { new ->
                        sources.clear()
                        sources.addAll(new)
                        local.saveSources(new)
                    })
                    else -> SettingsTab()
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFBBC4CA))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HomeTab(onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading("Добро пожаловать", "Вы смотрите как гость. Регистрация не требуется.")
        Card(colors = CardDefaults.cardColors(containerColor = surface)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Открыть видео", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Первая версия воспроизводит прямые HTTPS-ссылки на видео (MP4 / HLS). Каталог фильмов будет подключён отдельно.",
                    color = Color(0xFFBFC7CB)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = "" },
                    label = { Text("Адрес видео") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (SourceRules.isValidHttpsUrl(url)) {
                        onPlay(url.trim())
                    } else {
                        error = "Укажите прямой HTTPS-адрес без логина и пароля"
                    }
                }) { Text("Смотреть") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Каталог", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Источник каталога пока не подключён. Мы не показываем фиктивные фильмы и не используем платные потоки без разрешения.",
            color = Color(0xFFBFC7CB)
        )
    }
}

@Composable
private fun HistoryTab(
    videos: List<WatchedVideo>,
    onPlay: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    Column {
        Heading("История", "Прогресс сохраняется только на этом устройстве.")
        if (videos.isEmpty()) {
            Text("Здесь появятся просмотренные видео.", color = Color(0xFFBFC7CB))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(videos, key = { it.url }) { video ->
                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(video.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "Позиция: ${video.positionMs / 60000} мин",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { onPlay(video.url) }) { Text("Смотреть") }
                            TextButton(onClick = { onRemove(video.url) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcesTab(sources: SnapshotStateList<String>, onChange: (List<String>) -> Unit) {
    var value by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Heading("Источники", "Свои адреса хранятся на устройстве и не публикуются.")
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; message = "" },
            label = { Text("Адрес HTTPS-зеркала") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val candidate = value.trim()
            if (!SourceRules.isValidHttpsUrl(candidate)) {
                message = "Введите правильный HTTPS-адрес"
            } else if (candidate in sources) {
                message = "Этот адрес уже добавлен"
            } else {
                onChange(sources.toList() + candidate)
                value = ""
                message = "Источник сохранён локально"
            }
        }) { Text("Добавить") }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = accent)
        }
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources, key = { it }) { address ->
                Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = {
                                scope.launch { message = SourceProbe.check(address) }
                            }) { Text("Проверить") }
                            TextButton(onClick = {
                                onChange(sources.filterNot { it == address })
                                message = "Источник удалён"
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    Column {
        Heading("Настройки", "Только то, что действительно нужно.")
        Text("Режим: гость", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text("История: локальная · Зеркала: локальные · Тема: тёмная")
        Spacer(Modifier.height(20.dp))
        Text(
            "Cinema — независимый экспериментальный клиент. Не является официальным приложением HDRezka.",
            color = Color(0xFFBFC7CB)
        )
    }
}
