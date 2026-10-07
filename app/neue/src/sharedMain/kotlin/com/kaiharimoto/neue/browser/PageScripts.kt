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
            const hints = [e.getAttribute('autocomplete'), e.getAttribute('name'), e.getAttribute('aria-label'), e.id, e.getAttribute('placeholder'),
              e.getAttribute('title'), e.getAttribute('data-testid'), typeof e.className === 'string' ? e.className.replace(/[-_]+/g, ' ') : '']
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
          const hit = document.elementFromPoint($x, $y);
          const sel = 'a[href], button, [role=button], [role=link], [role=tab], summary, input, select, textarea, [onclick]';
          const pressable = hit && hit.closest(sel);
          // Something that is not pressable stands for itself only when it is small: never another site's frame, never a
          // container whose middle is somewhere else (the red team, 1.1.52).
          if (!hit || hit.tagName === 'IFRAME') return 'null';
          const box = hit.getBoundingClientRect();
          if (!pressable && box.width * box.height > innerWidth * innerHeight * 0.15) return 'null';
          const e = pressable || hit;
          const n = 100000 + Math.floor(Math.random() * 100000);
          e.setAttribute('$REF', String(n));
          const hints = [e.getAttribute('autocomplete'), e.getAttribute('name'), e.getAttribute('aria-label'), e.id, e.getAttribute('placeholder'),
            e.getAttribute('title'), e.getAttribute('data-testid'), typeof e.className === 'string' ? e.className.replace(/[-_]+/g, ' ') : '']
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

    /**
     * Where to press the element [ref] names, in CSS pixels, scrolled into view first; JSON {x, y}, {covered: what} when
     * something else is on top of it there (an overlay, a frame, a dialog — the press would land on that, not on what the
     * guard looked at: the red team, 1.1.52), or "null".
     */
    fun centre(ref: Int): String = """
        (() => {
          const e = document.querySelector('[$REF="$ref"]');
          if (!e) return 'null';
          if (e.tagName === 'IFRAME') return JSON.stringify({covered: 'another site\'s frame'});
          e.scrollIntoView({block: 'center', inline: 'center'});
          const r = e.getBoundingClientRect();
          const x = r.left + r.width / 2, y = r.top + r.height / 2;
          const top = document.elementFromPoint(x, y);
          if (!top || (top !== e && !e.contains(top))) {
            return JSON.stringify({covered: top ? (top.tagName.toLowerCase() + ' ' + (top.innerText || top.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 60)) : 'nothing'});
          }
          return JSON.stringify({x, y});
        })()
    """.trimIndent()

    const val LINKS = """JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a => [(a.innerText || a.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim(), String(a.href)]))"""

    const val HTML = "document.documentElement.outerHTML"

    /** The mark a kept picture carries: its number in the page's words. */
    const val PIC = "data-nmt-pic"

    /**
     * The page's pictures, after scrolling it through so lazy ones load and then back to the top; each marked with its
     * number, with what is said of it and the words just before it. JSON list.
     */
    val PICTURES = """
        (async () => {
          document.querySelectorAll('img[loading=lazy]').forEach(i => { try { i.loading = 'eager'; } catch (e) {} });
          const h = Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement.scrollHeight);
          for (let y = 0; y < h && y < 60000; y += Math.max(400, innerHeight * 0.8)) { scrollTo(0, y); await new Promise(r => setTimeout(r, 120)); }
          scrollTo(0, document.body ? document.body.scrollHeight : 0);
          await new Promise(r => setTimeout(r, 600));
          scrollTo(0, 0);
          await Promise.all(Array.from(document.images).filter(i => !i.complete).map(i => new Promise(r => { i.onload = i.onerror = r; setTimeout(r, 4000); })));
          document.querySelectorAll('[$PIC]').forEach(e => e.removeAttribute('$PIC'));
          const out = [];
          let n = 0;
          for (const i of document.images) {
            const st = getComputedStyle(i);
            if (st.display === 'none' || st.visibility === 'hidden') continue;
            const w = i.naturalWidth || 0, h2 = i.naturalHeight || 0;
            if (w < 120 || h2 < 120) continue;
            n++;
            i.setAttribute('$PIC', String(n));
            let near = '';
            let e = i;
            for (let k = 0; k < 6 && e && near.length < 20; k++) {
              e = e.previousElementSibling || e.parentElement;
              if (e) near = (e.innerText || '').replace(/\s+/g, ' ').trim();
            }
            out.push({n, src: String(i.currentSrc || i.src || ''), alt: (i.alt || i.title || '').replace(/\s+/g, ' ').trim(), near: near.slice(-160), w, h: h2});
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** The page's HTML, each marked picture replaced by "[Picture N: what is said of it]" in a copy; the page is left as it is. */
    val MARKED_HTML = """
        (() => {
          const copy = document.documentElement.cloneNode(true);
          copy.querySelectorAll('img[$PIC]').forEach(i => {
            const p = document.createElement('p');
            const alt = (i.getAttribute('alt') || i.getAttribute('title') || '').replace(/\s+/g, ' ').trim().slice(0, 120);
            p.textContent = '[Picture ' + i.getAttribute('$PIC') + (alt ? ': ' + alt : '') + ']';
            i.replaceWith(p);
          });
          return copy.outerHTML;
        })()
    """.trimIndent()

    const val SETTLED = """JSON.stringify({ready: document.readyState, size: (document.body && document.body.innerText || '').length})"""

    const val HERE = """JSON.stringify({url: location.href, title: document.title})"""

    /**
     * Whether the page asks to log in (the red team, 1.1.52: a session that ran out overnight was read as every chapter
     * after): a password field a person could see, or a "Log in" / "Sign in" link or button — never on a page of someone
     * logged in. "true" or "false".
     */
    const val ASKS_LOGIN = """(() => {
          const seen = e => { const r = e.getBoundingClientRect(); const st = getComputedStyle(e); return r.width > 1 && r.height > 1 && st.visibility !== 'hidden' && st.display !== 'none'; };
          if (Array.from(document.querySelectorAll('input[type=password]')).some(seen)) return 'true';
          // A way to log out anywhere in the page, a closed menu's too, is someone logged in, whatever else it offers.
          const out = /^\s*(log\s*-?\s*out|sign\s*-?\s*out)\s*$/i;
          if (Array.from(document.querySelectorAll('a, button, [role=button], [role=menuitem]')).some(e => out.test(e.textContent || e.getAttribute('aria-label') || ''))) return 'false';
          const ask = /^\s*(log\s*-?\s*in|sign\s*-?\s*in)\s*$/i;
          return Array.from(document.querySelectorAll('a, button, [role=button]')).some(e => ask.test(e.innerText || e.getAttribute('aria-label') || '') && seen(e)) ? 'true' : 'false';
        })()"""

    const val RATIO = "window.devicePixelRatio || 1"

    const val VIDEO = """!!document.querySelector('video, mux-player, iframe[src*="player"], iframe[src*="vimeo"], iframe[src*="youtube"], iframe[src*="mux"], iframe[src*="wistia"]')"""

    /**
     * The video a chapter is about, as a function the scripts below share: every `<video>`, those inside players' shadow
     * roots too (`<mux-player>` keeps its own there: the red team, 1.1.52 — it was found and never reached), the biggest
     * that is not a short decorative loop; kept as `window.__nmtVideo` so the recording stops the one it started.
     */
    private const val FIND_VIDEO = """
          function nmtVideos(root, out) {
            root.querySelectorAll('*').forEach(e => { if (e.tagName === 'VIDEO') out.push(e); if (e.shadowRoot) nmtVideos(e.shadowRoot, out); });
            return out;
          }
          function nmtVideo() {
            const all = nmtVideos(document, []);
            const area = v => { const r = v.getBoundingClientRect(); return r.width * r.height; };
            const real = all.filter(v => !v.loop && area(v) >= 160 * 90);
            const pick = (real.length ? real : all).sort((a, b) => area(b) - area(a))[0] || null;
            if (pick) window.__nmtVideo = pick;
            return pick;
          }
    """

    /**
     * The page's video: where it is, how long, and whether it is in another site's player (an iframe this page cannot
     * reach into, which the study opens by itself). JSON or "null".
     */
    val VIDEO_INFO = """
        (() => {
          $FIND_VIDEO
          const v = (window.__nmtVideo && window.__nmtVideo.isConnected) ? window.__nmtVideo : nmtVideo();
          if (v) {
            const r = v.getBoundingClientRect();
            return JSON.stringify({frame: '', duration: isFinite(v.duration) ? v.duration : 0, time: v.currentTime,
              ended: v.ended, paused: v.paused, x: r.left, y: r.top, w: r.width, h: r.height,
              tracks: v.textTracks ? v.textTracks.length : 0, drm: !!v.mediaKeys});
          }
          const f = document.querySelector('iframe[src*="player"], iframe[src*="vimeo"], iframe[src*="youtube"], iframe[src*="mux"], iframe[src*="wistia"], iframe[src*="video"]');
          return f ? JSON.stringify({frame: String(f.src)}) : 'null';
        })()
    """.trimIndent()

    /**
     * Every caption cue the page's video has, as [seconds, words] pairs: its text tracks turned on (hidden) so they load,
     * and a `<track>` file fetched with the page's own login when the cues do not come. Awaited; JSON.
     */
    val CAPTIONS = """
        (async () => {
          $FIND_VIDEO
          const v = (window.__nmtVideo && window.__nmtVideo.isConnected) ? window.__nmtVideo : nmtVideo();
          if (!v) return '[]';
          const out = [];
          // Captions and subtitles only: a metadata track (thumbnails, one address a cue) or a chapters track is not what
          // was said (the red team, 1.1.52: it passed for captions, and the sound was never listened to).
          const pick = Array.from(v.textTracks || []).filter(t => t.kind === 'subtitles' || t.kind === 'captions');
          const english = pick.filter(t => !t.language || t.language.startsWith('en'));
          const use = (english.length ? english : pick).slice(0, 1);
          for (const t of use) if (t.mode === 'disabled') t.mode = 'hidden';
          await new Promise(r => setTimeout(r, 2500));
          for (const t of use) {
            for (const c of Array.from(t.cues || [])) {
              const words = String(c.text || '');
              if (!/^\s*(https?:)?\/\//.test(words)) out.push([c.startTime, words]);
            }
          }
          if (out.length) return JSON.stringify(out);
          for (const el of Array.from(v.querySelectorAll('track[src]:not([kind]), track[src][kind=subtitles], track[src][kind=captions]')).slice(0, 1)) {
            try {
              const r = await fetch(el.src, {credentials: 'include'});
              if (r.ok) return JSON.stringify([[-1, await r.text()]]);
            } catch (e) {}
          }
          return '[]';
        })()
    """.trimIndent()

    /**
     * Starts the video from the start at [rate] and records its sound as it plays, from the element itself
     * (`captureStream`): nothing is downloaded, and a protected video (DRM) says so instead. "ok", or why not.
     */
    fun startListening(rate: Double): String = """
        (async () => {
          $FIND_VIDEO
          const v = nmtVideo();
          if (!v) return 'no video';
          if (v.mediaKeys) return 'protected';
          window.__nmtChunks = [];
          window.__nmtError = '';
          try {
            const stream = (v.captureStream || v.mozCaptureStream).call(v);
            const audio = new MediaStream(stream.getAudioTracks());
            if (!audio.getAudioTracks().length) {
              await new Promise(r => setTimeout(r, 1500));
              stream.getAudioTracks().forEach(t => audio.addTrack(t));
            }
            const rec = new MediaRecorder(audio.getAudioTracks().length ? audio : stream, {mimeType: 'audio/webm;codecs=opus'});
            rec.ondataavailable = async e => {
              if (e.data && e.data.size) {
                const b = new Uint8Array(await e.data.arrayBuffer());
                let s = '';
                for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
                window.__nmtChunks.push(btoa(s));
              }
            };
            rec.onerror = e => { window.__nmtError = String(e.error || e); };
            window.__nmtRecorder = rec;
            v.currentTime = 0;
            v.playbackRate = $rate;
            await v.play();
            rec.start(5000);
            return 'ok';
          } catch (e) {
            return 'failed: ' + e;
          }
        })()
    """.trimIndent()

    /** The sound recorded since last asked, as base64 pieces of one webm file; JSON list. */
    const val TAKE_SOUND = """JSON.stringify((window.__nmtChunks || []).splice(0))"""

    /** Stops the recording (its last piece arrives with the next [TAKE_SOUND]) and the video. */
    const val STOP_LISTENING = """(async () => { const r = window.__nmtRecorder; if (r && r.state !== 'inactive') { r.stop(); await new Promise(x => setTimeout(x, 800)); } const v = window.__nmtVideo || document.querySelector('video'); if (v) v.pause(); return true; })()"""

    fun scroll(down: Boolean): String = "window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight * 0.85)); true"
}
