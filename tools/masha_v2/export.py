"""Stage 4 - export (owner: orchestrator).

build(ctx), after body/face/hands:
  1. Masha_Body loses the MPFB helper geometry (the MASK modifier's hidden verts) - the GLB carries
     only the visible skin.
  2. Names/materials = the HoloRig/HoloShader contract: nodes Masha_Body, Masha_Head, Masha_Eyes,
     Masha_Hair; materials Masha_Skin, Masha_MouthInterior, Masha_Teeth, Masha_Tongue, Masha_Brows,
     Masha_Lashes, Masha_Eye, Masha_Hair. The GLB materials are plain (no images): at runtime
     HoloShader replaces every one of them and reads its textures from assets/masha/textures/.
  3. Clips on Masha_Rig (names HoloRig expects): Idle (6 s loop), Talk (4 s loop), Listen (4 s loop),
     Think (4 s), Explain (3.2 s), Wave (2.6 s). Body motion is authored as rotations about
     character axes (see `Pose`), hands/fingers come from Agent 5's pose library. Soft bones
     (breast/glute/hair) and twist bones are NOT keyed: the app drives them (SpringBonesFilament).
  4. The v1 holotank (tools/masha/build_masha.py: pedestal, beam, rings + RingSpin0/1).
  5. <out>/masha.glb (high), then Masha_Body LOD (body.make_lod) -> <out>/masha_lite.glb.
  6. <out>/runtime_textures/: the PNGs HoloShader.TextureAssets names.

Character axes (armature space = world: Z up, she faces -Y, her left is +X):
  FWD  about +X : bend forward (spine/head nod down); for an arm, positive = swing backwards
  TURN about +Z : turn towards her left
  LEAN about +Y : lean/roll towards her left; for the left arm, positive lowers it (adducts)
A rotation R (armature space) on a bone becomes the local pose rotation Rrest^-1 R Rrest, i.e. it
rotates the bone about that character axis as if its parents were at rest (children follow parents).
"""
import json
import math
import os
import shutil
import sys

import bpy
from mathutils import Euler, Quaternion, Vector

import common

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_TOOLS = os.environ.get("MASHA_V1_TOOLS", "")  # dir of tools/masha (v1 build_masha.py) for the holotank

FPS = 30
KEY_STEP = 2  # keyframe every 2 frames (15 Hz, sampled again by the exporter)
X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))

MPFB_DATA = os.path.join(os.environ.get("APPDATA", ""), "Blender Foundation", "Blender", "4.2",
                         "extensions", ".user", "blender_org", "mpfb", "data")
CARD_TEXTURES = {  # runtime name -> MPFB CC0 asset (same assets as on the mesh)
    "ponytail01_diffuse.png": ("hair", "ponytail01", "ponytail01_diffuse.png"),
    "eyebrow008.png": ("eyebrows", "eyebrow008", "eyebrow008.png"),
    "eyelashes03.png": ("eyelashes", "eyelashes03", "eyelashes03.png"),
}
STAGE_TEXTURES = ["masha_suit_mask_2048.png", "masha_suit_normal_2048.png", "masha_eye_basecolor.png",
                  "masha_eye_irismask.png", "masha_teeth_basecolor.png", "masha_tongue_basecolor.png"]

# Rest arms are an A-pose ~47 deg below horizontal; relaxed arms hang ~25 deg lower.
ARM_DOWN = 30.0
ELBOW_REST = 10.0

M = "mixamorig:"


def side_name(s):
    return "Left" if s > 0 else "Right"


# ------------------------------------------------------------------ posing
class Pose:
    """Per-frame pose: {bone: [armature-space quaternions]} + {bone: local quaternion} (hand poses)."""

    def __init__(self):
        self.arm = {}
        self.local = {}

    def rot(self, bone, axis, deg, first=False):
        """Rotations of a bone apply in call order; `first` puts this one before all others."""
        if abs(deg) > 1e-6:
            rs = self.arm.setdefault(bone, [])
            q = Quaternion(axis, math.radians(deg))
            rs.insert(0, q) if first else rs.append(q)
        return self

    def hand(self, pose, weight=1.0, sides=(1, -1)):
        """Blend a hand pose from hand_poses.json (weight 0..1) on top of whatever is there."""
        for bone, e in pose.items():
            s = 1 if f"{M}Left" in bone else -1
            if s not in sides:
                continue
            q = Euler([math.radians(v) for v in e], "XYZ").to_quaternion()
            base = self.local.get(bone, Quaternion())
            self.local[bone] = base.slerp(q, weight) if weight < 1 else q
        return self


class Rig:
    def __init__(self, rig):
        self.rig = rig
        self.rest = {b.name: b.matrix_local.to_quaternion() for b in rig.data.bones}
        self.head = {b.name: b.head_local.copy() for b in rig.data.bones}
        self.tail = {b.name: b.tail_local.copy() for b in rig.data.bones}
        for pb in rig.pose.bones:
            pb.rotation_mode = "QUATERNION"
        self.hinge = {}
        self.arm_axis = {s: (self.tail[f"{M}{side_name(s)}Arm"] - self.head[f"{M}{side_name(s)}Arm"]).normalized()
                         for s in (1, -1)}
        for s in (1, -1):
            # elbow hinge: perpendicular to the upper arm and to "forward" (-Y), signed so +deg
            # swings the forearm forwards/up
            arm = (self.tail[f"{M}{side_name(s)}Arm"] - self.head[f"{M}{side_name(s)}Arm"]).normalized()
            ax = arm.cross(Vector((0, -1, 0))).normalized()
            fore = (self.tail[f"{M}{side_name(s)}ForeArm"] - self.head[f"{M}{side_name(s)}ForeArm"]).normalized()
            test = Quaternion(ax, 0.2) @ fore
            self.hinge[s] = ax if test.y < fore.y else -ax

    def keyable(self):
        return [pb.name for pb in self.rig.pose.bones if pb.name.startswith(M)]

    def apply(self, pose, frame, bones):
        for name in bones:
            pb = self.rig.pose.bones[name]
            q = Quaternion()
            for r in pose.arm.get(name, ()):
                q = r @ q
            rr = self.rest[name]
            local = rr.inverted() @ q @ rr
            if name in pose.local:
                local = local @ pose.local[name]
            pb.rotation_quaternion = local
            pb.keyframe_insert("rotation_quaternion", frame=frame, group=name)


def elbow(p, rig, s, deg):
    return p.rot(f"{M}{side_name(s)}ForeArm", rig.hinge[s], deg)


def cycle(t, loop, period):
    """sin with the period nearest to `period` that fits a whole number of times in `loop` s."""
    n = max(1, round(loop / period))
    return 2 * math.pi * t * n / loop


def base(rig, poses, t, amp=1.0, loop=6.0):
    """Alive rest: breathing (~3 s), weight shift (~6 s), slow look (~6 s), arms and hands relaxed.
    Periods are fitted to `loop` so looping clips close seamlessly."""
    p = Pose()
    br = math.sin(cycle(t, loop, 3.0))
    sway = math.sin(cycle(t, loop, 6.0))
    look = math.sin(cycle(t, loop, 6.0) + 0.7)
    p.rot(M + "Hips", Y, 1.0 * sway * amp)
    p.rot(M + "Spine", Y, -0.7 * sway * amp)
    p.rot(M + "Spine1", X, -0.8 * br * amp)
    p.rot(M + "Spine2", X, -1.0 * br * amp)
    p.rot(M + "Neck", Z, 1.2 * look * amp)
    p.rot(M + "Head", Z, 2.0 * look * amp).rot(M + "Head", Y, 0.8 * sway * amp)
    for s in (1, -1):
        sd = side_name(s)
        p.rot(f"{M}{sd}Arm", Y, s * (ARM_DOWN + 0.8 * br * amp))
        p.rot(f"{M}{sd}Arm", X, -3.0)  # a touch forward, clear of the hips
        elbow(p, rig, s, ELBOW_REST + 1.5 * br * amp)
        p.rot(f"{M}{sd}Shoulder", X, -0.6 * br * amp)
    p.hand(poses["relaxed"])
    return p


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * min(max(t, 0.0), 1.0))


def envelope(t, dur, rise=0.25, fall=0.3):
    return ease(t / (dur * rise)) * ease((dur - t) / (dur * fall))


def clips(rig, poses):
    R = -1  # right side: the gesturing hand

    def talk(t):
        p = base(rig, poses, t, 1.2, loop=4.0)
        nod = math.sin(2 * math.pi * t / 1.0) * 0.6 + math.sin(2 * math.pi * t / 2.0) * 0.8
        g = 0.5 + 0.5 * math.sin(2 * math.pi * t / 2.0)
        p.rot(M + "Head", X, 1.8 * nod).rot(M + "Head", Z, 2.0 * math.sin(2 * math.pi * t / 4.0))
        p.rot(M + "RightArm", X, -(10 + 5 * g))
        elbow(p, rig, R, 30 + 18 * g)
        p.rot(M + "RightHand", Z, 8 * (g - 0.5))
        p.hand(poses["explain"], 0.35 + 0.25 * g, sides=(R,))
        return p

    def listen(t):
        p = base(rig, poses, t, 0.7, loop=4.0)
        p.rot(M + "Head", Y, -5.0).rot(M + "Head", X, 3.0).rot(M + "Neck", X, 2.0)
        p.rot(M + "Spine2", X, 1.5)
        return p

    def think(t):
        e = envelope(t, 4.0, 0.2, 0.2)
        p = base(rig, poses, t, 0.6)
        # thoughtful hand raised to chin height, elbow kept outside the torso (solved with the base
        # pose included, _armsearch3.py); the other arm stays relaxed
        p.rot(M + "RightArm", Y, 10 * e).rot(M + "RightArm", X, -35 * e).rot(M + "RightArm", Z, 20 * e)
        elbow(p, rig, R, 100 * e)
        p.hand(poses["thinking_chin"], e, sides=(R,))
        p.rot(M + "Head", Y, -5 * e).rot(M + "Head", X, 4 * e).rot(M + "Head", Z, 5 * e)
        return p

    def explain(t):
        e = envelope(t, 3.2, 0.3, 0.3)
        o = ease((t - 0.8) / 0.6) * e
        p = base(rig, poses, t, 0.8)
        p.rot(M + "RightArm", X, -32 * e).rot(M + "RightArm", Y, -10 * e)
        elbow(p, rig, R, 45 * e)
        p.rot(M + "RightHand", Z, -15 * o)
        p.hand(poses["explain"], max(e, o), sides=(R,))
        p.rot(M + "Head", Z, -6 * e).rot(M + "Head", X, 2 * e)
        return p

    def wave(t):
        e = envelope(t, 2.6, 0.25, 0.3)
        w = math.sin(2 * math.pi * t / 0.6) * ease((t - 0.5) / 0.3) * e
        p = base(rig, poses, t, 0.8)
        # elbow out at shoulder height, forearm up, hand waving (solved in _armsearch.py): the
        # upper arm first rotates outwards about its own axis so the elbow flexes upwards
        # (for the right arm +Y raises it; the base pose's ARM_DOWN is undone too)
        p.rot(M + "RightArm", rig.arm_axis[R], 75 * e, first=True)
        p.rot(M + "RightArm", Y, (ARM_DOWN + 45) * e).rot(M + "RightArm", X, -15 * e)
        elbow(p, rig, R, 80 * e)
        p.rot(M + "RightHand", Z, 14 * w)
        p.hand(poses["wave"], e, sides=(R,))
        p.rot(M + "Head", Z, -4 * e).rot(M + "Head", Y, -3 * e)
        return p

    return [("Idle", 6.0, lambda t: base(rig, poses, t)), ("Talk", 4.0, talk), ("Listen", 4.0, listen),
            ("Think", 4.0, think), ("Explain", 3.2, explain), ("Wave", 2.6, wave)]


def make_clip(rig, name, seconds, fn):
    ob = rig.rig
    ob.animation_data_create()
    act = bpy.data.actions.new(name)
    ob.animation_data.action = act
    bones = rig.keyable()
    frames = int(round(seconds * FPS))
    for f in list(range(0, frames, KEY_STEP)) + [frames]:
        rig.apply(fn(f / FPS), f + 1, bones)
    track = ob.animation_data.nla_tracks.new()
    track.name = name
    strip = track.strips.new(name, 1, act)
    strip.name = name
    track.mute = True
    ob.animation_data.action = None
    for pb in ob.pose.bones:
        pb.rotation_quaternion = Quaternion()
    return act


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
        export_force_sampling=True, export_def_bones=False, export_extras=False, export_lights=False,
        export_cameras=False, export_all_influences=False, export_animation_mode="NLA_TRACKS",
        export_frame_step=1, export_optimize_animation_size=True,
    )
    try:
        bpy.ops.export_scene.gltf(**common_kw, export_try_sparse_sk=True)
    except TypeError:
        bpy.ops.export_scene.gltf(**common_kw)
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
    rig = Rig(rig_ob)
    poses = _hand_poses(ctx)
    for name, sec, fn in clips(rig, poses):
        make_clip(rig, name, sec, fn)
        print("EXPORT clip", name, sec, "s")
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
    bpy.context.scene.frame_set(int(round(t * FPS)) + 1)
