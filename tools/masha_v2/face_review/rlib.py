"""Mouth close-up render helpers (works on the pipeline head or an imported GLB head).
views: front / threeq / side (EEVEE, skin preview material) and cut (sagittal cutaway, Workbench)."""
import bpy, math, os
import numpy as np
from mathutils import Vector

MC = Vector((0.0, -0.150, 1.5935))   # mouth centre (Blender Z-up, face -Y)
DIST = 0.30
LENS = 100


def mat(name, rgb, rough=0.5, sss=0.0):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes.get("Principled BSDF")
    if p is None:
        m.node_tree.nodes.clear()
        p = m.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
        out = m.node_tree.nodes.new("ShaderNodeOutputMaterial")
        m.node_tree.links.new(p.outputs["BSDF"], out.inputs["Surface"])
    for l in list(p.inputs["Base Color"].links):
        m.node_tree.links.remove(l)
    p.inputs["Base Color"].default_value = (*rgb, 1)
    p.inputs["Roughness"].default_value = rough
    p.inputs["Alpha"].default_value = 1.0
    if sss:
        p.inputs["Subsurface Weight"].default_value = sss
        p.inputs["Subsurface Radius"].default_value = (0.02, 0.008, 0.004)
    m.diffuse_color = (*rgb, 1)
    m.blend_method = "OPAQUE"
    return m


PREVIEW = {"Skin": ((0.55, 0.38, 0.31), 0.5, 0.15), "MouthInterior": ((0.30, 0.10, 0.12), 0.6, 0),
           "Teeth": ((0.92, 0.90, 0.85), 0.35, 0), "Tongue": ((0.85, 0.40, 0.55), 0.55, 0),
           "Brows": ((0.12, 0.08, 0.06), 0.6, 0), "Lashes": ((0.05, 0.04, 0.04), 0.6, 0)}


def setup(head, hide_others=True):
    for o in bpy.data.objects:
        if o.type == "MESH" and o != head and not o.name.startswith("Masha_Eyes") and hide_others:
            o.hide_render = True
        if o.type == "LIGHT":
            bpy.data.objects.remove(o, do_unlink=True)
    for i, m in enumerate(head.data.materials):
        key = next((k for k in PREVIEW if m is not None and k in m.name), None)
        if key:
            rgb, r, s = PREVIEW[key]
            head.data.materials[i] = mat("pv_" + key, rgb, r, s)
    sc = bpy.context.scene
    sc.world = sc.world or bpy.data.worlds.new("w")
    sc.world.color = (0.04, 0.04, 0.05)
    sc.view_settings.view_transform = "AgX"
    for name, loc, energy, size in (("k", (0.5, -0.9, 1.9), 22, 0.8), ("f", (-0.7, -0.8, 1.7), 8, 1.0),
                                    ("r", (-0.4, 0.6, 1.8), 12, 0.6), ("b", (0.0, -0.6, 1.45), 2, 0.6)):
        L = bpy.data.objects.new("pl_" + name, bpy.data.lights.new("pl_" + name, "AREA"))
        L.data.energy, L.data.size, L.location = energy, size, loc
        L.rotation_euler = (MC - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        sc.collection.objects.link(L)
    cam = bpy.data.objects.get("pl_cam") or bpy.data.objects.new("pl_cam", bpy.data.cameras.new("pl_cam"))
    if cam.name not in sc.collection.objects:
        sc.collection.objects.link(cam)
    sc.camera = cam
    # label
    cu = bpy.data.curves.new("pl_lbl", "FONT")
    t = bpy.data.objects.new("pl_lbl", cu)
    sc.collection.objects.link(t)
    t.parent = cam
    em = bpy.data.materials.new("pl_lblmat")
    em.use_nodes = True
    em.node_tree.nodes.clear()
    e = em.node_tree.nodes.new("ShaderNodeEmission")
    e.inputs["Color"].default_value = (1, 0.15, 0.55, 1)
    e.inputs["Strength"].default_value = 3
    o = em.node_tree.nodes.new("ShaderNodeOutputMaterial")
    em.node_tree.links.new(e.outputs[0], o.inputs[0])
    em.diffuse_color = (1, 1, 0.3, 1)
    cu.materials.append(em)
    return cam, t


def views(mc=MC, dist=DIST):
    a = math.radians(35)
    return {"front": (mc + Vector((0, -dist, 0.006)), mc, None),
            "threeq": (mc + Vector((dist * math.sin(a), -dist * math.cos(a), 0.006)), mc, None),
            "side": (mc + Vector((dist, -0.008, 0.0)), mc + Vector((0, -0.008, 0)), None),
            "cut": (mc + Vector((dist, -0.004, -0.004)), mc + Vector((0, 0.022, -0.004)), dist - 0.0005)}


def set_keys(head, keys):
    kb = head.data.shape_keys.key_blocks
    for k in kb[1:]:
        k.slider_min = -1.0
        k.value = 0.0
    for n, v in keys.items():
        if n in kb:
            kb[n].slider_max = max(1.0, v)
            kb[n].value = v
    bpy.context.view_layer.update()


def shot(path, cam, lbl, view, label, res=(400, 330)):
    sc = bpy.context.scene
    loc, tgt, clip = views()[view]
    cam.location = loc
    cam.rotation_euler = (tgt - loc).to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = LENS
    cam.data.clip_start = clip or 0.02
    d = (clip or 0.02) + 0.002
    half = d * 18 / LENS
    lbl.data.body = label
    lbl.data.size = half * 0.13
    lbl.location = (-half * 0.95, half * (res[1] / res[0]) * 0.95 - half * 0.13, -d)
    lbl.rotation_euler = (0, 0, 0)
    if view == "cut":
        sc.render.engine = "BLENDER_WORKBENCH"
        sc.display.shading.light = "STUDIO"
        sc.display.shading.color_type = "MATERIAL"
        sc.display.shading.show_cavity = False
    else:
        sc.render.engine = "BLENDER_EEVEE_NEXT"
        sc.eevee.taa_render_samples = 16
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    return path


def sheet(paths, out, cols):
    ims = [bpy.data.images.load(p) for p in paths]
    w, h = ims[0].size
    rows = (len(ims) + cols - 1) // cols
    big = np.zeros((rows * h, cols * w, 4), dtype=np.float32)
    big[..., 3] = 1
    for i, im in enumerate(ims):
        a = np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4)
        r, c = divmod(i, cols)
        y0 = (rows - 1 - r) * h
        big[y0:y0 + h, c * w:(c + 1) * w] = a
        big[y0:y0 + 1, c * w:(c + 1) * w, :3] = 0.3
        big[y0:y0 + h, c * w:c * w + 1, :3] = 0.3
        bpy.data.images.remove(im)
    img = bpy.data.images.new("pl_sheet", cols * w, rows * h, alpha=True)
    img.pixels.foreach_set(big.ravel())
    img.filepath_raw = out
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)
    return out


def render_grid(head, specs, vlist, out_png, tile_dir, cols=None, res=(400, 330), rows_per_page=4):
    """specs: [(label, {morph: w})]; one tile per (spec, view), row-major spec x view unless cols given."""
    cam, lbl = bpy.data.objects["pl_cam"], bpy.data.objects["pl_lbl"]
    os.makedirs(tile_dir, exist_ok=True)
    paths = []
    for label, keys in specs:
        set_keys(head, keys)
        for v in vlist:
            safe = "".join(ch if ch.isalnum() else "_" for ch in label)[:60]
            paths.append(shot(os.path.join(tile_dir, f"{safe}__{v}.png"), cam, lbl, v, f"{label} [{v}]", res))
    set_keys(head, {})
    c = cols or len(vlist)
    per = c * rows_per_page
    outs = []
    for p in range(0, len(paths), per):
        o = out_png if len(paths) <= per else out_png.replace(".png", f"_p{p // per + 1}.png")
        outs.append(sheet(paths[p:p + per], o, c))
    return outs
