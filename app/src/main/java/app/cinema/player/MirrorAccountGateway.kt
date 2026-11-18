package app.cinema.player

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class MirrorLoginResult(
    val success: Boolean,
    val message: String,
    val retryInBrowser: Boolean = false
)

internal object MirrorLoginProtocol {
    fun decode(status: Int, body: String): MirrorLoginResult {
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

    fun hasSession(source: String): Boolean {
        val origin = origin(source) ?: return false
        val c = cookieManager.getCookie(origin).orEmpty()
        return listOf("dle_user_id=", "dle_password=").all { c.contains(it) }
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
                val connection = URI("$origin/ajax/login/").toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    connection.setRequestProperty("Origin", origin)
                    connection.setRequestProperty("Referer", "$origin/")
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8")
                    cookieManager.getCookie(origin)?.let {
                        connection.setRequestProperty("Cookie", it)
                    }
                    val form = listOf(
                        "login_name" to username.trim(),
                        "login_password" to password,
                        "login_not_save" to "0",
                        "login" to "submit"
                    ).joinToString("&") { (k, v) ->
                        k + "=" + URLEncoder.encode(v, "UTF-8")
                    }
                    connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val body = if (status == 200)
                        connection.inputStream.bufferedReader().use { reader ->
                            val buffer=CharArray(12000)
                            val size=reader.read(buffer)
                            if(size>0) String(buffer,0,size) else ""
                        }
                    else ""
                    // Session cookies can arrive on a 302/303 response as well as 200.
                    // Never replay the credential-bearing POST to a redirect destination.
                    connection.headerFields.orEmpty().forEach { (key, values) ->
                        if (key?.equals("Set-Cookie", ignoreCase = true) == true) {
                            values.forEach { raw ->
                                if (raw.isNotBlank()) cookieManager.setCookie(origin, raw)
                            }
                        }
                    }
                    cookieManager.flush()
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (!isSafeRedirect(origin, location)) {
                            return@withContext MirrorLoginResult(false,
                                "Сайт переадресует на другой домен. Выберите его отдельным зеркалом — пароль туда не отправлялся.")
                        }
                        if (hasSession(origin))
                            return@withContext MirrorLoginResult(true, "Сеанс сохранён")
                    }
                    val outcome = MirrorLoginProtocol.decode(status, body)
                    if (!outcome.success) return@withContext outcome
                    if (hasSession(origin)) outcome else MirrorLoginResult(false,
                        "Сайт принял запрос, но не предоставил сессионные cookie.",
                        retryInBrowser = true)
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                MirrorLoginResult(false, "Прямое подключение недоступно.", retryInBrowser = true)
            }
        }

    fun forgetLocalSession(source: String) {
        val origin = origin(source) ?: return
        MirrorSitePolicy.cookieScopes(origin).forEach { scope ->
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
