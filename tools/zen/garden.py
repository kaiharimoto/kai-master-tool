"""
The zen garden's materials, made in Blender and baked for the app.

Neue's zen mode (docs/NEUE.md §3a) draws a garden of white sand behind the deck,
raked live with the classical samon, then swept clean by a wide rake.
The *pattern* is computed by the app per pixel; what it cannot invent at run
time is what sand looks like up close, and what a rake looks like. Those are
made here:

- ``sand_normal.png`` / ``sand_height.png`` / ``sand_albedo.png`` — a tileable
  1024 x 1024 patch of fine sand: individual grains (a Voronoi field, domed), a
  low drift of unevenness between them, and the odd darker grain. Cycles bakes
  the shading normal (tangent space), the grain height and the diffuse colour
  off a flat plane whose material carries all of it as bump and colour.
- ``rake_bar.png`` / ``rake_handle.png`` — the rakes, seen from above, in
  parts: one six-tine length of wooden bar that tiles along its length, and a
  handle running back out of the picture, fading. The app lays one segment for
  the rake that draws the patterns and a whole window's height of them for the
  wide rake that sweeps it clean, so every tine sits on a groove it leaves.

Everything is grey: the garden is white sand on paper and black sand on ink,
never a colour (Master UI; the two colour exceptions are the foil and the group
markers). Tileability comes after the bake: the patch is cross-faded with
itself shifted by half, which leaves no seam at the edges and no visible
repeat at the grain scale.

Run with the pip ``bpy`` module (see tools/foil/README.md for the venv):

    ./venv/bin/python tools/zen/garden.py --out app/neue/src/jvmMain/resources/zen
"""

import argparse
import os
import sys

import bpy
import numpy as np
from PIL import Image

SIZE = 1024


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 16
    scene.view_settings.view_transform = "Standard"
    return scene


def sand_material():
    mat = bpy.data.materials.new("sand")
    mat.use_nodes = True
    nt = mat.node_tree
    n = nt.nodes
    links = nt.links
    n.clear()

    out = n.new("ShaderNodeOutputMaterial")
    bsdf = n.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Roughness"].default_value = 0.92
    links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])

    coord = n.new("ShaderNodeTexCoord")

    # Grains: a Voronoi field, each cell domed (1 - F1 distance, softened).
    grains = n.new("ShaderNodeTexVoronoi")
    grains.feature = "F1"
    grains.distance = "EUCLIDEAN"
    grains.inputs["Scale"].default_value = 210.0
    grains.inputs["Randomness"].default_value = 0.9
    links.new(coord.outputs["UV"], grains.inputs["Vector"])

    dome = n.new("ShaderNodeMapRange")
    dome.inputs["From Min"].default_value = 0.0
    dome.inputs["From Max"].default_value = 0.62
    dome.inputs["To Min"].default_value = 1.0
    dome.inputs["To Max"].default_value = 0.0
    dome.clamp = True
    links.new(grains.outputs["Distance"], dome.inputs["Value"])

    # A low drift between grains: sand is never a flat plate.
    drift = n.new("ShaderNodeTexNoise")
    drift.inputs["Scale"].default_value = 9.0
    drift.inputs["Detail"].default_value = 6.0
    drift.inputs["Roughness"].default_value = 0.55
    links.new(coord.outputs["UV"], drift.inputs["Vector"])

    height = n.new("ShaderNodeMath")
    height.operation = "MULTIPLY_ADD"
    height.inputs[1].default_value = 0.18  # drift weight
    links.new(drift.outputs["Fac"], height.inputs[0])
    links.new(dome.outputs["Result"], height.inputs[2])

    bump = n.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.85
    bump.inputs["Distance"].default_value = 0.004
    links.new(height.outputs["Value"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])

    # Colour: grains vary a little in value, and one in a few dozen is dark.
    tint = n.new("ShaderNodeMapRange")
    tint.inputs["From Min"].default_value = 0.0
    tint.inputs["From Max"].default_value = 1.0
    tint.inputs["To Min"].default_value = 0.80
    tint.inputs["To Max"].default_value = 0.97
    links.new(grains.outputs["Color"], tint.inputs["Value"])

    speck = n.new("ShaderNodeMath")
    speck.operation = "GREATER_THAN"
    speck.inputs[1].default_value = 0.988
    sep = n.new("ShaderNodeSeparateColor")
    links.new(grains.outputs["Color"], sep.inputs["Color"])
    links.new(sep.outputs["Green"], speck.inputs[0])

    darken = n.new("ShaderNodeMath")
    darken.operation = "MULTIPLY_ADD"
    darken.inputs[1].default_value = -0.28
    links.new(speck.outputs["Value"], darken.inputs[0])
    links.new(tint.outputs["Result"], darken.inputs[2])

    shade = n.new("ShaderNodeMath")
    shade.operation = "MULTIPLY"
    links.new(darken.outputs["Value"], shade.inputs[0])
    links.new(dome.outputs["Result"], shade.inputs[1])
    # Crevices between grains are a touch darker than the grains' tops.
    ao = n.new("ShaderNodeMapRange")
    ao.inputs["From Min"].default_value = 0.0
    ao.inputs["From Max"].default_value = 1.0
    ao.inputs["To Min"].default_value = 0.78
    ao.inputs["To Max"].default_value = 1.0
    links.new(dome.outputs["Result"], ao.inputs["Value"])
    value = n.new("ShaderNodeMath")
    value.operation = "MULTIPLY"
    links.new(darken.outputs["Value"], value.inputs[0])
    links.new(ao.outputs["Result"], value.inputs[1])

    grey = n.new("ShaderNodeCombineColor")
    links.new(value.outputs["Value"], grey.inputs["Red"])
    links.new(value.outputs["Value"], grey.inputs["Green"])
    links.new(value.outputs["Value"], grey.inputs["Blue"])
    links.new(grey.outputs["Color"], bsdf.inputs["Base Color"])

    # The height, for its own bake, through an emission the bake can read.
    emit = n.new("ShaderNodeEmission")
    links.new(height.outputs["Value"], emit.inputs["Color"])
    mat["emit"] = emit.name
    mat["bsdf"] = bsdf.name
    mat["out"] = out.name
    return mat


def bake_sand(out_dir):
    scene = reset()
    bpy.ops.mesh.primitive_plane_add(size=2)
    plane = bpy.context.active_object
    mat = sand_material()
    plane.data.materials.append(mat)
    nt = mat.node_tree

    def bake(kind, name, colorspace, fn):
        img = bpy.data.images.new(name, SIZE, SIZE, float_buffer=False)
        img.colorspace_settings.name = colorspace
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = img
        nt.nodes.active = tex
        fn()
        bpy.ops.object.bake(type=kind, pass_filter={"COLOR"} if kind == "DIFFUSE" else set(), margin=0)
        path = os.path.join(out_dir, "raw_" + name + ".png")
        img.filepath_raw = path
        img.file_format = "PNG"
        img.save()
        nt.nodes.remove(tex)
        return path

    bpy.context.view_layer.objects.active = plane
    plane.select_set(True)
    scene.render.bake.use_selected_to_active = False

    normal = bake("NORMAL", "sand_normal", "Non-Color", lambda: None)
    albedo = bake("DIFFUSE", "sand_albedo", "sRGB", lambda: None)

    # Height: route the emission to the output for the EMIT bake.
    out = nt.nodes[mat["out"]]
    emit = nt.nodes[mat["emit"]]
    bsdf = nt.nodes[mat["bsdf"]]
    nt.links.new(emit.outputs["Emission"], out.inputs["Surface"])
    height = bake("EMIT", "sand_height", "Non-Color", lambda: None)
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    return normal, albedo, height


def make_tileable(src, dst, normal=False):
    """Cross-fade the patch with itself shifted by half: seamless at every edge."""
    a = np.asarray(Image.open(src).convert("RGB")).astype(np.float32)
    h, w, _ = a.shape
    shifted = np.roll(np.roll(a, h // 2, axis=0), w // 2, axis=1)
    y = np.abs(np.linspace(-1, 1, h))[:, None]
    x = np.abs(np.linspace(-1, 1, w))[None, :]
    # Weight of the original: 1 in the middle, 0 at the edges (where the shifted
    # copy's middle is), smooth between.
    k = np.clip(1.0 - np.maximum(x, y), 0, 1)
    k = (k * k * (3 - 2 * k))[..., None]
    k = np.clip(k * 1.6, 0, 1)
    out = a * k + shifted * (1 - k)
    if normal:
        # Blended normals shorten; bring them back to unit length.
        v = out / 127.5 - 1.0
        v /= np.maximum(np.linalg.norm(v, axis=2, keepdims=True), 1e-6)
        out = (v + 1.0) * 127.5
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    if not normal:
        img = img.convert("L")
    img.save(dst, optimize=True)
    os.remove(src)
    return dst


def wood_material(name, grain="Y", distortion=0.0):
    """
    Pale, fine-grained wood, in grey: the garden has no colour. The grain runs
    along the piece — across y for the bar, which lies along x — and without
    distortion it depends on y alone, so a length of bar tiles seamlessly.
    """
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    p = nt.nodes["Principled BSDF"]
    p.inputs["Roughness"].default_value = 0.6
    coord = nt.nodes.new("ShaderNodeTexCoord")
    wave = nt.nodes.new("ShaderNodeTexWave")
    wave.wave_type = "BANDS"
    wave.bands_direction = grain
    wave.inputs["Scale"].default_value = 9.0
    wave.inputs["Distortion"].default_value = distortion
    wave.inputs["Detail"].default_value = 2.0
    nt.links.new(coord.outputs["Object"], wave.inputs["Vector"])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.50, 0.50, 0.50, 1)
    ramp.color_ramp.elements[1].color = (0.66, 0.66, 0.66, 1)
    nt.links.new(wave.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], p.inputs["Base Color"])
    return mat


# One unit of rake is one segment of six tines: sixty window pixels at the app's
# ten-pixel groove pitch. Sprites are drawn at four pixels to the window's one.
PX_PER_UNIT = 240
TINES_PER_UNIT = 6
BAR_DEPTH = 0.13
# The segment's frame, in units: from the back of the handle's joint to past the tine tips.
BAR_BACK, BAR_FRONT = -0.12, 0.2


def light_rake(scene, world_level):
    """
    The garden's own light: from the upper left, as the sand shader has it. A
    sun rather than a lamp, because a lamp falls off across the frame and the
    bar's segments would step in brightness where they meet.
    """
    bpy.ops.object.light_add(type="SUN", location=(0, 0, 5.0))
    key = bpy.context.active_object
    key.data.energy = 2.0
    key.data.angle = 0.35
    key.rotation_euler = (-0.72, -0.55, 0)
    world = bpy.data.worlds.new("soft")
    scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (world_level, world_level, world_level, 1)


def render_top(scene, path, centre_y, frame_w, frame_h):
    scene.render.resolution_x = round(frame_w * PX_PER_UNIT)
    scene.render.resolution_y = round(frame_h * PX_PER_UNIT)
    scene.render.film_transparent = True
    bpy.ops.object.camera_add(location=(0, centre_y, 10))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(frame_w, frame_h)
    scene.camera = cam
    scene.render.filepath = path
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    bpy.ops.render.render(write_still=True)


def grey(path, fade_from=None):
    """Drop what little colour the render has; optionally fade the image out toward its bottom."""
    img = Image.open(path).convert("RGBA")
    arr = np.asarray(img).astype(np.float32)
    lum = arr[..., 0] * 0.2126 + arr[..., 1] * 0.7152 + arr[..., 2] * 0.0722
    arr[..., 0] = arr[..., 1] = arr[..., 2] = lum
    if fade_from is not None:
        h = arr.shape[0]
        y = np.arange(h, dtype=np.float32)[:, None]
        start = h * fade_from
        arr[..., 3] *= np.clip(1.0 - (y - start) / (h - start), 0.0, 1.0) ** 1.5
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def render_rake_bar(out_dir, name="rake_bar.png"):
    """
    One unit of a rake's bar, seen from above: a squared length of wood with six
    tines under it, their tips just showing ahead of it (a rake is tilted as it
    is drawn). The bar runs past both edges of the frame, so the sprite tiles
    along x and a rake of any width is whole segments — every tine where the app
    draws a groove. The rake travels toward +y, up the image; the bar's centre
    line is at 0.625 of the height from the top.
    """
    scene = reset()
    scene.cycles.samples = 256
    wood = wood_material("wood", grain="Y")
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=(0, 0, 0.07))
    bar = bpy.context.active_object
    bar.scale = (4.0, BAR_DEPTH, 0.1)
    bpy.ops.object.modifier_add(type="BEVEL")
    bar.modifiers["Bevel"].width = 0.03
    bar.modifiers["Bevel"].segments = 6
    bar.data.materials.append(wood)
    pitch = 1.0 / TINES_PER_UNIT
    for k in range(-3 * TINES_PER_UNIT, 3 * TINES_PER_UNIT):
        x = pitch * (k + 0.5)
        bpy.ops.mesh.primitive_cone_add(radius1=0.02, radius2=0.01, depth=0.2, location=(x, BAR_DEPTH * 0.42, -0.06))
        tine = bpy.context.active_object
        tine.rotation_euler = (1.0, 0, 0)
        tine.data.materials.append(wood)
    light_rake(scene, 0.12)
    path = os.path.join(out_dir, name)
    render_top(scene, path, (BAR_BACK + BAR_FRONT) / 2, 1.0, BAR_FRONT - BAR_BACK)
    grey(path)
    return path


def render_rake_handle(out_dir, name="rake_handle.png"):
    """
    A handle, from where it meets the bar (the top of the image) running back
    toward the gardener and fading, since the gardener is out of the picture.
    It rises as it goes, so from above it is a little foreshortened.
    """
    scene = reset()
    scene.cycles.samples = 256
    wood = wood_material("handle", grain="X")
    length = 5.0
    tilt = 0.5
    bpy.ops.mesh.primitive_cylinder_add(radius=0.034, depth=length, vertices=48, location=(0, -length / 2 * np.cos(tilt), length / 2 * np.sin(tilt)))
    handle = bpy.context.active_object
    handle.rotation_euler = (np.pi / 2 - tilt, 0, 0)
    handle.data.materials.append(wood)
    light_rake(scene, 0.12)
    path = os.path.join(out_dir, name)
    frame_h = 3.0
    render_top(scene, path, -frame_h / 2, 0.2, frame_h)
    grey(path, fade_from=0.15)
    return path


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)

    normal, albedo, height = bake_sand(args.out)
    make_tileable(normal, os.path.join(args.out, "sand_normal.png"), normal=True)
    make_tileable(albedo, os.path.join(args.out, "sand_albedo.png"))
    make_tileable(height, os.path.join(args.out, "sand_height.png"))
    # The garden's rakes, in parts: a bar that tiles six tines at a time, and a handle.
    render_rake_bar(args.out)
    render_rake_handle(args.out)
    print("[zen] wrote", sorted(os.listdir(args.out)))


if __name__ == "__main__":
    main()
