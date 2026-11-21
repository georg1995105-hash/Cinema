package app.cinema.player

/**
 * Read public poster metadata from the film page already open in Lift WebView.
 * No private APIs, cookies or playback URLs are read.
 *
 * Lift is an SPA: images can be inserted after onPageFinished, so callers
 * repeat this lightweight inspection while the same details URL is visible.
 */
internal object LiftPosterMetadata {
    val script = """
        (function() {
          const photos = [];
          const seen = new Set();
          const ignored = /(?:favicon|placeholder|spacer|avatar|sprite|logo|blank|no[-_]?poster)/i;
          function add(raw) {
            if (!raw || photos.length >= 8) return;
            try {
              const url = new URL(raw, document.baseURI);
              if (url.protocol !== 'https:' || url.username || url.password ||
                  ignored.test(url.pathname) || seen.has(url.href)) return;
              seen.add(url.href);
              photos.push(url.href);
            } catch (e) {}
          }
          function image(node) {
            if (!node) return;
            for (const key of ['data-src', 'data-original', 'data-lazy-src',
                               'data-image', 'data-poster', 'src', 'content']) {
              add(node.getAttribute(key));
            }
            add(node.currentSrc);
            for (const attr of ['data-srcset', 'srcset']) {
              const set = node.getAttribute(attr) || '';
              for (const entry of set.split(',')) {
                add(entry.trim().split(/\s+/)[0]);
              }
            }
          }
          function background(node) {
            if (!node) return;
            const css = (node.style.backgroundImage || '') + ' ' +
                        (getComputedStyle(node).backgroundImage || '');
            for (const match of css.matchAll(/url\(\s*['"]?([^'")]+)['"]?\s*\)/gi)) {
              add(match[1]);
            }
          }
          const selectors = [
            'meta[property="og:image"]', 'meta[name="og:image"]',
            'meta[property="twitter:image"]', 'meta[name="twitter:image"]',
            'meta[itemprop="image"]', 'link[itemprop="image"]',
            'img[itemprop="image"]', 'img.poster', 'img.cover',
            '.movie-poster img', '.film-poster img', '.detail-poster img',
            '.poster img', '.cover img', '.details img', '.movie-details img',
            'img[data-poster]', 'img[data-src]', 'img[data-original]', 'img[data-lazy-src]'
          ];
          for (const selector of selectors) {
            for (const node of document.querySelectorAll(selector)) image(node);
          }
          for (const selector of ['.poster', '.cover', '.movie-poster',
                                  '.film-poster', '.detail-poster', '[data-poster]']) {
            for (const node of document.querySelectorAll(selector)) background(node);
          }
          // Fallback for first-party film pages that have a plain portrait
          // image rather than a poster class. Ignore tiny icons and avatars.
          if (!photos.length) {
            for (const node of document.querySelectorAll('main img, article img, img')) {
              if (node.closest('nav, header, footer, [class*="avatar"]')) continue;
              const w = node.naturalWidth || node.width;
              const h = node.naturalHeight || node.height;
              if (w >= 80 && h >= 120 && h > w * 1.15) image(node);
              if (photos.length >= 8) break;
            }
          }
          const heading = document.querySelector(
            'h1, [itemprop="name"], .film-title, .movie-title, .detail-title');
          const ogTitle = document.querySelector('meta[property="og:title"]');
          return JSON.stringify({
            title: (heading?.textContent || ogTitle?.content || '')
              .replace(/\s+/g, ' ').trim().slice(0, 130),
            posters: photos
          });
        })();
    """.trimIndent()
}
