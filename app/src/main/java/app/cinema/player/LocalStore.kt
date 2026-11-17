package app.cinema.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchedVideo(val url: String, val positionMs: Long)

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

    fun cardsPerRow(): Int = prefs.getInt("cards_per_row", 5).coerceIn(2, 8)
    fun saveCardsPerRow(value: Int) { prefs.edit().putInt("cards_per_row", value.coerceIn(2, 8)).apply() }
    fun holdSpeed(): Int = prefs.getInt("hold_speed", 2).takeIf { it in 2..4 } ?: 2
    fun saveHoldSpeed(value: Int) { if (value in 2..4) prefs.edit().putInt("hold_speed", value).apply() }

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
}
