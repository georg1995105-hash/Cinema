package app.cinema.player

import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Synchronises the ordinary, non-subscriber Android site profile before the
 * WebView navigates. OkHttp already sends hdmbbs=1; WebView's subresource/AJAX
 * requests otherwise use a different cookie profile and can return another
 * stream result for the very same visitor.
 *
 * This cookie is not an authentication token or Premium entitlement. Existing
 * site account cookies are left intact and are never sent across origins.
 */
internal object MirrorGuestSession {
    const val profileCookie = "hdmbbs=1; Path=/; Secure; SameSite=Lax"

    fun loadFilm(view: WebView, address: String) {
        val origin = MirrorAccountGateway.origin(address)
        // User-saved HTTPS mirrors need the same guest profile as built-ins.
        // Only do this for a verified film-page shape, never arbitrary sites.
        if (origin == null || MirrorPostId.fromUrl(address) == null) {
            view.loadUrl(address, MirrorSitePolicy.webViewHeaders(address))
            return
        }
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        // The callback guarantees the cookie update is committed before the
        // navigation and its first same-origin AJAX request are started.
        manager.setCookie("$origin/", profileCookie) {
            // WebView.loadUrl must run on the view's main Looper. CookieManager
            // invokes this callback on the same Looper as setCookie(); calling
            // view.post here can stall on a not-yet-attached hidden WebView.
            runCatching {
                view.loadUrl(address, MirrorSitePolicy.webViewHeaders(address))
            }
        }
    }
}
