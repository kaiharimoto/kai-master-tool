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

    /**
     * The page's video: where it is, how long, and whether it is in another site's player (an iframe this page cannot
     * reach into, which the study opens by itself). JSON or "null".
     */
    val VIDEO_INFO = """
        (() => {
          const v = document.querySelector('video');
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
          const v = document.querySelector('video');
          if (!v) return '[]';
          const out = [];
          const tracks = Array.from(v.textTracks || []);
          const pick = tracks.filter(t => t.kind === 'subtitles' || t.kind === 'captions');
          const use = (pick.length ? pick : tracks).filter(t => !t.language || t.language.startsWith('en')).slice(0, 1);
          for (const t of (use.length ? use : tracks.slice(0, 1))) t.mode = 'hidden';
          await new Promise(r => setTimeout(r, 2500));
          for (const t of (use.length ? use : tracks.slice(0, 1))) {
            for (const c of Array.from(t.cues || [])) out.push([c.startTime, String(c.text || '')]);
          }
          if (out.length) return JSON.stringify(out);
          for (const el of Array.from(v.querySelectorAll('track[src]')).slice(0, 1)) {
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
          const v = document.querySelector('video');
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
    const val STOP_LISTENING = """(async () => { const r = window.__nmtRecorder; if (r && r.state !== 'inactive') { r.stop(); await new Promise(x => setTimeout(x, 800)); } const v = document.querySelector('video'); if (v) v.pause(); return true; })()"""

    fun scroll(down: Boolean): String = "window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight * 0.85)); true"
}
