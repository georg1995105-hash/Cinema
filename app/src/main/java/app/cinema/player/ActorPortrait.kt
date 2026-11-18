package app.cinema.player

import android.webkit.WebSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** An exact Wikidata person match with a Commons P18 portrait. Never guess a photo by a similar name. */
internal data class OpenPortrait(val image: String, val attributionPage: String)

internal object ActorPhotoLibrary {
    private val cache = ConcurrentHashMap<String, OpenPortrait>()
    private val semaphore = Semaphore(2)
    private val none = OpenPortrait("", "")

    suspend fun exactPortrait(name: String): OpenPortrait? {
        val normalized = clean(name)
        if (normalized.length < 4 || normalized.length > 95) return null
        cache[normalized]?.let { return it.takeIf { photo -> photo.image.isNotBlank() } }
        return semaphore.withPermit {
            cache[normalized]?.let { return@withPermit it.takeIf { photo -> photo.image.isNotBlank() } }
            val found = withContext(Dispatchers.IO) { lookup(name.trim(), normalized) }
            cache[normalized] = found ?: none
            found
        }
    }

    private fun clean(name: String): String =
        name.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    private fun lookup(name: String, normalized: String): OpenPortrait? {
        // The open catalog lists English actor names; the older mirror provides
        // Russian ones. Look up an *exact* label, never a loosely matched face.
        val suitable = sequenceOf("ru", "en").mapNotNull { language ->
            val query = buildString {
                append("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json")
                append("&language=").append(language).append("&limit=8&search=")
                append(URLEncoder.encode(name, "UTF-8"))
            }
            val list = fetchJson(query)?.optJSONArray("search") ?: return@mapNotNull null
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { result ->
                val label = clean(result.optString("label"))
                val description = result.optString("description").lowercase()
                label == normalized && listOf(
                    "актёр", "актер", "актриса", "режисс", "кинематограф", "сценарист",
                    "actor", "actress", "director", "film maker", "filmmaker"
                ).any { description.contains(it) } &&
                    result.optString("id").matches(Regex("Q[0-9]{1,15}"))
            }.singleOrNull()
        }.firstOrNull() ?: return null

        val id = suitable.optString("id")
        val entityUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
            "&format=json&ids=" + id + "&props=claims"
        val claims = fetchJson(entityUrl)?.optJSONObject("entities")
            ?.optJSONObject(id)?.optJSONObject("claims") ?: return null
        val images = claims.optJSONArray("P18") ?: return null
        val fileName = (0 until images.length()).mapNotNull { i ->
            images.optJSONObject(i)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                ?.optString("value")?.takeIf { it.isNotBlank() }
        }.firstOrNull { it.matches(Regex("(?i).+\\.(jpg|jpeg|png|webp)")) }
            ?: return null

        val encoded = URLEncoder.encode(fileName.replace(' ', '_'), "UTF-8")
            .replace("+", "%20")
        return OpenPortrait(
            image = "https://commons.wikimedia.org/wiki/Special:FilePath/$encoded?width=280",
            attributionPage = "https://commons.wikimedia.org/wiki/File:$encoded"
        )
    }

    private fun fetchJson(address: String): JSONObject? = runCatching {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3600
            connection.readTimeout = 3900
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "Cinema Android (https://github.com/georg1995105-hash/Cinema; public actor portrait lookup)"
            )
            if (connection.responseCode != 200) return@runCatching null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            if (response.length > 350_000) return@runCatching null
            JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Prefer a picture verified on the mirror's own actor profile; use Wikimedia only on exact identification. */
@Composable
internal fun ActorPortrait(
    person: FilmPerson,
    source: String,
    modifier: Modifier = Modifier,
    filmUrl: String = ""
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val imageLoader = remember(context) { posterImageLoader(context) }
    var mirrorFailed by remember(person.photo) { mutableStateOf(false) }
    var sourceFailed by remember(person.pid, filmUrl) { mutableStateOf(false) }
    var externalFailed by remember(person.name) { mutableStateOf(false) }
    // The reference film's person data-pid can resolve a real photo via the
    // mirror's ordinary person-info API even when the HTML has no data-photo.
    val sourcePortrait by produceState(
        initialValue = Pair(false, ""),
        person.pid, filmUrl, person.photo, mirrorFailed
    ) {
        val photo = if (person.pid.isNotBlank() && filmUrl.isNotBlank() &&
            (person.photo.isBlank() || mirrorFailed)) {
            MirrorPersonSource.portrait(
                filmUrl, person, WebSettings.getDefaultUserAgent(context)
            ).orEmpty()
        } else ""
        value = true to photo
    }
    val wikimedia by produceState<OpenPortrait?>(
        initialValue = null,
        person.name, person.photo, mirrorFailed, sourcePortrait, sourceFailed
    ) {
        if ((person.photo.isBlank() || mirrorFailed) &&
            sourcePortrait.first && (sourcePortrait.second.isBlank() || sourceFailed)) {
            delay(1100)
            value = ActorPhotoLibrary.exactPortrait(person.name)
        }
    }
    val actual = person.photo.takeIf { it.isNotBlank() && !mirrorFailed }
    val site = sourcePortrait.second.takeIf { it.isNotBlank() && !sourceFailed }
    val displayed = actual ?: site ?: wikimedia?.image?.takeUnless { externalFailed }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!displayed.isNullOrBlank()) {
            val request = remember(displayed, source) {
                ImageRequest.Builder(context).data(displayed)
                    .addHeader("User-Agent", WebSettings.getDefaultUserAgent(context))
                    .apply {
                        if ((actual != null || site != null) &&
                            SourceRules.sameOrigin(source, displayed)) {
                            addHeader("Referer", filmUrl.takeIf { SourceRules.sameOrigin(source, it) }
                                ?: person.url.takeIf { SourceRules.sameOrigin(source, it) }
                                ?: source.trimEnd('/') + "/")
                        }
                    }
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (actual != null) mirrorFailed = true
                    else if (site != null) sourceFailed = true
                    else externalFailed = true
                }
            )
            if (actual == null && !externalFailed && wikimedia != null) {
                Text(
                    "ⓘ",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).clickable {
                        wikimedia?.attributionPage?.let(uriHandler::openUri)
                    }.padding(5.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            Icon(Icons.Outlined.Person, contentDescription = null,
                tint = appMuted, modifier = Modifier.size(38.dp))
        }
    }
}
