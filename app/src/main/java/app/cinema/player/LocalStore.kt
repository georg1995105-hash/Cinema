package app.cinema.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchedVideo(val url: String, val positionMs: Long)

/** Playback state is stored only on the user's device and keyed by their own film URL. */
data class PlaybackSelection(
    val season: String = "",
    val episode: String = "",
    val voice: String = "",
    val quality: String = "",
    val positionMs: Long = 0L
)

class LocalStore(context: Context) {
    // Keep the same storage name so updates retain the previous history and mirror list.
    private val prefs = context.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)

    private fun strings(key: String): List<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until array.length()).map { array.getString(it) }
            .filter(SourceRules::isValidHttpsUrl).distinct()
    }.getOrDefault(emptyList())

    private fun saveStrings(key: String, values: List<String>) {
        val array = JSONArray()
        values.distinct().filter(SourceRules::isValidHttpsUrl).forEach(array::put)
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun sources(): List<String> = strings("sources")
    fun saveSources(values: List<String>) = saveStrings("sources", values)
    fun favourites(): List<String> = strings("favourites")
    fun saveFavourites(values: List<String>) = saveStrings("favourites", values)

    fun activeSource(): String? = prefs.getString("active_source", null)
        ?.takeIf { it in sources() }
    fun saveActiveSource(value: String?) {
        prefs.edit().putString("active_source", value).apply()
    }

    /** Keep the original keys and recognize the same mirror with or without a trailing slash. */
    fun savedAccountName(source: String): String {
        val origin = MirrorAccountGateway.origin(source) ?: return ""
        val names = accountNames()
        val matchingKey = sequenceOf(origin, source).plus(names.keys().asSequence())
            .firstOrNull { names.has(it) && SourceRules.sameOrigin(origin, it) }
        if (matchingKey != null) return names.optString(matchingKey).take(128)
        return prefs.getString("last_account_name_v1", "").orEmpty().take(128)
    }

    fun saveAccountName(source: String, name: String) {
        val origin = MirrorAccountGateway.origin(source) ?: return
        val entries = accountNames()
        val login = name.trim().take(128)
        entries.put(origin, login)
        val editor = prefs.edit().putString("account_names_v1", entries.toString())
        if (login.isNotBlank()) editor.putString("last_account_name_v1", login)
        editor.apply()
    }

    private fun accountNames(): JSONObject = runCatching {
        JSONObject(prefs.getString("account_names_v1", "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    fun cardsPerRow(): Int = prefs.getInt("cards_per_row", 5).coerceIn(2, 8)
    fun saveCardsPerRow(value: Int) { prefs.edit().putInt("cards_per_row", value.coerceIn(2, 8)).apply() }
    fun holdSpeed(): Int = prefs.getInt("hold_speed", 2).takeIf { it in 2..4 } ?: 2
    fun saveHoldSpeed(value: Int) { if (value in 2..4) prefs.edit().putInt("hold_speed", value).apply() }

    internal fun playbackSpeed(): Float = prefs.getFloat("playback_speed", 1f)
    internal fun savePlaybackSpeed(speed: Float) {
        prefs.edit().putFloat("playback_speed", speed).apply()
    }

    internal fun pictureMode(): String = prefs.getString("picture_mode", "").orEmpty()
    internal fun savePictureMode(mode: String) {
        prefs.edit().putString("picture_mode", mode).apply()
    }

    fun autoNextEpisode(): Boolean = prefs.getBoolean("auto_next_episode", true)
    fun saveAutoNextEpisode(enabled: Boolean) {
        prefs.edit().putBoolean("auto_next_episode", enabled).apply()
    }

    fun seekSeconds(): Int = prefs.getInt("seek_seconds", 10).takeIf { it in listOf(10, 15, 30) } ?: 10
    fun saveSeekSeconds(value: Int) {
        if (value in listOf(10, 15, 30)) prefs.edit().putInt("seek_seconds", value).apply()
    }

    fun history(): List<WatchedVideo> = runCatching {
        val data = JSONArray(prefs.getString("watch_history", "[]") ?: "[]")
        (0 until data.length()).map {
            val obj = data.getJSONObject(it)
            WatchedVideo(obj.getString("url"), obj.optLong("positionMs", 0))
        }.filter { SourceRules.isValidHttpsUrl(it.url) }
    }.getOrDefault(emptyList())

    fun saveHistory(entries: List<WatchedVideo>) {
        val array = JSONArray()
        entries.take(50).forEach {
            array.put(JSONObject().put("url", it.url).put("positionMs", it.positionMs.coerceAtLeast(0)))
        }
        prefs.edit().putString("watch_history", array.toString()).apply()
    }
    /** Bounded device-only poster/title index for history and bookmarks. */
    internal fun rememberMovie(movie: CatalogMovie) {
        if (!SourceRules.isValidHttpsUrl(movie.url)) return
        val all = runCatching {
            JSONArray(prefs.getString("movie_index_v1", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        val items = JSONArray()
        items.put(JSONObject()
            .put("url",movie.url)
            .put("title",CatalogTitleCleaner.clean(movie.title, movie.details).take(130))
            .put("poster",movie.poster.takeIf(MirrorPoster::isPhoto).orEmpty())
            .put("posters", JSONArray(movie.posterAlternatives.filter(MirrorPoster::isPhoto).take(7)))
            .put("details",movie.details.take(110)))
        for (i in 0 until all.length()) {
            if (items.length() >= 160) break
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("url") != movie.url) items.put(item)
        }
        prefs.edit().putString("movie_index_v1",items.toString()).apply()
    }

    internal fun savedMovie(url: String): CatalogMovie? = runCatching {
        val array = JSONArray(prefs.getString("movie_index_v1","[]") ?: "[]")
        val item = (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .firstOrNull { it.optString("url") == url } ?: return@runCatching null
        val title = CatalogTitleCleaner.clean(item.optString("title"), item.optString("details"))
        if (title.isBlank()) return@runCatching null
        CatalogMovie(
            url = url, title = title,
            poster = item.optString("poster").takeIf(MirrorPoster::isPhoto).orEmpty(),
            details = item.optString("details"),
            posterAlternatives = MirrorCatalog.readPosters(item).filter { it != item.optString("poster") }
        )
    }.getOrNull()

    fun playbackSelection(url: String): PlaybackSelection? = runCatching {
        val json = JSONObject(prefs.getString("playback_selections_v2", "{}") ?: "{}")
        val saved = json.optJSONObject(url) ?: return@runCatching null
        PlaybackSelection(
            season = saved.optString("season").take(6),
            episode = saved.optString("episode").take(6),
            voice = saved.optString("voice").take(15),
            quality = saved.optString("quality").take(30),
            positionMs = saved.optLong("positionMs", 0L).coerceAtLeast(0L)
        )
    }.getOrNull()

    fun savePlaybackSelection(url: String, selection: PlaybackSelection) {
        if (!SourceRules.isValidHttpsUrl(url)) return
        val objectRoot = runCatching {
            JSONObject(prefs.getString("playback_selections_v2", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        objectRoot.put(url, JSONObject()
            .put("season", selection.season)
            .put("episode", selection.episode)
            .put("voice", selection.voice)
            .put("quality", selection.quality)
            .put("positionMs", selection.positionMs.coerceAtLeast(0L)))
        // Bound persisted data; this is an on-device preference, never sync to public accounts.
        if (objectRoot.length() > 100) {
            val keys = objectRoot.keys().asSequence().toList()
            keys.take(objectRoot.length() - 100).forEach(objectRoot::remove)
        }
        prefs.edit().putString("playback_selections_v2", objectRoot.toString()).apply()
    }

}
