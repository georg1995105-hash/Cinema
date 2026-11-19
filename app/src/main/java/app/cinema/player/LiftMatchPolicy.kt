package app.cinema.player

import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** A browser-observed first-party Lift film page. No stream URLs or auth tokens. */
internal data class LiftFilmMatch(val url: String, val title: String, val year: Int?)

internal object LiftMatchPolicy {
    private val yearPattern = Regex("""\b(?:18|19|20)\d{2}\b""")
    private val detailId = Regex("""(?:^|&)id=([0-9]{1,12})(?:&|$)""")
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")
    private val qualityPrefix = Regex("""^(?:(?:4K|UHD|FHD|HD|SD|TS|Все серии)\s+)+""", RegexOption.IGNORE_CASE)

    fun yearFromDetails(details: String): Int? =
        yearPattern.find(details)?.value?.toIntOrNull()

    fun safeDetailsUrl(value: String): Boolean = runCatching {
        if (value.length !in 20..4096) return@runCatching false
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("liftw.ws", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.path == "/details.html" &&
            detailId.containsMatchIn(uri.rawQuery.orEmpty())
    }.getOrDefault(false)

    fun parseBrowserResults(raw: String?): List<LiftFilmMatch> = runCatching {
        val decoded = JSONTokener(raw.orEmpty()).nextValue()
        val rows = when (decoded) {
            is String -> JSONArray(decoded)
            is JSONArray -> decoded
            else -> JSONArray()
        }
        buildList {
            for (i in 0 until minOf(rows.length(), 35)) {
                val obj = rows.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (!safeDetailsUrl(url)) continue
                val uri = URI(url)
                val nameFromUrl = uri.rawQuery.orEmpty().split('&').firstOrNull {
                    it.startsWith("name=")
                }?.substringAfter("name=")?.let {
                    URLDecoder.decode(it, "UTF-8")
                }.orEmpty()
                val title = nameFromUrl.ifBlank {
                    qualityPrefix.replace(obj.optString("title").trim(), "")
                }.trim().take(150)
                if (title.isBlank()) continue
                val year = obj.optString("year").toIntOrNull()
                    ?: uri.rawQuery.orEmpty().split('&').firstOrNull {
                        it.startsWith("year=")
                    }?.substringAfter("year=")?.toIntOrNull()
                add(LiftFilmMatch(url, title, year?.takeIf { it in 1800..2100 }))
            }
        }.distinctBy { filmIdentity(it.url) }
    }.getOrDefault(emptyList())

    private fun filmIdentity(url: String): String =
        runCatching {
            val uri = URI(url)
            detailId.find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
        }.getOrNull().orEmpty().ifBlank { url }

    fun normalize(title: String): String =
        punctuation.replace(title.trim().lowercase(Locale.ROOT).replace('ё', 'е'), " ")
            .trim().replace(Regex("""\s+"""), " ")

    /**
     * Never silently choose a remake with the same title but a different year.
     * Missing year allows an exact-title match only if it is unique.
     */
    fun singleExact(title: String, year: Int?, results: List<LiftFilmMatch>): LiftFilmMatch? {
        val normalized = normalize(title)
        if (normalized.isBlank()) return null
        val exact = results.filter {
            normalize(it.title) == normalized && (year == null || it.year == year)
        }
        return exact.distinctBy { filmIdentity(it.url) }.singleOrNull()
    }

    /** Never interpolate query text directly in source code. */
    fun searchScript(title: String): String {
        val quoted = org.json.JSONObject.quote(title.trim().take(140))
        return """
            (function(){
              const field = document.querySelector('input#q, input[type="search"]');
              if (!field) return false;
              const descriptor = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value');
              descriptor.set.call(field, $quoted);
              field.dispatchEvent(new Event('input', {bubbles:true}));
              field.dispatchEvent(new Event('change', {bubbles:true}));
              field.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter',bubbles:true}));
              return true;
            })();
        """.trimIndent()
    }

    /** Hide only the Lift site's own bottom navigation when embedded under Cinema tabs. */
    val hideDuplicateNavigationScript = """
        (function(){
          const known = new Set(['/index.html','/reco.html','/mine.html','/search.html','/profile.html']);
          const navLinks = Array.from(document.querySelectorAll('a[href]')).filter(a => {
            try {
              const u = new URL(a.href);
              return (u.hostname === 'liftw.ws' || u.hostname === 'www.liftw.ws') &&
                known.has(u.pathname);
            } catch (_) { return false; }
          });
          if (navLinks.length < 4) return false;
          let candidate = navLinks[0].parentElement;
          while (candidate && candidate !== document.body) {
            if (navLinks.filter(a => candidate.contains(a)).length >= 4 &&
                !candidate.querySelector('a[href*="details.html"]')) {
              candidate.style.display = 'none';
              return true;
            }
            candidate = candidate.parentElement;
          }
          return false;
        })();
    """.trimIndent()

    /** Public DOM links only; data is returned to app via evaluateJavascript. */
    val inspectResultsScript = """
        (function(){
          return JSON.stringify(Array.from(
            document.querySelectorAll('a[href*="details.html?id="]')
          ).slice(0, 35).map(a => ({
            url: a.href,
            title: (a.textContent || '').trim().slice(0, 150),
            year: (new URL(a.href).searchParams.get('year') || '')
          })));
        })();
    """.trimIndent()
}
