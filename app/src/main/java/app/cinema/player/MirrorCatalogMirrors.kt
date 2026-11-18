package app.cinema.player

import java.net.URI

/**
 * Multiple domains of the SAME Rezka catalogue are transport mirrors,
 * not different video providers. Never regard HTTPS 200 alone as success:
 * the caller must parse actual film cards from the returned document.
 *
 * Preserve the source path and encoded search query. Never reuse cookies
 * or authorization from one origin on another.
 */
internal object MirrorCatalogMirrors {
    private val provenCatalogueHosts = listOf(
        "https://rezka-ua.pub/",
        "https://hdrezka.sh/"
    )

    fun candidates(pageUrl: String, alternatives: List<String> = provenCatalogueHosts): List<String> {
        if (!SourceRules.isValidHttpsUrl(pageUrl)) return emptyList()
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val tail = buildString {
            append(page.rawPath.orEmpty().ifBlank { "/" })
            page.rawQuery?.let { append('?').append(it) }
        }
        val origins = if (MirrorSitePolicy.isRecognizedMirror(pageUrl))
            listOf(pageUrl) + alternatives else listOf(pageUrl)
        val used = mutableSetOf<String>()
        return origins.mapNotNull { address ->
            if (!SourceRules.isValidHttpsUrl(address)) return@mapNotNull null
            val origin = runCatching { URI(address) }.getOrNull() ?: return@mapNotNull null
            val key = "${origin.host.orEmpty().lowercase()}:${if (origin.port == -1) 443 else origin.port}"
            if (!used.add(key)) return@mapNotNull null
            "https://${origin.rawAuthority}$tail"
                .takeIf(SourceRules::isValidHttpsUrl)
        }
    }

    fun origin(url: String): String? {
        if (!SourceRules.isValidHttpsUrl(url)) return null
        return runCatching {
            val u = URI(url)
            "https://${u.rawAuthority}/"
        }.getOrNull()
    }
}
