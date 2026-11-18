package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Film pages may publish only data-pid, while the actual photo is delivered by
 * the same mirror's /ajax/person_info/ endpoint. This is ordinary public person
 * metadata, not a premium or authentication bypass.
 */
internal object MirrorPersonSource {
    private val semaphore = Semaphore(3)
    private val cache = ConcurrentHashMap<String, String>()
    private val client: OkHttpClient = MirrorHttp.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(7, TimeUnit.SECONDS).build()
    private val idPattern = Regex("[0-9]{1,12}")

    internal fun photoFromResponse(
        raw: String, filmUrl: String, expectedName: String
    ): String? = runCatching {
        val data = JSONObject(raw)
        if (!data.optBoolean("success", false) &&
            data.optString("success") !in setOf("1", "true")) return@runCatching null
        val person = data.optJSONObject("person") ?: return@runCatching null
        fun normalized(value: String) = value.trim().lowercase()
            .replace('ё', 'е').replace(Regex("\\s+"), " ")
        val names = listOf(person.optString("name"), person.optString("name_alt"))
            .filter(String::isNotBlank).map(::normalized)
        // The person ID alone is not sufficient evidence: reject wrong profiles.
        if (names.isNotEmpty() && normalized(expectedName) !in names) return@runCatching null
        val rawPhoto = person.optString("photo").trim()
        if (rawPhoto.isBlank() || rawPhoto.contains("nopersonphoto", true) ||
            rawPhoto.equals("null", true)) return@runCatching null
        val resolved = URI(filmUrl).resolve(rawPhoto).toString()
        resolved.takeIf(MirrorPoster::isPhoto)
    }.getOrNull()

    suspend fun portrait(filmUrl: String, person: FilmPerson): String? {
        val filmId = MirrorPostId.fromUrl(filmUrl) ?: return null
        val personId = person.pid
        if (!personId.matches(idPattern) || person.name.isBlank()) return null
        val origin = MirrorAccountGateway.origin(filmUrl) ?: return null
        val key = "$origin/$filmId/$personId"
        cache[key]?.let { return it }
        return semaphore.withPermit {
            cache[key]?.let { return@withPermit it }
            val photo = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder().url("$origin/ajax/person_info/")
                        .post(FormBody.Builder()
                            .add("id", filmId).add("pid", personId).build())
                        .header("Referer", filmUrl)
                        .header("Origin", origin)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "application/json, text/plain, */*")
                        .apply {
                            MirrorSitePolicy.requestHeaders(filmUrl, "Cinema Android").forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 200) return@use null
                        val raw = response.body?.string()?.takeIf { it.length <= 150_000 }
                            ?: return@use null
                        photoFromResponse(raw, filmUrl, person.name)
                    }
                }.getOrNull()
            }
            if (photo != null) cache[key] = photo
            photo
        }
    }
}
