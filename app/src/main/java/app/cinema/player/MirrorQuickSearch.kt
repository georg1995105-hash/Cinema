package app.cinema.player

import org.json.JSONObject

/**
 * Quick search returns titles and links, but usually no posters. Resolve a limited
 * number of posters from each film's actual same-origin page with a common timeout.
 * Never use proxy domains, third-party page HTML or fabricated image URLs.
 */
internal object MirrorQuickSearch {
    fun javascript(query: String): String {
        val quoted = JSONObject.quote(query.trim().take(120))
        return """
        (function() {
          window.__kinoQuickSearchResult = null;
          var q = $quoted;
          function absolute(raw) {
            try {
              if(!raw)return '';
              var u=new URL(raw,location.origin);
              return u.protocol==='https:' ? u.href : '';
            } catch(e) {return '';}
          }
          function sameOrigin(raw) {
            try {return new URL(raw).origin===location.origin;}catch(e){return false;}
          }
          function posterFromPage(doc,base) {
            var nodes = [
              doc.querySelector('.b-post__poster img'),
              doc.querySelector('.b-post__cover img'),
              doc.querySelector('.b-post__images img'),
              doc.querySelector('.b-post__image img'),
              doc.querySelector('[itemprop="image"]'),
              doc.querySelector('meta[property="og:image"]'),
              doc.querySelector('meta[name="twitter:image"]')
            ].filter(Boolean);
            for(var i=0;i<nodes.length;i++) {
              var node=nodes[i];
              var src=node.getAttribute('data-src') ||
                node.getAttribute('data-original') ||
                node.getAttribute('data-lazy-src') ||
                node.getAttribute('src') ||
                node.getAttribute('content');
              if(!src)continue;
              try {
                var url=new URL(src,base);
                if(url.protocol==='https:' &&
                  !/(?:placeholder|spacer|blank\.|\/1x1\.)/i.test(url.href))
                  return url.href;
              }catch(e){}
            }
            return '';
          }
          fetch('/engine/ajax/search.php?q='+encodeURIComponent(q), {
            method:'GET',credentials:'same-origin',redirect:'follow',
            headers:{'Accept':'text/html','X-Requested-With':'XMLHttpRequest'}
          }).then(function(resp) {
            if(!resp.ok || !sameOrigin(resp.url)) throw Error('search unavailable');
            return resp.text();
          }).then(async function(html) {
            var doc=new DOMParser().parseFromString(html,'text/html');
            var cards=Array.from(doc.querySelectorAll(
              '.b-search__live_section > ul > li, .b-search__live_section li'
            )).slice(0,40).map(function(li) {
              var a=li.querySelector('a[href]');
              if(!a)return null;
              var name=li.querySelector('span.enty')?.textContent ||
                a.querySelector('.enty')?.textContent ||
                a.getAttribute('title') || a.textContent || '';
              var url=absolute(a.getAttribute('href'));
              if(!url || !sameOrigin(url))return null;
              var img=li.querySelector('img');
              var raw=img&&(img.getAttribute('data-src')||
                img.getAttribute('data-original')||img.getAttribute('src'));
              var poster=absolute(raw);
              return {url:url,title:name.trim(),poster:poster,details:''};
            }).filter(function(x){return x && x.title && x.url});
            // The fast suggestion endpoint normally contains no poster images.
            // Fetch the film page only for the first 8 missing posters, with one
            // common 2.6 second timeout; never delay results indefinitely.
            var missing=cards.filter(function(c){return !c.poster;}).slice(0,8);
            if(missing.length) {
              var controller=new AbortController();
              var timeout=setTimeout(function(){controller.abort();},2600);
              try {
                await Promise.all(missing.map(async function(card) {
                  try {
                    var response=await fetch(card.url,{
                      method:'GET',credentials:'same-origin',redirect:'follow',
                      signal:controller.signal,headers:{'Accept':'text/html'}
                    });
                    if(!response.ok || !sameOrigin(response.url))return;
                    var size=Number(response.headers.get('content-length')||0);
                    if(size>1600000)return;
                    var body=await response.text();
                    if(body.length>1600000)return;
                    card.poster=posterFromPage(
                      new DOMParser().parseFromString(body,'text/html'),response.url
                    );
                  }catch(e){}
                }));
              }finally {clearTimeout(timeout);}
            }
            window.__kinoQuickSearchResult=JSON.stringify({cards:cards});
          }).catch(function() {
            window.__kinoQuickSearchResult=JSON.stringify({cards:[]});
          });
          return 'started';
        })();
        """.trimIndent()
    }
}
