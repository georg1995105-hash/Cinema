package app.cinema.player

import java.time.Duration

internal object MirrorPreview {
    private val minutes = Regex("""(?iu)(\d{1,3})\s*(?:мин(?:ут[аы]?)?\.?|minutes?\b|min\b)""")

    fun expectedDuration(value: String): Long? {
        val text = value.trim()
        if (text.startsWith("PT", true)) {
            return runCatching { Duration.parse(text).toMillis() }.getOrNull()?.takeIf { it in 1_000L..28_800_000L }
        }
        return minutes.find(text)?.groupValues?.get(1)?.toLongOrNull()?.times(60_000)?.takeIf {
            it in 1_000L..28_800_000L
        }
    }

    fun isPreview(actualMs: Long, expectedMs: Long?): Boolean =
        expectedMs != null && expectedMs >= 8 * 60_000L &&
            actualMs in 1L..150_000L && actualMs < expectedMs / 5

    val runtimeJavascript = """
        function kinoFilmRuntime() {
          var schema = document.querySelector(
            '.b-post [itemprop~="duration"], .b-post__infotable [itemprop~="duration"], meta[property="video:duration"]'
          );
          var value = schema?.getAttribute('content') || schema?.getAttribute('datetime') || schema?.textContent || '';
          if (/^(?:PT|\d+\s*мин)/i.test(value.trim())) return value.trim();
          for (var row of document.querySelectorAll('.b-post__infotable tr, .b-post__info tr')) {
            var label = (row.cells[0]?.textContent || '').trim();
            if (/^(?:время|длительность|продолжительность|хронометраж)(?:\s|:|$)/i.test(label)) {
              return (row.cells[1]?.textContent || '').trim();
            }
          }
          return '';
        }
    """.trimIndent()
}
