"""Shared helpers for the Masha v2 build (Blender 4.2 LTS + MPFB 2.0.17, headless).

Pipeline contract (each stage owns its module, see build.py):
  body.build(ctx)   -> MPFB human, macros baked, mixamo rig, eyes/brows/lashes/hair/teeth/tongue,
                       soft bones (breast/glute/hair chains), suit masks.       [Agent 3]
  face.build(ctx)   -> face targets subset, head split (Masha_Head), eye bones,
                       closed-mouth rest, eye/iris materials.                    [Agent 4]
  hands.build(ctx)  -> forearm twist bones + weights, finger pose library.        [Agent 5]
  export.build(ctx) -> clips, materials/names contract for HoloRig, GLB per tier. [orchestrator]

ctx is a plain dict. Keys set by stages (never rename, only add):
  body, rig, eyes, brows, lashes, hair, teeth, tongue  (bpy objects; may be None)
  head                                                 (set by face stage)
  soft_bones  : {bone_name: {"kind": "breast"|"glute"|"hair", "parent": str}}
  hand_poses  : {pose_name: {bone_name: (x_deg, y_deg, z_deg)}}   local-space euler XYZ
  face_shapes : [shape key names kept on Masha_Head]
  out_dir     : str, qa_dir: str, quality: "high"|"lite"
Units: metres, Blender Z-up, character faces -Y, feet on ground at z=0.
Bone names: MPFB "mixamo" rig ("mixamorig:Hips", ...). Added bones use "masha:" prefix.
"""
import importlib
import math
import os
import sys

import bpy
from mathutils import Vector


def dynamic_import(pkg, key):
    for amod in list(sys.modules):
        if amod.endswith(pkg):
            return getattr(importlib.import_module(amod), key)
    raise ValueError(f"MPFB module {pkg} not loaded; is the MPFB extension enabled?")


def mpfb(name):
    """mpfb('HumanService') -> class from mpfb.services.<lower>."""
    return dynamic_import(f"mpfb.services.{name.lower()}", name)


def clear_scene():
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for coll in (bpy.data.meshes, bpy.data.armatures, bpy.data.materials, bpy.data.images, bpy.data.actions):
        for block in list(coll):
            if block.users == 0:
                coll.remove(block)


def select_only(*objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]


def ensure_dir(path):
    os.makedirs(path, exist_ok=True)
    return path


def tri_count(obj):
    return sum(len(p.vertices) - 2 for p in obj.data.polygons)


def report(ctx, label=""):
    total = 0
    print(f"== REPORT {label}")
    for o in bpy.data.objects:
        if o.type == "MESH" and not o.hide_render:
            t = tri_count(o)
            total += t
            keys = len(o.data.shape_keys.key_blocks) if o.data.shape_keys else 0
            print(f"   {o.name:32s} verts={len(o.data.vertices):6d} tris={t:6d} keys={keys:3d} mats={[m.name for m in o.data.materials if m]}")
    rig = ctx.get("rig")
    if rig:
        print(f"   bones={len(rig.data.bones)}")
    print(f"   TOTAL tris={total}")
    return total


# ---------------------------------------------------------------- QA renders
def _look(cam, loc, target):
    cam.location = Vector(loc)
    cam.rotation_euler = (Vector(target) - cam.location).to_track_quat("-Z", "Y").to_euler()


def qa_renders(out_dir, prefix, shots=None, engine="BLENDER_EEVEE_NEXT", res=(900, 1400)):
    """Neutral clay-ish renders for critical review. shots: list of (name, cam_loc, target, lens)."""
    scene = bpy.context.scene
    scene.render.engine = engine
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.render.film_transparent = False
    if scene.world is None:
        scene.world = bpy.data.worlds.new("qa")
    scene.world.color = (0.05, 0.05, 0.06)
    cam = bpy.data.objects.get("qa_cam")
    if cam is None:
        cam = bpy.data.objects.new("qa_cam", bpy.data.cameras.new("qa_cam"))
        scene.collection.objects.link(cam)
        for name, rot, energy in (("qa_key", (math.radians(55), 0, math.radians(35)), 3.0),
                                  ("qa_fill", (math.radians(70), 0, math.radians(-60)), 1.2),
                                  ("qa_rim", (math.radians(110), 0, math.radians(180)), 2.5)):
            light = bpy.data.objects.new(name, bpy.data.lights.new(name, "SUN"))
            light.data.energy = energy
            light.rotation_euler = rot
            scene.collection.objects.link(light)
    scene.camera = cam
    shots = shots or [
        ("front", (0, -5.2, 0.95), (0, 0, 0.9), 50),
        ("profile", (5.2, 0, 0.95), (0, 0, 0.9), 50),
        ("threeq", (3.7, -3.7, 1.0), (0, 0, 0.9), 50),
        ("face", (0, -0.75, 1.60), (0, 0, 1.585), 85),
        ("face_threeq", (0.5, -0.55, 1.61), (0, 0, 1.585), 85),
    ]
    ensure_dir(out_dir)
    paths = []
    for name, loc, target, lens in shots:
        _look(cam, loc, target)
        cam.data.lens = lens
        scene.render.filepath = os.path.join(out_dir, f"{prefix}_{name}.png")
        bpy.ops.render.render(write_still=True)
        paths.append(scene.render.filepath)
    return paths


def parse_args(defaults):
    """blender -b -P script.py -- --key value ..."""
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = dict(defaults)
    for i in range(0, len(argv) - 1, 2):
        out[argv[i].lstrip("-")] = argv[i + 1]
    return out
