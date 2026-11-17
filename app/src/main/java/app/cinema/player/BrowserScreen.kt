package app.cinema.player

import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.net.URI

/**
 * Temporary guest browser for the mirror selected locally by the user.
 * Does not bypass the site's access requirements or provide a native catalogue.
 */
@Composable
internal fun BrowserScreen(url: String, onBack: () -> Unit, accountMode: Boolean = false) {
    if (!SourceRules.isValidHttpsUrl(url)) {
        Column {
            ScreenHeading("Просмотр сайта", "Выберите зеркало в настройках", onBack)
            Text("Адрес недоступен. Добавьте защищённый адрес сайта.")
        }
        return
    }

    val ctx = LocalContext.current
    val originHost = remember(url) { URI(url).host.lowercase() }
    var loading by remember(url) { mutableStateOf(true) }
    var currentTitle by remember(url) { mutableStateOf("Зеркало сайта") }
    var message by remember(url) { mutableStateOf("") }

    val webView = remember(ctx, url) {
        WebView(ctx).apply {
            setBackgroundColor(AndroidColor.rgb(19, 24, 28))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val scheme = request.url.scheme ?: return true
                    val host = request.url.host?.lowercase() ?: return true
                    return scheme != "https" || !(host == originHost || host.endsWith("." + originHost))
                }

                override fun onPageStarted(view: WebView, target: String, favicon: android.graphics.Bitmap?) {
                    loading = true
                    message = ""
                }

                override fun onPageFinished(view: WebView, target: String) {
                    loading = false
                    currentTitle = view.title?.take(70)?.ifBlank { "Зеркало сайта" } ?: "Зеркало сайта"
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        loading = false
                        message = "Не удалось загрузить страницу. Проверьте доступность зеркала."
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Закрыть просмотр")
            }
            Column(Modifier.weight(1f)) {
                Text(if (accountMode) "Аккаунт зеркала" else "Просмотр сайта",
                    style = MaterialTheme.typography.titleMedium)
                Text(currentTitle, style = MaterialTheme.typography.bodySmall, color = appMuted, maxLines = 1)
            }
            IconButton(onClick = { webView.reload() }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить страницу")
            }
        }
        if (accountMode) Text(
            "Войдите в аккаунт на самом сайте. Пароли не сохраняются приложением. " +
                "Авторизация и синхронизация истории зависят от возможностей зеркала.",
            color = appMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
