// ---------- vector helpers ----------
const add = (a, b) => [a[0] + b[0], a[1] + b[1]], sub = (a, b) => [a[0] - b[0], a[1] - b[1]];
const mul = (a, s) => [a[0] * s, a[1] * s], dot = (a, b) => a[0] * b[0] + a[1] * b[1];
const norm = a => Math.hypot(a[0], a[1]), unit = a => { const n = norm(a); return n ? [a[0] / n, a[1] / n] : [0, 0]; };

// ---------- Schneider curve fitting ----------
function bq(b, t) { const u = 1 - t; return [u*u*u*b[0][0] + 3*u*u*t*b[1][0] + 3*u*t*t*b[2][0] + t*t*t*b[3][0], u*u*u*b[0][1] + 3*u*u*t*b[1][1] + 3*u*t*t*b[2][1] + t*t*t*b[3][1]]; }
function bq1(b, t) { const u = 1 - t; return [3*u*u*(b[1][0]-b[0][0]) + 6*u*t*(b[2][0]-b[1][0]) + 3*t*t*(b[3][0]-b[2][0]), 3*u*u*(b[1][1]-b[0][1]) + 6*u*t*(b[2][1]-b[1][1]) + 3*t*t*(b[3][1]-b[2][1])]; }
function bq2(b, t) { const u = 1 - t; return [6*u*(b[2][0]-2*b[1][0]+b[0][0]) + 6*t*(b[3][0]-2*b[2][0]+b[1][0]), 6*u*(b[2][1]-2*b[1][1]+b[0][1]) + 6*t*(b[3][1]-2*b[2][1]+b[1][1])]; }
function chord(P) { const u = [0]; for (let i = 1; i < P.length; i++) u.push(u[i-1] + norm(sub(P[i], P[i-1]))); const L = u[u.length-1] || 1; return u.map(x => x / L); }
function genBez(P, U, tl, tr) {
  const f = P[0], l = P[P.length-1]; const C = [[0,0],[0,0]], X = [0,0];
  for (let i = 0; i < P.length; i++) { const u = U[i], ux = 1 - u; const a0 = mul(tl, 3*u*ux*ux), a1 = mul(tr, 3*ux*u*u);
    C[0][0] += dot(a0,a0); C[0][1] += dot(a0,a1); C[1][0] += dot(a0,a1); C[1][1] += dot(a1,a1);
    const tmp = sub(P[i], bq([f,f,l,l], u)); X[0] += dot(a0,tmp); X[1] += dot(a1,tmp); }
  const d = C[0][0]*C[1][1] - C[1][0]*C[0][1]; let al = d ? (X[0]*C[1][1] - X[1]*C[0][1]) / d : 0, ar = d ? (C[0][0]*X[1] - C[1][0]*X[0]) / d : 0;
  const seg = norm(sub(f, l)), eps = 1e-6 * seg;
  if (al < eps || ar < eps || al > seg * 2 || ar > seg * 2) { al = ar = seg / 3; }
  return [f, add(f, mul(tl, al)), add(l, mul(tr, ar)), l];
}
function maxErr(P, b, U) { let m = 0, s = P.length >> 1; for (let i = 1; i < P.length - 1; i++) { const v = sub(bq(b, U[i]), P[i]); const d = dot(v, v); if (d > m) { m = d; s = i; } } return [m, s]; }
function newton(b, p, u) { const d = sub(bq(b, u), p), q1 = bq1(b, u), q2 = bq2(b, u); const den = dot(q1,q1) + dot(d,q2); return den ? Math.min(1, Math.max(0, u - dot(d,q1) / den)) : u; }
function fitCubic(P, tl, tr, err) {
  if (P.length === 2) { const d = norm(sub(P[0], P[1])) / 3; return [[P[0], add(P[0], mul(tl, d)), add(P[1], mul(tr, d)), P[1]]]; }
  let U = chord(P), b = genBez(P, U, tl, tr), [e, s] = maxErr(P, b, U);
  if (e < err) return [b];
  if (e < err * 16) for (let it = 0; it < 30; it++) { U = U.map((u, i) => newton(b, P[i], u)); b = genBez(P, U, tl, tr); [e, s] = maxErr(P, b, U); if (e < err) return [b]; }
  s = Math.max(1, Math.min(P.length - 2, s));
  let tc = unit(sub(P[Math.max(0, s-2)], P[Math.min(P.length-1, s+2)])); if (!tc[0] && !tc[1]) tc = unit(sub(P[s-1], P[s+1]));
  return fitCubic(P.slice(0, s + 1), tl, tc, err).concat(fitCubic(P.slice(s), mul(tc, -1), tr, err));
}
// closed loop: split at corners (or 4 even points), keep smooth tangents across non-corner splits
function fitLoop(P, err, cornerDeg = 50) {
  const n = P.length, k = 3; const ang = i => { const a = unit(sub(P[i], P[(i - k + n) % n])), b = unit(sub(P[(i + k) % n], P[i])); return Math.acos(Math.max(-1, Math.min(1, dot(a, b)))) * 180 / Math.PI; };
  const A = P.map((_, i) => ang(i)); let brk = [];
  for (let i = 0; i < n; i++) if (A[i] > cornerDeg && A[i] >= A[(i-1+n)%n] && A[i] >= A[(i+1)%n]) { if (!brk.length || i - brk[brk.length-1] > 4) brk.push(i); }
  const corner = new Set(brk);
  if (brk.length < 2) { const s = brk.length ? brk[0] : 0; brk = [0,1,2,3].map(j => (s + Math.round(j * n / 4)) % n).sort((a, b) => a - b); }
  // add even splits into long runs so runs stay under ~120 points
  const all = []; for (let j = 0; j < brk.length; j++) { const a = brk[j], b = brk[(j+1) % brk.length]; const len = (b - a + n) % n || n; all.push(a); const parts = Math.ceil(len / 120); for (let q = 1; q < parts; q++) all.push((a + Math.round(q * len / parts)) % n); }
  const tanAt = (i, dir) => { if (corner.has(i)) return dir > 0 ? unit(sub(P[(i + k) % n], P[i])) : unit(sub(P[(i - k + n) % n], P[i])); const t = unit(sub(P[(i + k) % n], P[(i - k + n) % n])); return dir > 0 ? t : mul(t, -1); };
  let segs = [];
  for (let j = 0; j < all.length; j++) { const a = all[j], b = all[(j+1) % all.length]; const run = []; for (let i = a; ; i = (i + 1) % n) { run.push(P[i]); if (i === b) break; }
    if (run.length < 2) continue; segs = segs.concat(fitCubic(run, tanAt(a, 1), tanAt(b, -1), err)); }
  return segs;
}
function fitOpen(P, err) { const k = Math.min(3, P.length - 1); return fitCubic(P, unit(sub(P[k], P[0])), unit(sub(P[P.length-1-k], P[P.length-1])), err); }
const r1 = v => Math.round(v * 10) / 10;
const segsD = (segs, close) => { if (!segs.length) return ''; let d = `M${r1(segs[0][0][0])},${r1(segs[0][0][1])}`; for (const s of segs) d += `C${r1(s[1][0])},${r1(s[1][1])} ${r1(s[2][0])},${r1(s[2][1])} ${r1(s[3][0])},${r1(s[3][1])}`; return d + (close ? 'Z' : ''); };

// ---------- marching squares ----------
function contours(F, W, H) {
  const v = (x, y) => (x < 0 || y < 0 || x >= W || y >= H) ? -1 : F[y * W + x];
  const segs = new Map(); const pt = {};
  const ep = (id, x, y) => { pt[id] = [x, y]; return id; };
  const hx = (x, y) => { const a = v(x, y), b = v(x + 1, y); return ep('h' + x + ',' + y, x + a / (a - b), y); };
  const vy = (x, y) => { const a = v(x, y), b = v(x, y + 1); return ep('v' + x + ',' + y, x, y + a / (a - b)); };
  const link = (a, b) => { (segs.get(a) || segs.set(a, []).get(a)).push(b); (segs.get(b) || segs.set(b, []).get(b)).push(a); };
  for (let y = -1; y < H; y++) for (let x = -1; x < W; x++) {
    const a = v(x, y) > 0, b = v(x + 1, y) > 0, c = v(x + 1, y + 1) > 0, d = v(x, y + 1) > 0;
    const code = (a ? 8 : 0) | (b ? 4 : 0) | (c ? 2 : 0) | (d ? 1 : 0); if (code === 0 || code === 15) continue;
    const T = () => hx(x, y), R = () => vy(x + 1, y), B = () => hx(x, y + 1), L = () => vy(x, y);
    const centre = (v(x,y) + v(x+1,y) + v(x+1,y+1) + v(x,y+1)) / 4 > 0;
    switch (code) {
      case 1: case 14: link(L(), B()); break; case 2: case 13: link(B(), R()); break; case 3: case 12: link(L(), R()); break;
      case 4: case 11: link(T(), R()); break; case 6: case 9: link(T(), B()); break; case 7: case 8: link(L(), T()); break;
      case 5: if (centre) { link(L(), T()); link(B(), R()); } else { link(L(), B()); link(T(), R()); } break;
      case 10: if (centre) { link(L(), B()); link(T(), R()); } else { link(L(), T()); link(B(), R()); } break;
    }
  }
  const seen = new Set(), loops = [];
  for (const start of segs.keys()) { if (seen.has(start)) continue; const loop = []; let prev = null, cur = start;
    while (cur && !seen.has(cur)) { seen.add(cur); loop.push(pt[cur]); const nb = segs.get(cur); const nx = nb.find(q => q !== prev && !seen.has(q)); prev = cur; cur = nx; }
    if (loop.length > 8) loops.push(loop); }
  return loops;
}
const area = L => { let s = 0; for (let i = 0; i < L.length; i++) { const a = L[i], b = L[(i + 1) % L.length]; s += a[0] * b[1] - b[0] * a[1]; } return s / 2; };
function smoothLoop(L, passes = 1) { let P = L; for (let p = 0; p < passes; p++) P = P.map((q, i) => { const a = P[(i - 1 + P.length) % P.length], b = P[(i + 1) % P.length]; return [(a[0] + 2 * q[0] + b[0]) / 4, (a[1] + 2 * q[1] + b[1]) / 4]; }); return P; }
module.exports={add,sub,mul,dot,norm,unit,bq,bq1,bq2,fitCubic,fitLoop,fitOpen,r1,segsD,contours,area,smoothLoop};
