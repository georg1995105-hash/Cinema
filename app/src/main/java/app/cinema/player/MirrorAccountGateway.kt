package app.cinema.player

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.Request

internal data class MirrorLoginResult(
    val success: Boolean,
    val message: String,
    val retryInBrowser: Boolean = false
)

internal object MirrorLoginProtocol {
    fun decode(status: Int, body: String): MirrorLoginResult {
        if (status == 0) return MirrorLoginResult(false,
            "Встроенный браузер не получил ответ входа: сбой сети, блокировка или перенаправление.")
        if (status != 200) return MirrorLoginResult(false,
            if (status in 300..399) "Зеркало перенаправило запрос."
            else if (status == 403 || status == 429 || status == 503)
                "Прямой запрос ограничен сайтом ($status)."
            else "Сервер ответил с ошибкой $status.",
            retryInBrowser = status in 300..399 || status == 403 ||
                status == 429 || status == 503)
        return runCatching {
            val json = JSONObject(body)
            val ok = json.optBoolean("success", false) || json.optInt("success", 0) == 1
            val msg = json.optString("message").replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(170)
            MirrorLoginResult(ok, if (ok) "Вход подтверждён сайтом" else
                msg.ifBlank { "Не удалось войти. Проверьте логин, пароль и зеркало." })
        }.getOrDefault(MirrorLoginResult(false,
            "Сайт вернул страницу вместо ответа входа.",
            retryInBrowser = true))
    }
}

/**
 * Credentials are posted only to the selected HTTPS mirror, never to a proxy or our backend.
 * Passwords are not persisted. Only site cookies are stored by Android's CookieManager.
 */
internal class MirrorAccountGateway(private val context: Context) {
    private val cookieManager: CookieManager get() = CookieManager.getInstance()
    private val http = MirrorHttp.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun hasSession(source: String): Boolean {
        val origin = origin(source) ?: return false
        val c = MirrorSitePolicy.mergeCookieHeaders(
            MirrorSitePolicy.sessionScopes(origin)
                .mapNotNull { cookieManager.getCookie(it)?.takeIf(String::isNotBlank) }
        )
        return MirrorGuestCookies.hasAccountSession(listOf(c))
    }

    suspend fun signIn(source: String, username: String, password: String,
                       userAgent: String): MirrorLoginResult =
        withContext(Dispatchers.IO) {
            val origin = origin(source)
                ?: return@withContext MirrorLoginResult(false, "Выберите защищённое зеркало.")
            if (username.isBlank() || password.isBlank())
                return@withContext MirrorLoginResult(false, "Введите логин и пароль.")
            if (username.length > 128 || password.length > 256)
                return@withContext MirrorLoginResult(false, "Слишком длинный логин или пароль.")

            try {
                val address = "$origin/ajax/login/"
                val form = FormBody.Builder()
                    .add("login_name", username.trim())
                    .add("login_password", password)
                    .add("login_not_save", "0")
                    .add("login", "submit")
                    .build()
                val builder = Request.Builder().url(address).post(form)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", origin)
                    .header("Referer", "$origin/")
                MirrorSitePolicy.requestHeaders(address, userAgent).forEach { (name, value) ->
                    builder.header(name, value)
                }
                http.newCall(builder.build()).execute().use { response ->
                    val status = response.code
                    val body = if (status == 200) {
                        response.body?.charStream()?.use { reader ->
                            val buffer = CharArray(12_000)
                            val size = reader.read(buffer)
                            if (size > 0) String(buffer, 0, size) else ""
                        }.orEmpty()
                    } else ""
                    // MirrorHttp synchronizes response cookies for this origin.
                    cookieManager.flush()
                    if (status in 300..399) {
                        if (!isSafeRedirect(origin, response.header("Location"))) {
                            return@withContext MirrorLoginResult(false,
                                "Сайт перенаправляет на другой домен. Пароль туда не отправлялся.")
                        }
                        if (hasSession(origin))
                            return@withContext MirrorLoginResult(true, "Сеанс сохранён")
                    }
                    val outcome = MirrorLoginProtocol.decode(status, body)
                    if (!outcome.success) return@withContext outcome
                    if (hasSession(origin)) outcome else MirrorLoginResult(false,
                        "Сайт принял запрос, но не предоставил сессионные cookie.",
                        retryInBrowser = true)
                }
            } catch (_: Exception) {
                MirrorLoginResult(false, "Прямое подключение недоступно.", retryInBrowser = true)
            }
        }

    fun forgetLocalSession(source: String) {
        val origin = origin(source) ?: return
        MirrorSitePolicy.sessionScopes(origin).forEach { scope ->
            listOf("dle_user_id", "dle_password", "dle_hash").forEach { name ->
                cookieManager.setCookie(scope, "$name=; Max-Age=0; Path=/; Secure")
            }
        }
        cookieManager.flush()
    }

    companion object {
        internal fun isSafeRedirect(sourceOrigin: String, location: String?): Boolean {
            if (location.isNullOrBlank()) return false
            return runCatching {
                val destination = URI(sourceOrigin).resolve(location)
                origin(destination.toString()) == sourceOrigin
            }.getOrDefault(false)
        }

        internal fun origin(address: String): String? {
            if (!SourceRules.isValidHttpsUrl(address)) return null
            return try {
                val uri = URI(address)
                val host = uri.host ?: return null
                "https://" + host + if (uri.port == -1 || uri.port == 443) "" else ":" + uri.port
            } catch (_: Exception) { null }
        }
    }
}
