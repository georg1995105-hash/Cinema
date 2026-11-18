package app.cinema.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class MirrorProbeResult(
    val available: Boolean, val latencyMs: Long?, val message: String
)

/**
 * HTTPS response latency, not ICMP ping and not a claim of video availability.
 * Share the film client's headers/cookies; a bare URLConnection gives false
 * negatives on Rezka domains that require their existing Android session.
 */
object SourceProbe {
    private val client = MirrorHttp.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    internal suspend fun inspect(url: String): MirrorProbeResult = withContext(Dispatchers.IO) {
        if (!SourceRules.isValidHttpsUrl(url))
            return@withContext MirrorProbeResult(false, null, "Неправильный адрес")
        val origin = MirrorAccountGateway.origin(url)
            ?: return@withContext MirrorProbeResult(false, null, "Неправильный адрес")
        val userAgent = System.getProperty("http.agent").orEmpty()
            .ifBlank { "Cinema Android" }
        val builder = Request.Builder().url(url).get()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "$origin/")
        MirrorSitePolicy.requestHeaders(url, userAgent).forEach { (name, value) ->
            builder.header(name, value)
        }
        val started = System.nanoTime()
        var current = url
        var redirects = 0
        try {
            while (redirects <= 3) {
                val currentOrigin = MirrorAccountGateway.origin(current)
                    ?: return@withContext MirrorProbeResult(false, null, "Неправильный адрес")
                val request = Request.Builder().url(current).get()
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Referer", "$currentOrigin/")
                    .apply {
                        MirrorSitePolicy.requestHeaders(current, userAgent)
                            .forEach { (name, value) -> header(name, value) }
                    }.build()
                val next = client.newCall(request).execute().use { response ->
                    val latency = ((System.nanoTime() - started) / 1_000_000L)
                        .coerceAtLeast(0L)
                    if (response.code in 300..399) {
                        val destination = MirrorProbeRedirect.next(
                            current, response.header("Location")
                        )
                        if (destination == null || redirects >= 3)
                            return@withContext MirrorProbeResult(false, latency,
                                "Зеркало перенаправляет на недопустимый адрес")
                        return@use destination
                    }
                    return@withContext when (response.code) {
                        in 200..299 -> MirrorProbeResult(true, latency,
                            if (redirects > 0)
                                "Сайт отвечает после перенаправления (видео не проверено)"
                            else "Сайт отвечает (доступность фильма не проверена)")
                        403, 429 -> MirrorProbeResult(false, latency,
                            "Сайт ограничил запрос (HTTP ${response.code})")
                        else -> MirrorProbeResult(false, latency,
                            "HTTP ${response.code}")
                    }
                }
                current = next
                redirects++
            }
            MirrorProbeResult(false, null, "Слишком много перенаправлений")
        } catch (_: java.net.SocketTimeoutException) {
            MirrorProbeResult(false, null, "Превышено время ответа")
        } catch (_: Exception) {
            MirrorProbeResult(false, null, "Нет ответа HTTPS")
        }
    }

    suspend fun check(url: String): String {
        val result = inspect(url)
        return result.message + (result.latencyMs?.let { " · $it мс" } ?: "")
    }
}
