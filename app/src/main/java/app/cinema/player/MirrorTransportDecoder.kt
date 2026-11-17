package app.cinema.player

import java.util.Base64

/**
 * Decodes the mirror's legacy base64/placeholder URL transport.
 * This does not decrypt DRM or alter subscriber access; rejected premium links
 * remain excluded by MirrorMediaParser.
 */
internal object MirrorTransportDecoder {
    private val knownSalts = listOf(
        "IyMjI14hISMjIUBA", "QEBAQEAhIyMhXl5e",
        "JCQhIUAkJEBeIUAjJCRA", "JCQjISFAIyFAIyM=", "Xl5eIUAjIyEhIyM="
    )
    private val dynamicTokens: List<String> by lazy {
        val alphabet = charArrayOf('@','#','!','^','$')
        buildList {
            for (a in alphabet) for (b in alphabet) {
                add(Base64.getEncoder().encodeToString("$a$b".toByteArray()))
                for (c in alphabet) {
                    add(Base64.getEncoder().encodeToString("$a$b$c".toByteArray()))
                }
            }
        }.distinct().sortedByDescending(String::length)
    }

    fun decode(value: String): String {
        val data = value.trim()
        if (!data.startsWith("#h")) return data
        if (data.length > 200_000) return ""
        var encoded = data.removePrefix("#h")
        var count = 0
        while ("//_//" in encoded) {
            if (++count > 64) return ""
            val marker = encoded.indexOf("//_//")
            val rest = encoded.substring(marker + 5)
            val known = knownSalts.firstOrNull(rest::startsWith)
            if (known != null) {
                encoded = encoded.substring(0, marker) + rest.drop(known.length)
                continue
            }
            // Some mirrors insert short base64-encoded punctuation groups.
            // Remove only a recognized group immediately after a marker.
            val group = dynamicTokens.firstOrNull(rest::startsWith) ?: return ""
            encoded = encoded.substring(0, marker) + rest.drop(group.length)
        }
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            if ((decoded.startsWith("[") || decoded.startsWith("https://")) &&
                decoded.length <= 150_000) decoded else ""
        }.getOrDefault("")
    }
}
