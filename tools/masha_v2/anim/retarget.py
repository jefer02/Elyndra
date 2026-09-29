"""Mixamo FBX -> Masha_Rig retarget (owner: Agent 4). Runs inside Blender (headless).

    import retarget
    src = retarget.load_clip(rig_ob, "Waving")      # -> Motion (target-local tracks, 30 fps)

The Mixamo downloads were made on Masha's own skeleton (masha_mixamo.fbx = Masha_Rig minus the masha:*
bones), but Mixamo hands them back re-posed: its rest is a T-pose (arms horizontal, legs straight) and the
skeleton is translated so the Hips head sits on the origin axis. Bone lengths and the bone frames are
Masha's (Mixamo rotated the bones into the T-pose carrying their local frames), so the retarget is a
world-orientation copy:

    Qtgt_world(t) = Qsrc_world(t)                       (bone frames rigidly attached to the same limb)
    local(t)      = (Rp^-1 Rb)^-1 . Qsrc_world(parent)^-1 . Qsrc_world(bone)    (parents first)
    hips_head(t)  = hips_src(t) + (hips_rest_tgt - hips_rest_src)   (same proportions: ratio 1)

`world-rest-delta` (Δw = Qsrc(t) Qsrc_rest^-1, Qtgt = Δw Qtgt_rest) would be wrong here: the two rests are
different physical poses (T vs A). A quick check is printed per file: bone count / names / fps / unit scale
and the world-copy residual on the rest (the angle between source and target rest frames is the T<->A
pose change itself, the per-bone lengths must match within 1 mm).

The raw FBX files are read from MASHA_MIXAMO_DIR (default: the user's download folder) and never copied:
the Mixamo licence forbids redistributing them. Only baked motion inside the app GLB ships.
"""
import math
import os
import pickle
import tempfile

import bpy
from mathutils import Quaternion, Vector

M = "mixamorig:"
MIXAMO_DIR = os.environ.get("MASHA_MIXAMO_DIR", r"C:\Users\jefer\Documents\Masha_Mixamo\descargas")
FPS = 30
# retargeted tracks are cached here (derived from the Mixamo files: local only, never in the repo)
CACHE_DIR = os.environ.get("MASHA_ANIM_CACHE", os.path.join(tempfile.gettempdir(), "masha_anim_cache"))
CACHE_VERSION = 2


def find_fbx(name, folder=None):
    """Case/space/underscore-insensitive lookup: 'relieved_sigh' finds 'relieved Sigh.fbx'."""
    folder = folder or MIXAMO_DIR
    key = name.lower().replace(" ", "").replace("_", "").replace(".fbx", "")
    for f in os.listdir(folder):
        if f.lower().endswith(".fbx") and f.lower()[:-4].replace(" ", "").replace("_", "") == key:
            return os.path.join(folder, f)
    raise FileNotFoundError(f"Mixamo clip '{name}' not found in {folder}")


class Motion:
    """Retargeted clip: per mixamorig bone a list of target-LOCAL quaternions (pose rotation_quaternion
    of Masha_Rig) and the Hips pose location per frame. frames = number of samples at `fps`."""

    def __init__(self, name, fps, rot, hips_loc):
        self.name = name
        self.fps = fps
        self.rot = rot            # {bone: [Quaternion]}
        self.hips_loc = hips_loc  # [Vector] (pose-local, i.e. pb.location)
        self.frames = len(hips_loc)

    @property
    def seconds(self):
        return (self.frames - 1) / self.fps

    def q(self, bone, f):
        f = min(max(int(f), 0), self.frames - 1)
        return self.rot[bone][f]

    def sample(self, bone, t):
        """Slerp at time t (s)."""
        x = min(max(t * self.fps, 0.0), self.frames - 1.0)
        i = int(math.floor(x))
        j = min(i + 1, self.frames - 1)
        a, b = self.rot[bone][i], self.rot[bone][j]
        return a.slerp(b, x - i)

    def sample_loc(self, t):
        x = min(max(t * self.fps, 0.0), self.frames - 1.0)
        i = int(math.floor(x))
        j = min(i + 1, self.frames - 1)
        return self.hips_loc[i].lerp(self.hips_loc[j], x - i)


def _strip_prefix(name):
    return name.split(":")[-1]


def load_clip(rig_ob, name, folder=None, verbose=True, cache=True):
    """Retargeted Motion of Mixamo clip `name` (cached by file size/mtime + rig rest)."""
    path = find_fbx(name, folder)
    st = os.stat(path)
    rest_sig = round(sum(b.head_local.length + b.tail_local.x for b in rig_ob.data.bones), 6)
    key = f"{os.path.basename(path)}|{st.st_size}|{int(st.st_mtime)}|{rest_sig}|{CACHE_VERSION}"
    cpath = os.path.join(CACHE_DIR, os.path.basename(path)[:-4].replace(" ", "_") + ".pkl")
    if cache and os.path.isfile(cpath):
        with open(cpath, "rb") as fh:
            c = pickle.load(fh)
        if c.get("key") == key:
            rot = {n: [Quaternion(q) for q in qs] for n, qs in c["rot"].items()}
            return Motion(name, c["fps"], rot, [Vector(v) for v in c["loc"]])
    mo = _retarget(rig_ob, name, path, verbose)
    if cache:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(cpath, "wb") as fh:
            pickle.dump({"key": key, "fps": mo.fps,
                         "rot": {n: [tuple(q) for q in qs] for n, qs in mo.rot.items()},
                         "loc": [tuple(v) for v in mo.hips_loc]}, fh)
    return mo


def _retarget(rig_ob, name, path, verbose=True):
    scene = bpy.context.scene
    keep_fps = (scene.render.fps, scene.render.fps_base)
    keep_frame = scene.frame_current
    before = set(bpy.data.objects)
    before_act = set(bpy.data.actions)
    bpy.ops.import_scene.fbx(filepath=path, automatic_bone_orientation=False, ignore_leaf_bones=False,
                             use_anim=True, anim_offset=0.0)
    new = [o for o in bpy.data.objects if o not in before]
    arm = next(o for o in new if o.type == "ARMATURE")
    fps = scene.render.fps / scene.render.fps_base
    act = arm.animation_data.action
    f0, f1 = (int(round(v)) for v in act.frame_range)

    tgt = rig_ob.data.bones
    src_names = {_strip_prefix(b.name): b.name for b in arm.data.bones}
    bones = [b.name for b in tgt if b.name.startswith(M) and _strip_prefix(b.name) in src_names]
    missing = [b.name for b in tgt if b.name.startswith(M) and _strip_prefix(b.name) not in src_names]
    # checks
    dlen = max(abs(arm.data.bones[src_names[_strip_prefix(n)]].length - tgt[n].length)
               for n in bones if not tgt[n].children or any(c.name.startswith(M) for c in tgt[n].children))
    scale = arm.matrix_world.to_scale()
    src_hips = arm.data.bones[src_names["Hips"]]
    hips_off = tgt[M + "Hips"].head_local - (arm.matrix_world @ src_hips.head_local)
    if verbose:
        print(f"RETARGET {os.path.basename(path)}: {len(arm.data.bones)} bones prefix "
              f"'{arm.data.bones[0].name.split(':')[0]}:' missing={missing} fps={fps:g} frames={f0}..{f1} "
              f"scale={tuple(round(s, 4) for s in scale)} max bone-length diff={dlen * 1000:.2f} mm "
              f"hips rest offset={tuple(round(v, 4) for v in hips_off)}")
    if missing:
        raise RuntimeError(f"{name}: bones missing in FBX: {missing}")
    if abs(fps - FPS) > 1e-3:
        raise RuntimeError(f"{name}: FBX at {fps} fps, expected {FPS}")

    # parents (nearest mixamorig ancestor) and rest rotations of the target
    par = {}
    for n in bones:
        p = tgt[n].parent
        while p is not None and not p.name.startswith(M):
            p = p.parent
        par[n] = p.name if p else None
    restrel = {}
    for n in bones:
        R = tgt[n].matrix_local.to_quaternion()
        restrel[n] = (tgt[par[n]].matrix_local.to_quaternion().inverted() @ R) if par[n] else R
    hips_rest_rot = tgt[M + "Hips"].matrix_local.to_3x3()
    hips_rest_head = tgt[M + "Hips"].head_local

    rot = {n: [] for n in bones}
    hips_loc = []
    check = {}   # frame index -> {bone: source head position (target coordinates)}
    ends = [M + s + b for s in ("Left", "Right") for b in ("Hand", "Foot", "HandIndex3")] + [M + "Head"]
    mw = arm.matrix_world
    mwq = mw.to_quaternion()
    for f in range(f0, f1 + 1):
        scene.frame_set(f)
        W = {}
        for n in bones:
            pb = arm.pose.bones[src_names[_strip_prefix(n)]]
            W[n] = (mwq @ pb.matrix.to_quaternion()).normalized()
        for n in bones:
            pw = W[par[n]] if par[n] else Quaternion()
            loc = restrel[n].inverted() @ pw.inverted() @ W[n]
            prev = rot[n][-1] if rot[n] else Quaternion()
            if loc.dot(prev) < 0:
                loc.negate()
            rot[n].append(loc.normalized())
        head = mw @ arm.pose.bones[src_names["Hips"]].head + hips_off
        hips_loc.append(hips_rest_rot.inverted() @ (head - hips_rest_head))
        if (f - f0) % 30 == 0:
            check[f - f0] = {n: mw @ arm.pose.bones[src_names[_strip_prefix(n)]].head + hips_off for n in ends}

    for o in new:
        data = o.data
        bpy.data.objects.remove(o, do_unlink=True)
        if data is not None and data.users == 0:
            if isinstance(data, bpy.types.Armature):
                bpy.data.armatures.remove(data)
            elif isinstance(data, bpy.types.Mesh):
                bpy.data.meshes.remove(data)
    for a in list(bpy.data.actions):
        if a not in before_act:
            bpy.data.actions.remove(a)
    scene.render.fps, scene.render.fps_base = keep_fps
    scene.frame_set(keep_frame)
    # verification: target FK of the retargeted locals must reproduce the source joint positions
    import rigmath
    model = rigmath.RigModel(rig_ob)
    err = 0.0
    for fi, ref in check.items():
        pose = rigmath.Pose({n: rot[n][fi] for n in bones}, hips_loc[fi])
        mats = model.fk(pose)
        err = max([err] + [(mats[n].translation - p).length for n, p in ref.items()])
    if verbose:
        print(f"RETARGET {name}: FK position residual vs source (hands/feet/head/fingertips) {err * 1000:.2f} mm")
    return Motion(name, fps, rot, hips_loc)
