package app.cinema.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.io.Reader
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP adapter for the chosen mirror. Reuses WebView cookies through MirrorHttp.
 * Never forwards a user session or its AJAX requests to another site.
 */
internal object MirrorNetworkSource {
    data class FilmResult(val finalUrl: String,
        val metadata: MirrorHtmlMetadata.Result? = null, val reason: String = "")
    private val http: OkHttpClient = MirrorHttp.client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(11, TimeUnit.SECONDS).build()

    private suspend fun execute(
        request: Request,
        client: OkHttpClient = http
    ): Response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, issue: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(issue)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            })
        }

    private suspend fun solveAnubis(
        pageUrl: String,
        html: String,
        userAgent: String,
        client: OkHttpClient = http
    ): Boolean {
        val challenge = MirrorAnubis.parse(html) ?: return false
        val solution = runInterruptible(Dispatchers.Default) {
            MirrorAnubis.solve(challenge)
        } ?: return false
        val passUrl = MirrorAnubis.passChallengeUrl(pageUrl, challenge, solution)
            ?: return false
        val builder = Request.Builder().url(passUrl).get()
            .header("Referer", pageUrl)
            .header("Accept", "text/html,application/xhtml+xml")
        MirrorSitePolicy.requestHeaders(pageUrl, userAgent).forEach { (name, value) ->
            builder.header(name, value)
        }
        execute(builder.build(), client).use { response ->
            response.body?.charStream()?.let { bounded(it, 400_000) }
            if (response.code !in 200..399) return false
        }
        return MirrorHttp.hasAnubisCookie(pageUrl)
    }

    private fun bounded(reader: Reader, max: Int): String? {
        val out = StringBuilder()
        val block = CharArray(8192)
        reader.use { input ->
            while (true) {
                val amount = input.read(block)
                if (amount == -1) break
                if (out.length + amount > max) return null
                out.append(block,0,amount)
            }
        }
        return out.toString()
    }

    suspend fun film(
        url: String,
        userAgent: String,
        mirrors: List<String> = emptyList(),
        client: OkHttpClient = http
    ): FilmResult = withContext(Dispatchers.IO) {
        if (!SourceRules.isValidHttpsUrl(url) || MirrorPostId.fromUrl(url) == null)
            return@withContext FilmResult(url, reason = "Неверный адрес фильма.")

        var current = url
        var redirects = 0
        var challengeAttempts = 0
        var staleCookieRetries = 0
        try {
            while (redirects <= 4 && challengeAttempts <= 2) {
                val referer = MirrorAccountGateway.origin(current).orEmpty() + "/"
                val builder = Request.Builder().url(current).get()
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml")
                MirrorSitePolicy.requestHeaders(current, userAgent).forEach { (name, value) ->
                    builder.header(name, value)
                }

                var retry = false
                var challengeHtml: String? = null
                execute(builder.build(), client).use { response ->
                    if (response.code == 403 && staleCookieRetries == 0 &&
                        MirrorHttp.clearStaleAnubisCookies(current)) {
                        staleCookieRetries++
                        retry = true
                        return@use
                    }

                    if (response.code in 300..399) {
                        val next = MirrorRedirect.resolveFilmUrl(
                            originalFilmUrl = url,
                            fromUrl = current,
                            location = response.header("Location"),
                            savedMirrors = mirrors
                        ) ?: return@withContext FilmResult(
                            current,
                            reason = "Редирект не ведёт на допустимое зеркало этого фильма."
                        )
                        if (next == current || redirects >= 4)
                            return@withContext FilmResult(
                                current,
                                reason = "Зеркало не завершило перенаправление."
                            )
                        current = next
                        redirects++
                        retry = true
                        return@use
                    }

                    if (!response.isSuccessful) {
                        val explanation = if (response.code in setOf(403, 429, 503))
                            "Зеркало ограничило доступ (HTTP ${response.code})."
                        else "Зеркало ответило HTTP ${response.code}."
                        return@withContext FilmResult(current, reason = explanation)
                    }

                    val body = response.body ?: return@withContext FilmResult(
                        current, reason = "Пустой ответ зеркала."
                    )
                    val html = bounded(body.charStream(), 1_800_000)
                        ?: return@withContext FilmResult(
                            current, reason = "Ответ зеркала слишком большой."
                        )

                    if (MirrorAnubis.parse(html) != null) {
                        challengeHtml = html
                        return@use
                    }

                    val parsed = MirrorHtmlMetadata.parse(current, html)
                    return@withContext FilmResult(current, parsed, parsed.failure)
                }

                if (retry) continue
                val protectedHtml = challengeHtml
                if (protectedHtml != null) {
                    if (challengeAttempts >= 2)
                        return@withContext FilmResult(
                            current,
                            reason = "Зеркало повторно запросило проверку Anubis."
                        )
                    val solved = solveAnubis(current, protectedHtml, userAgent, client)
                    if (!solved)
                        return@withContext FilmResult(
                            current,
                            reason = "Не удалось пройти проверку Anubis у зеркала."
                        )
                    challengeAttempts++
                    continue
                }
            }
            FilmResult(current, reason = "Зеркало не завершило сетевую проверку.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.net.SocketTimeoutException) {
            FilmResult(current, reason = "Зеркало не ответило вовремя.")
        } catch (_: Exception) {
            FilmResult(current, reason = "Не удалось получить страницу фильма по HTTPS.")
        }
    }

    /**
     * Resolve one logical Rezka film across the user's saved mirrors.
     * Mirrors are transport candidates, not separate playback sources in the UI.
     * Prefer a candidate that exposes real player metadata, then one with film details.
     */
    suspend fun resolveFilm(
        url: String,
        mirrors: List<String>,
        userAgent: String,
        requiredVoice: String = "",
        client: OkHttpClient = http
    ): FilmResult = supervisorScope {
            if (!SourceRules.isValidHttpsUrl(url) || MirrorPostId.fromUrl(url) == null)
                return@supervisorScope FilmResult(url, reason = "Неверный адрес фильма.")

            // The selected site's film page is authoritative. A faster response
            // from an unrelated built-in mirror must never silently replace it.
            val preferred = film(url, userAgent, mirrors, client)
            val preferredPlayer = preferred.metadata?.player
            val preferredCompatible = preferredPlayer != null &&
                (requiredVoice.isBlank() ||
                    MirrorVoiceSelection.supports(preferredPlayer, requiredVoice))
            if (preferredCompatible) return@supervisorScope preferred

            val candidates = (mirrors + MirrorSitePolicy.builtInOrigins).asSequence()
                .filter(SourceRules::isValidHttpsUrl)
                .mapNotNull { MirrorFilmUrl.changeOrigin(url, it) }
                .filter { it != url }
                .distinct()
                .take(9)
                .toList()

            val completed = Channel<FilmResult>(Channel.UNLIMITED)
            val jobs = candidates.map { candidate ->
                launch(Dispatchers.IO) {
                    completed.send(film(candidate, userAgent, mirrors, client))
                }
            }
            var bestInfo: FilmResult? = preferred.takeIf { it.metadata?.info != null }
            var bestFailure: FilmResult? = preferred.takeIf { it.reason.isNotBlank() }
            var incompatiblePlayerSeen = preferredPlayer != null && !preferredCompatible
            try {
                repeat(candidates.size) {
                    val result = completed.receive()
                    val player = result.metadata?.player
                    if (player != null) {
                        val compatible = requiredVoice.isBlank() ||
                            MirrorVoiceSelection.supports(player, requiredVoice)
                        if (compatible) {
                            jobs.forEach { it.cancel() }
                            return@supervisorScope result
                        }
                        incompatiblePlayerSeen = true
                    }
                    if (bestInfo == null && result.metadata?.info != null) bestInfo = result
                    if (bestFailure == null && result.reason.isNotBlank()) bestFailure = result
                }
                if (requiredVoice.isNotBlank() && incompatiblePlayerSeen) {
                    FilmResult(url, reason = "Выбранная озвучка недоступна на проверенных зеркалах.")
                } else {
                    bestInfo ?: bestFailure
                        ?: FilmResult(url, reason = "Зеркала не предоставили страницу фильма.")
                }
            } finally {
                jobs.forEach { it.cancel() }
                completed.close()
            }
        }

    /** Normal site AJAX, without fabricated Premium privileges or guessed translator IDs. */
    suspend fun playback(pageUrl:String, fields:Map<String,String>, userAgent:String):String =
        withContext(Dispatchers.IO) {
            if (MirrorPostId.fromUrl(pageUrl)==null ||
                fields["id"]!=MirrorPostId.fromUrl(pageUrl) ||
                fields["translator_id"].isNullOrBlank())
                return@withContext JSONObject().put("__kinoFailure","invalid").toString()
            val origin=MirrorAccountGateway.origin(pageUrl)
                ?:return@withContext JSONObject().put("__kinoFailure","invalid").toString()
            val form=FormBody.Builder().apply {
                fields.forEach { (k,v) -> add(k,v) }
            }.build()
            val builder = Request.Builder().url(origin+"/ajax/get_cdn_series/").post(form)
                .header("Referer", pageUrl)
                .header("Origin", origin)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/plain, */*")
            MirrorSitePolicy.requestHeaders(pageUrl, userAgent).forEach { (name, value) ->
                builder.header(name, value)
            }
            val request = builder.build()
            try {
                execute(request).use { response ->
                    if (response.code in 300..399)
                        return@withContext JSONObject().put("__kinoFailure","redirect").toString()
                    if (response.code!=200)
                        return@withContext JSONObject().put("__kinoStatus",response.code).toString()
                    val body=response.body?.let { bounded(it.charStream(),600_000) }
                        ?:return@withContext JSONObject().put("__kinoFailure","invalid").toString()
                    val data=runCatching { JSONObject(body) }.getOrElse {
                        JSONObject().put("__kinoFailure",
                            if(Regex("captcha|anubis|verify you are human|checking your browser",
                                RegexOption.IGNORE_CASE).containsMatchIn(body.take(1200)))
                                "blocked" else "invalid")
                    }
                    data.put("__kinoStatus",response.code)
                    val episodes=data.optString("episodes")
                    if(episodes.isNotBlank()){
                        val doc=Jsoup.parse(episodes+data.optString("seasons"))
                        val values=JSONArray()
                        for(node in doc.select(".b-simple_episode__item[data-episode_id], .b-simple_episode__item[data-episode-id]")) {
                            val id=node.attr("data-episode_id").ifBlank { node.attr("data-episode-id") }
                            val season=node.attr("data-season_id").ifBlank {
                                node.closest("[id^=simple-episodes-list-]")?.id()
                                    ?.removePrefix("simple-episodes-list-").orEmpty()
                            }
                            if(!id.matches(Regex("[0-9]{1,4}")) ||
                                !season.matches(Regex("[0-9]{1,4}"))) continue
                            values.put(JSONObject().put("season",season).put("episode",id)
                                .put("label",node.text()))
                        }
                        data.put("__kinoEpisodes",values)
                    }
                    data.toString()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_:java.net.SocketTimeoutException) {
                JSONObject().put("__kinoFailure","timeout").toString()
            } catch (_:Exception) {
                JSONObject().put("__kinoFailure","network").toString()
            }
        }
}
