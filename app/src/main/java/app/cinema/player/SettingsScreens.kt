package app.cinema.player

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(open: (Page) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeading("Настройки", "Только нужное")
        Spacer(Modifier.height(24.dp))
        MenuItem("Источники и зеркала", "Добавление и проверка адресов", Icons.Outlined.Link) {
            open(Page.SOURCES)
        }
        MenuItem("Плеер", "Перемотка и проверка воспроизведения", Icons.Outlined.PlayArrow) {
            open(Page.PLAYER)
        }
        MenuItem("Обновления", "Новая версия и ход загрузки", Icons.Outlined.SystemUpdate) {
            open(Page.UPDATES)
        }
        MenuItem("Внешний вид", "Оформление и число карточек", Icons.Outlined.Palette) {
            open(Page.APPEARANCE)
        }
        MenuItem("О приложении", "Версия и сведения", Icons.Outlined.Info) {
            open(Page.ABOUT)
        }
        Spacer(Modifier.height(18.dp))
        Text("Вход в аккаунт не требуется. Гостевой режим включён.",
            color = appMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MenuItem(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    click: () -> Unit) {
    Surface(onClick = click, color = appPanel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = appBlue)
            Spacer(Modifier.width(17.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = appMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = appMuted)
        }
    }
}

@Composable
internal fun SourcesScreen(
    sources: SnapshotStateList<String>,
    selected: String?,
    change: (List<String>) -> Unit,
    select: (String?) -> Unit,
    back: () -> Unit,
    onBrowse: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        ScreenHeading("Зеркала", "Адреса сохраняются только на этом устройстве", back)
        Spacer(Modifier.height(20.dp))
        Surface(color = appPanel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Добавить адрес", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(input, { input = it; message = "" },
                    label = { Text("Адрес сайта") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val address = input.trim()
                    when {
                        !SourceRules.isValidHttpsUrl(address) -> message = "Нужен защищённый адрес с https://"
                        address in sources -> message = "Этот адрес уже добавлен"
                        else -> {
                            change(sources.toList() + address)
                            if (selected == null) select(address)
                            input = ""
                            message = "Адрес добавлен"
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить")
                }
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = appBlue)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (sources.isEmpty()) {
            EmptyPanel("Зеркала не добавлены",
                "Добавьте своё зеркало вручную. Сохранение адреса не подключает каталог автоматически.",
                Icons.Outlined.Link)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sources, key = { it }) { address ->
                    Surface(color = appCard, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(address, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (address == selected) Icon(
                                    Icons.Outlined.CheckCircle, contentDescription = "Выбрано",
                                    tint = appBlue)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (address != selected) TextButton(onClick = { select(address) }) {
                                    Text("Выбрать")
                                }
                                else Text("Основное", color = appBlue,
                                    style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { onBrowse(address) }) { Text("Открыть") }
                                Spacer(Modifier.width(6.dp))
                                TextButton(enabled = testing != address, onClick = {
                                    testing = address
                                    scope.launch {
                                        message = SourceProbe.check(address)
                                        testing = null
                                    }
                                }) { Text(if (testing == address) "Проверка…" else "Проверить") }
                                TextButton(onClick = { change(sources.filterNot { it == address }) }) {
                                    Text("Удалить", color = appMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerSettingsScreen(local: LocalStore, back: () -> Unit, openTest: () -> Unit) {
    var seconds by remember { mutableIntStateOf(local.seekSeconds()) }
    var speed by remember { mutableIntStateOf(local.holdSpeed()) }
    Column {
        ScreenHeading("Плеер", "Настройки воспроизведения", back)
        Spacer(Modifier.height(25.dp))
        Text("Шаг перемотки", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        SimpleTabs(listOf("10 секунд", "15 секунд", "30 секунд"), "$seconds секунд") {
            seconds = it.substringBefore(' ').toInt()
            local.saveSeekSeconds(seconds)
        }
        Spacer(Modifier.height(18.dp))
        Text("Ускорение при удержании", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        SimpleTabs(listOf("2×", "3×", "4×"), "$speed×") {
            speed = it.substringBefore('×').toInt()
            local.saveHoldSpeed(speed)
        }
        Spacer(Modifier.height(14.dp))
        Text("Двойное нажатие перематывает. Удержание временно ускоряет видео.",
            color = appMuted)
        Spacer(Modifier.height(18.dp))
        Text("Плеер сохраняет место остановки и воспроизводит доступные видеоссылки.",
            color = appMuted)
        Spacer(Modifier.height(22.dp))
        MenuItem("Проверка плеера", "Открыть видео по прямой ссылке",
            Icons.Outlined.PlayArrow, openTest)
    }
}

@Composable
internal fun AppearanceScreen(local: LocalStore, back: () -> Unit) {
    var count by remember { mutableIntStateOf(local.cardsPerRow()) }
    Column {
        ScreenHeading("Внешний вид", "Оформление", back)
        Spacer(Modifier.height(24.dp))
        Text("Карточек в ряду: $count", fontWeight = FontWeight.SemiBold)
        Slider(
            value = count.toFloat(), onValueChange = {
                count = it.toInt().coerceIn(2, 8)
                local.saveCardsPerRow(count)
            },
            valueRange = 2f..8f, steps = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Настройка будет применяться к сетке после подключения каталога.",
            color = appMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Surface(color = appPanel, shape = RoundedCornerShape(15.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Palette, contentDescription = null, tint = appBlue)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Тёмная тема", fontWeight = FontWeight.Medium)
                    Text("Используется во всех разделах", color = appMuted)
                }
            }
        }
    }
}

@Composable
internal fun AboutScreen(back: () -> Unit) {
    Column {
        ScreenHeading("О приложении", null, back)
        Spacer(Modifier.height(24.dp))
        Text("Кино", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("Версия 0.4.5", color = appBlue)
        Spacer(Modifier.height(18.dp))
        Text("Независимое приложение. Каталог и вход в сторонний сервис пока не подключены.",
            color = appMuted)
    }
}

@Composable
internal fun VideoTestScreen(back: () -> Unit, play: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column {
        ScreenHeading("Проверка плеера", "Воспроизведение по прямой ссылке", back)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(url, { url = it; error = "" },
            label = { Text("Адрес видео") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            if (SourceRules.isValidHttpsUrl(url)) play(url.trim())
            else error = "Укажите правильный защищённый адрес видео"
        }) { Text("Смотреть") }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
