package app.cinema.player

import org.json.JSONArray

internal object MirrorQuickSearch {
    fun javascript(query: String): String {
        val variants = JSONArray(listOf(query.trim().take(120)) + SearchRelevance.alternateQuery(query))
        return """
        (function() {
          window.__kinoQuickSearchResult = null;
          ${MirrorCatalog.cardsJavascript}
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
                var url;
                try { url = new URL(a.getAttribute('href'), resp.url); } catch(e) { return []; }
                if (url.origin !== location.origin || !/\/\d{1,12}-[^/]+\.html$/i.test(url.pathname)) return [];
                var title = (li.querySelector('.enty')?.textContent || a.getAttribute('title') ||
                             a.firstChild?.textContent || '').trim();
                var details = (li.querySelector('.year, .details, .b-search__live_item_info, small')?.textContent ||
                    Array.from(a.childNodes).filter(function(n){return n.nodeType === 3})
                      .map(function(n){return n.textContent}).join(' ')).trim().replace(/^\((.*)\)$/, '$1');
                var posters = kinoPosters(li, location.href);
                return title ? [{url:url.href, title:title, posters:posters, details:details}] : [];
              });
          }
          async function results(q, signal) {
            var url = new URL('/search/', location.origin);
            url.search = new URLSearchParams({do:'search',subaction:'search',q:q});
            var response = await fetch(url.href, {credentials:'same-origin',signal:signal});
            if (!response.ok || new URL(response.url).origin !== location.origin) return [];
            var html = await response.text();
            return kinoCatalogCards(new DOMParser().parseFromString(html, 'text/html'), response.url, location.origin);
          }
          var controller = new AbortController();
          var timeout = setTimeout(function(){controller.abort()}, 5000);
          (async function() {
            var cards = [];
            function normalize(s) {
              return s.normalize('NFKC').toLowerCase().replace(/ё/g,'е').replace(/[^\p{L}\p{N}]+/gu,' ').trim();
            }
            var matchedQuery = queries[0];
            for (var q of queries) {
              cards = cards.concat(await search(q, controller.signal));
              var expected = normalize(q);
              if (cards.some(function(card) {
                return (' ' + normalize(card.title) + ' ').includes(' ' + expected + ' ');
              })) {
                matchedQuery = q;
                break;
              }
            }
            if (cards.some(function(card) {return card.posters.length === 0})) {
              try {
                var listing = await results(matchedQuery, controller.signal);
                if (listing.length) cards = listing.concat(cards);
              } catch (e) {}
            }
            window.__kinoQuickSearchResult = JSON.stringify({cards:cards});
          })().catch(function() {
            window.__kinoQuickSearchResult = JSON.stringify({cards:[], failed:true});
          }).finally(function(){clearTimeout(timeout)});
        })();
        """.trimIndent()
    }

}
