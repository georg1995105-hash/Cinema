package app.cinema.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale

private val russian = Locale.forLanguageTag("ru")
private val countryNames = Locale.getISOCountries().asSequence()
    .map { Locale("", it).getDisplayCountry(russian).replaceFirstChar { c -> c.titlecase(russian) } }
    .filter { it.isNotBlank() }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    .toList()
private val types = listOf("Все", "Фильмы", "Сериалы", "Мультфильмы", "Аниме")
private val genres = listOf(
    "Вестерны", "Семейные", "Фэнтези", "Биографические", "Арт-хаус", "Боевики",
    "Военные", "Детективы", "Криминал", "Приключения", "Драмы", "Спортивные",
    "Фантастика", "Комедии", "Мелодрамы", "Триллеры", "Ужасы", "Мюзиклы",
    "Музыкальные", "Исторические", "Документальные", "Детские", "Путешествия",
    "Познавательные", "Короткометражные", "Русские", "Зарубежные"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(
    source: String?,
    cardsPerRow: Int,
    onOpen: (CatalogMovie) -> Unit,
    onAddSource: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("Все") }
    LaunchedEffect(query) {
        delay(520)
        submittedQuery = query.trim()
    }
    val countries = remember { mutableStateListOf<String>() }
    val selectedGenres = remember { mutableStateListOf<String>() }
    val years = remember { mutableStateListOf<Int>() }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var expandCountries by rememberSaveable { mutableStateOf(false) }
    var expandGenres by rememberSaveable { mutableStateOf(false) }
    var expandYears by rememberSaveable { mutableStateOf(false) }
    var speechError by remember { mutableStateOf("") }
    val speech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) query = text
        }
    }
    val selectedType = type
    val countryFilter = countries.toList()
    val genreFilter = selectedGenres.toList()
    val yearFilter = years.toList()
    val resultFilter: (CatalogMovie) -> Boolean = { movie ->
        val kindMatches = when (selectedType) {
            "Фильмы" -> "/films/" in movie.url
            "Сериалы" -> "/series/" in movie.url
            "Мультфильмы" -> "/cartoons/" in movie.url
            "Аниме" -> "/animation/" in movie.url
            else -> true
        }
        val details = movie.details
        kindMatches && (countryFilter.isEmpty() || countryFilter.any { details.contains(it, true) }) &&
            (genreFilter.isEmpty() || genreFilter.any { details.contains(it, true) }) &&
            (yearFilter.isEmpty() || yearFilter.any { details.contains(it.toString()) })
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeading("Поиск")
        Spacer(Modifier.height(13.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Название фильма или сериала") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submittedQuery = query.trim() }),
                singleLine = true,
                modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    try {
                        speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Произнесите название фильма")
                        })
                        speechError = ""
                    } catch (_: ActivityNotFoundException) {
                        speechError = "На устройстве нет службы распознавания речи"
                    }
                }, modifier = Modifier.size(50.dp)
            ) { Icon(Icons.Outlined.Mic, contentDescription = "Голосовой поиск") }
        }
        if (speechError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(speechError, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(9.dp))
        OutlinedButton(onClick = { showFilters = !showFilters },
            shape = RoundedCornerShape(13.dp)) {
            Icon(Icons.Outlined.Tune, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Фильтры")
            if (countries.isNotEmpty() || selectedGenres.isNotEmpty() || years.isNotEmpty())
                Text(" · " + (countries.size + selectedGenres.size + years.size))
            Icon(if (showFilters) Icons.Outlined.KeyboardArrowUp
                 else Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        Spacer(Modifier.height(10.dp))
        if (showFilters) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Surface(color = appPanel, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Тип контента", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        SimpleTabs(types, type) { type = it }
                        Spacer(Modifier.height(12.dp))
                        FilterHeader("Страны", countries.size, expandCountries) {
                            expandCountries = !expandCountries
                        }
                        if (expandCountries) {
                            OutlinedTextField(countryQuery, { countryQuery = it },
                                label = { Text("Поиск по странам") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                leadingIcon = {
                                    Icon(Icons.Outlined.Search, contentDescription = null)
                                })
                            Spacer(Modifier.height(8.dp))
                            val filtered = countryNames.filter {
                                countryQuery.isBlank() || it.contains(countryQuery, true)
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
                                items(filtered) { country ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (country in countries) countries.remove(country)
                                            else countries.add(country)
                                        }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = country in countries,
                                            onCheckedChange = {
                                                if (it) { if (country !in countries) countries.add(country) }
                                                else countries.remove(country)
                                            })
                                        Spacer(Modifier.width(8.dp))
                                        Text(country)
                                    }
                                }
                            }
                        }
                        FilterHeader("Жанры", selectedGenres.size, expandGenres) {
                            expandGenres = !expandGenres
                        }
                        if (expandGenres) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                genres.forEach { item ->
                                    FilterChip(
                                        selected = item in selectedGenres,
                                        onClick = {
                                            if (item in selectedGenres) selectedGenres.remove(item)
                                            else selectedGenres.add(item)
                                        }, label = { Text(item) }
                                    )
                                }
                            }
                        }
                        FilterHeader("Годы (до пяти)", years.size, expandYears) {
                            expandYears = !expandYears
                        }
                        if (expandYears) {
                            val maxYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items((maxYear downTo 1950).toList()) { year ->
                                    FilterChip(selected = year in years,
                                        onClick = {
                                            if (year in years) years.remove(year)
                                            else if (years.size < 5) years.add(year)
                                        }, label = { Text(year.toString()) })
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(onClick = {
                                countries.clear(); selectedGenres.clear(); years.clear()
                            }) { Text("Сбросить") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { showFilters = false }) { Text("Применить") }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Параметры сохраняются на экране. Отбор по каталогу заработает после подключения источника.",
                    color = appMuted, style = MaterialTheme.typography.bodySmall)
            }
        } else if (submittedQuery.isBlank()) {
            EmptyPanel("Найти фильм или сериал", "Введите название для поиска по выбранному зеркалу.",
                Icons.Outlined.Search)
        } else {
            val url = remember(source, submittedQuery) {
                source?.let { MirrorCatalog.searchUrl(it, submittedQuery) }
            }
            CatalogGrid(
                source = source,
                section = "Поиск",
                kind = "Все",
                cardsPerRow = cardsPerRow,
                onOpen = onOpen,
                onBrowse = {},
                onAddSource = onAddSource,
                requestOverride = url,
                movieFilter = resultFilter
            )
        }
    }
}

@Composable
private fun FilterHeader(title: String, count: Int, expanded: Boolean, toggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title + if (count > 0) " · $count" else "",
            modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Развернуть",
            tint = appBlue)
    }
}
