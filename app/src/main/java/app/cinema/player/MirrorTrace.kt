package app.cinema.player

import java.net.URI
import java.util.ArrayDeque

/**
 * Minimal on-device diagnostics for real source failures.
 *
 * Never stores paths, URLs with query strings, request or response bodies,
 * cookies, Authorization headers, media payloads or account identifiers.
 * The user decides whether to copy this local report.
 */
internal object MirrorTrace {
    private const val capacity = 60
    private val lock = Any()
    private val events = ArrayDeque<String>()

    private fun host(url: String?): String = runCatching {
        if (url == null) return@runCatching "unknown"
        URI(url).host?.lowercase()?.takeIf {
            it.length <= 180 && it.matches(Regex("[a-z0-9.:-]+"))
        } ?: "unknown"
    }.getOrDefault("unknown")

    fun clear() = synchronized(lock) { events.clear() }

    /**
     * Only fixed categories are accepted. No arbitrary error messages:
     * those could contain URLs, tokens, usernames, or a session.
     */
    fun add(stage: String, address: String?, status: Int? = null, result: String = "response") {
        val safeStage = stage.takeIf { it in setOf(
            "html", "ajax", "media", "person", "challenge", "webview", "unknown"
        ) } ?: "unknown"
        val safeResult = result.takeIf { it in setOf(
            "response", "timeout", "dns", "tls", "io", "blocked",
            "not_ready", "empty", "invalid", "no_stream"
        ) } ?: "io"
        val code = status?.takeIf { it in 100..599 }?.toString() ?: "-"
        val entry = "$safeStage | ${host(address)} | HTTP $code | $safeResult"
        synchronized(lock) {
            if (events.size >= capacity) events.removeFirst()
            events.addLast(entry)
        }
    }

    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("Cinema: диагностика сетевого пути")
            appendLine("Содержит только хосты, этапы и коды ответа (без токенов и cookies).")
            if (events.isEmpty()) appendLine("Сетевые события не зафиксированы.")
            events.forEachIndexed { i, entry -> appendLine("${i+1}. $entry") }
        }.trimEnd()
    }

    fun stage(address: String): String {
        val path = runCatching { URI(address).path.orEmpty() }.getOrDefault("")
        return when {
            path.contains("person_info", true) -> "person"
            path.contains("/ajax/", true) -> "ajax"
            path.contains("pass-challenge", true) -> "challenge"
            path.endsWith(".m3u8", true) || path.endsWith(".mpd", true) ||
                path.endsWith(".ts", true) || path.endsWith(".m4s", true) ||
                path.endsWith(".mp4", true) -> "media"
            else -> "html"
        }
    }

    fun networkFailure(error: java.io.IOException): String = when (error) {
        is java.net.SocketTimeoutException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is javax.net.ssl.SSLException -> "tls"
        else -> "io"
    }
}
