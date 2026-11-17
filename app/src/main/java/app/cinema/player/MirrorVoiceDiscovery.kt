package app.cinema.player

internal object MirrorVoiceDiscovery {
    val javascript = """
    function kinoPlayerMetadata() {
      function clean(s) { return String(s || '').replace(/\s+/g,' ').trim(); }
      function id(v) { return /^\d{1,12}$/.test(String(v ?? '').trim()) ? String(v).trim() : ''; }
      var init = Array.from(document.scripts).map(function(script) {
        return (script.textContent || '').match(
          /\binitCDN(Series|Movies)Events\s*\(\s*['"]?(\d{1,12})['"]?\s*,\s*['"]?(\d{1,12})['"]?(?:\s*,\s*['"]?([01])['"]?\s*,\s*['"]?([01])['"]?(?:\s*,\s*['"]?([01])['"]?)?)?/i
        );
      }).find(Boolean);
      var selected = '', voices = [];
      var list = '#translators-list, .b-post__translators, .b-translator';
      var selectors = [
        '#translators-list li', '.b-translator__item',
        ':is(' + list + ') [data-translator_id], :is(' + list + ') [data-translator-id]',
        ':is(' + list + ') [data-translation-id], :is(' + list + ') [data-id]',
        'select[name*="translator"] option[value]', 'select[id*="translator"] option[value]'
      ];
      for (var node of document.querySelectorAll(selectors.join(','))) {
        var row = node.closest('li, .b-translator__item') || node;
        var element = row.querySelector('[data-translator_id], [data-translator-id], [data-translation-id], [data-id]');
        var attrs = [node, row, element].filter(Boolean);
        function attr(names) {
          for (var e of attrs) for (var name of names) {
            var value = e.getAttribute(name);
            if (value !== null && value !== '') return value;
          }
          return '';
        }
        var voiceId = id(attr(['data-translator_id','data-translator-id','data-translation-id','data-id']) ||
            (node.tagName === 'OPTION' ? node.value : ''));
        if (!voiceId) continue;
        var name = clean(attr(['data-title']) || row.textContent || attr(['title'])).slice(0,75);
        var requiresSubscription = /^(?:1|true|premium)$/i.test(attr(['data-premium','data-is-premium','data-access'])) ||
          attrs.some(function(e) {return e.matches('.b-translator__premium, [data-premium="1"], [data-access="premium"]')}) ||
          !!row.querySelector('.b-translator__premium, [data-premium="1"], [data-access="premium"]');
        function flag(names, index) {
          var explicit = attr(names);
          if (/^[01]$/.test(explicit)) return Number(explicit);
          return init && init[3] === voiceId && init[index] ? Number(init[index]) : 0;
        }
        if (name && !voices.some(function(v){return v.id === voiceId})) {
          voices.push({id:voiceId, name:name,
            isCamrip:flag(['data-camrip','data-is-camrip','data-is_camrip'],4),
            isAds:flag(['data-ads','data-is-ads','data-is_ads'],5),
            isDirector:flag(['data-director','data-is-director','data-is_director'],6),
            requiresSubscription:requiresSubscription});
        }
        if (attrs.some(function(e) {
          return e.matches('.active, .selected, :checked, [aria-selected="true"]');
        })) selected = voiceId;
      }
      var defaultVoice = selected || init?.[3] || (voices.length === 1 ? voices[0].id : '');
      return {
        id: init?.[2] || id(document.querySelector('input[name="post_id"], #post_id')?.value) ||
            id(document.querySelector('.b-post')?.getAttribute('data-post-id')) ||
            id(document.querySelector('.b-userset__fav_holder')?.getAttribute('data-post_id')),
        series: !!(init?.[1].toLowerCase() === 'series' ||
                    document.querySelector('#simple-episodes-tabs, #simple-seasons-tabs, .b-simple_episode__item') ||
                    /^\/series\//i.test(location.pathname)),
        defaultVoice: defaultVoice,
        flags: {isCamrip:Number(init?.[4] || 0), isAds:Number(init?.[5] || 0), isDirector:Number(init?.[6] || 0)},
        voices: voices.slice(0, 45)
      };
    }
    """.trimIndent()

    val episodesJavascript = """
    function kinoEpisodes(root) {
      var seasonTabs = root.querySelectorAll('.b-simple_season__item[data-tab_id], .b-simple_season__item[data-season_id]');
      var soleSeason = seasonTabs.length === 1 ?
        (seasonTabs[0].getAttribute('data-tab_id') || seasonTabs[0].getAttribute('data-season_id')) : '';
      return Array.from(root.querySelectorAll('.b-simple_episode__item[data-episode_id], .b-simple_episode__item[data-episode-id]'))
        .map(function(e) {
          var season = e.getAttribute('data-season_id') || e.getAttribute('data-season-id') ||
            e.closest('ul[id^="simple-episodes-list-"]')?.id.replace('simple-episodes-list-','') ||
            e.closest('[data-season_id]')?.getAttribute('data-season_id') || soleSeason || '';
          return {season:season, episode:e.getAttribute('data-episode_id') || e.getAttribute('data-episode-id'),
            label:(e.textContent || '').trim()};
        });
    }
    """.trimIndent()
}
