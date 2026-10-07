// Walks the Lounge's page in headless Chromium, as a friend would (docs/LOUNGE.md).
//
//   node walk.js <url> <shots dir> 'step;;step;;…'
//
// Steps: wait:ms  shot:name  click:x,y  rclick:x,y  dbl:x,y  drag:x,y,x2,y2  type:text  key:Name  eval:js  offline:1|0
//        cut:  — drops the page's socket to kai's computer, as a lost connection would (the page should come back by itself)
// The browser's profile is kept in $PROFILE (default ./profile), so a second walk comes back under the first one's
// token; $W and $H set the window (1280×800), and $MOBILE a phone's touch screen.
const { chromium } = require('playwright');

(async () => {
  const ctx = await chromium.launchPersistentContext(process.env.PROFILE || 'profile', {
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    viewport: { width: +(process.env.W || 1280), height: +(process.env.H || 800) },
    // Headless Chromium has no locale of its own, and Skia's text shaping asks for one.
    locale: 'en-US',
    ...(process.env.MOBILE ? { isMobile: true, hasTouch: true, deviceScaleFactor: 2 } : {}),
  });
  const p = ctx.pages()[0] || await ctx.newPage();
  const logs = [];
  p.on('console', m => logs.push(m.type() + ': ' + m.text()));
  p.on('pageerror', e => logs.push('pageerror: ' + e.message));
  // With a cut: in the steps, the page's socket goes through Playwright, so a step can drop it.
  const sockets = [];
  if (process.argv[4].includes('cut:')) {
    await p.routeWebSocket(/\/ws$/, ws => { sockets.push([ws, ws.connectToServer()]); });
  }
  await p.goto(process.argv[2]);
  for (const s of process.argv[4].split(';;')) {
    const i = s.indexOf(':');
    const k = s.slice(0, i), v = s.slice(i + 1);
    const at = () => v.split(',').map(Number);
    if (k === 'wait') await p.waitForTimeout(+v);
    else if (k === 'shot') await p.screenshot({ path: process.argv[3] + '/' + v + '.png' });
    else if (k === 'click') await p.mouse.click(...at());
    else if (k === 'rclick') await p.mouse.click(...at(), { button: 'right' });
    else if (k === 'dbl') await p.mouse.dblclick(...at());
    else if (k === 'drag') {
      const [x, y, x2, y2] = at();
      await p.mouse.move(x, y); await p.mouse.down(); await p.mouse.move(x2, y2, { steps: 12 }); await p.mouse.up();
    }
    else if (k === 'type') await p.keyboard.type(v, { delay: 30 });
    else if (k === 'key') await p.keyboard.press(v);
    else if (k === 'eval') console.log('eval:', await p.evaluate(v));
    else if (k === 'offline') await ctx.setOffline(v === '1');
    else if (k === 'cut') for (const [page, server] of sockets.splice(0)) { await server.close(); await page.close({ code: 1006 }).catch(() => page.close()); }
  }
  console.log(logs.filter(l => !l.includes('WEBGL_debug_renderer_info')).join('\n'));
  await ctx.close();
})();
