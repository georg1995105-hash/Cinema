package app.cinema.player

/**
 * Catalog access and interactive browser checks are distinct from plain
 * HTTPS reachability. A JS challenge must be allowed to finish in WebView.
 */
internal object CatalogAccessPolicy {
    fun needsBrowserInspection(httpStatus: Int): Boolean =
        httpStatus in setOf(403, 429, 503)

    // Checks only whether the already opened public page is presenting
    // an interactive browser challenge. Does not solve, skip or spoof one.
    val challengeJavascript = """
        (function(){
          try {
            var title = document.title || '';
            return !!document.querySelector(
              '#anubis_challenge, .cf-turnstile, #challenge-form, #cf-challenge-running, ' +
              '[data-sitekey], script[src*="within.website/x/cmd/anubis"]'
            ) || /checking your browser|just a moment|verify you are human|проверяем.{0,30}не бот|captcha/i.test(title);
          } catch (_) { return false; }
        })();
    """.trimIndent()
}
