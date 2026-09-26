#!/usr/bin/env python3
"""Foil border mockups for the card face, rendered in Blender (Cycles, CPU).

    python tools/foil/mockups.py --out OUT_DIR            # bpy from PyPI (pip install bpy)
    blender -b -P tools/foil/mockups.py -- --out OUT_DIR  # or a Blender binary

Six looks for the border band `drawPrismaticInset` paints today (Prismatic.kt),
one card, one light rig, one camera. The point of doing it in Blender rather than
straight in SkSL is to argue about the *look* with real light before anybody
writes a shader, and to leave the shader port with the exact textures the
renders were made from. Everything a port would sample is generated here as a
PNG under OUT/textures/ and then *used by the Blender materials themselves*, so
what kai picks from the contact sheet is what those textures produce:

  A  reference      today's two-hue pink/cyan band, flat, angle swung by tilt
                    -> reference_ramp.png (the Compose gradient, sRGB-interpolated)
  B  thin film      polished metal under an interference film; hue from
                    film thickness * cos(view)^k      -> iridescence_ramp.png, film_noise.png
  C  holographic    concentric diffraction grating: a silver anisotropic mirror
                    plus the grating equation lambda = d*|dot(L+V, g)|/m
                                                      -> spectrum_ramp.png (g is analytic)
  D  brushed silver monochrome anisotropic brushed metal, no hue
                                                      -> brushed_height.png
  E  glitter        Voronoi flakes, each a tilted mirror with its own film
                    thickness, so every glint has its own hue
                                                      -> flake_normal.png, flake_id.png, iridescence_ramp.png
  F  prismatic secret  diamond cells of alternating +/-45 deg gratings over the
                    whole frame (not the art or the text box), printed ink over
                    it as a colour filter             -> secret_cells.png, spectrum_ramp.png, foil_mask.png

foil_mask.png (R = 4% band, G = frame outside art and text box, B = art box) is
exported for the port; the renders compute the same regions analytically.

Renders go to OUT/raw as RGBA (film transparent: no backdrop, no shadow), then
Pillow puts them on paper #FFFFFF and ink #000000, assembles the turntable GIFs,
the two contact sheets and index.html.
"""
import argparse
import math
import os
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

try:
    import bpy
except ImportError:  # pragma: no cover
    sys.exit("needs bpy: `pip install bpy` (Python 3.11) or run under `blender -b -P`")

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FONT_DIR = REPO / "app/ui/src/commonMain/composeResources/font"

# ---- The card -----------------------------------------------------------------
CARD_W, CARD_H = 0.59, 0.86          # metres; 59:86, square corners
BAND = 0.04                          # FOIL_INSET: a fraction of the card's WIDTH
BAND_U = BAND                        # ...in u
BAND_V = BAND * CARD_W / CARD_H      # ...in v (same physical thickness)
# Measured off a 813x1185 YGOPRODeck monster card (u right, v UP):
ART_BOX = (0.097, 0.905, 1 - 0.720, 1 - 0.169)   # umin, umax, vmin, vmax
TEXT_BOX = (0.050, 0.950, 1 - 0.952, 1 - 0.738)

# ---- The rig (world space: camera on -Y looking +Y, Z up) -----------------------
CAM_DIST = 4.0
KEY_AZ, KEY_EL = -25.0, 35.0         # the key: diffuse light for the print, and the
                                     # light the gratings diffract (L in the port)
STRIP_AZ = 36.0                      # two tall strips; a card turned +/-20 deg mirrors them


def direction(az_deg, el_deg):
    """az 0 = toward the camera, + = camera's right; el + = up."""
    a, e = math.radians(az_deg), math.radians(el_deg)
    return (math.sin(a) * math.cos(e), -math.cos(a) * math.cos(e), math.sin(e))


KEY_DIR = direction(KEY_AZ, KEY_EL)

VARIANTS = {
    "A": ("Reference", "Today's border: a flat pink/cyan gradient band whose angle swings with tilt. No light, no metal."),
    "B": ("Thin-film metal", "Polished metal under an interference film: mirror highlights, with hue sliding through the film's colours as the view angle changes."),
    "C": ("Holographic grating", "Concentric diffraction grating: a silver mirror whose rainbow arcs travel round the band as the card turns, computed from the grating equation."),
    "D": ("Brushed silver", "Monochrome ink foil: anisotropic brushed aluminium, hairline texture, no hue at all. The Master UI purist option."),
    "E": ("Glitter", "Secret-rare sparkle: thousands of tiny tilted mirror flakes, each with its own film colour, so glints wink on and off in different hues."),
    "F": ("Prismatic secret", "Konami's prismatic-secret idea: diamond cells of alternating diagonal gratings over the whole frame (not the art), printed ink on top as a filter."),
}


# =================================================================================
# Textures (the shader port's inputs). numpy only, deterministic, tileable.
# =================================================================================

def _srgb_encode(lin):
    lin = np.clip(lin, 0, 1)
    return np.where(lin <= 0.0031308, 12.92 * lin, 1.055 * np.power(lin, 1 / 2.4) - 0.055)


def _save(arr01, path, mode=None):
    a = (np.clip(arr01, 0, 1) * 255 + 0.5).astype(np.uint8)
    Image.fromarray(a, mode).save(path)


def _cmf(lam):
    """CIE 1931 2-deg colour matching functions, Wyman/Sloan/Shirley 2013 fit."""
    def g(x, mu, s1, s2):
        s = np.where(x < mu, s1, s2)
        return np.exp(-0.5 * ((x - mu) / s) ** 2)
    x = 1.056 * g(lam, 599.8, 37.9, 31.0) + 0.362 * g(lam, 442.0, 16.0, 26.7) - 0.065 * g(lam, 501.1, 20.4, 26.2)
    y = 0.821 * g(lam, 568.8, 46.9, 40.5) + 0.286 * g(lam, 530.9, 16.3, 31.1)
    z = 1.217 * g(lam, 437.0, 11.8, 36.0) + 0.681 * g(lam, 459.0, 26.0, 13.8)
    return np.stack([x, y, z], -1)


XYZ2RGB = np.array([[3.2406, -1.5372, -0.4986], [-0.9689, 1.8758, 0.0415], [0.0557, -0.2040, 1.0570]])


def _blur_noise(n, sx, sy, seed):
    """White noise low-passed by an (anisotropic) Gaussian in frequency: tileable by construction."""
    rng = np.random.default_rng(seed)
    w = rng.standard_normal((n, n))
    fy = np.fft.fftfreq(n)[:, None]
    fx = np.fft.fftfreq(n)[None, :]
    G = np.exp(-2 * math.pi ** 2 * ((sx * fx) ** 2 + (sy * fy) ** 2))
    out = np.fft.ifft2(np.fft.fft2(w) * G).real
    lo, hi = np.percentile(out, [1, 99])
    return np.clip((out - lo) / (hi - lo), 0, 1)


def make_textures(tex: Path):
    tex.mkdir(parents=True, exist_ok=True)

    # A: today's gradient, 0 white / .28 pink / .65 cyan / 1 white, interpolated in
    # sRGB as Compose's Brush.linearGradient does.
    t = np.linspace(0, 1, 256)
    stops = [(0, (1, 1, 1)), (0.28, (1, 0x69 / 255, 0xB4 / 255)), (0.65, (0, 1, 1)), (1, (1, 1, 1))]
    ref = np.stack([np.interp(t, [s[0] for s in stops], [s[1][c] for s in stops]) for c in range(3)], -1)
    _save(ref[None], tex / "reference_ramp.png")

    # C, F: wavelength 380..780nm -> sRGB. Ends fall to black on their own.
    lam = np.linspace(380, 780, 256)
    rgb = _cmf(lam) @ XYZ2RGB.T
    rgb = np.clip(rgb, 0, None)
    rgb /= rgb.max()
    rgb = np.clip(rgb * 1.6, 0, 1)      # a foil rainbow is saturated and bright in the middle
    rgb[0] = rgb[-1] = 0
    _save(_srgb_encode(rgb)[None], tex / "spectrum_ramp.png")

    # B, E: thin-film interference over a metal. x = effective thickness
    # (film thickness * cos(theta_t)) over 0..1000nm; value = F0 colour.
    lam = np.linspace(380, 780, 81)
    cmf = _cmf(lam)
    # A high-index film over a darker metal: the two interfaces reflect about
    # equally, which is what makes the fringes deep rather than pastel.
    n_film = 2.4                                      # TiO2
    n_sub = 3.1 + 3.3j                                # chromium
    r01 = (1 - n_film) / (1 + n_film)
    r12 = (n_film - n_sub) / (n_film + n_sub)
    teff = np.linspace(0, 1000, 256)[:, None]
    delta = 4 * math.pi * n_film * teff / lam[None, :]
    e = np.exp(1j * delta)
    R = np.abs((r01 + r12 * e) / (1 + r01 * r12 * e)) ** 2          # (256, 81)
    xyz = R @ cmf
    white = np.ones(len(lam)) @ cmf
    rgb = (xyz @ XYZ2RGB.T) / (white @ XYZ2RGB.T)                    # white-balanced
    rgb = np.clip(rgb, 0, 1)
    # Push saturation a little: the ramp is an art-directed input, not a measurement.
    lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
    rgb = np.clip(lum[:, None] + (rgb - lum[:, None]) * 1.4, 0, None)
    rgb = np.clip(rgb / np.percentile(rgb.max(1), 95) * 0.95, 0, 1)
    _save(_srgb_encode(rgb)[None], tex / "iridescence_ramp.png")

    # B: film thickness variation, isotropic, tileable.
    _save(_blur_noise(256, 18, 18, 7), tex / "film_noise.png", "L")

    # D: brushed hairlines along x (long in x, thin in y), tileable.
    h = 0.65 * _blur_noise(512, 90, 1.2, 11) + 0.35 * _blur_noise(512, 30, 0.6, 12)
    _save(h, tex / "brushed_height.png", "L")

    # E: Voronoi flakes, tileable. Each flake a random tilt (<= 24 deg) and a random id.
    S, N = 512, 40
    rng = np.random.default_rng(3)
    jit = rng.random((N, N, 2)) * 0.8 + 0.1
    tilt = np.radians(24) * np.sqrt(rng.random((N, N)))
    azim = rng.random((N, N)) * 2 * math.pi
    ident = rng.random((N, N))
    yy, xx = np.mgrid[0:S, 0:S] + 0.5
    cx, cy = xx / S * N, yy / S * N
    ci, cj = np.floor(cx).astype(int), np.floor(cy).astype(int)
    best = np.full((S, S), np.inf)
    bi = np.zeros((S, S), int)
    bj = np.zeros((S, S), int)
    for dj in (-1, 0, 1):
        for di in (-1, 0, 1):
            ni, nj = (ci + di) % N, (cj + dj) % N
            px = ci + di + jit[nj, ni, 0]
            py = cj + dj + jit[nj, ni, 1]
            d = (cx - px) ** 2 + (cy - py) ** 2
            m = d < best
            best[m], bi[m], bj[m] = d[m], ni[m], nj[m]
    th, az = tilt[bj, bi], azim[bj, bi]
    nrm = np.stack([np.sin(th) * np.cos(az), np.sin(th) * np.sin(az), np.cos(th)], -1)
    _save(nrm * 0.5 + 0.5, tex / "flake_normal.png")
    _save(ident[bj, bi], tex / "flake_id.png", "L")

    # F: 16x16 cells, 4px each; R = which diagonal the grating runs (checker),
    # G = a per-cell random nudge to the grating period (the shimmer).
    C = 16
    par = (np.add.outer(np.arange(C), np.arange(C)) % 2).astype(float)
    rnd = np.random.default_rng(5).random((C, C))
    cell = np.stack([par, rnd, np.zeros_like(par)], -1)
    cell = np.repeat(np.repeat(cell, 4, 0), 4, 1)
    _save(cell, tex / "secret_cells.png")

    # Masks, in card space, 590x860 (top row = top of the card).
    W, H = 590, 860
    u = (np.arange(W) + 0.5) / W
    v = 1 - (np.arange(H) + 0.5) / H
    U, V = np.meshgrid(u, v)
    band = (np.minimum(U, 1 - U) < BAND_U) | (np.minimum(V, 1 - V) < BAND_V)
    def box(b):
        return (U > b[0]) & (U < b[1]) & (V > b[2]) & (V < b[3])
    art = box(ART_BOX)
    frame = ~band & ~art & ~box(TEXT_BOX)
    _save(np.stack([band, frame, art], -1).astype(float), tex / "foil_mask.png")


# =================================================================================
# Node plumbing
# =================================================================================

class NB:
    """A tiny builder: numbers and sockets are interchangeable arguments."""

    def __init__(self, nt):
        self.nt = nt
        self.x = 0

    def node(self, kind, **props):
        n = self.nt.nodes.new(kind)
        for k, v in props.items():
            setattr(n, k, v)
        n.location = (self.x, 0)
        self.x += 40
        return n

    def feed(self, sock, val):
        if val is None:
            return
        if hasattr(val, "is_output"):
            self.nt.links.new(val, sock)
        else:
            sock.default_value = val

    def math(self, op, a, b=None, clamp=False):
        n = self.node("ShaderNodeMath", operation=op, use_clamp=clamp)
        self.feed(n.inputs[0], a)
        if b is not None:
            self.feed(n.inputs[1], b)
        return n.outputs[0]

    def vmath(self, op, a, b=None, scale=None):
        n = self.node("ShaderNodeVectorMath", operation=op)
        self.feed(n.inputs[0], a)
        if b is not None:
            self.feed(n.inputs[1], b)
        if scale is not None:
            self.feed(n.inputs["Scale"], scale)
        return n.outputs["Value"] if op in ("DOT_PRODUCT", "LENGTH", "DISTANCE") else n.outputs["Vector"]

    def xyz(self, x=0.0, y=0.0, z=0.0):
        n = self.node("ShaderNodeCombineXYZ")
        for i, v in enumerate((x, y, z)):
            self.feed(n.inputs[i], v)
        return n.outputs[0]

    def sep(self, v):
        n = self.node("ShaderNodeSeparateXYZ")
        self.feed(n.inputs[0], v)
        return n.outputs

    def mix(self, fac, a, b, kind="RGBA", blend="MIX"):
        n = self.node("ShaderNodeMix", data_type=kind, blend_type=blend)
        self.feed(n.inputs[0], fac)
        ia, ib, out = {"RGBA": (6, 7, 2), "VECTOR": (4, 5, 1), "FLOAT": (2, 3, 0)}[kind]
        self.feed(n.inputs[ia], a)
        self.feed(n.inputs[ib], b)
        return n.outputs[out]

    def image(self, path, vec, colorspace="sRGB", interp="Linear", ext="REPEAT"):
        img = bpy.data.images.load(str(path), check_existing=True)
        img.colorspace_settings.name = colorspace
        n = self.node("ShaderNodeTexImage", interpolation=interp, extension=ext)
        n.image = img
        self.feed(n.inputs[0], vec)
        return n.outputs["Color"]

    def ramp1d(self, path, x):
        """Sample a 256x1 ramp at x in 0..1 (clamped at the ends)."""
        return self.image(path, self.xyz(x, 0.5, 0.0), ext="EXTEND")

    def to_world(self, v):
        n = self.node("ShaderNodeVectorTransform", vector_type="VECTOR", convert_from="OBJECT", convert_to="WORLD")
        self.feed(n.inputs[0], v)
        return n.outputs[0]

    def principled(self, **inputs):
        n = self.node("ShaderNodeBsdfPrincipled")
        for k, v in inputs.items():
            self.feed(n.inputs[k.replace("_", " ")], v)
        return n.outputs[0]

    def mix_shader(self, fac, a, b):
        n = self.node("ShaderNodeMixShader")
        self.feed(n.inputs[0], fac)
        self.feed(n.inputs[1], a)
        self.feed(n.inputs[2], b)
        return n.outputs[0]


# =================================================================================
# Materials
# =================================================================================

def build_material(letter, card_img, tex):
    mat = bpy.data.materials.new(f"foil_{letter}")
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    b = NB(nt)
    out = b.node("ShaderNodeOutputMaterial")

    tc = b.node("ShaderNodeTexCoord")
    geo = b.node("ShaderNodeNewGeometry")
    uv = tc.outputs["UV"]
    obj = tc.outputs["Object"]           # metres, card centred at the origin, in its own XY
    u, v, _ = b.sep(uv)
    ox, oy, _ = b.sep(obj)
    V = geo.outputs["Incoming"]          # world, toward the eye
    N = geo.outputs["Normal"]

    print_col = b.image(card_img, uv)
    paper = b.principled(Base_Color=print_col, Roughness=0.6, Specular_IOR_Level=0.15)

    # Band: the outer 4% of the card's width, on all four sides.
    du = b.math("MINIMUM", u, b.math("SUBTRACT", 1.0, u))
    dv = b.math("MINIMUM", v, b.math("SUBTRACT", 1.0, v))
    band = b.math("MAXIMUM", b.math("LESS_THAN", du, BAND_U), b.math("LESS_THAN", dv, BAND_V))

    cos_v = b.math("ABSOLUTE", b.vmath("DOT_PRODUCT", N, V))

    def tiled(scale_per_m, rotate_deg=0.0):
        m = b.node("ShaderNodeMapping")
        b.feed(m.inputs["Vector"], obj)
        m.inputs["Rotation"].default_value = (0, 0, math.radians(rotate_deg))
        m.inputs["Scale"].default_value = (scale_per_m,) * 3
        return m.outputs[0]

    def film_colour(thickness_nm, gain_k=3.0):
        """Iridescence ramp at thickness * cos(view)^k. k > 1 exaggerates the angle
        shift a real film gives (a port knob: `filmAngleGain`)."""
        cosk = b.math("POWER", cos_v, gain_k)
        teff = b.math("MULTIPLY", thickness_nm, cosk)
        return b.ramp1d(tex / "iridescence_ramp.png", b.math("DIVIDE", teff, 1000.0))

    def diffraction(g_obj, period_nm, jitter=None, orders=((1, 1.0), (2, 0.55), (3, 0.25))):
        """The grating equation, one light: an order m shows wavelength
        lambda = d * |dot(L + V, g)| / m, with g the in-plane direction ACROSS the grooves."""
        gw = b.vmath("NORMALIZE", b.to_world(g_obj))
        lv = b.vmath("ADD", b.xyz(*KEY_DIR), V)
        s = b.math("ABSOLUTE", b.vmath("DOT_PRODUCT", lv, gw))
        if jitter is not None:
            s = b.math("ADD", s, jitter)
        acc = None
        for m, w in orders:
            lam = b.math("MULTIPLY", s, period_nm / m)
            x = b.math("DIVIDE", b.math("SUBTRACT", lam, 380.0), 400.0)
            c = b.ramp1d(tex / "spectrum_ramp.png", x)
            c = b.vmath("SCALE", c, scale=w)
            acc = c if acc is None else b.vmath("ADD", acc, c)
        return acc

    mask = band
    if letter == "A":
        # Flat: an emission, like the 2D stroke. Card-space px (y down), angle from the
        # tilt, exactly the Kotlin arithmetic. The angle lives on a Value node named
        # "foil_angle" the render loop rewrites every frame.
        ang = b.node("ShaderNodeValue", name="foil_angle", label="foil_angle")
        ang.outputs[0].default_value = 135.0
        rad = b.math("RADIANS", ang.outputs[0])
        dx = b.math("SINE", rad)
        dy = b.math("MULTIPLY", b.math("COSINE", rad), -1.0)
        span = b.math("ADD", b.math("MULTIPLY", b.math("ABSOLUTE", dx), CARD_W), b.math("MULTIPLY", b.math("ABSOLUTE", dy), CARD_H))
        px = b.math("MULTIPLY", b.math("SUBTRACT", u, 0.5), CARD_W)
        py = b.math("MULTIPLY", b.math("SUBTRACT", 0.5, v), CARD_H)
        proj = b.math("ADD", b.math("MULTIPLY", px, dx), b.math("MULTIPLY", py, dy))
        t = b.math("ADD", b.math("DIVIDE", proj, span), 0.5)
        col = b.ramp1d(tex / "reference_ramp.png", t)
        em = b.node("ShaderNodeEmission")
        b.feed(em.inputs["Color"], col)
        foil = em.outputs[0]

    elif letter == "B":
        noise = b.image(tex / "film_noise.png", tiled(1 / 0.6), colorspace="Non-Color")
        noise = b.sep(noise)[0]
        thick = b.math("ADD", 170.0, b.math("MULTIPLY", noise, 280.0))
        col = film_colour(thick)
        foil = b.principled(Base_Color=col, Metallic=1.0, Roughness=0.16)

    elif letter == "C":
        # Grooves are concentric rings about the card's centre: the direction across
        # them is radial. Analytic in the port too (normalize(p - centre)).
        radial = b.xyz(ox, oy, 0.0)
        ring_tangent = b.to_world(b.xyz(b.math("MULTIPLY", oy, -1.0), ox, 0.0))
        rainbow = diffraction(radial, 1450.0)
        foil = b.principled(Base_Color=(0.80, 0.81, 0.83, 1), Metallic=1.0, Roughness=0.22,
                            Anisotropic=0.85, Tangent=ring_tangent,
                            Emission_Color=rainbow, Emission_Strength=1.1)

    elif letter == "D":
        h = b.sep(b.image(tex / "brushed_height.png", tiled(1 / 0.5), colorspace="Non-Color"))[0]
        bump = b.node("ShaderNodeBump")
        bump.inputs["Strength"].default_value = 0.35
        bump.inputs["Distance"].default_value = 0.0006
        b.feed(bump.inputs["Height"], h)
        tangent = b.to_world(b.xyz(1.0, 0.0, 0.0))   # brushed along x
        foil = b.principled(Base_Color=(0.86, 0.86, 0.86, 1), Metallic=1.0, Roughness=0.28,
                            Anisotropic=0.9, Tangent=tangent, Normal=bump.outputs["Normal"])

    elif letter == "E":
        vec = tiled(1 / 0.30)
        nmap = b.node("ShaderNodeNormalMap", space="TANGENT")
        nmap.inputs["Strength"].default_value = 1.0
        b.feed(nmap.inputs["Color"], b.image(tex / "flake_normal.png", vec, colorspace="Non-Color", interp="Closest"))
        ident = b.sep(b.image(tex / "flake_id.png", vec, colorspace="Non-Color", interp="Closest"))[0]
        thick = b.math("ADD", 120.0, b.math("MULTIPLY", ident, 380.0))
        col = b.mix(0.6, (0.92, 0.92, 0.94, 1), film_colour(thick, 2.0))
        # Each flake is also a scrap of grating at its own angle (from its id), so it
        # throws its own colour at the key light and changes it as the card turns.
        ang = b.math("MULTIPLY", ident, 2 * math.pi * 7.0)
        g = b.xyz(b.math("COSINE", ang), b.math("SINE", ang), 0.0)
        glint = diffraction(g, 2600.0, orders=((1, 1.0), (2, 0.8), (3, 0.6)))
        # Only a third of the flakes carry a grating: the rest are plain mirrors.
        few = b.math("LESS_THAN", b.math("FRACT", b.math("MULTIPLY", ident, 13.0)), 0.35)
        foil = b.principled(Base_Color=col, Metallic=1.0, Roughness=0.07, Normal=nmap.outputs["Normal"],
                            Emission_Color=b.vmath("SCALE", glint, scale=few), Emission_Strength=1.3)

    elif letter == "F":
        vec = tiled(1 / 0.128, 45.0)
        cell = b.sep(b.image(tex / "secret_cells.png", vec, colorspace="Non-Color", interp="Closest"))
        diag = b.mix(cell[0], (0.7071, 0.7071, 0.0), (0.7071, -0.7071, 0.0), kind="VECTOR")
        nudge = b.math("MULTIPLY", b.math("SUBTRACT", cell[1], 0.5), 0.06)
        rainbow = diffraction(diag, 3000.0, jitter=nudge,
                              orders=((1, 1.0), (2, 0.6), (3, 0.4)))
        # Ink over foil: the print tints the metal and filters the rainbow; the outer
        # band has no ink on it and stays silver.
        tint = b.mix(band, print_col, (1, 1, 1, 1))
        base = b.mix(1.0, (0.85, 0.85, 0.87, 1), tint, blend="MULTIPLY")
        em = b.mix(1.0, rainbow, b.mix(0.55, tint, (1, 1, 1, 1)), blend="MULTIPLY")
        metal = b.principled(Base_Color=base, Metallic=1.0, Roughness=0.2,
                             Emission_Color=em, Emission_Strength=0.8)
        # Off the band, keep 30% of the paper so the frame never goes fully dark.
        foil = b.mix_shader(b.math("MULTIPLY", b.math("SUBTRACT", 1.0, band), 0.3), metal, paper)

        def inbox(bx):
            iu = b.math("MULTIPLY", b.math("GREATER_THAN", u, bx[0]), b.math("LESS_THAN", u, bx[1]))
            iv = b.math("MULTIPLY", b.math("GREATER_THAN", v, bx[2]), b.math("LESS_THAN", v, bx[3]))
            return b.math("MULTIPLY", iu, iv)
        art_or_text = b.math("MAXIMUM", inbox(ART_BOX), inbox(TEXT_BOX))
        mask = b.math("MAXIMUM", band, b.math("SUBTRACT", 1.0, art_or_text))

    surf = b.mix_shader(mask, paper, foil)
    nt.links.new(surf, out.inputs["Surface"])
    return mat


# =================================================================================
# Scene
# =================================================================================

def build_world(world):
    world.use_nodes = True
    nt = world.node_tree
    nt.nodes.clear()
    b = NB(nt)
    out = b.node("ShaderNodeOutputWorld")
    tc = b.node("ShaderNodeTexCoord")
    d = b.vmath("NORMALIZE", tc.outputs["Generated"])
    x, y, z = b.sep(d)
    az = b.math("ARCTAN2", x, b.math("MULTIPLY", y, -1.0))
    el = b.math("ARCSINE", z)

    def soft_box(a0, e0, hw, hh, soft):
        def edge(val, c, half):
            dist = b.math("ABSOLUTE", b.math("SUBTRACT", val, math.radians(c)))
            n = b.node("ShaderNodeMapRange", interpolation_type="SMOOTHSTEP")
            b.feed(n.inputs["Value"], dist)
            n.inputs["From Min"].default_value = math.radians(half - soft)
            n.inputs["From Max"].default_value = math.radians(half + soft)
            n.inputs["To Min"].default_value = 1.0
            n.inputs["To Max"].default_value = 0.0
            return n.outputs[0]
        return b.math("MULTIPLY", edge(az, a0, hw), edge(el, e0, hh))

    # A grey studio: darker below the horizon, lighter above, and a low wide panel
    # behind the camera so a card facing you has a highlight across its upper half.
    grad = b.node("ShaderNodeMapRange", interpolation_type="SMOOTHSTEP")
    b.feed(grad.inputs["Value"], el)
    grad.inputs["From Min"].default_value = -0.25
    grad.inputs["From Max"].default_value = 0.6
    grad.inputs["To Min"].default_value = 0.035
    grad.inputs["To Max"].default_value = 0.30
    lum = grad.outputs[0]
    # (az, el, half-width, half-height, softness, strength), degrees. The panels a
    # flat mirror sweeps across between -25 and +25 degrees of turn (az doubles).
    for box in ((0, 7, 16, 3.5, 2.0, 1.6), (-17, -5, 4, 7, 2.0, 1.0), (21, 11, 3.5, 9, 1.5, 1.3),
                (-9, 16, 3, 4, 1.5, 0.9), (11, -9, 6, 3, 2.0, 0.7)):
        lum = b.math("ADD", lum, b.math("MULTIPLY", soft_box(*box[:5]), box[5]))
    bg = b.node("ShaderNodeBackground")
    b.feed(bg.inputs["Color"], b.xyz(lum, lum, lum))
    bg.inputs["Strength"].default_value = 1.0
    nt.links.new(bg.outputs[0], out.inputs["Surface"])


def area_light(name, az, el, dist, size_x, size_y, power):
    d = direction(az, el)
    data = bpy.data.lights.new(name, "AREA")
    data.shape = "RECTANGLE"
    data.size, data.size_y = size_x, size_y
    data.energy = power
    o = bpy.data.objects.new(name, data)
    bpy.context.scene.collection.objects.link(o)
    o.location = tuple(c * dist for c in d)
    # Aim -Z of the light at the origin.
    from mathutils import Vector
    o.rotation_euler = (-Vector(d)).to_track_quat("-Z", "Y").to_euler()
    return o


def build_scene(width, height):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.render.threads_mode = "FIXED"
    sc.render.threads = os.cpu_count() or 4
    sc.render.resolution_x, sc.render.resolution_y = width, height
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.view_settings.view_transform = "Standard"
    sc.view_settings.look = "None"
    sc.cycles.use_denoising = True
    sc.cycles.max_bounces = 4
    sc.cycles.caustics_reflective = False
    sc.cycles.caustics_refractive = False
    sc.render.filter_size = 1.2

    w = bpy.data.worlds.new("studio")
    sc.world = w
    build_world(w)

    # The card: a plane in its own XY, stood up to face the camera (-Y).
    mesh = bpy.data.meshes.new("card")
    hx, hy = CARD_W / 2, CARD_H / 2
    mesh.from_pydata([(-hx, -hy, 0), (hx, -hy, 0), (hx, hy, 0), (-hx, hy, 0)], [], [(0, 1, 2, 3)])
    uvl = mesh.uv_layers.new(name="UVMap")
    for loop, co in zip(mesh.loops, [(0, 0), (1, 0), (1, 1), (0, 1)]):
        uvl.data[loop.index].uv = co
    card = bpy.data.objects.new("card", mesh)
    sc.collection.objects.link(card)
    card.rotation_euler = (math.radians(90), 0, 0)

    cam_data = bpy.data.cameras.new("cam")
    cam_data.sensor_fit = "VERTICAL"
    cam_data.sensor_height = 24
    cam_data.lens = 24 * CAM_DIST / (CARD_H / 0.86)   # the card fills ~86% of the height
    cam = bpy.data.objects.new("cam", cam_data)
    sc.collection.objects.link(cam)
    cam.location = (0, -CAM_DIST, 0)
    cam.rotation_euler = (math.radians(90), 0, 0)
    sc.camera = cam

    area_light("key", KEY_AZ, KEY_EL, 4.5, 1.4, 1.4, 150)
    # Narrow and a few degrees short of the +/-40 a 20-degree turn mirrors, so at
    # +/-20 a bar of light sits across part of the card instead of flooding it.
    area_light("stripL", -STRIP_AZ, 2, 4.5, 0.22, 3.2, 9)
    area_light("stripR", STRIP_AZ, 2, 4.5, 0.22, 3.2, 7)
    return card


def fetch_card(dest: Path, card_id: str):
    if dest.exists():
        return dest
    url = f"https://images.ygoprodeck.com/images/cards/{card_id}.jpg"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "kai-master-tool foil mockups"})
        with urllib.request.urlopen(req, timeout=30) as r:
            dest.write_bytes(r.read())
        print(f"card art: {url}")
    except Exception as e:  # a placeholder rather than no render
        print(f"card art download failed ({e}); drawing a placeholder")
        im = Image.new("RGB", (813, 1185), (190, 150, 90))
        d = ImageDraw.Draw(im)
        d.rectangle([79, 200, 736, 853], fill=(40, 70, 130))
        for i in range(0, 657, 24):
            d.line([79 + i, 200, 736, 853 - i], fill=(90, 140, 200), width=6)
        d.rectangle([40, 875, 772, 1128], fill=(235, 225, 200))
        im.save(dest)
    return dest


# =================================================================================
# Presentation
# =================================================================================

def on(bg, im):
    base = Image.new("RGBA", im.size, bg + (255,))
    return Image.alpha_composite(base, im.convert("RGBA")).convert("RGB")


def font(size, bold=False):
    for name in (("archivo_semibold.ttf" if bold else "archivo_regular.ttf"), "archivo_regular.ttf"):
        p = FONT_DIR / name
        if p.exists():
            return ImageFont.truetype(str(p), size)
    try:
        return ImageFont.truetype("DejaVuSans.ttf", size)
    except OSError:
        return ImageFont.load_default()


def contact_sheet(out, letters, tags, bg, fg, path):
    stills = {(L, t): Image.open(out / "raw" / f"{L}_{t}.png") for L in letters for t in tags}
    sw, sh = next(iter(stills.values())).size
    scale = 300 / sh
    cw, ch = int(sw * scale), 300
    label_w, pad, head = 300, 24, 104
    W = label_w + len(tags) * (cw + pad) + pad
    H = head + len(letters) * (ch + pad) + pad
    sheet = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(sheet)
    fb, fr, fs = font(44, True), font(20), font(16)
    d.text((pad, 24), "Card foil mockups: A-F at -20, 0, +20 degrees", font=font(22, True), fill=fg)
    for c, t in enumerate(tags):
        d.text((label_w + c * (cw + pad) + cw // 2, head - 16), TAG_LABEL[t], font=fs, fill=fg, anchor="mm")
    for r, L in enumerate(letters):
        y = head + r * (ch + pad)
        d.text((pad, y + 10), L, font=fb, fill=fg)
        d.text((pad, y + 66), VARIANTS[L][0], font=fr, fill=fg)
        for c, t in enumerate(tags):
            im = on(bg, stills[(L, t)]).resize((cw, ch), Image.LANCZOS)
            sheet.paste(im, (label_w + c * (cw + pad), y))
    sheet.save(path)


TAG_LABEL = {"m20": "-20°", "p00": "0°", "p20": "+20°"}


def write_html(out, letters, tags, seconds):
    rows = []
    for L in letters:
        name, desc = VARIANTS[L]
        imgs = "".join(
            f'<figure><img src="{L}/{L}_{t}.png" alt="{L} {TAG_LABEL[t]}"><figcaption>{TAG_LABEL[t]}</figcaption></figure>'
            for t in tags)
        rows.append(f"""
<section>
  <header><span class="letter">{L}</span><h2>{name}</h2><p>{desc}</p></header>
  <div class="strip">{imgs}
    <figure><img src="{L}/{L}_turntable.gif" alt="{L} turntable"><figcaption>turntable</figcaption></figure>
    <figure><img class="detail" src="{L}/{L}_detail.png" alt="{L} corner detail"><figcaption>corner, 3&times;</figcaption></figure>
  </div>
</section>""")
    html = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Card foil mockups</title>
<style>
  :root {{ --paper:#fff; --ink:#000; }}
  * {{ box-sizing:border-box; border-radius:0; box-shadow:none; }}
  body {{ margin:0; background:var(--paper); color:var(--ink);
         font:15px/1.45 Inter, system-ui, -apple-system, "Segoe UI", sans-serif; }}
  main {{ max-width:1500px; margin:0 auto; padding:40px 16px 80px; }}
  h1 {{ font-size:28px; font-weight:700; letter-spacing:-0.01em; margin:0 0 6px; }}
  .lede {{ margin:0 0 8px; max-width:72ch; }}
  .meta {{ margin:0 0 32px; font-size:13px; }}
  section {{ border-top:1px solid var(--ink); padding:20px 0 28px; }}
  header {{ display:grid; grid-template-columns:56px 1fr; column-gap:12px; margin-bottom:14px; }}
  .letter {{ grid-row:span 2; font-size:44px; font-weight:700; line-height:1; }}
  h2 {{ font-size:20px; margin:2px 0 2px; }}
  header p {{ margin:0; max-width:80ch; }}
  .strip {{ display:flex; gap:16px; overflow-x:auto; align-items:flex-start; }}
  figure {{ margin:0; flex:0 0 auto; }}
  figure img {{ display:block; height:360px; width:auto; }}
  figure img.detail {{ height:360px; }}
  figcaption {{ font-size:12px; margin-top:4px; }}
  .sheets a {{ color:var(--ink); }}
  @media (max-width:700px) {{ figure img, figure img.detail {{ height:240px; }} }}
</style></head>
<body><main>
<h1>Card foil mockups</h1>
<p class="lede">Six treatments for the 4% border band on a card face, rendered in Blender (Cycles) with one card,
one light rig and one camera. Stills are the card turned &minus;20&deg;, 0&deg; and +20&deg; about its vertical
axis with the lights fixed; the turntable sweeps &minus;25&deg;&rarr;+25&deg;&rarr;&minus;25&deg;. In the app the
pointer stands in for that tilt.</p>
<p class="meta sheets">Contact sheets: <a href="contact_white.png">on paper</a> &middot;
<a href="contact_black.png">on ink</a>. Render time {seconds/60:.1f} min on 4 CPU cores.</p>
{''.join(rows)}
</main></body></html>
"""
    (out / "index.html").write_text(html)


# =================================================================================
# Main
# =================================================================================

def render_to(path):
    bpy.context.scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


def set_pose(card, mat, letter, tilt_deg):
    card.rotation_euler = (math.radians(90), 0, math.radians(tilt_deg))
    if letter == "A":
        # foilAngleFor(feel) = FOIL_REST + feel.x * 25, with feel.x = tilt / 25.
        feel = max(-1.0, min(1.0, tilt_deg / 25.0))
        mat.node_tree.nodes["foil_angle"].outputs[0].default_value = 135.0 + feel * 25.0


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--card", default="89631139", help="passcode (YGOPRODeck) or a local image path")
    ap.add_argument("--variants", default="ABCDEF")
    ap.add_argument("--still-height", type=int, default=720)
    ap.add_argument("--still-samples", type=int, default=128)
    ap.add_argument("--gif-height", type=int, default=400)
    ap.add_argument("--gif-samples", type=int, default=40)
    ap.add_argument("--gif-frames", type=int, default=20)
    ap.add_argument("--no-gif", action="store_true")
    ap.add_argument("--only-present", action="store_true", help="skip rendering; rebuild sheets/html from raw/")
    a = ap.parse_args(argv)

    out = a.out.resolve()
    tex, raw = out / "textures", out / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    letters = [L for L in a.variants.upper() if L in VARIANTS]
    tags = ["m20", "p00", "p20"]
    tilts = {"m20": -20.0, "p00": 0.0, "p20": 20.0}
    t0 = time.time()

    if not a.only_present:
        make_textures(tex)
        card_path = Path(a.card) if Path(a.card).exists() else fetch_card(out / f"card_{a.card}.jpg", a.card)
        aspect = 0.74    # frame width / height: room for the card turned 25 degrees
        sh = a.still_height
        card = build_scene(int(sh * aspect) // 2 * 2, sh)
        sc = bpy.context.scene
        mats = {L: build_material(L, card_path, tex) for L in letters}
        for L in letters:
            card.data.materials.clear()
            card.data.materials.append(mats[L])
            sc.render.resolution_x, sc.render.resolution_y = int(sh * aspect) // 2 * 2, sh
            sc.cycles.samples = a.still_samples
            for t in tags:
                set_pose(card, mats[L], L, tilts[t])
                s = time.time()
                render_to(raw / f"{L}_{t}.png")
                print(f"{L} {t}: {time.time() - s:.1f}s", flush=True)
            if not a.no_gif:
                gh = a.gif_height
                sc.render.resolution_x, sc.render.resolution_y = int(gh * aspect) // 2 * 2, gh
                sc.cycles.samples = a.gif_samples
                for i in range(a.gif_frames):
                    tilt = -25.0 * math.cos(2 * math.pi * i / a.gif_frames)
                    set_pose(card, mats[L], L, tilt)
                    render_to(raw / f"{L}_gif{i:02d}.png")
                print(f"{L} gif done at {time.time() - t0:.0f}s", flush=True)

    # ---- presentation
    for L in letters:
        d = out / L
        d.mkdir(exist_ok=True)
        for t in tags:
            im = Image.open(raw / f"{L}_{t}.png")
            on((255, 255, 255), im).save(d / f"{L}_{t}.png")
            on((0, 0, 0), im).save(d / f"{L}_{t}_black.png")
        # Top-left corner of the 0-degree still, 3x, so a 4% band can be judged.
        rgba = Image.open(raw / f"{L}_p00.png")
        x0, y0, _, _ = rgba.getchannel("A").point(lambda p: 255 if p > 128 else 0).getbbox()
        im = on((255, 255, 255), rgba)
        h = im.size[1]
        crop = im.crop((x0 - 8, y0 - 8, x0 - 8 + int(h * 0.2), y0 - 8 + int(h * 0.25)))
        crop.resize((crop.width * 3, crop.height * 3), Image.LANCZOS).save(d / f"{L}_detail.png")
        frames = sorted(raw.glob(f"{L}_gif*.png"))
        if frames:
            pics = [on((255, 255, 255), Image.open(f)) for f in frames]
            pal = [p.convert("P", palette=Image.ADAPTIVE, colors=255, dither=Image.FLOYDSTEINBERG) for p in pics]
            pal[0].save(d / f"{L}_turntable.gif", save_all=True, append_images=pal[1:], duration=90, loop=0, optimize=False, disposal=1)
    contact_sheet(out, letters, tags, (255, 255, 255), (0, 0, 0), out / "contact_white.png")
    contact_sheet(out, letters, tags, (0, 0, 0), (255, 255, 255), out / "contact_black.png")
    secs = time.time() - t0
    timing = out / "render_seconds.txt"
    if not a.only_present:
        timing.write_text(f"{secs:.0f}\n")
    elif timing.exists():
        secs = float(timing.read_text())
    write_html(out, letters, tags, secs)
    print(f"done in {secs:.0f}s -> {out}")


if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    main(argv)
