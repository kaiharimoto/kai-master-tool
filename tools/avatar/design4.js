// v4: the design, simplified. Reads trace/geometry3.json (the fitted measurements) and draws the head
// and its net as clean primitives: an elliptical head that sweeps into the traced tail, a mask ring that is
// a circle with a flattened bottom, straight spokes, and two matching cheek ovals.
// node design4.js  -> trace/geometry4.json, trace/v4-*.png
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const { add, sub, mul, dot, norm, unit, bq, fitOpen, fitCubic, r1 } = require('./lib.js');
const fitCubicRun = (P, tl, tr) => fitCubic(P, tl, tr, .02);
const OUT = path.join(__dirname, 'trace'), g3 = require('./trace/geometry3.json');
const REFPNG = process.env.AVATAR_REF || path.join(__dirname, 'reference.png'); // the screenshot kai drew from: not committed
const arg = (k, d) => { const a = process.argv.find(s => s.startsWith('--' + k + '=')); return a ? +a.split('=')[1] : d; };
const log = (...a) => console.log(...a);
function solve(A, b) { const n = b.length, M = A.map((r, i) => r.concat([b[i]]));
  for (let c = 0; c < n; c++) { let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; [M[c], M[p]] = [M[p], M[c]];
    for (let r = c + 1; r < n; r++) { const f = M[r][c] / M[c][c]; if (!f) continue; for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; } }
  const x = new Array(n).fill(0); for (let r = n - 1; r >= 0; r--) { let s = M[r][n]; for (let k = r + 1; k < n; k++) s -= M[r][k] * x[k]; x[r] = s / M[r][r]; } return x; }
function nelder(f, x0, step, iters = 4000) { const n = x0.length; let S = [x0.slice()]; for (let i = 0; i < n; i++) { const x = x0.slice(); x[i] += step[i]; S.push(x); } let F = S.map(f);
  for (let it = 0; it < iters; it++) { const ix = [...Array(n + 1).keys()].sort((a, b) => F[a] - F[b]); S = ix.map(i => S[i]); F = ix.map(i => F[i]);
    const c = new Array(n).fill(0); for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) c[j] += S[i][j] / n;
    const xr = c.map((v, j) => v + (v - S[n][j])), fr = f(xr);
    if (fr < F[0]) { const xe = c.map((v, j) => v + 2 * (v - S[n][j])), fe = f(xe); if (fe < fr) { S[n] = xe; F[n] = fe; } else { S[n] = xr; F[n] = fr; } }
    else if (fr < F[n - 1]) { S[n] = xr; F[n] = fr; }
    else { const xc = c.map((v, j) => v + .5 * (S[n][j] - v)), fc = f(xc); if (fc < F[n]) { S[n] = xc; F[n] = fc; } else for (let i = 1; i <= n; i++) { S[i] = S[i].map((v, j) => S[0][j] + .5 * (v - S[0][j])); F[i] = f(S[i]); } } }
  return S[0]; }
const sampleSegs = (segs, per = 24) => { const o = []; for (const s of segs) for (let i = 0; i < per; i++) o.push(bq(s, i / per)); o.push(segs[segs.length - 1][3]); return o; };
function resample(P, step) { const out = [P[0]]; let acc = 0; for (let i = 1; i < P.length; i++) { let a = P[i - 1]; const b = P[i]; let d = norm(sub(b, a)); while (acc + d >= step) { const t = (step - acc) / d; a = add(a, mul(sub(b, a), t)); out.push(a); d = norm(sub(b, a)); acc = 0; } acc += d; } return out; }
const lineSeg = (a, b) => [a, add(a, mul(sub(b, a), 1 / 3)), add(a, mul(sub(b, a), 2 / 3)), b];
const nearest = (P, p) => { let best = P[0], bd = 1e9; for (const q of P) { const d = norm(sub(q, p)); if (d < bd) { bd = d; best = q; } } return [best, bd]; };
// first crossing of the ray p + t·c (t > 0) with a closed polyline
const rayHit = (P, p, c) => { let best = null, bt = 1e9; for (let i = 0; i < P.length; i++) { const a = P[i], b = P[(i + 1) % P.length], e = sub(b, a), den = c[0] * e[1] - c[1] * e[0]; if (Math.abs(den) < 1e-9) continue;
  const w = sub(a, p), t = (w[0] * e[1] - w[1] * e[0]) / den, u = (w[0] * c[1] - w[1] * c[0]) / den; if (t > 0 && t < bt && u >= 0 && u <= 1) { bt = t; best = add(p, mul(c, t)); } } return best; };

// =================== 1. the head: an ellipse on the round side, the traced tail on the other ===================
const trace = sampleSegs(g3.bodySegs, 16);
const ellDist = (q, p) => { const [cx, cy, a, b, t] = q, r = t * Math.PI / 180, dx = p[0] - cx, dy = p[1] - cy, u = dx * Math.cos(r) + dy * Math.sin(r), v = -dx * Math.sin(r) + dy * Math.cos(r), rho = Math.hypot(u / a, v / b); return (rho - 1) * Math.hypot(u, v) / rho; };
const leftPts = trace.filter(p => p[0] < 200 && p[1] > 110 && p[1] < 300);
const E = nelder(x => leftPts.reduce((s, p) => s + ellDist(x, p) ** 2, 0), [245, 221, 146, 119, 7], [10, 10, 10, 10, 5]);
const eRms = Math.sqrt(leftPts.reduce((s, p) => s + ellDist(E, p) ** 2, 0) / leftPts.length);
log('head ellipse: centre', E[0].toFixed(1), E[1].toFixed(1), 'axes', E[2].toFixed(1), E[3].toFixed(1), 'tilt', E[4].toFixed(1), 'rms on the left side', eRms.toFixed(2));
const ellPt = th => { const r = E[4] * Math.PI / 180, u = E[2] * Math.cos(th), v = E[3] * Math.sin(th); return [E[0] + u * Math.cos(r) - v * Math.sin(r), E[1] + u * Math.sin(r) + v * Math.cos(r)]; };
// the traced part kept: the top edge from x≈205, the tail, and the right edge down to where the neck begins
const xTop = arg('xtop', 208), yRight = arg('yright', 200);
let iStart = -1, iEnd = -1; // walk the traced loop in its own order
trace.forEach((p, i) => { if (iStart < 0 && p[1] < 115 && Math.abs(p[0] - xTop) < 3) iStart = i; });
const n0 = trace.length; const kept = []; for (let k = 0; k < n0; k++) { const p = trace[(iStart + k) % n0]; if (p[0] > 250 && p[1] > yRight) break; kept.push(p); }
// the ellipse part: from the bottom-right of the ellipse, round the bottom and left side, up to the top edge
const thOf = p => { const r = E[4] * Math.PI / 180, dx = p[0] - E[0], dy = p[1] - E[1]; return Math.atan2((-dx * Math.sin(r) + dy * Math.cos(r)) / E[3], (dx * Math.cos(r) + dy * Math.sin(r)) / E[2]); };
let th0 = thOf(kept[0]); // top-left join, on the ellipse
const thBot = arg('thbot', 50) * Math.PI / 180; // where the ellipse hands over to the sweep (angle from the ellipse's +x axis, y down)
const ell = []; for (let th = thBot; ; th += 0.01) { let t2 = th; if (t2 > th0 + 2 * Math.PI) break; const tt = ((t2 - th0) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI); if (tt < .01) break; ell.push(ellPt(t2)); if (ell.length > 800) break; }
const ellArc = resample(ell, 1.5);
// ordered loop: kept (top edge → tail → right edge) then a gap (the sweep) then the ellipse arc back to the top
// the jaw: an Euler-spiral blend. Its bend k(s) runs from the traced tail's bend to the ellipse's, as
// k(u) = k0 + (k1 - k0)u + a·u(1-u) + b·u²(1-u) over its length L; L, a and b are solved so it lands on the ellipse
// point with the ellipse's heading. Join points are searched for the blend whose bend rises most evenly.
const headingOf = v => Math.atan2(v[1], v[0]);
const bendOf = (P, i, span = 6) => { const a = P[Math.max(0, i - span)], c = P[Math.min(P.length - 1, i + span)], t1 = unit(sub(P[i], a)), t2 = unit(sub(c, P[i]));
  return Math.atan2(t1[0] * t2[1] - t1[1] * t2[0], dot(t1, t2)) / ((norm(sub(P[i], a)) + norm(sub(c, P[i]))) / 2); };
function spiral(P0, h0, k0, k1, L, a, b, n = 240, alpha = 1) { const pts = [P0]; let x = P0[0], y = P0[1], h = h0; const ds = L / n;
  for (let i = 0; i < n; i++) { const u = (i + .5) / n, k = k0 + (k1 - k0) * Math.pow(u, alpha) + a * u * (1 - u) + b * u * u * (1 - u); h += k * ds / 2; x += Math.cos(h) * ds; y += Math.sin(h) * ds; h += k * ds / 2; pts.push([x, y]); }
  return { pts, h }; }
const angDiff = (x, y) => Math.atan2(Math.sin(x - y), Math.cos(x - y));
// pure clothoid: the bend is a straight ramp from the tail's to the ellipse's, so it can neither overshoot nor dent.
// For a hand-over height on the tail, solve the ramp's length and where on the ellipse it lands tangentially.
const ellBend = th => { const r = []; for (let t = th - .06; t <= th + .0601; t += .006) r.push(ellPt(t)); return bendOf(r, 10, 5); };
function blendFor(yKeep) {
  const kk = []; for (let k = 0; k < n0; k++) { const p = trace[(iStart + k) % n0]; if (p[0] > 250 && p[1] > yKeep) break; kk.push(p); }
  const KR = resample(kk, 1.5), P0 = KR[KR.length - 1], h0 = headingOf(sub(P0, KR[KR.length - 7])), k0 = Math.max(0, bendOf(KR, KR.length - 8, 5));
  const cost = q => { const [L, th, la] = q; if (L < 10 || L > 500 || Math.abs(la) > 1.6) return 1e9; const k1 = ellBend(th), r = spiral(P0, h0, k0, k1, L, 0, 0, 160, Math.exp(la)), e = r.pts[r.pts.length - 1], E1 = ellPt(th), h1 = headingOf(sub(ellPt(th + .01), E1));
    return (e[0] - E1[0]) ** 2 + (e[1] - E1[1]) ** 2 + (60 * angDiff(r.h, h1)) ** 2; };
  let q = null, best = 1e18; for (const tg of [.9, 1.3, 1.7, 2.1]) for (const L0 of [120, 200, 280]) for (const la0 of [-.7, 0, .7]) { const qq = nelder(cost, [L0, tg, la0], [15, .1, .3], 700), c = cost(qq); if (c < best) { best = c; q = qq; } }
  const k1 = ellBend(q[1]), r = spiral(P0, h0, k0, k1, q[0], 0, 0, 240, Math.exp(q[2]));
  return { yKeep, thDeg: q[1] * 180 / Math.PI, kept: kk, KR, pts: r.pts, miss: Math.sqrt(best), L: q[0], alpha: Math.exp(q[2]), k0, k1 }; }
let jawBest = null;
for (let y = arg('ylo', 140); y <= arg('yhi', 270); y += 5) { const r = blendFor(y); log('  hand-over y', y, 'lands at', r.thDeg.toFixed(0) + '°', 'miss', r.miss.toFixed(2), 'α', r.alpha.toFixed(2)); if (r.thDeg < 20 || r.thDeg > 160 || r.miss > .6) continue; r.score = Math.abs(Math.log(r.alpha)); if (!jawBest || r.score < jawBest.score) jawBest = r; }
if (!jawBest) throw new Error('no landing found');
log('jaw: tail kept to y', jawBest.yKeep, 'lands on the ellipse at', jawBest.thDeg.toFixed(1) + '°', 'length', jawBest.L.toFixed(1), 'miss', jawBest.miss.toFixed(2), 'ramp shape α', jawBest.alpha.toFixed(2), 'bend ramps', (jawBest.k0 * 180 / Math.PI * 4).toFixed(2), '→', (jawBest.k1 * 180 / Math.PI * 4).toFixed(2), 'deg/4px');
kept.length = 0; kept.push(...jawBest.kept);
const keptR = jawBest.KR, P2 = keptR[keptR.length - 1];
const ellJ = []; for (let th = jawBest.thDeg * Math.PI / 180; ; th += 0.01) { const tt = ((th - th0) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI); if (tt < .01 || ellJ.length > 800) break; ellJ.push(ellPt(th)); }
ellArc.length = 0; ellArc.push(...resample(ellJ, 1.5));
const jawPts = resample(jawBest.pts, 1.5);
const loopPts = keptR.concat(jawPts.slice(1, -1), ellArc);
const isData = loopPts.map(() => 1);
// penalised periodic B-spline: the gap between the right edge and the ellipse is bridged by the smoothness term alone
let per = 0; for (let i = 0; i < loopPts.length; i++) per += norm(sub(loopPts[(i + 1) % loopPts.length], loopPts[i]));
const M = Math.round(per / arg('spacing', 10)), lam = arg('lambda', 3);
const us = []; { let acc = 0; for (let i = 0; i < loopPts.length; i++) { us.push(acc / per * M); acc += norm(sub(loopPts[(i + 1) % loopPts.length], loopPts[i])); } }
const basis = u => { const k = Math.floor(u), t = u - k; return [k, [(1 - t) ** 3 / 6, (3 * t ** 3 - 6 * t * t + 4) / 6, (-3 * t ** 3 + 3 * t * t + 3 * t + 1) / 6, t ** 3 / 6]]; };
const A = Array.from({ length: M }, () => new Array(M).fill(0)), bx = new Array(M).fill(0), by = new Array(M).fill(0);
loopPts.forEach((p, i) => { const w = (p[1] < 32 && p[0] > 395) ? 12 : 1; const [k, bs] = basis(us[i]); for (let a = 0; a < 4; a++) { const ia = (k + a) % M; bx[ia] += w * bs[a] * p[0]; by[ia] += w * bs[a] * p[1]; for (let c = 0; c < 4; c++) A[ia][(k + c) % M] += w * bs[a] * bs[c]; } });
// third differences: penalising the change of curvature keeps the bridge a fair sweep with no dent
for (let j = 0; j < M; j++) { const idx = [j - 1, j, j + 1, j + 2].map(v => (v + M) % M), cf = [-1, 3, -3, 1]; for (let a = 0; a < 4; a++) for (let c = 0; c < 4; c++) A[idx[a]][idx[c]] += lam * cf[a] * cf[c]; }
const cxs = solve(A.map(r => r.slice()), bx), cys = solve(A.map(r => r.slice()), by);
const cp = j => [cxs[(j + M) % M], cys[(j + M) % M]];
const bodySegs = []; for (let k = 0; k < M; k++) { const c0 = cp(k), c1 = cp(k + 1), c2 = cp(k + 2), c3 = cp(k + 3); bodySegs.push([mul(add(add(c0, mul(c1, 4)), c2), 1 / 6), mul(add(mul(c1, 2), c2), 1 / 3), mul(add(c1, mul(c2, 2)), 1 / 3), mul(add(add(c1, mul(c2, 4)), c3), 1 / 6)]); }
const body = sampleSegs(bodySegs, 30);
const keptDev = kept.filter(p => p[1] > 20).map(p => nearest(body, p)[1]); // the tip is held separately
log('head: spans', M, 'tail and top kept within', Math.max(...keptDev).toFixed(2), 'px (mean', (keptDev.reduce((s, d) => s + d, 0) / keptDev.length).toFixed(2) + ')',
  'ellipse kept within', Math.max(...ellArc.map(p => nearest(body, p)[1])).toFixed(2), 'px', 'bottom at y', Math.max(...body.map(p => p[1])).toFixed(1));
// convexity of the base: the curvature should never change sign from the right edge round to the top-left
{ const S = resample(body, 2), n = S.length, K = []; for (let i = 0; i < n; i++) { const a = S[(i - 2 + n) % n], b = S[i], c = S[(i + 2) % n], t1 = unit(sub(b, a)), t2 = unit(sub(c, b)); K.push(Math.atan2(t1[0] * t2[1] - t1[1] * t2[0], dot(t1, t2))); }
  let infl = 0, prev = 0; for (const k of K) if (Math.abs(k) > .004) { if (prev && Math.sign(k) !== Math.sign(prev)) infl++; prev = k; } log('head inflections', infl);
  // the jaw, from where the traced tail ends to the bottom of the head: its bend should rise smoothly, with no bumps
  let i0 = 0, i1 = 0, d0 = 1e9, d1 = 1e9; S.forEach((p, i) => { const a = norm(sub(p, P2)), b = norm(sub(p, ellPt(Math.PI / 2))); if (a < d0) { d0 = a; i0 = i; } if (b < d1) { d1 = b; i1 = i; } });
  const J = []; for (let i = i0; i !== i1; i = (i + 1) % n) J.push(K[i]); const Js = J.map((_, i) => (J[Math.max(0, i - 2)] + J[Math.max(0, i - 1)] + J[i] + J[Math.min(J.length - 1, i + 1)] + J[Math.min(J.length - 1, i + 2)]) / 5);
  let ext = 0; for (let i = 1; i < Js.length - 1; i++) if ((Js[i] > Js[i - 1] && Js[i] > Js[i + 1]) || (Js[i] < Js[i - 1] && Js[i] < Js[i + 1])) ext++;
  log('jaw curvature (deg per 4 px), tail end to bottom:', Js.filter((_, i) => i % 4 === 0).map(v => (v * 180 / Math.PI).toFixed(1)).join(' '), '| turning points', ext); }

// =================== 2. the mask ring: a circle with its bottom flattened, like a mouth ===================
const arch = n => sampleSegs(g3.strokes[n].segs, 40);
const archPts = arch(12).concat(arch(15)).filter(p => !g3.eyes || (Math.hypot(p[0] - g3.eyes.L.cx, p[1] - g3.eyes.L.cy) > 30 && Math.hypot(p[0] - g3.eyes.R.cx, p[1] - g3.eyes.R.cy) > 30));
// upper half: a circle; lower half: a superellipse with the same width, squarer (m > 2) so its bottom runs flat
const RING_N = 720;
function dShape([cx, cy, R, Rb, m]) { const pts = []; // clockwise in screen space from the right side
  for (let i = 0; i < RING_N; i++) { const th = i / RING_N * 2 * Math.PI, c = Math.cos(th), s = Math.sin(th);
    if (s < 0) pts.push([cx + R * c, cy + R * s]); else pts.push([cx + R * Math.sign(c) * Math.pow(Math.abs(c), 2 / m), cy + Rb * Math.pow(s, 2 / m)]); }
  return pts; }
const MFIX = arg('m', 2.6); // kai reads the ring as a circle with a flattened, mouth-like bottom: hold the squareness when asked
const dCost = q => { if (MFIX) q[4] = MFIX; if (q[4] < 2 || q[4] > 6 || q[2] < 40 || q[3] < 40) return 1e9; const P = dShape(q); return archPts.reduce((s, p) => s + nearest(P, p)[1] ** 2, 0); };
// the top arch is a true circle: fit it alone; the bottom keeps that width and the reference's depth, and only
// its squareness is the design's choice (kai: a circle whose bottom straightens, like a mouth)
const topPts = sampleSegs(g3.strokes[12].segs, 60).filter(p => Math.hypot(p[0] - g3.eyes.L.cx, p[1] - g3.eyes.L.cy) > 30 && Math.hypot(p[0] - g3.eyes.R.cx, p[1] - g3.eyes.R.cy) > 30);
const botPts = sampleSegs(g3.strokes[15].segs, 60).filter(p => Math.hypot(p[0] - g3.eyes.L.cx, p[1] - g3.eyes.L.cy) > 30 && Math.hypot(p[0] - g3.eyes.R.cx, p[1] - g3.eyes.R.cy) > 30);
const C0 = nelder(q => topPts.reduce((s, p) => s + (Math.hypot(p[0] - q[0], p[1] - q[1]) - q[2]) ** 2, 0), [226, 208, 77], [4, 4, 4]);
// the ring as designed: a circle on top, a squarer superellipse below with the same width, the whole shape
// allowed a small tilt for the screenshot's turn. Squareness is the design's choice (kai: the bottom straightens like a mouth).
const mRing = MFIX || 2.6;
function ringShape([cx, cy, R, Rb, tl]) { const r = tl * Math.PI / 180, P = dShape([0, 0, R, Rb, mRing]);
  return P.map(([x, y]) => [cx + x * Math.cos(r) - y * Math.sin(r), cy + x * Math.sin(r) + y * Math.cos(r)]); }
const allArch = topPts.concat(botPts);
// the ring shares the head's tilt, so the face turns with the head as one design
const Rq5 = nelder(q => { q[4] = E[4]; const P = ringShape(q); return allArch.reduce((s, p) => s + nearest(P, p)[1] ** 2, 0); }, [228, 208, 77, 78, E[4]], [3, 3, 3, 3, 0], 1200); Rq5[4] = E[4];
const Rq = Rq5;
const dShapeU = () => ringShape(Rq5);
const ringPoly = dShapeU(), ringRms = Math.sqrt(archPts.reduce((s, p) => s + nearest(ringPoly, p)[1] ** 2, 0) / archPts.length);
log('ring: centre', Rq[0].toFixed(1), Rq[1].toFixed(1), 'radius', Rq[2].toFixed(1), 'bottom depth', Rq[3].toFixed(1), 'tilt', Rq[4].toFixed(1), 'squareness', mRing, 'rms', ringRms.toFixed(2), 'top rms', Math.sqrt(topPts.reduce((s, p) => s + nearest(ringPoly, p)[1] ** 2, 0) / topPts.length).toFixed(2), 'bottom rms', Math.sqrt(botPts.reduce((s, p) => s + nearest(ringPoly, p)[1] ** 2, 0) / botPts.length).toFixed(2));
// as cubics: the dense outline fitted in four quarters (right, top, left, bottom), tangents matched at the joins
const ringSegs = (() => { const P = ringPoly, n = P.length, q = [0, n * 3 / 4, n / 2, n / 4].map(Math.round), out = [];
  // dShape runs from the right side downward (θ increasing, y down); walk it backwards so the ring goes right → top → left → bottom
  const rev = P.slice().reverse(); const idx = i => (n - 1 - i + n) % n; const starts = q.map(idx).sort((a, b) => a - b);
  for (let k = 0; k < 4; k++) { const a = starts[k], b = starts[(k + 1) % 4]; const run = []; for (let i = a; ; i = (i + 1) % n) { run.push(rev[i]); if (i === b) break; }
    const tan = i => unit(sub(rev[(i + 2) % n], rev[(i - 2 + n) % n]));
    out.push(...fitCubicRun(run, tan(a), mul(tan(b), -1))); }
  return out; })();

// =================== 3. the rest of the net, simplified ===================
const S3 = i => sampleSegs(g3.strokes[i].segs, 40);
const nodeR = g3.node, nodeL = { cx: arg('nodelx', 96), cy: nodeR.cy, rx: nodeR.rx, ry: nodeR.ry, tilt: 0 };
const W1 = 11, W2 = 9.5; // two line weights: the main lines and the fine ones
const lines = [];
const add1 = (name, segs, w) => lines.push({ name, segs, w });
const fit1 = (P, n = 1) => { if (n === 1) return fitOpen(P, 1e9); const k = Math.floor(P.length / 2); return fitOpen(P.slice(0, k + 1), 1e9).concat(fitOpen(P.slice(k), 1e9)); };
const ringTop = nearest(ringPoly, [238, 132])[0];
// spine: from the top of the ring, along the tail's upper edge, to the tip
{ let P = S3(9).slice().reverse(); P = P.filter(p => norm(sub(p, ringTop)) > 6); P = [ringTop].concat(P); add1('spine', fit1(resample(P, 2), 2), W1); }
const spine = sampleSegs(lines[0].segs, 60);
// right vein: from the spine, down through the right node, to the lower edge
{ const P = S3(13); const s = nearest(spine, P[0])[0]; add1('right vein', fit1([s].concat(resample(P, 2).filter(p => norm(sub(p, s)) > 6))), W1); }
// tail rim and its diagonal, and the link from the right node to the rim
{ const P = S3(10); const s = nearest(spine, P[0])[0]; add1('tail rim', fit1([s].concat(resample(P, 2).filter(p => norm(sub(p, s)) > 6)), 2), W2); }
const rim = sampleSegs(lines[2].segs, 60);
{ const P = S3(11); const a = nearest(rim, P[0])[0], b = nearest(spine, P[P.length - 1])[0]; add1('tail rib', fit1([a].concat(resample(P, 2).filter(p => norm(sub(p, a)) > 6 && norm(sub(p, b)) > 6), [b])), W2); }
{ const P = S3(14); const b = nearest(rim, P[P.length - 1])[0]; add1('node to rim', fit1([[nodeR.cx, nodeR.cy]].concat(resample(P, 2).filter(p => norm(sub(p, [nodeR.cx, nodeR.cy])) > 8 && norm(sub(p, b)) > 6), [b])), W2); }
// straight spokes, each running on to meet the ring
const spoke = (name, from, dirFrom, w) => { const c = unit(dirFrom); const q = rayHit(ringPoly, from, c); add1(name, [lineSeg(from, q)], w); return q; };
const hubL = spoke('left spoke', [nodeL.cx, nodeL.cy], [1, 0], W2);
const hubR = spoke('right spoke', [nodeR.cx, nodeR.cy], [-1, 0], W2);
// the lower spokes run from the lower edge straight to the hub under their eye
{ const P = S3(16), a = P[0], c = unit(sub(a, hubL)); add1('left lower spoke', [lineSeg(add(a, mul(c, 30)), hubL)], W2); log('left lower spoke turned', (Math.acos(dot(unit(sub(P[P.length - 1], P[0])), mul(c, -1))) * 180 / Math.PI).toFixed(1), '° to meet the hub'); }
{ const P = S3(17), a = P[0], c = unit(sub(a, hubR)); add1('right lower spoke', [lineSeg(add(a, mul(c, 30)), hubR)], W2); log('right lower spoke turned', (Math.acos(dot(unit(sub(P[P.length - 1], P[0])), mul(c, -1))) * 180 / Math.PI).toFixed(1), '° to meet the hub'); }
{ const P = S3(18); const top = nearest(ringPoly, [P[0][0], Rq[1] + Rq[3]])[0]; const c = unit(sub(P[P.length - 1], P[0])); add1('centre line', [lineSeg(top, add(top, mul(c, 110)))], W1); }
add1('ring', ringSegs, W1);
// lines that reach the edge run on past it, so the body's outline cuts them cleanly
for (const l of lines) if (l.name === 'tail rim' || l.name === 'right vein') { const s = l.segs[l.segs.length - 1], d = unit(sub(s[3], s[2])); l.segs.push(lineSeg(s[3], add(s[3], mul(d, 70)))); }
// the lower spokes and the centre line start (or end) outside the head too, whatever the base does
for (const l of lines) if (/lower spoke/.test(l.name)) { const s = l.segs[0], d = unit(sub(s[0], s[3])); l.segs[0] = lineSeg(add(s[0], mul(d, 50)), s[3]); }
for (const l of lines) if (l.name === 'centre line') { const s = l.segs[0], d = unit(sub(s[3], s[0])); l.segs[0] = lineSeg(s[0], add(s[3], mul(d, 40))); }
log('lines:', lines.map(l => `${l.name} (${l.segs.length} curve${l.segs.length > 1 ? 's' : ''}, ${l.w})`).join(', '));

// =================== output and pictures ===================
const pack = segs => segs.map(s => s.map(p => [r1(p[0]), r1(p[1])]));
const D = (segs, close) => { let d = `M${r1(segs[0][0][0])},${r1(segs[0][0][1])}`; for (const s of segs) d += `C${r1(s[1][0])},${r1(s[1][1])} ${r1(s[2][0])},${r1(s[2][1])} ${r1(s[3][0])},${r1(s[3][1])}`; return d + (close ? 'Z' : ''); };
const geo = { W: g3.W, H: g3.H, colours: g3.colours, eyes: g3.eyes, bodySegs: pack(bodySegs),
  head: { ellipse: { cx: r1(E[0]), cy: r1(E[1]), rx: r1(E[2]), ry: r1(E[3]), tilt: r1(E[4]), rmsOnLeftSide: +eRms.toFixed(2) }, bottom: r1(Math.max(...body.map(p => p[1]))) },
  ring: { cx: r1(Rq[0]), cy: r1(Rq[1]), r: r1(Rq[2]), bottomDepth: r1(Rq[3]), tilt: r1(Rq[4]), squareness: mRing, rms: +ringRms.toFixed(2) },
  nodes: [nodeL, nodeR].map(n => ({ cx: r1(n.cx), cy: r1(n.cy), rx: r1(n.rx), ry: r1(n.ry), tilt: r1(n.tilt) })),
  lines: lines.map(l => ({ name: l.name, segs: pack(l.segs), w: l.w, closed: l.name === 'ring' })), fit: g3.fit };
const bodyD = D(bodySegs, true); geo.chin = { mid: [r1(E[0]), geo.head.bottom], low: geo.head.bottom };
fs.writeFileSync(path.join(OUT, 'geometry4.json'), JSON.stringify(geo));
const svg = ({ eyes = true, op = 1, net = true } = {}) => `<svg xmlns="http://www.w3.org/2000/svg" width="465" height="352" viewBox="0 0 465 352"><defs><clipPath id="c"><path d="${bodyD}"/></clipPath></defs><g opacity="${op}"><path d="${bodyD}" fill="#362e42"/>${net ? `<g clip-path="url(#c)" fill="none" stroke="#8747c2" stroke-linecap="round" stroke-linejoin="round">${lines.map(l => `<path d="${D(l.segs, l.name === 'ring')}" stroke-width="${l.w}"/>`).join('')}${[nodeL, nodeR].map(n => `<ellipse cx="${n.cx}" cy="${n.cy}" rx="${n.rx}" ry="${n.ry}" transform="rotate(${n.tilt} ${n.cx} ${n.cy})" fill="#8747c2" stroke="none"/>`).join('')}</g>` : ''}${eyes ? ['L', 'R'].map(k => { const e = g3.eyes[k]; return `<ellipse cx="${e.cx}" cy="${e.cy}" rx="${e.rx}" ry="${e.ry}" transform="rotate(${e.tilt} ${e.cx} ${e.cy})" fill="#fbf539"/>`; }).join('') : ''}</g></svg>`;
(async () => { const b = await chromium.launch(), p = await b.newPage({ viewport: { width: 950, height: 720 } });
  const ref = 'data:image/png;base64,' + fs.readFileSync(REFPNG).toString('base64'), u = s => 'data:image/svg+xml;base64,' + Buffer.from(s).toString('base64');
  const old = fs.existsSync(path.join(OUT, 'v3.svg')) ? 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(OUT, 'v3.svg')).toString('base64') : '';
  await p.setContent(`<body style="margin:0;background:#888;display:grid;grid-template-columns:465px 465px;gap:10px 10px"><img src="${ref}"><img src="${u(svg())}" style="background:#6b9887"><div style="position:relative"><img src="${ref}" style="position:absolute"><img src="${u(svg({ op: .55 }))}" style="position:absolute"></div><img src="${u(svg({ eyes: false }))}" style="background:#6b9887"></body>`);
  await p.waitForTimeout(300); await p.screenshot({ path: path.join(OUT, 'v4-compare.png') });
  await p.setContent(`<body style="margin:0;background:#6b9887"><img src="${u(svg({ eyes: false }))}" style="width:930px"></body>`); await p.setViewportSize({ width: 930, height: 704 });
  await p.waitForTimeout(200); await p.screenshot({ path: path.join(OUT, 'v4-net.png') }); await b.close(); })();
