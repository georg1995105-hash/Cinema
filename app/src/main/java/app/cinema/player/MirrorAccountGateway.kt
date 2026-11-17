package app.cinema.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class MirrorLoginResult(val success: Boolean, val message: String)

internal object MirrorLoginProtocol {
    fun decode(status: Int, body: String): MirrorLoginResult {
        if (status != 200) return MirrorLoginResult(false,
            if (status in 300..399) "Сайт перенаправил запрос. Проверьте зеркало."
            else "Сервер ответил с ошибкой $status.")
        return runCatching {
            val json = JSONObject(body)
            val ok = json.optBoolean("success", false) || json.optInt("success", 0) == 1
            val msg = json.optString("message").replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(170)
            MirrorLoginResult(ok, if (ok) "Вход подтверждён сайтом" else
                msg.ifBlank { "Не удалось войти. Проверьте логин, пароль и зеркало." })
        }.getOrDefault(MirrorLoginResult(false, "Некорректный ответ сайта."))
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

    suspend fun signIn(source: String, username: String, password: String): MirrorLoginResult =
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
                    connection.setRequestProperty("User-Agent",
                        WebSettings.getDefaultUserAgent(context))
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
                        connection.inputStream.bufferedReader().use { it.readText().take(12000) }
                    else ""
                    val outcome = MirrorLoginProtocol.decode(status, body)
                    if (!outcome.success) return@withContext outcome
                    connection.headerFields.orEmpty().forEach { (key, values) ->
                        if (key?.equals("Set-Cookie", ignoreCase = true) == true) {
                            values.forEach { raw ->
                                if (raw.isNotBlank()) cookieManager.setCookie(origin, raw)
                            }
                        }
                    }
                    cookieManager.flush()
                    if (hasSession(origin)) outcome else MirrorLoginResult(false,
                        "Сайт подтвердил вход, но не предоставил сохраняемый сеанс.")
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                MirrorLoginResult(false, "Не удалось подключиться к зеркалу. Проверьте адрес и доступ.")
            }
        }

    fun forgetLocalSession(source: String) {
        val origin = origin(source) ?: return
        listOf("dle_user_id", "dle_password", "dle_hash").forEach { name ->
            cookieManager.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
        }
        cookieManager.flush()
    }

    companion object {
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
