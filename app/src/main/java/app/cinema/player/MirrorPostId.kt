package app.cinema.player

import java.net.URI

/** Extract a film's actual post ID from the source's conventional film URL. */
internal object MirrorPostId {
    private val pattern = Regex("/(\\d{1,12})-[^/]+\\.html$", RegexOption.IGNORE_CASE)
    fun isSameFilm(expected: String, actual: String): Boolean {
        if (!SourceRules.sameOrigin(expected, actual)) return false
        val expectedId = fromUrl(expected)
        val actualId = fromUrl(actual)
        return if (expectedId != null && actualId != null) expectedId == actualId
        else expected.substringBefore('?').trimEnd('/') == actual.substringBefore('?').trimEnd('/')
    }

    fun canOpen(expected: String, actual: String): Boolean {
        if (isSameFilm(expected, actual)) return true
        val expectedId = fromUrl(expected) ?: return false
        if (expectedId != fromUrl(actual)) return false
        val a = URI(expected)
        val b = URI(actual)
        if (a.port !in listOf(-1, 443) || b.port !in listOf(-1, 443)) return false
        return a.host.lowercase().removePrefix("www.") == b.host.lowercase().removePrefix("www.")
    }

    fun fromUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
            return@runCatching null
        pattern.find(uri.path.orEmpty().trimEnd('/'))?.groupValues?.get(1)
    }.getOrNull()
}
