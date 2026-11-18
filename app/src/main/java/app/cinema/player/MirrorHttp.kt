package app.cinema.player

import android.webkit.CookieManager
import okhttp3.OkHttpClient

internal object MirrorHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val address = request.url.toString()
            val referer = request.header("Referer")
            val manager = CookieManager.getInstance()
            val scoped = request.newBuilder().removeHeader("Cookie")
            if (referer != null && SourceRules.sameOrigin(referer, address)) {
                val cookies = MirrorSitePolicy.cookieScopes(address)
                    .mapNotNull { manager.getCookie(it)?.takeIf(String::isNotBlank) }
                MirrorSitePolicy.mergeCookieHeaders(cookies).takeIf(String::isNotBlank)
                    ?.let { scoped.header("Cookie", it) }
            }
            val response = chain.proceed(scoped.build())
            val setCookies = response.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                MirrorSitePolicy.cookieScopes(address).forEach { scope ->
                    setCookies.forEach { manager.setCookie(scope, it) }
                }
                manager.flush()
            }
            response
        }
        .build()
}
