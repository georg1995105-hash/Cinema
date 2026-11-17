package app.cinema.player

import java.net.URI

internal object MirrorPoster {
    private val placeholder = Regex(
        """placeholder|spacer|favicon|(?:^|[/_.-])(?:logo|blank|1x1|no[-_]?(?:poster|cover|image|photo)|(?:default|empty)[-_]?(?:poster|cover|image)|hdrezka|rezka)(?:[/_.-]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun isPhoto(raw: String): Boolean =
        SourceRules.isValidHttpsUrl(raw) && !placeholder.containsMatchIn(URI(raw.trim()).path.orEmpty().substringAfterLast('/'))

    val javascript = """
        function kinoPosters(node, base) {
          var results = [];
          function add(raw) {
            if (!raw || results.length >= 8) return;
            try {
              var url = new URL(raw, base);
              if (url.protocol === 'https:' && !url.username && !url.password &&
                  !/${placeholder.pattern}/i.test(url.pathname.split('/').pop()) && !results.includes(url.href)) {
                results.push(url.href);
              }
            } catch (e) {}
          }
          if (!node) return results;
          var images = node.matches('img,meta,source') ? [node] :
            [node, ...node.querySelectorAll('img, picture source, meta[itemprop~="image"], link[itemprop~="image"]')];
          for (var img of images) {
            for (var attr of ['data-src', 'data-original', 'data-lazy-src', 'data-image',
                             'data-url', 'data-bg', 'data-background', 'data-cover']) {
              add(img.getAttribute(attr));
            }
            for (var attr of ['data-srcset', 'srcset']) {
              (img.getAttribute(attr) || '').split(',').reverse().forEach(function(entry) {
                add(entry.trim().split(/\s+/)[0]);
              });
            }
            var background = (img.getAttribute('style') || '').match(/url\(\s*(['"]?)(.*?)\1\s*\)/i);
            if (background) add(background[2]);
            if (img.matches('img,meta,source,link')) {
              add(img.currentSrc);
              add(img.getAttribute('src'));
              add(img.getAttribute('content'));
              if (img.matches('link[itemprop~="image"]')) add(img.getAttribute('href'));
            }
          }
          var noscript = node.querySelector('noscript');
          if (noscript) {
            var fallback = (noscript.textContent || '').match(/src=['"]([^'"]+)/i);
            if (fallback) add(fallback[1]);
          }
          return results;
        }
        function kinoFilmPosters(doc, base) {
          var nodes = doc.querySelectorAll('.b-post__poster, .b-post__cover, .b-post__image, ' +
              '.b-post [itemprop="image"], meta[property="og:image"], meta[name="twitter:image"]');
          return [...new Set(Array.from(nodes).flatMap(function(node) {
            return kinoPosters(node, base);
          }))].slice(0, 8);
        }
    """.trimIndent()
}
