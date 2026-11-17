package app.cinema.player

import java.net.URI

/** Address validation only; never tries to bypass access restrictions. */
object SourceRules {
    fun isValidHttpsUrl(input: String): Boolean = try {
        val uri = URI(input.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
    } catch (_: Exception) {
        false
    }

    fun sameOrigin(first: String, second: String): Boolean {
        if (!isValidHttpsUrl(first) || !isValidHttpsUrl(second)) return false
        val a = URI(first.trim())
        val b = URI(second.trim())
        return a.host.equals(b.host, ignoreCase = true) &&
            (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)
    }
}
