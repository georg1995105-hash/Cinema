package app.cinema.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
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

    private fun safeRedirect(from: String, destination: String?, original: String): String? {
        if (destination.isNullOrBlank()) return null
        return runCatching { URI(from).resolve(destination).toString() }
            .getOrNull()?.takeIf {
                SourceRules.sameOrigin(original,it) && MirrorPostId.isSameFilm(original,it)
            }
    }

    suspend fun film(url: String, userAgent: String): FilmResult =
        withContext(Dispatchers.IO) {
            if (!SourceRules.isValidHttpsUrl(url) || MirrorPostId.fromUrl(url) == null)
                return@withContext FilmResult(url,reason="Неверный адрес фильма.")
            var current=url
            try {
                repeat(3) { attempt ->
                    val referer=MirrorAccountGateway.origin(current).orEmpty()+"/"
                    val request=Request.Builder().url(current).get()
                        .header("Referer",referer).header("User-Agent",userAgent)
                        .header("Accept","text/html,application/xhtml+xml").build()
                    execute(request).use { response ->
                        if (response.code in 300..399) {
                            val next=safeRedirect(current,response.header("Location"),url)
                                ?:return@withContext FilmResult(current,
                                    reason="Зеркало перенаправляет на другую страницу. Выберите другое зеркало.")
                            if (next==current || attempt==2)
                                return@withContext FilmResult(current,
                                    reason="Зеркало не завершило перенаправление.")
                            current=next
                            return@repeat
                        }
                        if(!response.isSuccessful) {
                            val explanation=if(response.code in setOf(403,429,503))
                                "Зеркало ограничило доступ (HTTP "+response.code+")."
                            else "Зеркало ответило HTTP "+response.code+"."
                            return@withContext FilmResult(current,reason=explanation)
                        }
                        val body=response.body ?:return@withContext FilmResult(current,
                            reason="Пустой ответ зеркала.")
                        val html=bounded(body.charStream(),1_800_000)
                            ?:return@withContext FilmResult(current,
                                reason="Ответ зеркала слишком большой.")
                        val parsed=MirrorHtmlMetadata.parse(current,html)
                        return@withContext FilmResult(current,parsed,parsed.failure)
                    }
                }
                FilmResult(current,reason="Слишком много перенаправлений.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_:java.net.SocketTimeoutException) {
                FilmResult(current,reason="Зеркало не ответило вовремя.")
            } catch (_:Exception) {
                FilmResult(current,reason="Не удалось получить страницу фильма по HTTPS.")
            }
        }

    /**
     * Resolve one logical Rezka film across the user's saved mirrors.
     * Mirrors are transport candidates, not separate playback sources in the UI.
     * Prefer a candidate that exposes real player metadata, then one with film details.
     */
    suspend fun resolveFilm(url: String, mirrors: List<String>, userAgent: String): FilmResult =
        supervisorScope {
            if (!SourceRules.isValidHttpsUrl(url) || MirrorPostId.fromUrl(url) == null)
                return@supervisorScope FilmResult(url, reason = "Неверный адрес фильма.")

            val candidates = buildList {
                add(url)
                mirrors.asSequence()
                    .filter(SourceRules::isValidHttpsUrl)
                    .mapNotNull { MirrorFilmUrl.changeOrigin(url, it) }
                    .forEach(::add)
            }.distinct().take(8)

            val completed = Channel<FilmResult>(Channel.UNLIMITED)
            val jobs = candidates.map { candidate ->
                launch(Dispatchers.IO) {
                    completed.send(film(candidate, userAgent))
                }
            }
            var bestInfo: FilmResult? = null
            var bestFailure: FilmResult? = null
            try {
                repeat(candidates.size) {
                    val result = completed.receive()
                    if (result.metadata?.player != null) {
                        jobs.forEach { it.cancel() }
                        return@supervisorScope result
                    }
                    if (bestInfo == null && result.metadata?.info != null) bestInfo = result
                    if (bestFailure == null && result.reason.isNotBlank()) bestFailure = result
                }
                bestInfo ?: bestFailure
                    ?: FilmResult(url, reason = "Зеркала не предоставили страницу фильма.")
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
            val request=Request.Builder().url(origin+"/ajax/get_cdn_series/").post(form)
                .header("Referer",pageUrl).header("Origin",origin)
                .header("User-Agent",userAgent).header("X-Requested-With","XMLHttpRequest")
                .header("Accept","application/json, text/plain, */*").build()
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
