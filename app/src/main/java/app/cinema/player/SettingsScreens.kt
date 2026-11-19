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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
internal fun SettingsScreen(open: (Page) -> Unit, onAccount: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val device = CinemaDevices.detect(context, androidx.compose.ui.platform.LocalConfiguration.current)
    Column(
        Modifier.then(if (device == CinemaDevice.TV) Modifier.widthIn(max = 780.dp)
            else Modifier.fillMaxWidth())
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeading("Настройки")
        Spacer(Modifier.height(16.dp))
        MenuItem("Обновления", Icons.Outlined.SystemUpdate) {
            open(Page.UPDATES)
        }
        MenuItem("Внешний вид", Icons.Outlined.Palette) {
            open(Page.APPEARANCE)
        }
        MenuItem("О приложении", Icons.Outlined.Info) {
            open(Page.ABOUT)
        }

    }
}

@Composable
private fun MenuItem(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    click: () -> Unit) {
    Surface(onClick = click, color = appPanel, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = appBlue)
            Spacer(Modifier.width(17.dp))
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
    val results=remember { mutableStateMapOf<String,MirrorProbeResult>() }
    val scope=rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val browserUserAgent = remember(context) {
        android.webkit.WebSettings.getDefaultUserAgent(context)
    }
    val builtIns = MirrorSitePolicy.builtInOrigins
    val allMirrors = (sources.toList() + builtIns).distinctBy {
        it.trim().trimEnd('/').lowercase()
    }
    LaunchedEffect(selected, browserUserAgent) {
        // Do not send two dozen automatic requests from one mobile/VPN
        // exit just because Settings opened: rate limits can produce HTTP 403
        // on otherwise available catalog endpoints. Check only the selected
        // mirror; the remaining sites have an explicit manual test button.
        allMirrors.firstOrNull {
            SourceRules.sameOrigin(it, selected.orEmpty())
        }?.let { address ->
            results[address] = SourceProbe.inspect(address, browserUserAgent)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Зеркала", "Выбор зеркала и время HTTPS-ответа", back)
        Spacer(Modifier.height(14.dp))
        Surface(color=appPanel,shape=RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input,{input=it;message=""},
                    label={Text("Адрес сайта")},singleLine=true,
                    modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Button(onClick={
                        val url=input.trim()
                        when{
                            !SourceRules.isValidHttpsUrl(url)->message="Нужен адрес HTTPS"
                            url in sources->message="Зеркало уже добавлено"
                            else->{
                                change(sources.toList()+url)
                                if(selected==null)select(url)
                                input=""
                                message="Адрес сохранён"
                            }
                        }
                    },shape=RoundedCornerShape(12.dp)){
                        Icon(Icons.Outlined.Add,contentDescription=null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить")
                    }
                    if(message.isNotBlank()){
                        Spacer(Modifier.width(10.dp))
                        Text(message,color=appMuted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if(allMirrors.isEmpty()) {
            EmptyPanel("Нет зеркал",
                "Добавьте HTTPS-адрес выбранного источника.",Icons.Outlined.Public)
        } else {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp),
                contentPadding=PaddingValues(bottom=24.dp)) {
                items(allMirrors,key={it}){address->
                    var actions by remember(address){mutableStateOf(false)}
                    val check=results[address]
                    Surface(color=appCard,shape=RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp),
                            verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Column(modifier=Modifier.weight(1f)) {
                                    Text(address,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(if (address in builtIns) "Из списка эталона" else "Добавлено вами",
                                        color=appMuted,style=MaterialTheme.typography.labelSmall)
                                }
                                if(SourceRules.sameOrigin(address, selected.orEmpty()))Icon(Icons.Outlined.CheckCircle,
                                    contentDescription="Выбрано",tint=appBlue)
                            }
                            if(check!=null) {
                                Text(check.message+
                                    (check.latencyMs?.let{" · "+it+" мс"}?:""),
                                    color=if(check.available)appBlue else appMuted,
                                    style=MaterialTheme.typography.bodySmall)
                            } else {
                                Text(if (SourceRules.sameOrigin(address, selected.orEmpty()))
                                    "Проверяем каталог…" else "Нажмите «Проверить»", color=appMuted,
                                    style=MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically){
                                if(!SourceRules.sameOrigin(address, selected.orEmpty())){
                                    TextButton(onClick={select(address)}){Text("Выбрать")}
                                }else{
                                    Text("Основное",color=appBlue,
                                        style=MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(enabled=testing!=address,onClick={
                                    testing=address
                                    scope.launch {
                                        results[address]=SourceProbe.inspect(address, browserUserAgent)
                                        testing=null
                                    }
                                }){Text(if(testing==address)"Проверка…" else "Проверить")}
                                Spacer(Modifier.weight(1f))
                                Box {
                                    IconButton(onClick={actions=true}){
                                        Icon(Icons.Outlined.MoreVert,
                                            contentDescription="Действия")
                                    }
                                    DropdownMenu(expanded=actions,onDismissRequest={actions=false}){
                                        DropdownMenuItem(text={Text("Открыть")},
                                            onClick={actions=false;onBrowse(address)})
                                        if (address in sources) {
                                            DropdownMenuItem(text={Text("Удалить моё")},onClick={
                                                actions=false
                                                change(sources.filterNot{it==address})
                                                results.remove(address)
                                            })
                                        }
                                    }
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
        // Direct-link developer test is intentionally absent from the
        // consumer UI. Real films launch from their own film cards.
    }
}

@Composable
internal fun AppearanceScreen(local: LocalStore, back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val device = CinemaDevices.detect(context, androidx.compose.ui.platform.LocalConfiguration.current)
    val minCards = CinemaDevices.minColumns(device)
    val maxCards = CinemaDevices.maxColumns(device)
    var count by remember(device) {
        mutableIntStateOf(local.cardsPerRow().coerceIn(minCards, maxCards))
    }
    Column {
        ScreenHeading("Внешний вид", back = back)
        Spacer(Modifier.height(24.dp))
        Text("Карточек в ряду: $count", fontWeight = FontWeight.SemiBold)
        Slider(
            value = count.toFloat(), onValueChange = {
                count = it.toInt().coerceIn(minCards, maxCards)
                local.saveCardsPerRow(count)
            },
            valueRange = minCards.toFloat()..maxCards.toFloat(), steps = maxCards - minCards - 1,
            modifier = Modifier.fillMaxWidth()
        )


    }
}

@Composable
internal fun AboutScreen(back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    Column {
        ScreenHeading("О приложении", null, back)
        Spacer(Modifier.height(24.dp))
        Text(androidx.compose.ui.res.stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        if (version != null) Text("Версия $version", color = appBlue)
        Spacer(Modifier.height(18.dp))
        Text("Каталог фильмов и сериалов, поиск, избранное и локальная история. Просмотр зависит от доступности выбранного источника.",
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
