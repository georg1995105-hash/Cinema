package app.cinema.player

import org.json.JSONObject

/** Uses only the user's current mirror and its existing WebView session. */
internal object MirrorQuickSearch {
    fun javascript(query: String): String {
        val quoted = JSONObject.quote(query.trim().take(120))
        return """
        (function() {
          window.__kinoQuickSearchResult=null;
          var q=$quoted;
          fetch('/engine/ajax/search.php?q='+encodeURIComponent(q), {
            method:'GET',credentials:'same-origin',redirect:'follow',
            headers:{'Accept':'text/html','X-Requested-With':'XMLHttpRequest'}
          }).then(function(resp) {
            if(!resp.ok || new URL(resp.url).origin!==location.origin) throw Error('unavailable');
            return resp.text();
          }).then(function(html) {
            var doc=new DOMParser().parseFromString(html,'text/html');
            var cards=Array.from(doc.querySelectorAll(
              '.b-search__live_section > ul > li, .b-search__live_section li'
            )).slice(0,40).map(function(li) {
              var a=li.querySelector('a[href]');
              if(!a)return null;
              var name=li.querySelector('span.enty')?.textContent ||
                a.getAttribute('title') || a.textContent || '';
              var img=li.querySelector('img');
              var url=new URL(a.getAttribute('href'),location.origin).href;
              var src=img&&(img.getAttribute('data-src')||img.getAttribute('src'));
              var poster=src?new URL(src,location.origin).href:'';
              return {url:url,title:name.trim(),poster:poster,details:''};
            }).filter(function(x){return x && x.title && x.url});
            window.__kinoQuickSearchResult=JSON.stringify({cards:cards});
          }).catch(function() {
            window.__kinoQuickSearchResult=JSON.stringify({cards:[]});
          });
          return 'started';
        })();
        """.trimIndent()
    }
}
