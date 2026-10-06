// Chessy's asset pack for Neue (app/neue/src/commonMain/composeResources/files/chessy/).
//
//   node tools/chessy/export.js path/to/chessy.html app/neue/src/commonMain/composeResources/files/chessy
//
// The input is the Chessy mockup page (kai's approved model, https://claude.ai/artifact/Q42YHqjvNELnJLV3qmax9U),
// built by the mockup's own pipeline outside this repository. Its stage exposes window.__chessyExport(): every
// picture exactly as the live look draws it (layers, their swing rims, each face's features, brows and tongue, the
// blink and the talking mouths), each with its box on the 1320 x 1740 sheet, and the rig's numbers. This writes them
// as WebP files and one chessy.json (read by core/ai/chessy/ChessyPack).
const fs = require('fs'), path = require('path');
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim() + '/playwright');
(async () => { const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1200, height: 900 } });
  await p.setContent('<!doctype html><html><head><meta charset=utf8></head><body>' + fs.readFileSync(process.argv[2], 'utf8') + '</body></html>');
  await p.waitForSelector('#p4[data-ready="1"]', { timeout: 60000 });
  const d = await p.evaluate(() => window.__chessyExport(.9));
  const out = process.argv[3]; fs.mkdirSync(out, { recursive: true });
  let bytes = 0;
  const save = (o, name) => { if (!o) return null; const buf = Buffer.from(o.src.split(',')[1], 'base64'); fs.writeFileSync(path.join(out, name + '.webp'), buf); bytes += buf.length;
    const r = { x: o.x, y: o.y, w: o.w, h: o.h, file: name + '.webp' }; return r; };
  for (const l of d.layers) { l.pic = save(l.pic, 'layer-' + l.id); l.rim = save(l.rim, 'rim-' + l.id); }
  for (const [f, v] of Object.entries(d.faces)) for (const k of ['features', 'brows', 'tongue']) v[k] = save(v[k], `${k}-${f}`);
  const P = d.parts;
  P.blink = save(P.blink, 'part-blink'); P.closed = save(P.closed, 'part-closed'); P.cline = save(P.cline, 'part-cline');
  P.talk = P.talk.map((t, i) => save(t, 'part-talk' + i));
  for (const g of ['closedBy', 'openBy', 'blinkBy']) for (const f of Object.keys(P[g])) P[g][f] = save(P[g][f], `part-${g}-${f}`);
  const rig = d.rig; delete d.rig; d.closedLength = rig.clLen; d.closedDy = rig.clDy;  // kai's closed-mouth setting
  d.layers = d.layers.filter(l => l.id !== 'marks');
  fs.writeFileSync(path.join(out, 'chessy.json'), JSON.stringify(d));
  console.log('pictures', (bytes / 1024 / 1024).toFixed(2), 'MB,', fs.readdirSync(out).length, 'files');
  await b.close(); })();
