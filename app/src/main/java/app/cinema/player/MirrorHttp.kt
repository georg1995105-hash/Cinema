package app.cinema.player

import android.webkit.CookieManager
import okhttp3.OkHttpClient

internal object MirrorHttp {
    fun hasAnubisCookie(url: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(url)) return false
        return MirrorSitePolicy.cookieScopes(url).any { scope ->
            CookieManager.getInstance().getCookie(scope).orEmpty()
                .split(';')
                .any { part ->
                    val name = part.substringBefore('=').trim()
                    val value = part.substringAfter('=', "").trim()
                    name.startsWith("techaro.lol-anubis-auth", ignoreCase = true) &&
                        value.isNotBlank()
                }
        }
    }

    /**
     * The reference client clears a stale Anubis auth cookie after HTTP 403.
     * Keep this scoped to the current mirror; never clear unrelated site cookies.
     */
    fun clearStaleAnubisCookies(url: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(url)) return false
        val manager = CookieManager.getInstance()
        val names = manager.getCookie(url).orEmpty()
            .split(';')
            .map { it.substringBefore('=').trim() }
            .filter { it.startsWith("techaro.lol-anubis-auth", ignoreCase = true) }
            .distinct()
        if (names.isEmpty()) return false
        MirrorSitePolicy.cookieScopes(url).forEach { scope ->
            names.forEach { name ->
                manager.setCookie(
                    scope,
                    "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax"
                )
            }
        }
        manager.flush()
        return true
    }

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
                val header = if (MirrorGuestCookies.isGuestFilmRequest(address, referer)) {
                    // Actual reference guest CookieJar: use the public app profile
                    // and Anubis, not the user's unrelated account credentials.
                    MirrorGuestCookies.fromStoredCookies(cookies)
                } else MirrorSitePolicy.mergeCookieHeaders(cookies)
                header.takeIf(String::isNotBlank)
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
