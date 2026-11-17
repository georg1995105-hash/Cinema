package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Only an explicit reachability check; a denied HEAD request is not proof of site failure. */
object SourceProbe {
    suspend fun check(url: String): String = withContext(Dispatchers.IO) {
        if (!SourceRules.isValidHttpsUrl(url)) return@withContext "Неправильный адрес"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "HEAD"
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            when (code) {
                in 200..299 -> "Сервер доступен. Код ответа: $code"
                in 300..399 -> "Сервер перенаправляет запрос. Код: $code"
                405 -> "Сервер не разрешил такую проверку"
                else -> "Ответ сервера: $code"
            }
        } catch (_: Exception) {
            "Не удалось связаться с сервером"
        } finally {
            connection?.disconnect()
        }
    }
}
