package app.cinema.player

import org.json.JSONArray

internal object MirrorQuickSearch {
    fun javascript(query: String): String {
        val variants = JSONArray(listOf(query.trim().take(120)) + SearchRelevance.alternateQuery(query))
        return """
        (function() {
          window.__kinoQuickSearchResult = null;
          ${MirrorPoster.javascript}
          var queries = $variants;
          async function search(q, signal) {
            var resp = await fetch('/engine/ajax/search.php?q='+encodeURIComponent(q), {
              credentials:'same-origin', signal:signal,
              headers:{'Accept':'text/html','X-Requested-With':'XMLHttpRequest'}
            });
            if(!resp.ok || new URL(resp.url).origin !== location.origin) throw Error('search failed');
            var html = await resp.text();
            var doc = new DOMParser().parseFromString(html, 'text/html');
            return Array.from(doc.querySelectorAll('.b-search__live_section li')).slice(0, 48)
              .flatMap(function(li) {
                var a = li.querySelector('a[href]');
                if (!a) return [];
                var url = new URL(a.getAttribute('href'), location.href);
                if (url.origin !== location.origin || !/\/\d{1,12}-[^/]+\.html$/i.test(url.pathname)) return [];
                var title = (li.querySelector('.enty')?.textContent || a.getAttribute('title') ||
                             a.firstChild?.textContent || '').trim();
                var details = (li.querySelector('.year, .details, .b-search__live_item_info, small')?.textContent || '').trim();
                var posters = kinoPosters(li, location.href);
                return title ? [{url:url.href, title:title, posters:posters, details:details}] : [];
              });
          }
          var controller = new AbortController();
          var timeout = setTimeout(function(){controller.abort()}, 3500);
          (async function() {
            var cards = [];
            function normalize(s) {
              return s.normalize('NFKC').toLowerCase().replace(/ё/g,'е').replace(/[^\p{L}\p{N}]+/gu,' ').trim();
            }
            for (var q of queries) {
              cards = cards.concat(await search(q, controller.signal));
              var expected = normalize(q);
              if (cards.some(function(card) {
                return (' ' + normalize(card.title) + ' ').includes(' ' + expected + ' ');
              })) break;
            }
            window.__kinoQuickSearchResult = JSON.stringify({cards:cards});
          })().catch(function() {
            window.__kinoQuickSearchResult = JSON.stringify({cards:[], failed:true});
          }).finally(function(){clearTimeout(timeout)});
        })();
        """.trimIndent()
    }

    fun postersJavascript(movies: List<CatalogMovie>): String {
        val urls = JSONArray(movies.filter { it.poster.isBlank() }.take(4).map { it.url })
        return """
        (function() {
          window.__kinoSearchPosters = null;
          ${MirrorPoster.javascript}
          var urls = $urls;
          var controller = new AbortController();
          var timeout = setTimeout(function(){controller.abort()}, 3000);
          Promise.all(urls.map(async function(address) {
            try {
              var url = new URL(address);
              if (url.origin !== location.origin) return null;
              var response = await fetch(address, {credentials:'same-origin', signal:controller.signal});
              if (!response.ok) return null;
              var finalUrl = new URL(response.url);
              var id = function(u) { return u.pathname.match(/\/(\d{1,12})-[^/]+\.html$/i)?.[1]; };
              if (finalUrl.origin !== location.origin || !id(url) || id(url) !== id(finalUrl)) return null;
              var body = await response.text();
              if (body.length > 1600000) return null;
              var doc = new DOMParser().parseFromString(body, 'text/html');
              return {url:address, posters:kinoFilmPosters(doc, response.url)};
            } catch(e) {return null;}
          })).then(function(found) {
            window.__kinoSearchPosters = JSON.stringify(found.filter(Boolean));
          }).finally(function(){clearTimeout(timeout)});
        })();
        """.trimIndent()
    }

    fun mergePosters(raw: String, movies: List<CatalogMovie>): List<CatalogMovie> = runCatching {
        val response = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching movies
        val entries = JSONArray(response)
        val posters = (0 until entries.length()).mapNotNull(entries::optJSONObject)
            .associate { it.optString("url") to MirrorCatalog.readPosters(it) }
        movies.map { movie ->
            val pictures = posters[movie.url].orEmpty()
            if (movie.poster.isNotBlank() || pictures.isEmpty()) movie
            else movie.copy(poster = pictures.first(), posterAlternatives = pictures.drop(1))
        }
    }.getOrDefault(movies)
}
