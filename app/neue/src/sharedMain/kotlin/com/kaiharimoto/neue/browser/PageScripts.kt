package com.kaiharimoto.neue.browser

/**
 * The few scripts the study's browser runs in a page to read it: each returns JSON, and none changes the page but for a
 * `data-nmt-ref` mark on what can be pressed, so a ref names the same element when it is pressed.
 */
object PageScripts {
    const val REF = "data-nmt-ref"

    /** The pressable elements a person could see, marked with refs; JSON list. */
    val ELEMENTS = """
        (() => {
          const sel = 'a[href], button, [role=button], [role=link], [role=tab], summary, input, select, textarea, [onclick]';
          const out = [];
          let n = 0;
          document.querySelectorAll('[$REF]').forEach(e => e.removeAttribute('$REF'));
          for (const e of document.querySelectorAll(sel)) {
            const r = e.getBoundingClientRect();
            const st = getComputedStyle(e);
            if (r.width < 2 || r.height < 2 || st.visibility === 'hidden' || st.display === 'none') continue;
            n += 1;
            e.setAttribute('$REF', String(n));
            out.push(describe(e, n));
            if (n >= 400) break;
          }
          return JSON.stringify(out);
          function describe(e, n) {
            const hints = [e.getAttribute('autocomplete'), e.getAttribute('name'), e.getAttribute('aria-label'), e.id, e.getAttribute('placeholder')]
              .filter(Boolean).join(' ').toLowerCase();
            return {
              ref: n, tag: e.tagName.toLowerCase(),
              text: (e.innerText || e.value || e.getAttribute('aria-label') || e.title || '').replace(/\s+/g, ' ').trim().slice(0, 140),
              href: e.href ? String(e.href) : '', type: (e.getAttribute('type') || '').toLowerCase(),
              role: (e.getAttribute('role') || '').toLowerCase(), inForm: !!e.closest('form'),
              download: e.hasAttribute('download'), hints: hints.slice(0, 200)
            };
          }
        })()
    """.trimIndent()

    /** The pressable element at a point (CSS pixels), marked with a ref; JSON or "null". */
    fun at(x: Double, y: Double): String = """
        (() => {
          let e = document.elementFromPoint($x, $y);
          const sel = 'a[href], button, [role=button], [role=link], [role=tab], summary, input, select, textarea, [onclick]';
          e = e && (e.closest(sel) || e);
          if (!e) return 'null';
          const n = 100000 + Math.floor(Math.random() * 100000);
          e.setAttribute('$REF', String(n));
          const hints = [e.getAttribute('autocomplete'), e.getAttribute('name'), e.getAttribute('aria-label'), e.id, e.getAttribute('placeholder')]
            .filter(Boolean).join(' ').toLowerCase();
          return JSON.stringify({
            ref: n, tag: e.tagName.toLowerCase(),
            text: (e.innerText || e.value || e.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 140),
            href: e.href ? String(e.href) : '', type: (e.getAttribute('type') || '').toLowerCase(),
            role: (e.getAttribute('role') || '').toLowerCase(), inForm: !!e.closest('form'),
            download: e.hasAttribute('download'), hints: hints.slice(0, 200)
          });
        })()
    """.trimIndent()

    /** Where to press the element [ref] names, in CSS pixels, scrolled into view first; JSON {x, y} or "null". */
    fun centre(ref: Int): String = """
        (() => {
          const e = document.querySelector('[$REF="$ref"]');
          if (!e) return 'null';
          e.scrollIntoView({block: 'center', inline: 'center'});
          const r = e.getBoundingClientRect();
          return JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2});
        })()
    """.trimIndent()

    const val LINKS = """JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a => [(a.innerText || a.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim(), String(a.href)]))"""

    const val HTML = "document.documentElement.outerHTML"

    const val SETTLED = """JSON.stringify({ready: document.readyState, size: (document.body && document.body.innerText || '').length})"""

    const val HERE = """JSON.stringify({url: location.href, title: document.title})"""

    const val RATIO = "window.devicePixelRatio || 1"

    const val VIDEO = """!!document.querySelector('video, mux-player, iframe[src*="player"], iframe[src*="vimeo"], iframe[src*="youtube"], iframe[src*="mux"], iframe[src*="wistia"]')"""

    fun scroll(down: Boolean): String = "window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight * 0.85)); true"
}
