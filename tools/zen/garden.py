"""
The zen garden's materials, made in Blender and baked for the app.

Neue's zen mode (docs/NEUE.md §3a) draws a garden of white sand behind the deck,
raked live by a steel ball tracing mathematical curves — a kinetic sand table.
The *pattern* is drawn by the app, frame by frame, into a height field; what it
cannot invent at run time is what sand looks like up close. That is made here:

- ``sand_normal.png`` / ``sand_height.png`` / ``sand_albedo.png`` — a tileable
  1024 x 1024 patch of fine sand: individual grains (a Voronoi field, domed), a
  low drift of unevenness between them, and the odd darker grain. Cycles bakes
  the shading normal (tangent space), the grain height and the diffuse colour
  off a flat plane whose material carries all of it as bump and colour.
- ``ball.png`` — the stylus: a polished steel ball, rendered top-down in an
  orthographic camera under a soft key and a studio-like environment, on a
  transparent film, 256 x 256.

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


def render_ball(out_dir):
    scene = reset()
    scene.cycles.samples = 256
    scene.render.resolution_x = 256
    scene.render.resolution_y = 256
    scene.render.film_transparent = True

    bpy.ops.mesh.primitive_uv_sphere_add(radius=1.0, segments=96, ring_count=48)
    ball = bpy.context.active_object
    bpy.ops.object.shade_smooth()
    steel = bpy.data.materials.new("steel")
    steel.use_nodes = True
    p = steel.node_tree.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = (0.62, 0.62, 0.62, 1)
    p.inputs["Metallic"].default_value = 1.0
    p.inputs["Roughness"].default_value = 0.28
    ball.data.materials.append(steel)

    # A soft studio: a big key from the upper left (the garden's own light), a
    # dim fill, and an environment with a bright band so the steel has
    # something to reflect.
    bpy.ops.object.light_add(type="AREA", location=(-3.5, 3.5, 5.0))
    key = bpy.context.active_object
    key.data.energy = 900
    key.data.size = 3.0
    key.rotation_euler = (0.6, -0.45, 0)
    bpy.ops.object.light_add(type="AREA", location=(4.0, -3.0, 3.0))
    fill = bpy.context.active_object
    fill.data.energy = 160
    fill.data.size = 4.0
    fill.rotation_euler = (-0.6, 0.6, 0)

    # Round lights: a mirror shows the shape of what it reflects.
    for light in (key, fill):
        light.data.shape = "DISK"

    world = bpy.data.worlds.new("studio")
    scene.world = world
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.32, 0.32, 0.32, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 1.0

    bpy.ops.object.camera_add(location=(0, 0, 10))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 2.02
    scene.camera = cam

    path = os.path.join(out_dir, "ball.png")
    scene.render.filepath = path
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    bpy.ops.render.render(write_still=True)
    # Grey, like everything else in the garden.
    img = Image.open(path).convert("RGBA")
    arr = np.asarray(img).astype(np.float32)
    lum = arr[..., 0] * 0.2126 + arr[..., 1] * 0.7152 + arr[..., 2] * 0.0722
    arr[..., 0] = arr[..., 1] = arr[..., 2] = lum
    Image.fromarray(arr.astype(np.uint8), "RGBA").save(path, optimize=True)
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
    render_ball(args.out)
    print("[zen] wrote", sorted(os.listdir(args.out)))


if __name__ == "__main__":
    main()
