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

    fun fromUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)
            return@runCatching null
        pattern.find(uri.path.orEmpty())?.groupValues?.get(1)
    }.getOrNull()
}
