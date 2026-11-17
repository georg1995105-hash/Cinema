package app.cinema.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchedVideo(val url: String, val positionMs: Long)

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("cinema_local_v1", Context.MODE_PRIVATE)

    fun sources(): List<String> = runCatching {
        val data = JSONArray(prefs.getString("sources", "[]") ?: "[]")
        (0 until data.length()).map { data.getString(it) }.filter(SourceRules::isValidHttpsUrl)
    }.getOrDefault(emptyList())

    fun saveSources(sources: List<String>) {
        val json = JSONArray()
        sources.distinct().forEach(json::put)
        prefs.edit().putString("sources", json.toString()).apply()
    }

    fun history(): List<WatchedVideo> = runCatching {
        val data = JSONArray(prefs.getString("watch_history", "[]") ?: "[]")
        (0 until data.length()).map {
            val obj = data.getJSONObject(it)
            WatchedVideo(obj.getString("url"), obj.optLong("positionMs", 0))
        }.filter { SourceRules.isValidHttpsUrl(it.url) }
    }.getOrDefault(emptyList())

    fun saveHistory(entries: List<WatchedVideo>) {
        val json = JSONArray()
        entries.take(50).forEach {
            json.put(JSONObject().put("url", it.url).put("positionMs", it.positionMs.coerceAtLeast(0)))
        }
        prefs.edit().putString("watch_history", json.toString()).apply()
    }
}
