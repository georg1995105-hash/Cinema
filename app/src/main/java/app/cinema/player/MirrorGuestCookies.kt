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
