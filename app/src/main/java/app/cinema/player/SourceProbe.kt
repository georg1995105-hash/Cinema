package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal data class MirrorProbeResult(
    val available: Boolean, val latencyMs: Long?, val message: String
)

/** HTTP reachability alone does not confirm working film/video streams. */
object SourceProbe {
    internal suspend fun inspect(url: String): MirrorProbeResult = withContext(Dispatchers.IO) {
        if (!SourceRules.isValidHttpsUrl(url))
            return@withContext MirrorProbeResult(false,null,"Неправильный адрес")
        val start=System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection=(URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout=6000;readTimeout=6000;requestMethod="GET"
                instanceFollowRedirects=false
                setRequestProperty("Accept", "text/html")
            }
            val code=connection.responseCode
            val latency=((System.nanoTime()-start)/1_000_000L).coerceAtLeast(0L)
            when(code){
                in 200..299 -> MirrorProbeResult(true,latency,"Сайт отвечает (видео не проверено)")
                in 300..399 -> MirrorProbeResult(false,latency,"Зеркало перенаправляет на другой адрес")
                403, 429 -> MirrorProbeResult(false,latency,"Доступ ограничен зеркалом: " + code)
                else -> MirrorProbeResult(false,latency,"Код ответа: "+code)
            }
        } catch (_: Exception) {
            MirrorProbeResult(false,null,"Нет ответа на проверку")
        } finally {connection?.disconnect()}
    }
    suspend fun check(url: String): String {
        val result=inspect(url)
        return result.message + (result.latencyMs?.let { " · " + it + " мс" } ?: "")
    }
}
