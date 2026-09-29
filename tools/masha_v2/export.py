"""Stage 4 - export (owner: orchestrator).

build(ctx), after body/face/hands:
  1. Masha_Body loses the MPFB helper geometry (the MASK modifier's hidden verts) - the GLB carries
     only the visible skin.
  2. Names/materials = the HoloRig/HoloShader contract: nodes Masha_Body, Masha_Head, Masha_Eyes,
     Masha_Hair; materials Masha_Skin, Masha_MouthInterior, Masha_Teeth, Masha_Tongue, Masha_Brows,
     Masha_Lashes, Masha_Eye, Masha_Hair. The GLB materials are plain (no images): at runtime
     HoloShader replaces every one of them and reads its textures from assets/masha/textures/.
  3. Clips on Masha_Rig (anim/clips.py, owner Agent 4): Base, Idle, Listen, Think, Var_*, Talk_*, Wave,
     Point, Nod, Shrug, React_* - Mixamo mocap retargeted and layered on the base pose
     (anim/base_pose.py) plus hand-keyed IK clips; 30 fps, every clip starts/ends in the base pose
     (loops: first == last frame). Soft bones (breast/glute/hair) and twist bones are NOT keyed:
     the app drives them (SpringBonesFilament).
  4. The v1 holotank (tools/masha/build_masha.py: pedestal, beam, rings + RingSpin0/1).
  5. <out>/masha.glb (high), then Masha_Body LOD (body.make_lod) -> <out>/masha_lite.glb.
  6. <out>/runtime_textures/: the PNGs HoloShader.TextureAssets names.

"""
import json
import os
import shutil
import sys

import bpy
from mathutils import Quaternion, Vector

import common

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_TOOLS = os.environ.get("MASHA_V1_TOOLS", "")  # dir of tools/masha (v1 build_masha.py) for the holotank

FPS = 30

MPFB_DATA = os.path.join(os.environ.get("APPDATA", ""), "Blender Foundation", "Blender", "4.2",
                         "extensions", ".user", "blender_org", "mpfb", "data")
CARD_TEXTURES = {  # runtime name -> MPFB CC0 asset (same assets as on the mesh)
    "ponytail01_diffuse.png": ("hair", "ponytail01", "ponytail01_diffuse.png"),
    "eyebrow008.png": ("eyebrows", "eyebrow008", "eyebrow008.png"),
    "eyelashes03.png": ("eyelashes", "eyelashes03", "eyelashes03.png"),
}
STAGE_TEXTURES = ["masha_suit_mask_2048.png", "masha_suit_normal_2048.png", "masha_eye_basecolor.png",
                  "masha_eye_irismask.png", "masha_teeth_basecolor.png", "masha_tongue_basecolor.png"]

ANIM_DIR = os.path.join(HERE, "anim")
# base pose JSON (anim/base_pose.py output): ctx["base_pose"] (dict) > $MASHA_BASE_POSE >
# <out_dir>/base_pose.json > the working copy next to the Mixamo downloads
BASE_POSE_DEFAULT = r"C:/Users/jefer/Documents/Masha_Mixamo/trabajo/base_pose.json"

M = "mixamorig:"


# ------------------------------------------------------------------ clips (anim/clips.py)
def _base_pose(ctx):
    if ctx.get("base_pose"):
        return ctx["base_pose"]
    for p in (os.environ.get("MASHA_BASE_POSE", ""), os.path.join(ctx["out_dir"], "base_pose.json"),
              BASE_POSE_DEFAULT):
        if p and os.path.isfile(p):
            with open(p) as fh:
                print("EXPORT base pose", p)
                return json.load(fh)
    raise FileNotFoundError("base_pose.json not found (set MASHA_BASE_POSE)")


def bake_clips(ctx, rig_ob, body):
    """Author (or take ctx["anim_clips"]) and bake every body clip onto Masha_Rig as muted NLA tracks
    (30 fps, keys from frame 0, all mixamorig bones + Hips location; masha:* never keyed).
    See anim/clips.py for the clip list and the layering method."""
    if ANIM_DIR not in sys.path:
        sys.path.insert(0, ANIM_DIR)
    import clips as anim_clips
    made = ctx.get("anim_clips")
    if made is None:
        lib = anim_clips.Library(rig_ob, _base_pose(ctx), {"poses": _hand_poses(ctx)}, body=body)
        made = lib.build()
    anim_clips.bake(rig_ob, made)
    for pb in rig_ob.pose.bones:      # export from the rest pose (the clips carry the base pose)
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = Quaternion()
        pb.location = Vector()
    for c in made:
        print(f"EXPORT clip {c.name}: {c.seconds:.2f} s {'loop' if c.loop else 'one-shot'} <- {c.source}")
    return made


# ------------------------------------------------------------------ geometry / materials
def strip_helpers(body):
    import bmesh
    for m in list(body.modifiers):
        if m.type == "MASK":
            body.modifiers.remove(m)
    g = body.vertex_groups.get("body")
    if g is None:
        return
    gi = g.index
    keep = {v.index for v in body.data.vertices if any(e.group == gi and e.weight > 0 for e in v.groups)}
    bm = bmesh.new()
    bm.from_mesh(body.data)
    bm.verts.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.index not in keep], context="VERTS")
    bm.to_mesh(body.data)
    bm.free()
    bones = {b.name for b in body.parent.data.bones} if body.parent else set()
    for vg in list(body.vertex_groups):
        if vg.name not in bones:
            body.vertex_groups.remove(vg)
    print("EXPORT body without helpers: verts", len(body.data.vertices), "tris", common.tri_count(body))


def triangulate_ngons():
    """glTF tangents (MikkTSpace) need tris/quads; MPFB proxies carry a few n-gons. Shape keys survive."""
    import bmesh
    for o in bpy.data.objects:
        if o.type != "MESH":
            continue
        bm = bmesh.new()
        bm.from_mesh(o.data)
        ng = [f for f in bm.faces if len(f.verts) > 4]
        if ng:
            bmesh.ops.triangulate(bm, faces=ng)
            bm.to_mesh(o.data)
            print("EXPORT triangulated", len(ng), "n-gons on", o.name)
        bm.free()


def plain(name, rgba):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    bsdf.inputs["Base Color"].default_value = rgba
    bsdf.inputs["Roughness"].default_value = 0.5
    if rgba[3] < 1:
        bsdf.inputs["Alpha"].default_value = rgba[3]
        mat.blend_method = "BLEND"
    return mat


PLAIN = {
    "Masha_Skin": (0.07, 0.29, 1.0, 0.86), "Masha_MouthInterior": (0.05, 0.03, 0.04, 1.0),
    "Masha_Teeth": (0.7, 0.72, 0.75, 1.0), "Masha_Tongue": (0.4, 0.2, 0.22, 1.0),
    "Masha_Brows": (0.1, 0.2, 0.5, 0.8), "Masha_Lashes": (0.05, 0.1, 0.3, 0.9),
    "Masha_Eye": (0.8, 0.82, 0.85, 1.0), "Masha_Hair": (0.12, 0.3, 0.8, 0.85),
}


def contract_materials(ctx):
    O = bpy.data.objects
    hair = O.get("Masha_Hair")
    if hair is not None:
        hair.data.materials.clear()
        hair.data.materials.append(bpy.data.materials.new("Masha_Hair"))
    eyes = O.get("Masha_Eyes")
    if eyes is not None and eyes.data.materials and eyes.data.materials[0].name != "Masha_Eye":
        eyes.data.materials[0].name = "Masha_Eye"
    for o in O:
        if o.type != "MESH":
            continue
        for i, m in enumerate(o.data.materials):
            if m is None:
                continue
            base_name = m.name.split(".")[0]
            if base_name in PLAIN:
                o.data.materials[i] = plain(base_name, PLAIN[base_name])
        # UV0 must be the body UV (suit mask / card maps): keep only the active render UV layer.
        uv = o.data.uv_layers
        if len(uv) > 1:
            keep = next((l for l in uv if l.active_render), uv[0]).name
            for l in [l.name for l in uv if l.name != keep]:
                uv.remove(uv[l])
    for img in list(bpy.data.images):
        if img.users == 0:
            bpy.data.images.remove(img)


# ------------------------------------------------------------------ holotank
def holotank():
    tools = REPO_TOOLS or os.path.normpath(os.path.join(HERE, "..", "masha"))
    if not os.path.isfile(os.path.join(tools, "build_masha.py")):
        print("EXPORT holotank skipped: build_masha.py not found (set MASHA_V1_TOOLS)")
        return
    if tools not in sys.path:
        sys.path.insert(0, tools)
    import build_masha as v1
    tex = v1.code_texture(512, "tank_code", v1.SEED + 3)
    v1.build_holotank(tex)
    print("EXPORT holotank from", tools)


# ------------------------------------------------------------------ export
def export_glb(path):
    common_kw = dict(
        filepath=path, export_format="GLB", use_selection=False, export_apply=False, export_yup=True,
        export_texcoords=True, export_normals=True, export_tangents=True, export_materials="EXPORT",
        export_image_format="AUTO", export_skins=True, export_morph=True, export_morph_normal=True,
        export_morph_tangent=False, export_animations=True, export_nla_strips=True,
        export_def_bones=False, export_extras=False, export_lights=False,
        export_cameras=False, export_all_influences=False, export_animation_mode="NLA_TRACKS",
        export_frame_step=1,
        # Clips are baked LINEAR at 30 fps by anim/clips.bake (keys already reduced within 0.02 deg):
        # export those keys as they are (no resampling). The exporter still writes T/R/S channels for
        # every joint (masha:* included); anim/glb_tools.py prunes them after the export (below).
        export_force_sampling=False, export_optimize_animation_size=True,
    )
    try:
        bpy.ops.export_scene.gltf(**common_kw, export_try_sparse_sk=True)
    except TypeError:
        bpy.ops.export_scene.gltf(**common_kw)
    # clip contract: no channels on masha:* bones, no scale, translation only on the Hips
    if ANIM_DIR not in sys.path:
        sys.path.insert(0, ANIM_DIR)
    import glb_tools
    st = glb_tools.prune_animation_channels(path)
    print(f"EXPORT pruned animation channels {st['channels_before']} -> {st['channels_after']}")
    # Filament 1.56 does not deform with sparse morph accessors that have no bufferView (what
    # export_try_sparse_sk writes): give them a shared all-zero base (see densify_morphs.py).
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    import densify_morphs
    densify_morphs.rewrite(path, path, "shared")
    print(f"EXPORT {path} {os.path.getsize(path) / 1e6:.2f} MB")


def copy_textures(ctx):
    out = common.ensure_dir(os.path.join(ctx["out_dir"], "runtime_textures"))
    src = os.path.join(ctx["out_dir"], "textures")
    for f in STAGE_TEXTURES:
        p = os.path.join(src, f)
        if os.path.isfile(p):
            shutil.copy2(p, os.path.join(out, f))
        else:
            print("EXPORT missing stage texture", f)
    for f, (sub, d, name) in CARD_TEXTURES.items():
        p = os.path.join(MPFB_DATA, sub, d, name)
        if os.path.isfile(p):
            shutil.copy2(p, os.path.join(out, f))
        else:
            print("EXPORT missing MPFB texture", p)
    print("EXPORT runtime textures ->", out, sorted(os.listdir(out)))


def _hand_poses(ctx):
    if ctx.get("hand_poses"):
        return ctx["hand_poses"]
    with open(os.path.join(ctx["out_dir"], "hand_poses.json")) as fh:
        return json.load(fh)["poses"]


def build(ctx):
    O = bpy.data.objects
    body = ctx.get("body") or O["Masha_Body"]
    rig_ob = ctx.get("rig") or O["Masha_Rig"]
    strip_helpers(body)
    contract_materials(ctx)
    bake_clips(ctx, rig_ob, body)
    holotank()
    triangulate_ngons()
    copy_textures(ctx)
    for o in O:
        o.hide_set(False)
        o.hide_render = False
    export_glb(os.path.join(ctx["out_dir"], "masha.glb"))
    import body as bodymod
    bodymod.make_lod({"body": body, "rig": rig_ob}, ratio=float(os.environ.get("MASHA_LOD_RATIO", "0.5")), obj=body)
    export_glb(os.path.join(ctx["out_dir"], "masha_lite.glb"))


# ------------------------------------------------------------------ QA
def pose_at(rig_ob, clip, t):
    """Put the rig in clip `clip` at t seconds (for QA renders)."""
    ad = rig_ob.animation_data
    for tr in ad.nla_tracks:
        tr.mute = tr.name != clip
    bpy.context.scene.frame_set(int(round(t * FPS)))
