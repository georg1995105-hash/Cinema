package app.cinema.player

/**
 * The reference guest CookieJar sends the app-profile cookie and the active
 * Anubis challenge cookie, rather than forwarding an existing logged-in
 * account's password / user-id cookies to the public stream endpoint.
 *
 * Keep the global Android CookieManager untouched so normal account login can
 * continue to work in the UI. This is request-level guest isolation only.
 */
internal object MirrorGuestCookies {
    /**
     * A custom mirror configured by the user can have an arbitrary hostname.
     * Film and its same-origin AJAX still require the normal guest cookie jar.
     * Never apply this rule to an unrelated site or a cross-origin redirect.
     */
    fun isGuestFilmRequest(address: String, referer: String?): Boolean {
        if (referer == null || !SourceRules.sameOrigin(referer, address)) return false
        return MirrorSitePolicy.isRecognizedMirror(address) ||
            MirrorPostId.fromUrl(address) != null ||
            MirrorPostId.fromUrl(referer) != null
    }

    /** A real account session requires both source-issued DLE cookies on this mirror. */
    fun hasAccountSession(raw: List<String>): Boolean {
        val values = linkedMapOf<String, String>()
        raw.forEach { header ->
            header.split(';').forEach { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = part.substring(0, index).trim()
                    if (name == "dle_user_id" || name == "dle_password") {
                        values[name] = part.substring(index + 1).trim()
                    }
                }
            }
        }
        return !values["dle_user_id"].isNullOrBlank() &&
            !values["dle_password"].isNullOrBlank()
    }

    /** A single cookie decision for the actual same-origin network request. */
    fun cookieHeaderFor(address: String, referer: String?, raw: List<String>): String {
        if (referer == null || !SourceRules.sameOrigin(referer, address)) return ""
        return if (isGuestFilmRequest(address, referer) && !hasAccountSession(raw))
            fromStoredCookies(raw)
        else MirrorSitePolicy.mergeCookieHeaders(raw)
    }

    fun fromStoredCookies(raw: List<String>): String {
        val challengeCookies = linkedMapOf<String, String>()
        raw.forEach { header ->
            header.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.startsWith("techaro.lol-anubis-auth", ignoreCase = true) &&
                    value.isNotBlank() && !name.contains(' ') && !name.contains(';')
                ) {
                    challengeCookies[name] = value
                }
            }
        }
        return (listOf("hdmbbs=1") +
            challengeCookies.map { (key, value) -> "$key=$value" }).joinToString("; ")
    }
}
