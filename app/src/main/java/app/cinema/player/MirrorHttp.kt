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
            val scoped = request.newBuilder().removeHeader("Cookie")
            if (referer != null && SourceRules.sameOrigin(referer, address)) {
                CookieManager.getInstance().getCookie(address)?.takeIf(String::isNotBlank)
                    ?.let { scoped.header("Cookie", it) }
            }
            chain.proceed(scoped.build())
        }
        .build()
}
