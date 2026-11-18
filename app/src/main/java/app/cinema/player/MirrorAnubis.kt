package app.cinema.player

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Native implementation of the Anubis flow used by the reference Rezka client.
 *
 * A protected mirror returns an HTML document containing anubis_challenge.
 * We parse it, solve the source-provided PoW, call pass-challenge on the same
 * origin, let MirrorHttp persist the auth cookie, and then retry the film URL.
 */
internal object MirrorAnubis {
    private const val servicePath = "/.within.website/x/cmd/anubis/"
    private const val passPath = ".within.website/x/cmd/anubis/api/pass-challenge"

    data class Challenge(
        val id: String,
        val randomData: String,
        val difficulty: Int,
        val basePrefix: String,
        val algorithm: String
    )

    data class Solution(
        val hash: String,
        val nonce: Long,
        val elapsedMs: Long
    )

    fun isChallengeUrl(pageUrl: String, candidateUrl: String): Boolean {
        if (!SourceRules.isValidHttpsUrl(pageUrl) ||
            !SourceRules.isValidHttpsUrl(candidateUrl) ||
            !SourceRules.sameOrigin(pageUrl, candidateUrl)) return false
        val path = runCatching { URI(candidateUrl).path.orEmpty() }.getOrDefault("")
        return path.contains(servicePath, ignoreCase = true)
    }

    fun isChallengeDocument(raw: String): Boolean {
        if (raw.isBlank()) return false
        val head = raw.take(200_000)
        return listOf(
            "anubis_challenge",
            "techaro.lol-anubis",
            "/.within.website/x/cmd/anubis/",
            "id=\"anubis_challenge\"",
            "id='anubis_challenge'"
        ).any { head.contains(it, ignoreCase = true) }
    }

    fun parse(html: String): Challenge? {
        if (!isChallengeDocument(html)) return null
        return runCatching {
            val doc = Jsoup.parse(html)
            val scriptValue = doc.getElementById("anubis_challenge")?.let { node ->
                node.data().ifBlank { node.html() }.ifBlank { node.text() }
            }?.trim().orEmpty()
            val rawJson = when {
                scriptValue.startsWith("{") -> scriptValue
                else -> extractAssignedObject(html, "anubis_challenge") ?: return null
            }
            val root = JSONObject(rawJson)
            val challenge = root.optJSONObject("challenge") ?: return null
            val rules = root.optJSONObject("rules") ?: JSONObject()
            val id = challenge.optString("id").trim()
            val randomData = challenge.optString("randomData").trim()
            val difficulty = rules.optInt(
                "difficulty",
                challenge.optInt("difficulty", 0)
            )
            val basePrefix = parseBasePrefix(doc, html)
            val algorithm = rules.optString("algorithm", "SHA-256").trim()
                .ifBlank { "SHA-256" }
            if (id.isBlank() || randomData.isBlank() || difficulty !in 1..8) return null
            if (digestName(algorithm) == null) return null
            Challenge(id, randomData, difficulty, basePrefix, algorithm)
        }.getOrNull()
    }

    fun solve(challenge: Challenge): Solution? {
        val digestAlgorithm = digestName(challenge.algorithm) ?: return null
        val digest = MessageDigest.getInstance(digestAlgorithm)
        val prefix = "0".repeat(challenge.difficulty)
        val started = System.currentTimeMillis()
        var nonce = 0L
        while (nonce < Long.MAX_VALUE) {
            if ((nonce and 0x3ffL) == 0L && Thread.currentThread().isInterrupted) return null
            digest.reset()
            val bytes = (challenge.randomData + nonce).toByteArray(StandardCharsets.UTF_8)
            val hash = digest.digest(bytes).toHex()
            if (hash.startsWith(prefix)) {
                return Solution(hash, nonce, (System.currentTimeMillis() - started).coerceAtLeast(0L))
            }
            nonce++
        }
        return null
    }

    fun passChallengeUrl(pageUrl: String, challenge: Challenge, solution: Solution): String? {
        if (!SourceRules.isValidHttpsUrl(pageUrl)) return null
        return runCatching {
            val page = URI(pageUrl)
            val origin = buildString {
                append(page.scheme)
                append("://")
                append(page.host)
                if (page.port != -1) append(":").append(page.port)
            }
            val prefix = challenge.basePrefix.trim().let {
                when {
                    it.isBlank() || it == "/" -> ""
                    else -> "/" + it.trim('/')
                }
            }
            val endpoint = "$origin$prefix/$passPath"
            endpoint +
                "?id=" + enc(challenge.id) +
                "&response=" + enc(solution.hash) +
                "&nonce=" + solution.nonce +
                "&redir=" + enc(pageUrl) +
                "&elapsedTime=" + solution.elapsedMs
        }.getOrNull()
    }

    private fun parseBasePrefix(document: org.jsoup.nodes.Document, html: String): String {
        val node = document.getElementById("anubis_base_prefix")
        val fromNode = node?.let {
            it.data().ifBlank { it.html() }.ifBlank { it.text() }
        }?.trim().orEmpty()
        decodeScalar(fromNode)?.let { return it }
        val match = Regex(
            """anubis_base_prefix\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun decodeScalar(raw: String): String? {
        if (raw.isBlank()) return null
        val value = raw.trim()
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is String -> parsed
                JSONObject.NULL -> ""
                else -> value.trim('"', '\'')
            }
        }.getOrElse { value.trim('"', '\'') }
    }

    private fun extractAssignedObject(html: String, name: String): String? {
        val index = html.indexOf(name, ignoreCase = true)
        if (index < 0) return null
        val start = html.indexOf('{', index)
        if (start < 0) return null
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (quote != '\u0000') {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = '\u0000'
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                continue
            }
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
        }
        return null
    }

    private fun digestName(raw: String): String? = when (
        raw.trim().lowercase(Locale.US)
    ) {
        "fast", "slow", "sha-256", "sha256" -> "SHA-256"
        "sha-1", "sha1" -> "SHA-1"
        "md5" -> "MD5"
        else -> null
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val alphabet = "0123456789abcdef"
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            chars[i * 2] = alphabet[value ushr 4]
            chars[i * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
