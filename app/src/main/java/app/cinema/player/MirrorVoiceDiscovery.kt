package app.cinema.player

/**
 * Single source of truth for translator discovery on the selected mirror.
 * Only IDs actually published by the page are accepted. Never invent translator 0.
 */
internal object MirrorVoiceDiscovery {
    val javascript = """
    function kinoDiscoverVoices() {
      function clean(s) { return String(s || '').replace(/\s+/g,' ').trim(); }
      function numeric(v) {
        var s=String(v||'').trim();
        return /^\d{1,12}$/.test(s) ? s : '';
      }
      var selectors=[
        '#translators-list [data-translator_id]',
        '#translators-list [data-translator-id]',
        '#translators-list [data-id]',
        '.b-translator__item',
        '.b-translator__item [data-id]',
        '.b-post__translators [data-id]',
        '[data-translator_id]', '[data-translator-id]',
        'select[name*="translator"] option[value]',
        'select[id*="translator"] option[value]'
      ];
      var found=[], selected='';
      document.querySelectorAll(selectors.join(',')).forEach(function(node) {
        var group=node.closest('li, .b-translator__item')||node;
        function readId(el) {
          if(!el)return '';
          return numeric(el.getAttribute('data-translator_id')||
             el.getAttribute('data-translator-id')||
             el.getAttribute('data-translation-id')||
             el.getAttribute('data-id')||
             (el.tagName==='OPTION'?el.getAttribute('value'):''));
        }
        var id=readId(node)||readId(group);
        if(!id)return;
        var name=clean(node.getAttribute('title')||
           node.getAttribute('data-title')||
           group.getAttribute('title')||
           group.getAttribute('data-title')||
           node.textContent||group.textContent).slice(0,75);
        if(!name)return;
        if(!found.some(function(x){return x.id===id;}))
          found.push({id:id,name:name});
        if(node.matches('.active, .selected, :checked') ||
           group.matches('.active, .selected') ||
           node.getAttribute('aria-selected')==='true')selected=id;
      });
      return {voices:found.slice(0,45), selected:selected};
    }
    """.trimIndent()
}
