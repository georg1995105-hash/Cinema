package app.cinema.player

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemaHistoryPersistenceTest {
    private val first = "https://liftw.ws/details.html?id=18899&name=Test"
    private val second = "https://v3-cinemeta.strem.io/meta/movie/tt9876543.json"

    @Test fun historySurvivesStoreRecreationAndDoesNotLoseGoodRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("cinema_local_v1", android.content.Context.MODE_PRIVATE)
        val original = prefs.getString("watch_history", null)
        try {
            // Mimics a legacy record with one bad row followed by valid films.
            val damaged = JSONArray().put("corrupt")
                .put(JSONObject().put("url", first).put("positionMs", 0))
                .put(JSONObject().put("url", second).put("positionMs", 125000))
                .put(JSONObject().put("url", first).put("positionMs", 999))
            prefs.edit().putString("watch_history", damaged.toString()).commit()

            val store = LocalStore(context)
            assertEquals(listOf(first, second), store.history().map { it.url })
            assertEquals(125000L, store.history()[1].positionMs)

            store.saveHistory(listOf(WatchedVideo(first, 0), WatchedVideo(second, 125000)))
            val reopened = LocalStore(context)
            assertEquals(listOf(first, second), reopened.history().map { it.url })
            assertEquals(125000L, reopened.history()[1].positionMs)
        } finally {
            val edit = prefs.edit()
            if (original == null) edit.remove("watch_history") else edit.putString("watch_history", original)
            edit.commit()
        }
    }
}
