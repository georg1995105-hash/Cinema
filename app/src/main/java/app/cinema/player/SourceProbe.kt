package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** An opt-in HTTP reachability check; HEAD failure does not imply a mirror is unusable. */
object SourceProbe {
    suspend fun check(url: String): String = withContext(Dispatchers.IO) {
        if (!SourceRules.isValidHttpsUrl(url)) return@withContext "Неверный HTTPS-адрес"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "HEAD"
                instanceFollowRedirects = false
            }
            val response = conn.responseCode
            when (response) {
                in 200..299 -> "Сервер отвечает: HTTP $response"
                in 300..399 -> "Переадресация: HTTP $response"
                405 -> "Метод HEAD запрещён; нужна проверка в плеере"
                else -> "Ответ сервера: HTTP $response"
            }
        } catch (_: Exception) {
            "Не удалось выполнить HTTP-проверку"
        } finally {
            conn?.disconnect()
        }
    }
}
