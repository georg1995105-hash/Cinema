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
}
