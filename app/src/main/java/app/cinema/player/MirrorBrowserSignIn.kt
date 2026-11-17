package app.cinema.player

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** An attached, short-lived WebView shares the mirror's real browser cookies.
 * Never sends credentials to a redirect or a separate authentication server.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MirrorBrowserSignIn(
    source: String,
    login: String,
    password: String,
    onComplete: (MirrorLoginResult) -> Unit
) {
    val context = LocalContext.current
    val currentComplete by rememberUpdatedState(onComplete)
    val origin = remember(source) { MirrorAccountGateway.origin(source) }
    if (origin == null) {
        LaunchedEffect(source) {
            currentComplete(MirrorLoginResult(false, "Неверный HTTPS-адрес зеркала."))
        }
        return
    }
    val gateway = remember(context) { MirrorAccountGateway(context.applicationContext) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val web = remember(origin) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        }
    }
    DisposableEffect(web) {
        var completed = false
        var launched = false
        fun finish(result: MirrorLoginResult) {
            if (completed) return
            completed = true
            handler.removeCallbacksAndMessages(null)
            currentComplete(result)
        }
        val usernameJs = JSONObject.quote(login.trim())
        val passwordJs = JSONObject.quote(password)
        val script = """
            (function() {
              if (window.__kinoLoginStarted) return;
              window.__kinoLoginStarted = true;
              var data = new URLSearchParams();
              data.set('login_name', $usernameJs);
              data.set('login_password', $passwordJs);
              data.set('login_not_save', '0');
              data.set('login', 'submit');
              fetch('/ajax/login/', {
                method: 'POST', credentials: 'same-origin', redirect: 'manual',
                headers: {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                          'X-Requested-With':'XMLHttpRequest'},
                body: data.toString()
              }).then(function(response) {
                return response.text().then(function(body) {
                  window.__kinoLoginResult = JSON.stringify({
                    status: response.status, body: body.slice(0,12000)
                  });
                });
              }).catch(function() {
                window.__kinoLoginResult = JSON.stringify({status:0,body:''});
              });
            })();
        """.trimIndent()
        var polls = 0
        fun poll() {
            if (completed) return
            if (polls++ >= 75) {
                finish(MirrorLoginResult(false,
                    "Зеркало не завершило вход. Возможно, требуется проверка сайта."))
                return
            }
            web.evaluateJavascript("window.__kinoLoginResult || null") { raw ->
                if (completed) return@evaluateJavascript
                val result = runCatching {
                    (JSONTokener(raw).nextValue() as? String)?.let(::JSONObject)
                }.getOrNull()
                if (result == null) {
                    handler.postDelayed({ poll() }, 200)
                } else {
                    CookieManager.getInstance().flush()
                    val status = result.optInt("status")
                    val response = MirrorLoginProtocol.decode(status, result.optString("body"))
                    val authenticated = gateway.hasSession(origin)
                    finish(
                        if (authenticated && (response.success || status in 300..399 || status == 0))
                            MirrorLoginResult(true, "Сеанс сохранён на устройстве")
                        else if (response.success && !authenticated)
                            MirrorLoginResult(false, "Сайт не предоставил сохраняемый сеанс.")
                        else response.copy(retryInBrowser = false)
                    )
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val address = request.url.toString()
                val allowed = MirrorAccountGateway.origin(address) == origin
                if (!allowed) finish(MirrorLoginResult(false,
                    "Сайт перенаправляет на другой домен. Выберите адрес отдельно."))
                return !allowed
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(MirrorLoginResult(false,
                    "Зеркало недоступно во встроенном браузере."))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (completed || launched || MirrorAccountGateway.origin(url) != origin) return
                launched = true
                handler.postDelayed({
                    if (completed) return@postDelayed
                    view.evaluateJavascript(script) {
                        handler.postDelayed({ poll() }, 150)
                    }
                }, 800)
            }
        }
        handler.postDelayed({
            if (!completed) finish(MirrorLoginResult(false,
                "Зеркало не ответило. Проверьте его доступность."))
        }, 22000)
        web.loadUrl("$origin/")
        onDispose {
            completed = true
            handler.removeCallbacksAndMessages(null)
            web.stopLoading()
            web.destroy()
        }
    }
    AndroidView(factory = { web }, modifier = Modifier.size(2.dp).alpha(0.01f))
}
