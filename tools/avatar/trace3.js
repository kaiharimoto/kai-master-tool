// v3: fits the design behind the screenshot, not its pixels.
// Silhouette: penalised periodic B-spline (G2) through the outer edge. Eyes: ellipses fitted to the
// edge of the flat yellow plateau (the glow is outside it). Veins: a line network — skeleton → graph →
// strokes of constant width meeting at shared points — completed under the eyes.
// node trace3.js [--lambda=.. --spacing=..]  -> trace/geometry3.json, trace/v3-*.png
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const { add, sub, mul, dot, norm, unit, bq, fitOpen, fitLoop, r1, contours, area, smoothLoop } = require('./lib.js');
const OUT = path.join(__dirname, 'trace');
const REFPNG = process.env.AVATAR_REF || path.join(__dirname, 'reference.png'); // the screenshot kai drew from: not committed
const arg = (k, d) => { const a = process.argv.find(s => s.startsWith('--' + k + '=')); return a ? +a.split('=')[1] : d; };
const log = (...a) => console.log(...a);

// ---------- small numerics ----------
function solve(A, b) { // Gaussian elimination, A n×n (array of rows), b n
  const n = b.length, M = A.map((r, i) => r.concat([b[i]]));
  for (let c = 0; c < n; c++) { let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; [M[c], M[p]] = [M[p], M[c]];
    for (let r = c + 1; r < n; r++) { const f = M[r][c] / M[c][c]; if (!f) continue; for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; } }
  const x = new Array(n).fill(0); for (let r = n - 1; r >= 0; r--) { let s = M[r][n]; for (let k = r + 1; k < n; k++) s -= M[r][k] * x[k]; x[r] = s / M[r][r]; } return x;
}
function nelder(f, x0, step, iters = 3000) {
  const n = x0.length; let S = [x0.slice()]; for (let i = 0; i < n; i++) { const x = x0.slice(); x[i] += step[i]; S.push(x); } let F = S.map(f);
  for (let it = 0; it < iters; it++) {
    const ix = [...Array(n + 1).keys()].sort((a, b) => F[a] - F[b]); S = ix.map(i => S[i]); F = ix.map(i => F[i]);
    const c = new Array(n).fill(0); for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) c[j] += S[i][j] / n;
    const xr = c.map((v, j) => v + (v - S[n][j])), fr = f(xr);
    if (fr < F[0]) { const xe = c.map((v, j) => v + 2 * (v - S[n][j])), fe = f(xe); if (fe < fr) { S[n] = xe; F[n] = fe; } else { S[n] = xr; F[n] = fr; } }
    else if (fr < F[n - 1]) { S[n] = xr; F[n] = fr; }
    else { const xc = c.map((v, j) => v + .5 * (S[n][j] - v)), fc = f(xc); if (fc < F[n]) { S[n] = xc; F[n] = fc; } else for (let i = 1; i <= n; i++) { S[i] = S[i].map((v, j) => S[0][j] + .5 * (v - S[0][j])); F[i] = f(S[i]); } }
  }
  let b = 0; for (let i = 1; i <= n; i++) if (F[i] < F[b]) b = i; return S[b];
}
function resample(P, step, closed) {
  const pts = closed ? P.concat([P[0]]) : P, out = [pts[0]]; let acc = 0;
  for (let i = 1; i < pts.length; i++) { let a = pts[i - 1]; const b = pts[i]; let d = norm(sub(b, a));
    while (acc + d >= step) { const t = (step - acc) / d; a = add(a, mul(sub(b, a), t)); out.push(a); d = norm(sub(b, a)); acc = 0; }
    acc += d; }
  if (!closed && norm(sub(out[out.length - 1], pts[pts.length - 1])) > step * .3) out.push(pts[pts.length - 1]);
  if (closed && norm(sub(out[out.length - 1], out[0])) < step * .5) out.pop();
  return out;
}
const smoothOpen = (P, passes) => { let Q = P; for (let p = 0; p < passes; p++) Q = Q.map((q, i) => i === 0 || i === Q.length - 1 ? q : [(Q[i-1][0] + 2*q[0] + Q[i+1][0]) / 4, (Q[i-1][1] + 2*q[1] + Q[i+1][1]) / 4]); return Q; };
const sampleSegs = (segs, per = 24) => { const o = []; for (const s of segs) for (let i = 0; i < per; i++) o.push(bq(s, i / per)); o.push(segs[segs.length - 1][3]); return o; };
const lineSeg = (a, b) => [a, add(a, mul(sub(b, a), 1 / 3)), add(a, mul(sub(b, a), 2 / 3)), b];

(async () => {
  const browser = await chromium.launch(); const page = await browser.newPage();
  const b64 = fs.readFileSync(REFPNG).toString('base64');
  const raw = await page.evaluate(async d => { const img = new Image(); img.src = 'data:image/png;base64,' + d; await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0);
    const a = x.getImageData(0, 0, c.width, c.height).data; let s = ''; for (let i = 0; i < a.length; i += 8192) s += String.fromCharCode.apply(null, a.subarray(i, i + 8192)); return { w: c.width, h: c.height, s: btoa(s) }; }, b64);
  const W = raw.w, H = raw.h, px = Buffer.from(raw.s, 'base64');
  const R = i => px[i * 4], G = i => px[i * 4 + 1], Bl = i => px[i * 4 + 2];
  const samp = (ch, x, y) => { const x0 = Math.max(0, Math.min(W - 2, Math.floor(x))), y0 = Math.max(0, Math.min(H - 2, Math.floor(y))), fx = x - x0, fy = y - y0; const v = (X, Y) => px[(Y * W + X) * 4 + ch];
    return (v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx) * (1 - fy) + (v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx) * fy; };
  const purple = (x, y) => samp(2, x, y) - samp(1, x, y); // b - g: body ≈ 20, vein ≈ 126

  // =================== silhouette ===================
  const F = new Float32Array(W * H); for (let i = 0; i < W * H; i++) F[i] = 18 - (G(i) - R(i));
  const comp = new Uint8Array(W * H); { const st = [200 * W + 250]; comp[st[0]] = 1;
    while (st.length) { const i = st.pop(), x = i % W, y = (i / W) | 0; for (const [dx, dy] of [[1,0],[-1,0],[0,1],[0,-1]]) { const X = x + dx, Y = y + dy; if (X < 0 || Y < 0 || X >= W || Y >= H) continue; const j = Y * W + X; if (!comp[j] && F[j] > 0) { comp[j] = 1; st.push(j); } } } }
  const near = i => { const x = i % W, y = (i / W) | 0; for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const X = x + dx, Y = y + dy; if (X >= 0 && Y >= 0 && X < W && Y < H && comp[Y * W + X]) return true; } return false; };
  const Fb = new Float32Array(W * H); for (let i = 0; i < W * H; i++) Fb[i] = near(i) ? F[i] : -20;
  let loop = contours(Fb, W, H).sort((a, b) => Math.abs(area(b)) - Math.abs(area(a)))[0]; if (area(loop) < 0) loop.reverse();
  loop = smoothLoop(loop, 1);
  const n0 = loop.length;
  const pick = (side, yy) => { let best = -1, bd = 1e9; loop.forEach((p, i) => { if ((side < 0 ? p[0] < 226 : p[0] > 226) && p[1] > 250) { const d = Math.abs(p[1] - yy); if (d < bd) { bd = d; best = i; } } }); return best; };
  const ia = pick(-1, 288), ib = pick(1, 284);
  let lowest = 0; loop.forEach((p, i) => { if (p[1] > loop[lowest][1]) lowest = i; });
  const between = (a, b, i) => a <= b ? (i >= a && i <= b) : (i >= a || i <= b);
  let from = ia, to = ib; if (!between(ia, ib, lowest)) { from = ib; to = ia; }
  const keep = []; for (let i = to; ; i = (i + 1) % n0) { keep.push(loop[i]); if (i === from) break; }
  const P0 = loop[from], P3 = loop[to];
  const t0 = unit(sub(loop[from], loop[(from - 6 + n0) % n0])), t3 = unit(sub(loop[to], loop[(to + 6) % n0]));
  const hch = norm(sub(P0, P3)) * 0.45, chin = [P0, add(P0, mul(t0, hch)), add(P3, mul(t3, hch)), P3];
  const cutY = Math.min(P0[1], P3[1]) - 2;
  const edgePts = resample(keep, 1.5, false), chinPts = resample(sampleSegs([chin], 200), 1.5, false).slice(1, -1);
  const ring = edgePts.concat(chinPts), isEdge = ring.map((_, i) => i < edgePts.length);
  const tipW = arg('tipw', 12); // the tail tip is the one tight curve: hold the fit to it
  const wgt = ring.map((p, i) => !isEdge[i] ? 1 : (p[0] > 180 && p[0] < 232 && p[1] < 112 ? .25 : (p[1] < 32 && p[0] > 395 ? tipW : 1))); // the pole behind the head
  // periodic cubic B-spline, uniform in arclength, with a second-difference penalty
  let per = 0; for (let i = 0; i < ring.length; i++) per += norm(sub(ring[(i + 1) % ring.length], ring[i]));
  const M = Math.round(per / arg('spacing', 10)), lam = arg('lambda', 2);
  const us = []; { let acc = 0; for (let i = 0; i < ring.length; i++) { us.push(acc / per * M); acc += norm(sub(ring[(i + 1) % ring.length], ring[i])); } }
  const basis = u => { const k = Math.floor(u), t = u - k; return [k, [(1 - t) ** 3 / 6, (3 * t ** 3 - 6 * t * t + 4) / 6, (-3 * t ** 3 + 3 * t * t + 3 * t + 1) / 6, t ** 3 / 6]]; };
  const A = Array.from({ length: M }, () => new Array(M).fill(0)), bx = new Array(M).fill(0), by = new Array(M).fill(0);
  ring.forEach((p, i) => { const [k, w] = basis(us[i]); for (let a = 0; a < 4; a++) { const ia2 = (k + a) % M; bx[ia2] += wgt[i] * w[a] * p[0]; by[ia2] += wgt[i] * w[a] * p[1]; for (let c = 0; c < 4; c++) A[ia2][(k + c) % M] += wgt[i] * w[a] * w[c]; } });
  for (let j = 0; j < M; j++) { const idx = [(j - 1 + M) % M, j, (j + 1) % M], cf = [1, -2, 1]; for (let a = 0; a < 3; a++) for (let c = 0; c < 3; c++) A[idx[a]][idx[c]] += lam * cf[a] * cf[c]; }
  const cx = solve(A.map(r => r.slice()), bx), cy = solve(A.map(r => r.slice()), by);
  const cp = j => [cx[(j + M) % M], cy[(j + M) % M]];
  const bodySegs = []; for (let k = 0; k < M; k++) { const c0 = cp(k), c1 = cp(k + 1), c2 = cp(k + 2), c3 = cp(k + 3);
    bodySegs.push([mul(add(add(c0, mul(c1, 4)), c2), 1 / 6), mul(add(mul(c1, 2), c2), 1 / 3), mul(add(c1, mul(c2, 2)), 1 / 3), mul(add(add(c1, mul(c2, 4)), c3), 1 / 6)]); }
  const bodyDense = sampleSegs(bodySegs, 40);
  const nearestDist = (P, Q) => { let m = 1e9; for (const q of Q) { const d = (P[0] - q[0]) ** 2 + (P[1] - q[1]) ** 2; if (d < m) m = d; } return Math.sqrt(m); };
  const devPts = edgePts.filter((p, i) => wgt[i] >= 1), devs = devPts.map(p => nearestDist(p, bodyDense));
  log('worst silhouette points', devs.map((d, i) => [d, devPts[i]]).sort((a, b) => b[0] - a[0]).slice(0, 6).map(([d, p]) => d.toFixed(2) + '@' + p.map(Math.round)).join(' '));
  const silDev = { max: Math.max(...devs), rms: Math.sqrt(devs.reduce((s, d) => s + d * d, 0) / devs.length) };
  // fairness: curvature every 2 px along the outline; count its sign changes and its local extrema (wiggles)
  let infl = 0, wig = 0; { const S = resample(bodyDense, 2, true), n = S.length, K = [];
    for (let i = 0; i < n; i++) { const a = S[(i - 2 + n) % n], b = S[i], c = S[(i + 2) % n]; const t1 = unit(sub(b, a)), t2 = unit(sub(c, b)); K.push(Math.atan2(t1[0] * t2[1] - t1[1] * t2[0], dot(t1, t2)) / 4); }
    const Ks = K.map((_, i) => (K[(i - 1 + n) % n] + 2 * K[i] + K[(i + 1) % n]) / 4);
    let prev = 0; for (const k of Ks) { if (Math.abs(k) > .002) { if (prev && Math.sign(k) !== Math.sign(prev)) infl++; prev = k; } }
    for (let i = 0; i < n; i++) { const a = Ks[(i - 1 + n) % n], b = Ks[i], c = Ks[(i + 1) % n]; if ((b > a && b > c) || (b < a && b < c)) if (Math.abs(b - a) + Math.abs(b - c) > .0004) wig++; } }
  log('silhouette: segments', M, 'lambda', lam, 'max dev', silDev.max.toFixed(2), 'rms', silDev.rms.toFixed(2), 'inflections', infl, 'curvature extrema', wig);

  // =================== eyes ===================
  const eyes = {};
  for (const [k, x0, x1] of [['L', 110, 222], ['R', 232, 340]]) {
    let m = 0, sx = 0, sy = 0; for (let y = 130; y < 270; y++) for (let x = x0; x < x1; x++) { const i = y * W + x; if (G(i) >= 200 && R(i) > 200) { m++; sx += x; sy += y; } }
    const c0 = [sx / m, sy / m], pts = [];
    for (let a = 0; a < 360; a += 2) { const d = [Math.cos(a * Math.PI / 180), Math.sin(a * Math.PI / 180)]; let best = null;
      for (let r = 14; r < 62; r += .25) { const g = t => samp(1, c0[0] + d[0] * t, c0[1] + d[1] * t); const der = g(r + .75) - g(r - .75);
        if (g(r - 2) > 215 && g(r + 2) < 185 && (!best || der < best.der)) best = { r, der }; }
      if (!best) continue; const r = best.r, g = t => samp(1, c0[0] + d[0] * t, c0[1] + d[1] * t);
      const dm = g(r - .25 + .75) - g(r - .25 - .75), d0 = best.der, dp = g(r + .25 + .75) - g(r + .25 - .75); const den = dm - 2 * d0 + dp; const off = den ? .25 * (dm - dp) / (2 * den) : 0;
      pts.push(add(c0, mul(d, r + off))); }
    const cost = q => { const [ex, ey, rx, ry, tl, nn] = q; if (rx < 5 || ry < 5 || nn < 1.2) return 1e9; const t = tl * Math.PI / 180; let s = 0;
      for (const p of pts) { const dx = p[0] - ex, dy = p[1] - ey, u = dx * Math.cos(t) + dy * Math.sin(t), v = -dx * Math.sin(t) + dy * Math.cos(t); const rho = Math.pow(Math.pow(Math.abs(u / rx), nn) + Math.pow(Math.abs(v / ry), nn), 1 / nn); const d = (rho - 1) * Math.hypot(u, v) / rho; s += d * d; } return s; };
    const q = nelder(cost, [c0[0], c0[1], 27, 43, k === 'L' ? 17 : -7, 2], [2, 2, 2, 2, 4, .3]);
    const res = Math.sqrt(cost(q) / pts.length);
    eyes[k] = { cx: q[0], cy: q[1], rx: q[2], ry: q[3], tilt: q[4], n: q[5], edgeRms: res, edgePts: pts.length };
    log('eye', k, eyes[k].cx.toFixed(1), eyes[k].cy.toFixed(1), eyes[k].rx.toFixed(2), '×', eyes[k].ry.toFixed(2), 'tilt', eyes[k].tilt.toFixed(1), 'n', eyes[k].n.toFixed(2), 'edge rms', res.toFixed(2), 'from', pts.length, 'edge points');
  }
  const eyeRho = (e, x, y) => { const t = e.tilt * Math.PI / 180, dx = x - e.cx, dy = y - e.cy, u = dx * Math.cos(t) + dy * Math.sin(t), v = -dx * Math.sin(t) + dy * Math.cos(t); return Math.pow(Math.pow(Math.abs(u / e.rx), e.n) + Math.pow(Math.abs(v / e.ry), e.n), 1 / e.n); };

  // =================== veins: mask, node, skeleton ===================
  const VM = new Uint8Array(W * H); for (let i = 0; i < W * H; i++) VM[i] = comp[i] && (Bl(i) - G(i)) > 73 ? 1 : 0;
  const label = (mask, want, conn8) => { const lab = new Int32Array(W * H).fill(-1), sizes = []; let id = 0;
    for (let s = 0; s < W * H; s++) { if (mask[s] !== want || lab[s] >= 0) continue; const st = [s]; lab[s] = id; let n = 0;
      while (st.length) { const i = st.pop(); n++; const x = i % W, y = (i / W) | 0; for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { if (!dx && !dy) continue; if (!conn8 && dx && dy) continue; const X = x + dx, Y = y + dy; if (X < 0 || Y < 0 || X >= W || Y >= H) continue; const j = Y * W + X; if (mask[j] === want && lab[j] < 0) { lab[j] = id; st.push(j); } } }
      sizes.push(n); id++; } return { lab, sizes }; };
  { const holes = label(VM, 0, false); for (let i = 0; i < W * H; i++) if (comp[i] && holes.sizes[holes.lab[i]] < 30) VM[i] = 1;
    const specks = label(VM, 1, true); for (let i = 0; i < W * H; i++) if (VM[i] && specks.sizes[specks.lab[i]] < 40) VM[i] = 0; }
  const chamfer = mask => { const INF = 1e9, d = new Float32Array(W * H); for (let i = 0; i < W * H; i++) d[i] = mask[i] ? INF : 0;
    const at = (x, y) => x < 0 || y < 0 || x >= W || y >= H ? 0 : d[y * W + x];
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const i = y * W + x; if (!d[i]) continue; d[i] = Math.min(d[i], at(x - 1, y) + 3, at(x, y - 1) + 3, at(x - 1, y - 1) + 4, at(x + 1, y - 1) + 4); }
    for (let y = H - 1; y >= 0; y--) for (let x = W - 1; x >= 0; x--) { const i = y * W + x; if (!d[i]) continue; d[i] = Math.min(d[i], at(x + 1, y) + 3, at(x, y + 1) + 3, at(x + 1, y + 1) + 4, at(x - 1, y + 1) + 4); }
    for (let i = 0; i < W * H; i++) d[i] /= 3; return d; };
  // the node: a morphological opening removes the lines and leaves the blob
  const DT = chamfer(VM), core = new Uint8Array(W * H); for (let i = 0; i < W * H; i++) core[i] = DT[i] >= 6.5 ? 1 : 0;
  const notCore = new Uint8Array(W * H); for (let i = 0; i < W * H; i++) notCore[i] = core[i] ? 0 : 1; const DC = chamfer(notCore);
  let node = null; { let m = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
    for (let y = 150; y < 250; y++) for (let x = 320; x < 380; x++) { const i = y * W + x; if (VM[i] && DC[i] <= 6.5) { m++; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; } }
    if (m > 30) { const ex = sx / m, ey = sy / m, a = sxx / m - ex * ex, c = syy / m - ey * ey, b = sxy / m - ex * ey, tr = a + c, det = a * c - b * b;
      const l1 = tr / 2 + Math.sqrt(tr * tr / 4 - det), l2 = tr / 2 - Math.sqrt(tr * tr / 4 - det); let tilt = .5 * Math.atan2(2 * b, a - c) * 180 / Math.PI + 90; while (tilt > 90) tilt -= 180; while (tilt < -90) tilt += 180;
      node = { cx: ex, cy: ey, rx: 2 * Math.sqrt(l2) + .5, ry: 2 * Math.sqrt(l1) + .5, tilt }; } }
  log('node', node && Object.values(node).map(v => v.toFixed(1)).join(' '));
  // Zhang–Suen
  const SK = VM.slice(); const at = (x, y) => (x < 0 || y < 0 || x >= W || y >= H) ? 0 : SK[y * W + x];
  for (let changed = true; changed;) { changed = false;
    for (const pass of [0, 1]) { const del = [];
      for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) { if (!SK[y * W + x]) continue;
        const p = [at(x, y-1), at(x+1, y-1), at(x+1, y), at(x+1, y+1), at(x, y+1), at(x-1, y+1), at(x-1, y), at(x-1, y-1)];
        const B = p.reduce((s, q) => s + q, 0); if (B < 2 || B > 6) continue;
        let A2 = 0; for (let j = 0; j < 8; j++) if (!p[j] && p[(j + 1) % 8]) A2++; if (A2 !== 1) continue;
        if (pass === 0 ? (p[0] * p[2] * p[4] || p[2] * p[4] * p[6]) : (p[0] * p[2] * p[6] || p[0] * p[4] * p[6])) continue;
        del.push(y * W + x); }
      if (del.length) { changed = true; for (const i of del) SK[i] = 0; } } }
  const N8 = [[1,0],[-1,0],[0,1],[0,-1],[1,1],[1,-1],[-1,1],[-1,-1]];
  const nb = i => { const x = i % W, y = (i / W) | 0, o = []; for (const [dx, dy] of N8) if (at(x + dx, y + dy)) o.push((y + dy) * W + x + dx); return o; };

  // =================== graph ===================
  let nodes = new Map(), nodeOf = new Int32Array(W * H).fill(-1), nid = 0;
  for (let i = 0; i < W * H; i++) if (SK[i] && nb(i).length !== 2 && nodeOf[i] < 0) { // cluster junction/end pixels
    const st = [i], pix = []; nodeOf[i] = nid; while (st.length) { const j = st.pop(); pix.push(j); if (nb(j).length === 1) continue; for (const q of nb(j)) if (nodeOf[q] < 0 && nb(q).length >= 3) { nodeOf[q] = nid; st.push(q); } }
    const c = pix.reduce((s, j) => [s[0] + j % W, s[1] + ((j / W) | 0)], [0, 0]); nodes.set(nid, { id: nid, x: c[0] / pix.length, y: c[1] / pix.length, pix }); nid++; }
  let edges = [], seen = new Set(); const ek = (a, b) => a < b ? a + ':' + b : b + ':' + a;
  for (const n of nodes.values()) for (const s of n.pix) for (const q of nb(s)) { if (nodeOf[q] === n.id || seen.has(ek(s, q))) continue; seen.add(ek(s, q));
    const chain = []; let prev = s, cur = q; while (nodeOf[cur] < 0) { chain.push(cur); const nx = nb(cur).find(r => r !== prev && !seen.has(ek(cur, r))); if (nx === undefined) break; seen.add(ek(cur, nx)); prev = cur; cur = nx; }
    if (nodeOf[cur] < 0) continue; edges.push({ a: n.id, b: nodeOf[cur], chain: chain.map(j => [j % W, (j / W) | 0]) }); }
  // edge polylines include their node centres at both ends
  const P = e => [[nodes.get(e.a).x, nodes.get(e.a).y], ...e.chain, [nodes.get(e.b).x, nodes.get(e.b).y]];
  const deg = id => edges.reduce((s, e) => s + (e.a === id) + (e.b === id), 0);
  const len = e => { const p = P(e); let s = 0; for (let i = 1; i < p.length; i++) s += norm(sub(p[i], p[i - 1])); return s; };
  for (let changed = true; changed;) { changed = false;
    // spurs: an end node on a short edge off a junction
    for (const e of edges.slice()) { const da = deg(e.a), db = deg(e.b); if (((da === 1 && db >= 3) || (db === 1 && da >= 3)) && len(e) < 16) { edges.splice(edges.indexOf(e), 1); nodes.delete(da === 1 ? e.a : e.b); changed = true; } }
    // contract very short edges between junctions
    for (const e of edges.slice()) { if (e.a !== e.b && deg(e.a) >= 3 && deg(e.b) >= 3 && len(e) < 7) { const A2 = nodes.get(e.a), B2 = nodes.get(e.b); A2.x = (A2.x + B2.x) / 2; A2.y = (A2.y + B2.y) / 2; A2.pix = A2.pix.concat(B2.pix);
        edges.splice(edges.indexOf(e), 1); for (const f of edges) { if (f.a === e.b) f.a = e.a; if (f.b === e.b) f.b = e.a; } nodes.delete(e.b); changed = true; break; } }
    // join the two edges at a degree-2 node
    for (const n of [...nodes.values()]) { const inc = edges.filter(e => e.a === n.id || e.b === n.id); if (inc.length !== 2 || inc[0] === inc[1]) continue;
      const [e1, e2] = inc; const c1 = e1.b === n.id ? e1.chain : e1.chain.slice().reverse(), c2 = e2.a === n.id ? e2.chain : e2.chain.slice().reverse();
      const a = e1.b === n.id ? e1.a : e1.b, b = e2.a === n.id ? e2.b : e2.a; edges = edges.filter(e => e !== e1 && e !== e2); edges.push({ a, b, chain: c1.concat([[n.x, n.y]], c2) }); nodes.delete(n.id); changed = true; break; }
    for (const n of [...nodes.values()]) if (deg(n.id) === 0) nodes.delete(n.id);
  }
  log('graph:', nodes.size, 'nodes,', edges.length, 'edges');

  // =================== strokes ===================
  const edgeDT = (() => { const notBody = new Uint8Array(W * H); for (let i = 0; i < W * H; i++) notBody[i] = comp[i] ? 1 : 0; return chamfer(notBody); })();
  const kindOf = n => { if (deg(n.id) !== 1) return 'junction';
    for (const k of ['L', 'R']) if (eyeRho(eyes[k], n.x, n.y) < 1.3) return 'eye' + k;
    if (edgeDT[Math.round(n.y) * W + Math.round(n.x)] < 10) return 'edge'; return 'free'; };
  // tangent pointing away from the node, measured past the junction wiggle
  const endDir = (e, side) => { let p = P(e); if (side === 1) p = p.slice().reverse(); const o = p[0]; let a = null, b = null, acc = 0;
    for (let i = 1; i < p.length; i++) { acc += norm(sub(p[i], p[i - 1])); if (!a && acc >= 5) a = p[i]; if (acc >= 16) { b = p[i]; break; } } if (!b) b = p[p.length - 1]; if (!a) a = o; return unit(sub(b, a)); };
  const pairOf = new Map(), throughAt = new Map();
  for (const n of nodes.values()) { if (deg(n.id) < 3) continue; const ends = [];
    edges.forEach((e, i) => { if (e.a === n.id) ends.push([i, 0]); if (e.b === n.id) ends.push([i, 1]); });
    const cand = []; for (let i = 0; i < ends.length; i++) for (let j = i + 1; j < ends.length; j++) { const d1 = endDir(edges[ends[i][0]], ends[i][1]), d2 = endDir(edges[ends[j][0]], ends[j][1]); cand.push([Math.acos(Math.max(-1, Math.min(1, -dot(d1, d2)))) * 180 / Math.PI, i, j]); }
    cand.sort((a, b) => a[0] - b[0]); const used = new Set();
    for (const [ang, i, j] of cand) { if (ang > 45 || used.has(i) || used.has(j)) continue; used.add(i); used.add(j); pairOf.set(ends[i].join(':'), ends[j]); pairOf.set(ends[j].join(':'), ends[i]); }
  }
  const usedE = new Set(), strokes = [];
  const walk = (ei, side) => { const parts = []; let e = ei, s = side; const start = { node: s === 0 ? edges[e].a : edges[e].b };
    while (!usedE.has(e)) { usedE.add(e); parts.push([e, s]); const far = s === 0 ? 1 : 0, key = e + ':' + far, pr = pairOf.get(key);
      if (!pr) return { parts, start, end: { node: far === 0 ? edges[e].a : edges[e].b }, closed: false };
      throughAt.set(far === 0 ? edges[e].a : edges[e].b, strokes.length); e = pr[0]; s = pr[1]; }
    return { parts, start, end: start, closed: true }; };
  edges.forEach((e, i) => { for (const s of [0, 1]) if (!usedE.has(i) && !pairOf.has(i + ':' + s)) strokes.push(walk(i, s)); });
  edges.forEach((e, i) => { if (!usedE.has(i)) strokes.push(walk(i, 0)); });
  for (const st of strokes) { let pts = [];
    st.parts.forEach(([e, s], k) => { let p = P(edges[e]); if (s === 1) p = p.slice().reverse(); if (k > 0) p = p.slice(1); pts = pts.concat(p); });
    // drop the junction wiggle near every junction the stroke passes or ends at
    const nodePts = new Set(); st.parts.forEach(([e]) => { for (const id of [edges[e].a, edges[e].b]) if (deg(id) >= 3) nodePts.add(id); });
    const js = [...nodePts].map(id => nodes.get(id));
    st.startKind = kindOf(nodes.get(st.start.node)); st.endKind = kindOf(nodes.get(st.end.node));
    pts = pts.filter((q, i) => i === 0 || i === pts.length - 1 || js.every(n => norm(sub(q, [n.x, n.y])) > 5));
    st.pts = st.closed ? resample(smoothLoop(pts, 3), 2, true) : resample(smoothOpen(pts, 4), 2, false); }
  const fitStroke = st => st.closed ? fitLoop(st.pts, .8, 60) : fitOpen(st.pts, .8);
  strokes.forEach(st => st.segs = fitStroke(st));
  // stems end exactly on the line they meet
  const closest = (segs, p) => { let best = null, bd = 1e9; for (const q of sampleSegs(segs, 60)) { const d = norm(sub(q, p)); if (d < bd) { bd = d; best = q; } } return best; };
  strokes.forEach((st, si) => { let changed = false;
    for (const [end, which] of [[st.start, 0], [st.end, 1]]) { if (st.closed) continue; const n = nodes.get(end.node); if (deg(n.id) < 3) continue;
      const ti = throughAt.get(n.id); if (ti === undefined || ti === si) continue; const q = closest(strokes[ti].segs, [n.x, n.y]);
      if (which === 0) { st.pts = [q].concat(st.pts.filter(p => norm(sub(p, [n.x, n.y])) > 6)); } else { st.pts = st.pts.filter(p => norm(sub(p, [n.x, n.y])) > 6).concat([q]); } changed = true; }
    if (changed) st.segs = fitStroke(st); });
  // width of each line: the distance between half-strength points across it
  strokes.forEach(st => { const ws = [], why = {}; const S = resample(sampleSegs(st.segs, 60), 1, false); st.why = why;
    for (let i = 6; i < S.length - 6; i += 2) { const p = S[i], t = unit(sub(S[i + 2], S[i - 2])), nrm = [-t[1], t[0]];
      if ([...nodes.values()].some(n => deg(n.id) >= 3 && Math.hypot(p[0] - n.x, p[1] - n.y) < 10)) { why.j = (why.j || 0) + 1; continue; } if (node && Math.hypot(p[0] - node.cx, p[1] - node.cy) < Math.max(node.rx, node.ry) + 8) continue;
      if (edgeDT[Math.round(p[1]) * W + Math.round(p[0])] < 7) { why.e = (why.e || 0) + 1; continue; }
      const prof = []; for (let o = -12; o <= 12; o += .25) prof.push(purple(p[0] + nrm[0] * o, p[1] + nrm[1] * o));
      const mid = prof.length >> 1; let pk = -1e9; for (let j = mid - 16; j <= mid + 16; j++) pk = Math.max(pk, prof[j]); if (pk < 100) { why.pk = (why.pk || 0) + 1; continue; }
      const half = (pk + 20) / 2; let lo = mid, hi = mid; while (lo > 0 && prof[lo] > half) lo--; while (hi < prof.length - 1 && prof[hi] > half) hi++;
      if (lo === 0 || hi === prof.length - 1) { why.b = (why.b || 0) + 1; continue; } const f1 = (half - prof[lo]) / (prof[lo + 1] - prof[lo]), f2 = (prof[hi - 1] - half) / (prof[hi - 1] - prof[hi]);
      ws.push(((hi - 1 + f2) - (lo + f1)) * .25); }
    ws.sort((a, b) => a - b); st.widthSamples = ws.length; st.w = ws.length ? Math.round(ws[ws.length >> 1] * 2) / 2 : null; });
  const known = strokes.filter(s => s.w).map(s => s.w).sort((a, b) => a - b), medW = known[known.length >> 1];
  strokes.forEach(st => { st.wMeasured = st.w; if (!st.w) st.w = medW; st.w = Math.min(12, Math.max(8, st.w)); });
  log('widths', strokes.map(s => `${s.startKind}-${s.endKind}:${s.wMeasured}(${s.widthSamples} ${JSON.stringify(s.why)} n${s.segs.length})→${s.w}`).join(' '));

  // =================== completion under the eyes, and past the silhouette ===================
  // an end's heading, from an 18 px chord so a hook in the skeleton's last pixels cannot turn it
  const endInfo = (st, which) => { let S = resample(sampleSegs(st.segs, 60), 1, false); if (which === 0) S = S.slice().reverse();
    const p = S[S.length - 1], q = S[Math.max(0, S.length - 19)]; return { p, c: unit(sub(p, q)) }; };
  const inferred = [];
  const arches = strokes.filter(st => !st.closed && ((st.startKind === 'eyeL' && st.endKind === 'eyeR') || (st.startKind === 'eyeR' && st.endKind === 'eyeL')));
  log('arches', arches.length, 'strokes', strokes.length, strokes.map(s => `${s.startKind}-${s.endKind}:${s.w}`).join(' '));
  let ringDense = [];
  if (arches.length === 2) {
    const endAt = (st, k) => endInfo(st, st.startKind === 'eye' + k ? 0 : 1);
    for (const k of ['L', 'R']) { const a = endAt(arches[0], k), b = endAt(arches[1], k);
      const ch = norm(sub(b.p, a.p)), phi = Math.acos(Math.max(-1, Math.min(1, dot(a.c, mul(b.c, -1))))), Rr = phi > 1e-3 ? ch / (2 * Math.sin(phi / 2)) : 1e9, h = phi > 1e-3 ? 4 / 3 * Math.tan(phi / 4) * Rr : ch / 3;
      const seg = [a.p, add(a.p, mul(a.c, h)), add(b.p, mul(b.c, h)), b.p];
      inferred.push({ segs: [seg], w: (arches[0].w + arches[1].w) / 2, kind: 'ring', eye: k }); }
    ringDense = []; for (const s of arches) { ringDense.push(...sampleSegs(s.segs, 40)); ringDense.push([NaN, NaN]); } for (const s of inferred) { ringDense.push(...sampleSegs(s.segs, 60)); ringDense.push([NaN, NaN]); }
  }
  // first crossing of the ray p + t·c (t > 1) with the ring polyline
  const hitRing = (p, c) => { let best = null, bt = 90;
    for (let i = 0; i + 1 < ringDense.length; i++) { const a = ringDense[i], b = ringDense[i + 1], e = sub(b, a), den = c[0] * e[1] - c[1] * e[0]; if (Math.abs(den) < 1e-9) continue;
      const w = sub(a, p), t = (w[0] * e[1] - w[1] * e[0]) / den, u = (w[0] * c[1] - w[1] * c[0]) / den; if (t > 1 && t < bt && u >= 0 && u <= 1) { bt = t; best = add(p, mul(c, t)); } }
    return best; };
  strokes.forEach(st => { if (st.closed) return;
    for (const which of [0, 1]) { const kind = which === 0 ? st.startKind : st.endKind, e = endInfo(st, which);
      if (kind === 'edge') { const q = add(e.p, mul(e.c, 1.5 * st.w)); if (which === 0) st.segs.unshift(lineSeg(q, e.p)); else st.segs.push(lineSeg(e.p, q)); }
      if ((kind === 'eyeL' || kind === 'eyeR') && !arches.includes(st)) { const k = kind.slice(3); let nearQ = null, nd = 1e9; for (const r of ringDense) { if (isNaN(r[0])) continue; const d = norm(sub(r, e.p)); if (d < nd) { nd = d; nearQ = r; } }
        const hq = nd < 1.6 * st.w ? nearQ : hitRing(e.p, e.c), q = hq || [eyes[k].cx, eyes[k].cy]; log('spoke into eye', k, 'from', e.p.map(Math.round), 'dir', e.c.map(v => v.toFixed(2)), hq ? 'meets ring at ' + hq.map(Math.round) : 'no ring hit, to centre');
        inferred.push({ segs: [lineSeg(e.p, q)], w: st.w, kind: 'spoke', eye: k }); } } });

  // =================== measure ===================
  const refSk = []; for (const e of edges) for (const p of e.chain) refSk.push(p);
  const mine = strokes.flatMap(s => sampleSegs(s.segs, 30).filter(p => edgeDT[Math.round(p[1]) * W + Math.round(p[0])] > 3));
  const grid = new Map(), gk = (x, y) => (x >> 3) + ',' + (y >> 3); for (const p of mine) { const k = gk(p[0], p[1]); (grid.get(k) || grid.set(k, []).get(k)).push(p); }
  const nearMine = p => { let m = 1e9; for (let dx = -2; dx <= 2; dx++) for (let dy = -2; dy <= 2; dy++) for (const q of grid.get(((p[0] >> 3) + dx) + ',' + ((p[1] >> 3) + dy)) || []) m = Math.min(m, norm(sub(p, q))); return m; };
  const cov = refSk.map(nearMine).filter(d => d < 1e8); cov.sort((a, b) => a - b);
  const veinDist = { mean: cov.reduce((s, d) => s + d, 0) / cov.length, p95: cov[Math.floor(cov.length * .95)], max: cov[cov.length - 1] };
  log('vein centrelines vs reference skeleton: mean', veinDist.mean.toFixed(2), 'p95', veinDist.p95.toFixed(2), 'max', veinDist.max.toFixed(2));

  const colours = { body: '#362e42', vein: '#8747c2', eye: '#fbf539' };
  const D = (segs, close) => { let d = `M${r1(segs[0][0][0])},${r1(segs[0][0][1])}`; for (const s of segs) d += `C${r1(s[1][0])},${r1(s[1][1])} ${r1(s[2][0])},${r1(s[2][1])} ${r1(s[3][0])},${r1(s[3][1])}`; return d + (close ? 'Z' : ''); };
  const eyePath = q => { const pts = []; for (let i = 0; i < 120; i++) { const th = i / 120 * Math.PI * 2, c = Math.cos(th), s = Math.sin(th); const x = q.rx * Math.sign(c) * Math.pow(Math.abs(c), 2 / q.n), y = q.ry * Math.sign(s) * Math.pow(Math.abs(s), 2 / q.n); const t = q.tilt * Math.PI / 180; pts.push([q.cx + x * Math.cos(t) - y * Math.sin(t), q.cy + x * Math.sin(t) + y * Math.cos(t)]); } return 'M' + pts.map(p => p.map(r1).join(',')).join('L') + 'Z'; };
  const bodyD = D(bodySegs, true);
  const svg = ({ eyesOn = true, mark = false, op = 1 } = {}) => `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><defs><clipPath id="c"><path d="${bodyD}"/></clipPath></defs><g opacity="${op}"><path d="${bodyD}" fill="${colours.body}"/><g clip-path="url(#c)" fill="none" stroke="${colours.vein}" stroke-linecap="round" stroke-linejoin="round">${strokes.map(s => `<path d="${D(s.segs, s.closed)}" stroke-width="${s.w}"/>`).join('')}${inferred.map(s => `<path d="${D(s.segs, false)}" stroke-width="${s.w}"${mark ? ' stroke="#ff8a00"' : ''}/>`).join('')}${node ? `<ellipse cx="${r1(node.cx)}" cy="${r1(node.cy)}" rx="${r1(node.rx)}" ry="${r1(node.ry)}" transform="rotate(${r1(node.tilt)} ${r1(node.cx)} ${r1(node.cy)})" fill="${colours.vein}" stroke="none"/>` : ''}</g>${eyesOn ? ['L', 'R'].map(k => `<path d="${eyePath(eyes[k])}" fill="${colours.eye}"/>`).join('') : ''}</g></svg>`;
  fs.writeFileSync(path.join(OUT, 'v3.svg'), svg());
  const renderPx = async s => Buffer.from(await page.evaluate(async s => { const img = new Image(); img.src = 'data:image/svg+xml;base64,' + btoa(s); await img.decode(); const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0); const a = x.getImageData(0, 0, c.width, c.height).data; let t = ''; for (let i = 0; i < a.length; i += 8192) t += String.fromCharCode.apply(null, a.subarray(i, i + 8192)); return btoa(t); }, s), 'base64');
  const rp = await renderPx(svg());
  const cls = i => rp[i * 4 + 3] < 128 ? 'bg' : (rp[i * 4 + 1] > 150 && rp[i * 4] > 150 ? 'eye' : (rp[i * 4 + 2] - rp[i * 4 + 1] > 50 ? 'vein' : 'body'));
  const ref = i => !comp[i] ? 'bg' : (G(i) >= 200 && R(i) > 200 ? 'eye' : (Bl(i) - G(i) > 73 ? 'vein' : 'body'));
  const st2 = { sil: [0, 0], eye: [0, 0], vein: [0, 0] };
  for (let i = 0; i < W * H; i++) { if (((i / W) | 0) > cutY) continue; const a = ref(i), m = cls(i);
    if (a !== 'bg' && m !== 'bg') st2.sil[0]++; if (a !== 'bg' || m !== 'bg') st2.sil[1]++;
    // eye overlap against the plateau; vein overlap where no eye covers (the glow ring counts as neither)
    const glow = G(i) < 200 && R(i) > 140 && comp[i] && Bl(i) - G(i) < 0;
    if (a === 'eye' && m === 'eye') st2.eye[0]++; if ((a === 'eye' || m === 'eye')) st2.eye[1]++;
    if (!glow) { if (a === 'vein' && m === 'vein') st2.vein[0]++; if (a === 'vein' || m === 'vein') st2.vein[1]++; } }
  const fit = { silhouetteIoU: +(st2.sil[0] / st2.sil[1]).toFixed(4), silhouetteMaxDevPx: +silDev.max.toFixed(2), silhouetteRmsDevPx: +silDev.rms.toFixed(2), silhouetteInflections: infl, silhouetteCurvatureExtrema: wig,
    eyesIoU: +(st2.eye[0] / st2.eye[1]).toFixed(4), eyeEdgeRmsPx: { L: +eyes.L.edgeRms.toFixed(2), R: +eyes.R.edgeRms.toFixed(2) },
    veinsIoU: +(st2.vein[0] / st2.vein[1]).toFixed(4), veinCentreMeanPx: +veinDist.mean.toFixed(2), veinCentreP95Px: +veinDist.p95.toFixed(2), veinCentreMaxPx: +veinDist.max.toFixed(2), comparedAbove: r1(cutY) };
  log(JSON.stringify(fit));
  const pack = segs => segs.map(s => s.map(p => [r1(p[0]), r1(p[1])]));
  const geo = { W, H, colours, fit, chin: { low: r1(Math.max(...sampleSegs([chin], 50).map(p => p[1]))), mid: bq(chin, .5).map(r1), cutY: r1(cutY) },
    bodySegs: pack(bodySegs), eyes: Object.fromEntries(Object.entries(eyes).map(([k, e]) => [k, { cx: r1(e.cx), cy: r1(e.cy), rx: +e.rx.toFixed(2), ry: +e.ry.toFixed(2), tilt: r1(e.tilt), n: +e.n.toFixed(2) }])),
    node: node && { cx: r1(node.cx), cy: r1(node.cy), rx: r1(node.rx), ry: r1(node.ry), tilt: r1(node.tilt) },
    strokes: strokes.map(s => ({ segs: pack(s.segs), closed: s.closed, w: s.w, ends: [s.startKind, s.endKind] })),
    inferred: inferred.map(s => ({ segs: pack(s.segs), w: s.w, kind: s.kind, eye: s.eye })) };
  fs.writeFileSync(path.join(OUT, 'geometry3.json'), JSON.stringify(geo));

  // =================== pictures ===================
  const u = s => 'data:image/svg+xml;base64,' + Buffer.from(s).toString('base64'), refURL = 'data:image/png;base64,' + b64;
  await page.setViewportSize({ width: W * 2 + 10, height: H * 2 + 10 });
  await page.setContent(`<body style="margin:0;background:#888;display:grid;grid-template-columns:${W}px ${W}px;gap:10px"><img src="${refURL}"><img src="${u(svg())}" style="background:#6b9887"><div style="position:relative"><img src="${refURL}" style="position:absolute"><img src="${u(svg({ op: .5 }))}" style="position:absolute"></div><img src="${u(svg({ eyesOn: false, mark: true }))}" style="background:#6b9887"></body>`);
  await page.waitForTimeout(300); await page.screenshot({ path: path.join(OUT, 'v3-compare.png') });
  await page.setViewportSize({ width: W * 2, height: H * 2 });
  await page.setContent(`<body style="margin:0;background:#6b9887"><img src="${u(svg({ eyesOn: false, mark: true }))}" style="width:${W * 2}px"></body>`);
  await page.waitForTimeout(200); await page.screenshot({ path: path.join(OUT, 'v3-net.png') });
  await browser.close();
})();
