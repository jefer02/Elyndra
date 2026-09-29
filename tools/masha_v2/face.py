"""Stage 2 - face (owner: Agent 4).

face.build(ctx) runs after body, before hands. Deterministic and headless.

  1. Face refinement: MPFB head/face targets loaded and baked into the basemesh, assets + rig refitted.
     Optional brow/lash/eye asset swaps (object names kept: Masha_Brows/Masha_Lashes/Masha_Eyes).
  2. Blendshapes: Meta visemes + ARKit face units loaded on the basemesh (before any vertex deletion:
     they are indexed by base-mesh vertex), MHCLO-interpolated to brows/lashes/teeth/tongue.
     The rest mouth is closed (REST_CLOSE baked into the basis; absolute shapes rebased so their
     full-weight pose equals MPFB's original). A subset (KEEP, 47 shapes) is kept. Lip-sync set (contract v2,
     docs/MASHA_LIPSYNC.md): visemes are LIP-ONLY (jaw component removed, jaw only via jawOpen), O/U/PP/FF
     strengthened with ARKit units, procedural tongue poses on TH/DD/nn/RR, tongue rest lowered 5 mm.
  3. Teeth decimated (keys re-created by nearest-vertex deltas). Head+neck separated from Masha_Body
     along a quad edge loop at the base of the neck -> Masha_Head (brows, lashes, teeth, tongue joined
     in). Masha_Body keeps NO shape keys. Seam normals made identical on both sides.
  4. Eye bones masha:eye.L / masha:eye.R (children of mixamorig:Head) at the eyeball centres; eyeballs
     100% weighted to them. Lid follow is a runtime coupling (see contract "eye_bones.lid_follow"); skinning
     the lids to the eye bones (LID_FOLLOW > 0) was tested and rejected: exposes the lid margin on look-up.
  5. Eye material (CC0 MPFB iris) with a soft iris-only emission mask; textures in <out>/textures.
  6. ctx["face_shapes"], ctx["head"], <out>/face_contract.json.
ctx["brows"], ctx["lashes"], ctx["teeth"], ctx["tongue"] are set to None (joined into Masha_Head).
"""
import json
import math
import os

import bmesh
import bpy
import mathutils
import mathutils.kdtree
import numpy as np
from mathutils import Vector

import common

# ---------------------------------------------------------------- 1. face shape
FACE_TARGETS = {
    # overall head: softer oval, a little narrower
    "head-oval": 0.35, "head-scale-horiz-decr": 0.08,
    # jaw/chin: narrower jaw angles, slimmer chin, slightly more projection (profile)
    "chin-width-decr": 0.35, "chin-bones-decr": 0.2, "chin-prominent-incr": 0.2,
    # cheeks: defined cheekbones, slimmer lower cheeks
    "l-cheek-bones-incr": 0.2, "r-cheek-bones-incr": 0.2,
    "l-cheek-volume-decr": 0.15, "r-cheek-volume-decr": 0.15,
    # nose: slim, refined tip, no hump
    "nose-scale-horiz-decr": 0.25, "nose-point-width-decr": 0.35, "nose-flaring-decr": 0.3,
    "nose-nostrils-width-decr": 0.25, "nose-hump-decr": 0.3, "nose-point-up": 0.15,
    # mouth: slightly narrower, defined fuller lips, soft upturned corners (calm/friendly)
    "mouth-scale-horiz-decr": 0.15, "mouth-upperlip-volume-incr": 0.25, "mouth-lowerlip-volume-incr": 0.25,
    "mouth-cupidsbow-incr": 0.35, "mouth-angles-up": 0.2,
    # eyes: a bit larger and more open, outer corners lifted
    "l-eye-scale-incr": 0.08, "r-eye-scale-incr": 0.08,
    "l-eye-height2-incr": 0.1, "r-eye-height2-incr": 0.1,
    "l-eye-corner2-up": 0.15, "r-eye-corner2-up": 0.15,
}
EYES = os.environ.get("FACE_EYES", "high-poly")
BROWS = os.environ.get("FACE_BROWS", "eyebrow008")
LASHES = os.environ.get("FACE_LASHES", "eyelashes03")
IRIS = os.environ.get("FACE_IRIS", "blue")


def refine_shape(ctx, targets=None):
    TargetService = common.mpfb("TargetService")
    HumanService = common.mpfb("HumanService")
    body = ctx["body"]
    stack = [{"target": k, "value": v} for k, v in (targets or FACE_TARGETS).items() if abs(v) > 1e-4]
    TargetService.bulk_load_targets(body, stack)
    TargetService.bake_targets(body)
    HumanService.refit(body)


def swap_asset(ctx, key, sub, name, atype, obj_name):
    HumanService = common.mpfb("HumanService")
    AssetService = common.mpfb("AssetService")
    old = ctx.get(key)
    if old is not None:
        bpy.data.objects.remove(old, do_unlink=True)
    before = set(bpy.data.objects)
    path = AssetService.find_asset_absolute_path(f"{name}.mhclo", asset_subdir=sub)
    HumanService.add_mhclo_asset(path, ctx["body"], asset_type=atype, subdiv_levels=0, material_type="GAMEENGINE")
    obj = [o for o in bpy.data.objects if o not in before and o.type == "MESH"][0]
    obj.name = obj.data.name = obj_name
    ctx[key] = obj
    return obj


def build(ctx):
    stop = os.environ.get("FACE_STOP", "")
    refine_shape(ctx)
    if EYES != "low-poly":
        swap_asset(ctx, "eyes", "eyes", EYES, "Eyes", "Masha_Eyes")
    if BROWS != "eyebrow001":
        swap_asset(ctx, "brows", "eyebrows", BROWS, "Eyebrows", "Masha_Brows")
    if LASHES != "eyelashes01":
        swap_asset(ctx, "lashes", "eyelashes", LASHES, "Eyelashes", "Masha_Lashes")
    if stop == "shape":
        return
    load_face_units(ctx)
    if stop == "units":
        return
    process_keys(ctx)
    if stop == "keys":
        return
    decimate_teeth(ctx)
    strip_cornea(ctx)
    name_materials(ctx)
    mouth_materials(ctx)
    split_head(ctx)
    add_eye_bones(ctx)
    eye_materials(ctx)
    ctx["face_shapes"] = [kb.name for kb in ctx["head"].data.shape_keys.key_blocks[1:]]
    write_contract(ctx)


# ---------------------------------------------------------------- 2. blendshapes
# Morph contract for lip-sync: docs/MASHA_LIPSYNC.md ("Morph contract") and the "lipsync" block of
# face_contract.json. Summary of how the mouth set is built (all in MPFB's raw delta space first):
#   raw deltas R (relative to MPFB's slightly parted neutral) -> composites/recipes -> lip-only visemes
#   (jaw removed) -> tongue poses -> rest-closure rebasing (restUndo) -> gains.
# Rest-pose lip closure: MPFB's neutral mouth is slightly parted (teeth visible at the corners). The basis
# gets C = 0.15 * mouthClose (~5 mm on the lips).
REST_CLOSE = ("mouthClose", 0.15)
# Shapes that are "absolute poses" of the lips: rebased (V' = V - restUndo * C) so weight 1.0 reproduces
# their MPFB pose despite the closed rest. restUndo = 1 for these; jawOpen is rebased before its gain, so its
# effective restUndo is GAINS["jawOpen"]. Every other mouth unit is a RELATIVE modifier (restUndo 0): it
# acts on top of whatever the lips are doing (sealed rest, or a viseme). A sum of absolute shapes whose
# restUndo-weighted total U exceeds 1 undoes C more than once; the runtime corrects it exactly with
# mouthClose += REST_FIX_PER_UNIT * (U - 1) (see write_contract / docs).
REBASE_PREFIX = ("viseme_", "jawOpen", "mouthPucker")
# Composite shapes (new name -> {source: weight}) built from raw deltas (L+R pairs merged).
COMPOSITES = {"mouthPress": {"mouthPressLeft": 1.0, "mouthPressRight": 1.0},
              "mouthUpperUp": {"mouthUpperUpLeft": 1.0, "mouthUpperUpRight": 1.0},
              "mouthLowerDown": {"mouthLowerDownLeft": 1.0, "mouthLowerDownRight": 1.0},
              "mouthStretch": {"mouthStretchLeft": 1.0, "mouthStretchRight": 1.0},
              "mouthDimple": {"mouthDimpleLeft": 1.0, "mouthDimpleRight": 1.0}}
# Per-shape gain applied to the final delta (so runtime weight 1.0 is the natural maximum).
# mouthClose: scaled so that mouthClose == jawOpen keeps the lips sealed over the open jaw
# (raw jaw 0.6*j needs raw close 0.6*j, plus the 0.09*j of rest closure the jaw removes -> 0.69).
GAINS = {"jawOpen": 0.6, "mouthClose": 0.69}

# Viseme recipes in raw space (before the jaw is removed): strengthen the weak MPFB shapes with ARKit units.
# seal=True: a closed-lip viseme; the lips must stay sealed when the jaw is removed, so the removed jaw
# component is the *sealed* jaw k*(jawOpen + mouthClose) instead of k*jawOpen.
VISEME_RECIPES = {
    "viseme_O": {"src": {"viseme_O": 1.0, "mouthFunnel": 0.3, "mouthPucker": 0.15}},
    "viseme_U": {"src": {"viseme_U": 1.0, "mouthPucker": 0.6, "mouthFunnel": 0.3}},
    "viseme_PP": {"src": {"viseme_PP": 1.0, "mouthPressLeft": 0.3, "mouthPressRight": 0.3,
                          "mouthRollLower": 0.15, "mouthRollUpper": 0.1}, "seal": True},
    "viseme_FF": {"src": {"viseme_FF": 1.0, "mouthRollLower": 0.3, "mouthShrugLower": 0.6,
                          "mouthUpperUpLeft": 0.2, "mouthUpperUpRight": 0.2}},
}
# Tongue poses (procedural, tongue mesh only, in the closed-jaw frame; the jaw carries the tongue with it).
# weight profile along the tongue from the tip: f = s**p, s = 1 at the tip -> 0 at TONGUE_REACH behind it.
TONGUE_REACH = 0.030
# MPFB's tongue01 floats ~15 mm above the mouth floor with its dorsum above the lower incisal edge, so any
# open mouth shows a flat pink slab filling the gap. The tongue rest is lowered (inner mouth only, invisible
# with closed lips): the tip ends up ~6 mm below the lower incisal edge, still ~9 mm above the floor.
TONGUE_REST_OFFSET = (0.0, 0.0, -0.005)
TONGUE_POSES = {
    # tip forward between the incisors (dental fricative); needs jawOpen >= ~0.12 to clear the teeth.
    # "w": lateral half-width (m) of the part that moves: a narrow tip, not the whole tongue front.
    "tip_forward": {"d": (0.0, -0.0105, 0.0095), "p": 1.6, "w": (0.008, 0.017)},
    # tip raised to the alveolar ridge behind the upper incisors (t/d/n/l, Spanish tap r)
    "tip_up": {"d": (0.0, 0.0015, 0.0175), "p": 2.0, "w": (0.010, 0.020)},
}
VISEME_TONGUE = {"viseme_TH": {"tip_forward": 1.0}, "viseme_DD": {"tip_up": 1.0},
                 "viseme_nn": {"tip_up": 1.0}, "viseme_RR": {"tip_up": 0.7}}
# Jaw-region cleanup of the lip-only visemes: residual skin motion is faded out where the jaw moves
# (chin, jaw line, mouth floor) away from the lips; the lips keep their exact shape.
LIP_KEEP_DIST = (0.007, 0.020)   # full residual within 7 mm of a lip vertex, none beyond 20 mm
KEEP = [
    "eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
    "browInnerUp", "browDownLeft", "browDownRight", "browOuterUpLeft", "browOuterUpRight",
    "cheekSquintLeft", "cheekSquintRight",
    "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
    "jawOpen", "mouthPucker", "mouthPress", "mouthLeft",
    "viseme_aa", "viseme_E", "viseme_I", "viseme_O", "viseme_U", "viseme_PP", "viseme_FF",
    "viseme_SS", "viseme_DD", "viseme_CH",
    # lip-sync additions (appended: the 31 indices above are unchanged)
    "viseme_kk", "viseme_nn", "viseme_RR", "viseme_TH",
    "mouthClose", "mouthFunnel", "mouthRollLower", "mouthRollUpper", "mouthUpperUp", "mouthLowerDown",
    "mouthShrugLower", "mouthShrugUpper", "mouthStretch", "mouthDimple", "mouthRight", "tongueOut",
]
LIP_ONLY = [n for n in KEEP if n.startswith("viseme_")]
REST_FIX_PER_UNIT = REST_CLOSE[1] / GAINS["mouthClose"]   # mouthClose weight that re-adds one C


def load_face_units(ctx):
    """Visemes (Meta, 15) + ARKit face units (52) on the basemesh, then MHCLO-interpolated to children.
    Must run before any vertex deletion: targets are indexed by base-mesh vertex index."""
    FaceService = common.mpfb("FaceService")
    body = ctx["body"]
    FaceService.load_targets(body, load_microsoft_visemes=False, load_meta_visemes=True, load_arkit_faceunits=True)
    FaceService.interpolate_targets(body)
    got = {}
    for key in ("brows", "lashes", "teeth", "tongue", "eyes", "hair"):
        o = ctx.get(key)
        got[key] = len(o.data.shape_keys.key_blocks) - 1 if o is not None and o.data.shape_keys else 0
    ctx["_interp_counts"] = got
    print("FACE interpolated keys per child:", got)


def _co(obj, kb):
    a = np.empty(len(obj.data.vertices) * 3, dtype=np.float32)
    kb.data.foreach_get("co", a)
    return a.reshape(-1, 3)


def _recipe(name):
    return VISEME_RECIPES.get(name, {"src": {name: 1.0}})


def _mix(deltas, src, like):
    return sum((deltas[k] * w for k, w in src.items() if k in deltas), np.zeros_like(like))


def lip_only_setup(ctx):
    """Jaw coefficient k per viseme: least squares of the viseme's lower-teeth motion onto jawOpen's (the lower
    teeth are the only rigid part that moves with the jaw alone). Plus the skin cleanup weight: 1 where the jaw
    moves the skin far from the lips (chin, jaw line, mouth floor), 0 on and near the lips."""
    teeth, body = ctx.get("teeth"), ctx["body"]
    ks = {n: 0.0 for n in LIP_ONLY}
    if teeth is not None and teeth.data.shape_keys is not None:
        kbs = teeth.data.shape_keys.key_blocks
        tb = _co(teeth, kbs[0])
        td = {kb.name: _co(teeth, kb) - tb for kb in kbs[1:]}
        J = td.get("jawOpen")
        if J is not None:
            n = np.linalg.norm(J, axis=1)
            low = n > 0.3 * n.max()
            jj = J[low].ravel()
            for name in LIP_ONLY:
                v = _mix(td, _recipe(name)["src"], tb)[low].ravel()
                ks[name] = max(0.0, float(jj @ v / (jj @ jj)))
    kbs = body.data.shape_keys.key_blocks
    bb = _co(body, kbs[0])
    jn = np.linalg.norm(_co(body, kbs["jawOpen"]) - bb, axis=1)
    g = body.vertex_groups.get("lips")
    skin = body.vertex_groups.get("body")
    lips = [v.index for v in body.data.vertices if g is not None and any(x.group == g.index and x.weight > 0.5 for x in v.groups)]
    ref = jn.max() if skin is None else max(jn[[v.index for v in body.data.vertices
                                               if any(x.group == skin.index and x.weight > 0.5 for x in v.groups)]].max(), 1e-9)
    jawness = np.clip(jn / (0.5 * ref), 0, 1)
    clean = np.zeros(len(bb), dtype=np.float32)
    if lips:
        kd = mathutils.kdtree.KDTree(len(lips))
        for i in lips:
            kd.insert(bb[i], i)
        kd.balance()
        for i in np.nonzero(jawness > 1e-3)[0]:
            dist = kd.find(bb[i])[2]
            clean[i] = jawness[i] * float(_smooth01(np.array(dist), *LIP_KEEP_DIST))
    ctx["_lip_only"] = {"k": ks, "clean": clean}
    print("FACE lip-only jaw coefficients (raw jawOpen units):", {k: round(v, 3) for k, v in ks.items()})
    return ctx["_lip_only"]


def tongue_pose(basis, poses):
    """Procedural tongue deltas (tongue mesh basis, metres): each pose moves the front of the tongue by d,
    weighted s**p along the tongue (s = 1 at the tip, 0 at TONGUE_REACH behind it)."""
    d = np.zeros_like(basis)
    if not poses:
        return d
    tip_y = float(basis[:, 1].min())
    s = np.clip((tip_y + TONGUE_REACH - basis[:, 1]) / TONGUE_REACH, 0, 1)
    for name, w in poses.items():
        P = TONGUE_POSES[name]
        f = s ** P["p"]
        if "w" in P:
            f = f * (1.0 - _smooth01(np.abs(basis[:, 0]), *P["w"]))
        d += w * f[:, None] * np.array(P["d"], dtype=np.float32)[None, :]
    return d


def process_keys(ctx):
    """Rest closure, composites, lip-only visemes, tongue poses, rebasing, gains and pruning, identically on
    every face mesh (skin/brows/lashes/teeth/tongue keep the same key names)."""
    lo = ctx.get("_lip_only") or lip_only_setup(ctx)
    for key in ("body", "brows", "lashes", "teeth", "tongue"):
        o = ctx.get(key)
        if o is None or o.data.shape_keys is None:
            continue
        kbs = o.data.shape_keys.key_blocks
        basis = _co(o, kbs[0])
        deltas = {kb.name: _co(o, kb) - basis for kb in kbs[1:]}
        zero = np.zeros_like(basis)
        cname, c = REST_CLOSE
        close = deltas.get(cname, zero) * c
        new_basis = basis + close
        if key == "tongue":
            new_basis = new_basis + np.array(TONGUE_REST_OFFSET, dtype=np.float32)[None, :]
        for name, recipe in COMPOSITES.items():
            deltas[name] = _mix(deltas, recipe, basis)
        # lip-only visemes: recipe mix minus its jaw component (sealed jaw for closed-lip visemes)
        J, M = deltas.get("jawOpen", zero), deltas.get("mouthClose", zero)
        lip = {}
        for name in LIP_ONLY:
            rec = _recipe(name)
            k = lo["k"].get(name, 0.0)
            d = _mix(deltas, rec["src"], basis) - k * (J + M if rec.get("seal") else J)
            if key == "teeth":
                d = zero.copy()                      # teeth only move with jawOpen
            elif key == "tongue":
                d = tongue_pose(basis, VISEME_TONGUE.get(name))
            elif key == "body":
                d = d * (1.0 - lo["clean"])[:, None]
            lip[name] = d
        deltas.update(lip)
        for name, d in list(deltas.items()):
            if name.startswith(REBASE_PREFIX):
                d = d - close
            deltas[name] = d * GAINS.get(name, 1.0)
        o.shape_key_clear()
        o.data.vertices.foreach_set("co", new_basis.ravel())
        o.data.update()
        o.shape_key_add(name="Basis", from_mix=False)
        for name in KEEP:
            d = deltas.get(name)
            if d is None:
                continue
            if key != "body" and float(np.abs(d).max()) < 1e-5:
                continue  # children only carry keys that move them (join fills the rest with basis)
            kb = o.shape_key_add(name=name, from_mix=False)
            kb.data.foreach_set("co", (new_basis + d).ravel())
        o.data.update()
    for key in ("eyes", "hair"):
        o = ctx.get(key)
        if o is not None and o.data.shape_keys:
            o.shape_key_clear()


# ---------------------------------------------------------------- 3. teeth + head split
TEETH_TRIS = int(os.environ.get("FACE_TEETH_TRIS", "2400"))         # decimation budget (MPFB teeth_base is 7.1k tris)
TEETH_BACK_CUT = 0.025     # delete teeth faces deeper than this behind the incisors (hidden molars/gums)
TEETH_FRONT_KEEP = 0.012   # incisors + canines (+ their gums) within this depth are never decimated


def decimate_teeth(ctx):
    """Trim hidden back molars, collapse-decimate to TEETH_TRIS, then re-create shape keys from
    nearest original vertices *of the same jaw* (upper/lower classified by jawOpen motion; a
    vertex group carries the label through the decimation so incisors never swap jaws)."""
    teeth = ctx.get("teeth")
    if teeth is None:
        return
    me = teeth.data
    kbs = list(me.shape_keys.key_blocks) if me.shape_keys else []
    basis = _co(teeth, kbs[0]) if kbs else np.array([v.co[:] for v in me.vertices], dtype=np.float32)
    deltas = {kb.name: _co(teeth, kb) - basis for kb in kbs[1:]}
    jaw = deltas.get("jawOpen")
    lower = (np.linalg.norm(jaw, axis=1) > 0.3 * np.linalg.norm(jaw, axis=1).max()) if jaw is not None         else np.zeros(len(basis), bool)
    trees = {}
    for lab in (False, True):
        ids = np.nonzero(lower == lab)[0]
        t = mathutils.kdtree.KDTree(max(1, len(ids)))
        for i in ids:
            t.insert(basis[i], int(i))
        t.balance()
        trees[lab] = t
    if kbs:
        teeth.shape_key_clear()
    g = teeth.vertex_groups.new(name="_lower_teeth")
    g.add([int(i) for i in np.nonzero(lower)[0]], 1.0, "REPLACE")

    # trim hidden back part
    front = float(basis[:, 1].min())
    bm = bmesh.new()
    bm.from_mesh(me)
    kill = [f for f in bm.faces if f.calc_center_median().y > front + TEETH_BACK_CUT]
    bmesh.ops.delete(bm, geom=kill, context="FACES")
    bm.to_mesh(me)
    bm.free()
    me.update()

    # protect the front teeth: only vertices in the "_decim" group (weight 1) may be collapsed
    dg_grp = teeth.vertex_groups.new(name="_decim")
    dg_grp.add([v.index for v in me.vertices if v.co.y > front + TEETH_FRONT_KEEP], 1.0, "REPLACE")
    common.select_only(teeth)
    for m in teeth.modifiers:
        m.show_viewport = m.type == "DECIMATE"
    mod = teeth.modifiers.new("dec", "DECIMATE")
    mod.use_collapse_triangulate = True
    mod.vertex_group = "_decim"
    mod.vertex_group_factor = 1.0
    bpy.ops.object.modifier_move_to_index(modifier="dec", index=0)
    lo, hi = 0.01, 1.0
    for _ in range(14):  # bisection on the ratio for the tri budget (front stays untouched)
        mod.ratio = (lo + hi) / 2
        bpy.context.view_layer.update()
        ev = teeth.evaluated_get(bpy.context.evaluated_depsgraph_get())
        n = sum(len(p.vertices) - 2 for p in ev.data.polygons)
        lo, hi = (mod.ratio, hi) if n < TEETH_TRIS else (lo, mod.ratio)
    mod.ratio = lo
    for m in teeth.modifiers:
        m.show_viewport = True
    bpy.ops.object.modifier_apply(modifier="dec")
    teeth.vertex_groups.remove(teeth.vertex_groups["_decim"])
    if deltas:
        nb = np.array([v.co[:] for v in me.vertices], dtype=np.float32)
        gi = teeth.vertex_groups["_lower_teeth"].index
        lab = [any(x.group == gi and x.weight > 0.5 for x in v.groups) for v in me.vertices]
        idx = np.array([trees[l].find(co)[1] for co, l in zip(nb, lab)])
        teeth.shape_key_add(name="Basis", from_mix=False)
        for name, d in deltas.items():
            kb = teeth.shape_key_add(name=name, from_mix=False)
            kb.data.foreach_set("co", (nb + d[idx]).ravel())
    teeth.vertex_groups.remove(teeth.vertex_groups["_lower_teeth"])
    print("FACE teeth tris", common.tri_count(teeth), "lower verts", int(sum(lab)) if deltas else 0)


def strip_cornea(ctx):
    """The high-poly eye has a cornea shell UV-mapped to the transparent corner of the atlas. Remove it:
    no alpha-blended geometry on mobile and no bulge (the eyeball's own gloss gives the highlight)."""
    eyes = ctx["eyes"]
    me = eyes.data
    uv = me.uv_layers.active.data
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.faces.ensure_lookup_table()
    kill = []
    for f in bm.faces:
        us = [uv[li].uv for li in me.polygons[f.index].loop_indices]
        if all(u.x > 0.85 and u.y < 0.15 for u in us):
            kill.append(f)
    bmesh.ops.delete(bm, geom=kill, context="FACES")
    bm.to_mesh(me)
    bm.free()
    for p in me.polygons:
        p.use_smooth = True  # MPFB ships the eyeball flat-shaded -> faceted highlights
    me.update()
    print("FACE cornea faces removed", len(kill), "eye tris", common.tri_count(eyes))


def name_materials(ctx):
    for key, name in (("brows", "Masha_Brows"), ("lashes", "Masha_Lashes"), ("teeth", "Masha_Teeth"),
                      ("tongue", "Masha_Tongue")):
        o = ctx.get(key)
        if o is not None and o.data.materials and o.data.materials[0] is not None:
            o.data.materials[0].name = name


TEETH_WHITE = (0.93, 0.905, 0.85)   # clean off-white enamel (linear-ish albedo)
GUM_PINK = (0.36, 0.19, 0.21)       # muted dark pink gums / interior
TONGUE_PINK = (0.46, 0.25, 0.27)    # muted tongue (texture luminance variation kept)


def _smooth01(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def mouth_materials(ctx):
    """Re-grade the CC0 teeth/tongue textures: MPFB teeth are grey with dark interproximal blots and
    saturated red gums (reads as missing teeth). Enamel -> off-white keeping soft shading; gums and the
    black atlas background -> muted dark pink; tongue desaturated/darkened a little."""
    tex_dir = common.ensure_dir(os.path.join(ctx["out_dir"], "textures"))
    for key, out_name in (("teeth", "masha_teeth_basecolor"), ("tongue", "masha_tongue_basecolor")):
        o = ctx.get(key)
        if o is None or not o.data.materials or o.data.materials[0] is None:
            continue
        mat = o.data.materials[0]
        texs = [n for n in mat.node_tree.nodes if n.type == "TEX_IMAGE" and n.image is not None]
        if not texs:
            continue
        tn = max(texs, key=lambda n: n.image.size[0])
        src = tn.image
        W, H = src.size
        px = np.array(src.pixels[:], dtype=np.float32).reshape(H, W, 4)
        rgb = px[..., :3]
        lum = rgb.mean(-1)
        red = rgb[..., 0] - 0.5 * (rgb[..., 1] + rgb[..., 2])
        if key == "teeth":
            gum = np.maximum(_smooth01(red, 0.06, 0.18), 1 - _smooth01(lum, 0.02, 0.08))
            shade = 0.74 + 0.26 * np.clip(lum / 0.8, 0, 1)
            enamel = np.array(TEETH_WHITE)[None, None, :] * shade[..., None]
            gshade = 0.75 + 0.25 * np.clip(lum / 0.45, 0, 1)
            gums = np.array(GUM_PINK)[None, None, :] * gshade[..., None]
            out = enamel * (1 - gum[..., None]) + gums * gum[..., None]
        else:
            rel = np.clip(lum / max(1e-4, float(lum.mean())), 0.6, 1.4)[..., None]
            out = np.array(TONGUE_PINK)[None, None, :] * rel
        res = px.copy()
        res[..., :3] = out
        res[..., 3] = 1.0
        img = bpy.data.images.get(out_name) or bpy.data.images.new(out_name, W, H, alpha=True)
        img.pixels.foreach_set(res.ravel())
        img.filepath_raw = os.path.join(tex_dir, out_name + ".png")
        img.file_format = "PNG"
        img.save()
        tn.image = img
        bsdf = next((n for n in mat.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
        if bsdf is not None:
            bsdf.inputs["Roughness"].default_value = 0.35 if key == "teeth" else 0.55
        print("FACE regraded", key, "->", img.filepath_raw)


SEAM_Z = 1.385  # target height of the neck-base loop on the front midline


def _walk_loop(v0, e0):
    """Walk a quad edge loop starting at vertex v0 along edge e0. Returns list of edges or None."""
    edges = [e0]
    v, e = e0.other_vert(v0), e0
    for _ in range(400):
        if v == v0:
            return edges
        if len(v.link_edges) != 4:
            return None
        faces = set(e.link_faces)
        nxt = [x for x in v.link_edges if x != e and not (set(x.link_faces) & faces)]
        if len(nxt) != 1:
            return None
        e = nxt[0]
        edges.append(e)
        v = e.other_vert(v)
    return None


def _contract_neck_loop(bm):
    """The neck seam of the face contract, by MPFB base-mesh index (topology is stable across
    proportion changes; the geometric search below can latch onto a non-encircling loop once the
    shoulders/neck are reshaped). Returns None unless the indices form one closed ring."""
    try:
        from body import NECK_SEAM_VERTS
    except ImportError:
        return None
    ns = set(NECK_SEAM_VERTS)
    if max(ns) >= len(bm.verts):
        return None
    edges = [e for e in bm.edges if e.verts[0].index in ns and e.verts[1].index in ns]
    deg = {}
    for e in edges:
        for v in e.verts:
            deg[v.index] = deg.get(v.index, 0) + 1
    if len(deg) != len(ns) or any(d != 2 for d in deg.values()) or len(edges) != len(ns):
        print("FACE contract neck seam is not a clean ring; falling back to search", len(edges), sorted(set(deg.values())))
        return None
    # one ring, not several: walk it
    adj = {}
    for e in edges:
        a, b = e.verts[0].index, e.verts[1].index
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)
    start = next(iter(ns))
    prev, cur, n = None, start, 0
    while True:
        nxt = adj[cur][0] if adj[cur][0] != prev else adj[cur][1]
        prev, cur, n = cur, nxt, n + 1
        if cur == start:
            break
    if n != len(ns):
        return None
    vs = sorted(ns)
    zs = [bm.verts[i].co.z for i in vs]
    front = min((bm.verts[i] for i in vs), key=lambda v: (abs(v.co.x), v.co.y))
    return {"edges": [(e.verts[0].index, e.verts[1].index) for e in edges], "verts": vs,
            "z_front": round(front.co.z, 4), "z_min": round(min(zs), 4), "z_max": round(max(zs), 4)}


def find_neck_loop(body, target_z=SEAM_Z):
    bm = bmesh.new()
    bm.from_mesh(body.data)
    bm.verts.ensure_lookup_table()
    contract = _contract_neck_loop(bm)
    if contract is not None:
        bm.free()
        return contract
    gi = body.vertex_groups["body"].index
    skin = set(v.index for v in body.data.vertices if any(g.group == gi and g.weight > 0.5 for g in v.groups))
    cands = [v for v in bm.verts if v.index in skin and abs(v.co.x) < 1e-4 and abs(v.co.z - target_z) < 0.03
             and v.co.y < -0.02]
    cands.sort(key=lambda v: abs(v.co.z - target_z))
    try:
        for v0 in cands:
            for e0 in v0.link_edges:
                o = e0.other_vert(v0)
                if o.co.x > 1e-4 and abs(o.co.z - v0.co.z) < 0.01:
                    loop = _walk_loop(v0, e0)
                    if loop and len(loop) > 20:
                        vs = sorted({v.index for e in loop for v in e.verts})
                        zs = [bm.verts[i].co.z for i in vs]
                        if max(zs) - min(zs) < 0.08 and all(i in skin for i in vs):
                            return {"edges": [(e.verts[0].index, e.verts[1].index) for e in loop], "verts": vs,
                                    "z_front": round(v0.co.z, 4), "z_min": round(min(zs), 4),
                                    "z_max": round(max(zs), 4)}
    finally:
        bm.free()
    raise RuntimeError("no clean neck edge loop found near z=%.3f" % target_z)


def _ensure_skin_slot(body):
    if len(body.data.materials) == 0:
        mat = bpy.data.materials.get("Masha_Skin") or bpy.data.materials.new("Masha_Skin")
        body.data.materials.append(mat)


def split_head(ctx):
    body = ctx["body"]
    _ensure_skin_slot(body)
    loop = find_neck_loop(body)
    seam = {k: loop[k] for k in ("z_front", "z_min", "z_max")}
    seam["vert_count"] = len(loop["verts"])
    seam["basemesh_vertex_indices"] = loop["verts"]
    ctx["neck_seam"] = seam
    print("FACE neck seam", {k: v for k, v in seam.items() if k != "basemesh_vertex_indices"})
    seam_edges = {tuple(sorted(e)) for e in loop["edges"]}
    me = body.data
    seam_pos = [me.vertices[i].co.copy() for i in loop["verts"]]
    seam_nrm = [me.vertices[i].normal.copy() for i in loop["verts"]]

    common.select_only(body)
    bpy.ops.object.mode_set(mode="EDIT")
    bm = bmesh.from_edit_mesh(me)
    bm.verts.ensure_lookup_table()
    bm.faces.ensure_lookup_table()
    top = max((v for v in bm.verts if abs(v.co.x) < 1e-4 and v.co.y > -0.1 and v.link_faces
               and v.co.z > 1.5), key=lambda v: v.co.z)
    seen = set()
    stack = list(top.link_faces)
    while stack:
        f = stack.pop()
        if f.index in seen:
            continue
        seen.add(f.index)
        for e in f.edges:
            if tuple(sorted((e.verts[0].index, e.verts[1].index))) in seam_edges:
                continue
            for g in e.link_faces:
                if g.index not in seen:
                    stack.append(g)
    if len(seen) > 7000:
        bpy.ops.object.mode_set(mode="OBJECT")
        raise RuntimeError(f"head flood fill leaked ({len(seen)} faces)")
    for f in bm.faces:
        f.select_set(False)
    for f in bm.faces:
        if f.index in seen:
            f.select_set(True)
    bmesh.update_edit_mesh(me)
    bpy.ops.mesh.separate(type="SELECTED")
    bpy.ops.object.mode_set(mode="OBJECT")
    head = [o for o in bpy.context.selected_objects if o != body][0]
    head.name = head.data.name = "Masha_Head"
    body.shape_key_clear()
    for m in list(head.modifiers):
        if m.type == "MASK":
            head.modifiers.remove(m)

    smooth_ear_rims(head)
    mouth_interior(head)
    _set_seam_normals(body, head, seam_pos, seam_nrm)
    ctx["_seam_shape_max_delta"] = _seam_delta(head, seam_pos)

    parts = [ctx.get(k) for k in ("brows", "lashes", "teeth", "tongue") if ctx.get(k) is not None]
    ctx["head_breakdown"] = {"skin": len(head.data.vertices)}
    for k in ("brows", "lashes", "teeth", "tongue"):
        if ctx.get(k) is not None:
            ctx["head_breakdown"][k] = {"verts": len(ctx[k].data.vertices), "tris": common.tri_count(ctx[k])}
    ctx["head_breakdown"]["skin"] = {"verts": len(head.data.vertices), "tris": common.tri_count(head)}
    common.select_only(head, *parts)
    bpy.context.view_layer.objects.active = head
    bpy.ops.object.join()
    for k in ("brows", "lashes", "teeth", "tongue"):
        ctx[k] = None
    ctx["head"] = head
    print("FACE head verts", len(head.data.vertices), "tris", common.tri_count(head),
          "keys", len(head.data.shape_keys.key_blocks) - 1, "mats", [m.name for m in head.data.materials if m])


MOUTH_INTERIOR_RGB = (0.30, 0.15, 0.17)  # muted dark pink for the skin mouth cavity


def mouth_interior(head):
    """Give the mouth cavity (part of the skin mesh) its own muted dark-pink material so the interior
    reads naturally behind the teeth. Found by flood fill from inside the mouth, bounded by 'lips'."""
    me = head.data
    g = head.vertex_groups.get("lips")
    if g is None:
        return
    lips = {v.index for v in me.vertices if any(x.group == g.index and x.weight > 0.5 for x in v.groups)}
    lz = [me.vertices[i].co.z for i in lips]
    fy = min(me.vertices[i].co.y for i in lips)
    p = Vector((0, fy + 0.03, (min(lz) + max(lz)) / 2))
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.faces.ensure_lookup_table()
    ok = [f for f in bm.faces if f.material_index == 0 and not any(v.index in lips for v in f.verts)]
    seed = min(ok, key=lambda f: (f.calc_center_median() - p).length)
    okset = {f.index for f in ok}
    seen, st = set(), [seed]
    while st:
        f = st.pop()
        if f.index in seen:
            continue
        seen.add(f.index)
        for e in f.edges:
            for h in e.link_faces:
                if h.index in okset and h.index not in seen:
                    st.append(h)
    bm.free()
    if not 50 < len(seen) < 800:
        print("FACE mouth interior fill rejected", len(seen))
        return
    mat = bpy.data.materials.get("Masha_MouthInterior") or bpy.data.materials.new("Masha_MouthInterior")
    mat.use_nodes = True
    b = mat.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*MOUTH_INTERIOR_RGB, 1)
    b.inputs["Roughness"].default_value = 0.6
    me.materials.append(mat)
    mi = len(me.materials) - 1
    for poly in me.polygons:
        if poly.index in seen:
            poly.material_index = mi
    print("FACE mouth interior faces", len(seen))


EAR_RIM_FRACTION = 0.30  # outer fraction (by radius in the side plane) of each ear that gets subdivided


def smooth_ear_rims(head):
    """The MPFB ear helix is low-poly and its silhouette looks faceted in 3/4 views. Subdivide (smooth)
    only the outer rim faces of each ear; shape keys, weights and UVs are interpolated by the op."""
    me = head.data
    g = head.vertex_groups.get("ears")
    if g is None:
        return
    ear = {v.index for v in me.vertices if any(x.group == g.index and x.weight > 0.5 for x in v.groups)}
    rim = set()
    for sign in (1, -1):
        ids = [i for i in ear if me.vertices[i].co.x * sign > 0]
        if not ids:
            continue
        c = sum((me.vertices[i].co for i in ids), Vector()) / len(ids)
        r = {i: math.hypot(me.vertices[i].co.y - c.y, me.vertices[i].co.z - c.z) for i in ids}
        cut = sorted(r.values())[int(len(r) * (1 - EAR_RIM_FRACTION))]
        rim |= {i for i in ids if r[i] >= cut}
    before = len(me.vertices)
    common.select_only(head)
    bpy.ops.object.mode_set(mode="EDIT")
    bm = bmesh.from_edit_mesh(me)
    bm.faces.ensure_lookup_table()
    for f in bm.faces:
        f.select_set(False)
    for f in bm.faces:
        if all(v.index in ear for v in f.verts) and any(v.index in rim for v in f.verts):
            f.select_set(True)
    bmesh.update_edit_mesh(me)
    bpy.ops.mesh.subdivide(number_cuts=1, smoothness=1.0, quadcorner="INNERVERT")
    bpy.ops.mesh.select_all(action="DESELECT")
    bpy.ops.object.mode_set(mode="OBJECT")
    print("FACE ear rims subdivided: +%d verts" % (len(me.vertices) - before))


def _seam_match(obj, seam_pos):
    kd = mathutils.kdtree.KDTree(len(seam_pos))
    for i, p in enumerate(seam_pos):
        kd.insert(p, i)
    kd.balance()
    out = {}
    for v in obj.data.vertices:
        co, i, d = kd.find(v.co)
        if d < 1e-6:
            out[v.index] = i
    return out


def _set_seam_normals(body, head, seam_pos, seam_nrm):
    """Both sides of the cut get the intact mesh's vertex normal at the seam, everything else auto."""
    for obj in (body, head):
        match = _seam_match(obj, seam_pos)
        nrm = [(0.0, 0.0, 0.0)] * len(obj.data.vertices)
        for vi, si in match.items():
            nrm[vi] = tuple(seam_nrm[si])
        for p in obj.data.polygons:
            p.use_smooth = p.use_smooth or bool(set(p.vertices) & match.keys())
        obj.data.normals_split_custom_set_from_vertices(nrm)
        print("FACE seam normals set on", obj.name, len(match))


def _seam_delta(head, seam_pos):
    match = _seam_match(head, seam_pos)
    if not head.data.shape_keys:
        return 0.0
    kbs = head.data.shape_keys.key_blocks
    base = _co(head, kbs[0])
    idx = np.array(sorted(match))
    return float(max(np.abs(_co(head, kb)[idx] - base[idx]).max() for kb in kbs[1:]))


# ---------------------------------------------------------------- 4. eye bones
EYE_BONES = {"L": "masha:eye.L", "R": "masha:eye.R"}  # L = character's left = +X
LID_FOLLOW = 0.0  # fraction of eye-bone rotation the upper lid/lashes follow (scaled by blink motion)


def _sphere_fit(pts):
    A = np.hstack([2 * pts, np.ones((len(pts), 1))])
    b = (pts ** 2).sum(1)
    sol = np.linalg.lstsq(A, b, rcond=None)[0]
    c = sol[:3]
    return c, math.sqrt(sol[3] + c @ c)


def add_eye_bones(ctx):
    rig, eyes, head = ctx["rig"], ctx["eyes"], ctx["head"]
    pts = np.array([(eyes.matrix_world @ v.co)[:] for v in eyes.data.vertices])
    centres = {}
    for side, sel in (("L", pts[:, 0] > 0), ("R", pts[:, 0] < 0)):
        c, r = _sphere_fit(pts[sel])
        centres[side] = (Vector(c), r)
    ctx["eye_centres"] = {s: ([round(x, 5) for x in c], round(r, 5)) for s, (c, r) in centres.items()}
    print("FACE eye centres", ctx["eye_centres"])

    common.select_only(rig)
    bpy.ops.object.mode_set(mode="EDIT")
    inv = rig.matrix_world.inverted()
    parent = rig.data.edit_bones["mixamorig:Head"]
    for side, name in EYE_BONES.items():
        c, r = centres[side]
        eb = rig.data.edit_bones.get(name) or rig.data.edit_bones.new(name)
        eb.head = inv @ c
        eb.tail = inv @ (c + Vector((0, -0.025, 0)))
        eb.roll = 0.0
        eb.parent = parent
        eb.use_connect = False
        eb.use_deform = True
    bpy.ops.object.mode_set(mode="OBJECT")

    # eyeballs: 100% to their bone
    for g in list(eyes.vertex_groups):
        eyes.vertex_groups.remove(g)
    groups = {s: eyes.vertex_groups.new(name=n) for s, n in EYE_BONES.items()}
    for v in eyes.data.vertices:
        groups["L" if (eyes.matrix_world @ v.co).x > 0 else "R"].add([v.index], 1.0, "REPLACE")

    # upper lids + lashes follow the eye bone a little (weighted by how much they move in a blink)
    if LID_FOLLOW > 0 and head.data.shape_keys:
        kbs = head.data.shape_keys.key_blocks
        base = _co(head, kbs[0])
        for side, key in (("L", "eyeBlinkLeft"), ("R", "eyeBlinkRight")):
            d = np.linalg.norm(_co(head, kbs[key]) - base, axis=1)
            dmax = d.max()
            g = head.vertex_groups.get(EYE_BONES[side]) or head.vertex_groups.new(name=EYE_BONES[side])
            for vi in np.nonzero(d > 0.08 * dmax)[0]:
                v = head.data.vertices[int(vi)]
                w = LID_FOLLOW * min(1.0, float(d[vi]) / (0.6 * dmax))
                for ge in v.groups:
                    ge.weight *= (1.0 - w)
                g.add([int(vi)], w, "REPLACE")
    n = _limit_weights(head, 4, set(rig.data.bones.keys()))
    print("FACE head bone influences trimmed", n)


def _limit_weights(obj, n, bones):
    """Keep the n strongest *bone* influences per vertex (Filament: max 4) and renormalise them.
    Non-bone MPFB groups (body, lips, scalp, joint-*...) are left untouched."""
    bone_idx = {g.index for g in obj.vertex_groups if g.name in bones}
    changed = 0
    for v in obj.data.vertices:
        gs = sorted((g for g in v.groups if g.group in bone_idx), key=lambda g: -g.weight)
        for g in gs[n:]:
            obj.vertex_groups[g.group].remove([v.index])
            changed += 1
        keep = [g for g in v.groups if g.group in bone_idx]
        tot = sum(g.weight for g in keep)
        if tot > 0 and gs[n:]:
            for g in keep:
                g.weight /= tot
    return changed


# ---------------------------------------------------------------- 5. eye material
EYE_EMISSION = float(os.environ.get("FACE_EYE_EMIS", "0.35"))         # emission strength of the iris-only mask (Filament: emissiveFactor)
SCLERA_DIM = 0.82           # sclera albedo multiplier (keeps eyes from reading as glowing balls)


def _eye_texture_path(ctx):
    AssetService = common.mpfb("AssetService")
    mhclo = AssetService.find_asset_absolute_path("low-poly.mhclo", asset_subdir="eyes")
    return os.path.join(os.path.dirname(os.path.dirname(mhclo)), "materials", f"{IRIS}_eye.png")


def _iris_masks(px):
    """px: HxWx4 float (bottom-up). Returns (mask HxW 0..1 iris incl. pupil, circles)."""
    rgb = px[..., :3]
    sat = rgb.max(-1) - rgb.min(-1)
    val = rgb.max(-1)
    H, W = sat.shape
    yy, xx = np.mgrid[0:H, 0:W]
    circles = []
    mask = np.zeros((H, W), dtype=np.float32)
    # the two eyes sit on the anti-diagonal halves of the atlas; the corner black disk is ignored
    for half in (xx + yy > W, xx + yy <= W):
        sel = half & (sat > 0.25) & (np.hypot(xx - 0.93 * W, yy - 0.07 * H) > 0.1 * W)
        cy, cx = yy[sel].mean(), xx[sel].mean()
        # iris radius: where saturation falls off radially
        r = np.hypot(xx - cx, yy - cy)
        prof = [sat[(r >= k) & (r < k + 2)].mean() for k in range(0, int(0.2 * W), 2)]
        thr = 0.5 * max(prof[len(prof) // 4: len(prof) // 2 + 1])
        rad = next(2 * i for i in range(len(prof) // 4, len(prof)) if prof[i] < thr)
        circles.append((float(cx), float(cy), float(rad)))
        m = np.clip((rad * 1.02 - r) / (0.08 * rad), 0, 1)
        mask = np.maximum(mask, m)
    return mask, circles, val


def eye_materials(ctx):
    tex_dir = common.ensure_dir(os.path.join(ctx["out_dir"], "textures"))
    src = bpy.data.images.load(_eye_texture_path(ctx), check_existing=True)
    W, H = src.size
    px = np.array(src.pixels[:], dtype=np.float32).reshape(H, W, 4)
    mask, circles, _ = _iris_masks(px)
    ctx["iris_circles_px"] = circles

    base = px.copy()
    base[..., :3] = px[..., :3] * (mask[..., None] + (1 - mask[..., None]) * SCLERA_DIM)
    emis = np.zeros_like(px)
    emis[..., :3] = px[..., :3] * mask[..., None]
    emis[..., 3] = 1.0
    maskimg = np.zeros_like(px)
    maskimg[..., :3] = mask[..., None]
    maskimg[..., 3] = 1.0
    paths = {}
    for name, arr in (("masha_eye_basecolor", base), ("masha_eye_emissive", emis), ("masha_eye_irismask", maskimg)):
        img = bpy.data.images.get(name) or bpy.data.images.new(name, W, H, alpha=True)
        img.pixels.foreach_set(arr.ravel())
        img.filepath_raw = os.path.join(tex_dir, name + ".png")
        img.file_format = "PNG"
        img.save()
        paths[name] = img.filepath_raw
    ctx["eye_textures"] = paths

    mat = bpy.data.materials.get("Masha_Eye") or bpy.data.materials.new("Masha_Eye")
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    tb = nt.nodes.new("ShaderNodeTexImage")
    tb.image = bpy.data.images["masha_eye_basecolor"]
    te = nt.nodes.new("ShaderNodeTexImage")
    te.image = bpy.data.images["masha_eye_emissive"]
    nt.links.new(tb.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(te.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = EYE_EMISSION
    bsdf.inputs["Roughness"].default_value = 0.28
    bsdf.inputs["Specular IOR Level"].default_value = 0.5
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    eyes = ctx["eyes"]
    eyes.data.materials.clear()
    eyes.data.materials.append(mat)


# ---------------------------------------------------------------- 6. contract
RECIPES = {
    "blink": {"eyeBlinkLeft": 1.0, "eyeBlinkRight": 1.0},
    "smile": {"mouthSmileLeft": 0.6, "mouthSmileRight": 0.6, "cheekSquintLeft": 0.35, "cheekSquintRight": 0.35,
              "eyeSquintLeft": 0.2, "eyeSquintRight": 0.2},
    "surprise": {"browInnerUp": 0.55, "browOuterUpLeft": 0.5, "browOuterUpRight": 0.5, "eyeWideLeft": 0.35,
                 "eyeWideRight": 0.35, "jawOpen": 0.2},
    "thoughtful": {"browDownLeft": 0.3, "browInnerUp": 0.25, "mouthPress": 0.35, "mouthLeft": 0.3,
                   "eyeSquintLeft": 0.25, "eyeSquintRight": 0.15},
    "listening": {"browInnerUp": 0.3, "browOuterUpLeft": 0.12, "browOuterUpRight": 0.12,
                  "mouthSmileLeft": 0.18, "mouthSmileRight": 0.18, "eyeWideLeft": 0.1, "eyeWideRight": 0.1},
}
RECIPE_GAZE = {"thoughtful": {"yaw_deg": -12, "pitch_deg": 10}, "listening": {"yaw_deg": 0, "pitch_deg": 0}}


# Lip-sync contract metadata: name -> (group, recommended max runtime weight, semantics).
# Mirrored in docs/MASHA_LIPSYNC.md ("Morph contract"); the JSON written by write_contract is authoritative.
MORPH_META = {
    "jawOpen": ("jaw", 0.6, "Jaw drop (chin, lower teeth, tongue, mouth floor). The ONLY jaw opening. x0.6 pre-scaled: "
                            "1.0 = natural maximum; speech 0.15-0.6."),
    "viseme_aa": ("viseme", 0.75, "A (father, casa). Lips-only: slightly raised upper lip, lips parted by the jaw."),
    "viseme_E": ("viseme", 0.75, "E (bed, mesa). Lips spread, upper teeth shown."),
    "viseme_I": ("viseme", 0.7, "I (see, sí). Strong spread."),
    "viseme_O": ("viseme", 0.8, "O. Rounded + protruded (MPFB O + 0.3 funnel + 0.15 pucker)."),
    "viseme_U": ("viseme", 0.85, "U / W glide. Tight round + protrusion (MPFB U + 0.6 pucker + 0.3 funnel)."),
    "viseme_PP": ("viseme", 1.0, "P/B/M. Lips sealed and pressed, slightly rolled; sealed at any jaw with the seal rule."),
    "viseme_FF": ("viseme", 0.9, "F/V(en). Lower lip up and tucked under the upper incisors; upper incisors shown."),
    "viseme_TH": ("viseme", 0.85, "Dental θ/ð (Spain z/c, English th). Tongue tip between the incisors: needs "
                                  "jawOpen >= 0.12 or the tip intersects the closed teeth."),
    "viseme_DD": ("viseme", 0.8, "T/D. Tongue tip raised to the alveolar ridge."),
    "viseme_kk": ("viseme", 0.7, "K/G/J(x). Lips neutral-open, tongue back (not visible)."),
    "viseme_CH": ("viseme", 0.8, "CH/SH/LL(y). Lips protruded and squared, teeth close."),
    "viseme_SS": ("viseme", 0.8, "S/Z(seseo). Lips spread, teeth close (jaw <= 0.15)."),
    "viseme_nn": ("viseme", 0.8, "N/L/Ñ. Tongue tip at the alveolar ridge, lips relaxed."),
    "viseme_RR": ("viseme", 0.75, "R (MPFB/Meta RR: English r, mild lip rounding) + tongue tip up 0.7. For the Spanish "
                                  "tap/trill prefer viseme_nn/DD lips with a short RR accent."),
    "mouthClose": ("mouthUnit", 1.0, "Lips sealed over an open jaw: mouthClose == jawOpen keeps the lips closed. "
                                     "Also the rest-closure correction channel."),
    "mouthFunnel": ("mouthUnit", 0.6, "Lips funnel (open rounding). Relative modifier (anticipate O/U)."),
    "mouthPucker": ("mouthUnit", 0.7, "Lips pushed forward, closed round (kiss). Absolute (restUndo 1)."),
    "mouthPress": ("mouthUnit", 0.6, "Lips pressed together (L+R). Relative."),
    "mouthRollLower": ("mouthUnit", 0.5, "Lower lip rolled in. Relative."),
    "mouthRollUpper": ("mouthUnit", 0.5, "Upper lip rolled in. Relative."),
    "mouthUpperUp": ("mouthUnit", 0.5, "Upper lip raised (L+R). Relative."),
    "mouthLowerDown": ("mouthUnit", 0.6, "Lower lip lowered (L+R). Relative."),
    "mouthShrugLower": ("mouthUnit", 0.5, "Lower lip pushed up/out. Relative."),
    "mouthShrugUpper": ("mouthUnit", 0.5, "Upper lip pushed up/out. Relative."),
    "mouthStretch": ("mouthUnit", 0.5, "Mouth corners stretched sideways/down (L+R). Relative."),
    "mouthDimple": ("mouthUnit", 0.5, "Corners pulled back, dimples (L+R). Relative."),
    "mouthLeft": ("mouthUnit", 0.5, "Whole mouth to her left. Relative."),
    "mouthRight": ("mouthUnit", 0.5, "Whole mouth to her right. Relative."),
    "tongueOut": ("tongue", 0.8, "Tongue out over the lower lip (tongue only). Use with jawOpen >= 0.3."),
    "mouthSmileLeft": ("expression", 0.6, "Smile corner. Relative."), "mouthSmileRight": ("expression", 0.6, "Smile corner. Relative."),
    "mouthFrownLeft": ("expression", 0.5, "Frown corner. Relative."), "mouthFrownRight": ("expression", 0.5, "Frown corner. Relative."),
}
SEAL = {"viseme_PP": 1.0, "viseme_FF": 0.6}   # mouthClose += jawOpen * w_viseme * SEAL (lips stay in contact)
TH_MIN_JAW = 0.12


def _rest_undo(name):
    if name.startswith(REBASE_PREFIX):
        return GAINS.get(name, 1.0) if name == "jawOpen" else 1.0
    return 0.0


def lipsync_contract(ctx):
    names = ctx["face_shapes"]
    ks = (ctx.get("_lip_only") or {}).get("k", {})
    morphs = []
    for i, n in enumerate(names):
        group, mx, doc = MORPH_META.get(n, ("eyes" if n.startswith("eye") else "brows" if n.startswith("brow")
                                             else "cheeks" if n.startswith("cheek") else "other", 1.0, ""))
        m = {"name": n, "index": i, "group": group, "lipOnly": n in LIP_ONLY, "recommendedMax": mx,
             "restUndo": round(_rest_undo(n), 3)}
        if n in LIP_ONLY:
            m["sourceJawOpen"] = round(ks.get(n, 0.0) / GAINS["jawOpen"], 3)
            if n in SEAL:
                m["sealWithJaw"] = SEAL[n]
            if n in VISEME_TONGUE:
                m["tongue"] = VISEME_TONGUE[n]
        if n == "viseme_TH":
            m["minJawOpen"] = TH_MIN_JAW
        if n == "tongueOut":
            m["minJawOpen"] = 0.3
        if doc:
            m["doc"] = doc
        morphs.append(m)
    return {
        "version": 2,
        "doc": "docs/MASHA_LIPSYNC.md#morph-contract. Resolve morphs BY NAME (indices are listed for reference; "
               "0-30 are unchanged from contract v1, the 16 lip-sync targets are appended).",
        "morphs": morphs,
        "rest_closure_rule": {
            "doc": "The basis has closed lips (C = 0.15 raw mouthClose, ~5 mm). Absolute shapes (restUndo > 0) each "
                   "undo C once at weight 1. When their restUndo-weighted sum U exceeds 1 the lips would be opened "
                   "(U-1) extra times; add mouthClose to put C back. Exact (linear).",
            "U": "sum(restUndo_i * w_i)  (visemes 1, mouthPucker 1, jawOpen 0.6, all others 0)",
            "correction": "if U > 1: mouthClose += %.4f * (U - 1)" % REST_FIX_PER_UNIT,
            "mouthClose_per_unit": round(REST_FIX_PER_UNIT, 4),
        },
        "seal_rule": {
            "doc": "Lip-contact visemes are built sealed at jawOpen 0. If the jaw is open while they are active, add "
                   "mouthClose so the lips stay in contact (mouthClose == jawOpen closes the lips over the jaw).",
            "correction": "mouthClose += jawOpen * sum(w_v * seal_v)",
            "seal": SEAL,
        },
        "order": "final mouthClose = authored mouthClose + seal correction + rest-closure correction; clamp to [0, 1].",
        "tongue_rest_offset_m": list(TONGUE_REST_OFFSET),
        "reconstruction": "viseme_X(1) + jawOpen(sourceJawOpen_X) + rest-closure correction == the original MPFB viseme "
                          "(lips exact; chin/jaw-line skin within a few mm, teeth/tongue follow jawOpen).",
    }


def write_contract(ctx):
    head = ctx["head"]
    nverts = len(head.data.vertices)
    ntargets = len(ctx["face_shapes"])
    contract = {
        "version": 2,
        "mesh": "Masha_Head",
        "head_vertices": nverts,
        "head_triangles": common.tri_count(head),
        "morph_targets": ctx["face_shapes"],
        "morph_vram_bytes_estimate": 24 * nverts * ntargets,
        "notes": [
            "Masha_Body carries no morph targets; all face morphs live on Masha_Head (brows, lashes, teeth, "
            "tongue joined in). Masha_Eyes carries no morphs; it is skinned to the eye bones.",
            "Rest pose has closed lips. Visemes are LIP-ONLY (no jaw): the jaw opens only through jawOpen "
            "(pre-scaled x0.6: 1.0 is the natural maximum, speech uses 0.15-0.6). See the 'lipsync' block and "
            "docs/MASHA_LIPSYNC.md for the rest-closure and seal rules.",
            "Normalise the viseme blend (sum of viseme weights <= 1); jawOpen is driven separately.",
        ],
        "lipsync": lipsync_contract(ctx),
        "eye_bones": {
            "left": EYE_BONES["L"], "right": EYE_BONES["R"], "parent": "mixamorig:Head",
            "rest": "head at eyeball centre, bone points forward (-Y Blender / +Z glTF)",
            "yaw": "rotate about bone-local Z (positive = eye looks to character's left)",
            "pitch": "rotate about bone-local X (positive = eye looks up)",
            "limits_deg": {"yaw": 25, "pitch_up": 15, "pitch_down": 20},
            "lid_follow": {
                "doc": "upper lids follow vertical gaze via morphs (skinning lids to eye bones was rejected: "
                       "it exposes the lid margin on look-up and drags inner-corner lashes on yaw)",
                "look_down": "eyeBlinkX += 0.45 * clamp(-pitch_deg / 20, 0, 1)   (added before the blink curve, "
                             "final weight = max(blink, coupling + blink))",
                "look_up": "eyeWideX += 0.35 * clamp(pitch_deg / 15, 0, 1)",
            },
            "centres_m": ctx.get("eye_centres"),
        },
        "old_to_new": {
            "MouthOpen": {"jawOpen": 0.55},
            "V_AA": {"viseme_aa": 1.0, "jawOpen": 0.5},
            "V_O": {"viseme_O": 1.0, "jawOpen": 0.2},
            "V_EE": {"viseme_E": 0.6, "viseme_I": 0.4},
            "V_FV": {"viseme_FF": 1.0},
            "V_MBP": {"viseme_PP": 1.0},
            "Smile": RECIPES["smile"],
            "BrowUp": {"browInnerUp": 0.7, "browOuterUpLeft": 0.5, "browOuterUpRight": 0.5},
            "BrowFrown": {"browDownLeft": 0.8, "browDownRight": 0.8, "mouthFrownLeft": 0.2, "mouthFrownRight": 0.2},
            "Blink_L": {"eyeBlinkLeft": 1.0},
            "Blink_R": {"eyeBlinkRight": 1.0},
        },
        "grapheme_to_viseme": {
            "_doc": "LEGACY (contract v1 letter-based lip-sync). Spanish/English letters -> viseme (weight). Digraphs "
                    "first. Visemes are lip-only since v2: drive jawOpen separately. Consonants are short (40-70 ms) "
                    "and blend 60% into the next vowel.",
            "a": {"viseme_aa": 1.0}, "e": {"viseme_E": 1.0}, "i": {"viseme_I": 1.0}, "y": {"viseme_I": 0.8},
            "o": {"viseme_O": 1.0}, "u": {"viseme_U": 1.0}, "w": {"viseme_U": 0.9},
            "m": {"viseme_PP": 1.0}, "b": {"viseme_PP": 1.0}, "p": {"viseme_PP": 1.0}, "v": {"viseme_PP": 0.7},
            "f": {"viseme_FF": 1.0},
            "s": {"viseme_SS": 1.0}, "z": {"viseme_SS": 0.9}, "c": {"viseme_SS": 0.8}, "x": {"viseme_SS": 0.7},
            "ch": {"viseme_CH": 1.0}, "sh": {"viseme_CH": 1.0}, "j": {"viseme_CH": 0.6}, "ll": {"viseme_CH": 0.7},
            "g": {"viseme_kk": 0.8}, "k": {"viseme_kk": 0.8}, "q": {"viseme_kk": 0.8},
            "t": {"viseme_DD": 1.0}, "d": {"viseme_DD": 1.0}, "n": {"viseme_nn": 0.9}, "l": {"viseme_nn": 0.9},
            "ñ": {"viseme_nn": 0.8, "viseme_I": 0.3}, "r": {"viseme_nn": 0.6}, "rr": {"viseme_nn": 0.7, "viseme_RR": 0.2},
            "th": {"viseme_TH": 1.0}, "h": {}, "_note_es": "Spanish 'v' is bilabial -> PP; "
            "English 'v' -> FF. 'c' before e/i and 'z' are /s/ (SS) or /θ/ (TH); 'c' before a/o/u, 'qu', 'k' -> kk.",
        },
        "recipes": {k: {"morphs": v, "gaze": RECIPE_GAZE.get(k)} for k, v in RECIPES.items()},
        "blink": {
            "shape": ["eyeBlinkLeft", "eyeBlinkRight"],
            "curve": "close 70 ms (ease-in), hold 20-30 ms at 1.0, open 110-150 ms (ease-out); total ~210 ms",
            "interval_s": [2.5, 6.0], "double_blink_probability": 0.15, "double_gap_ms": 120,
            "extra": "blink on large gaze shifts (>15 deg) and at the end of an utterance; "
                     "while thinking lower rate (5-9 s); asymmetric offset L/R 0-15 ms",
        },
        "idle": {
            "breathing": {"period_s": [3.6, 4.4], "chest_spine2_pitch_deg": 0.8, "shoulders_up_mm": 2,
                          "head_counter_pitch_deg": 0.3, "note": "body clip or procedural on Spine1/2"},
            "micro_expressions": {
                "interval_s": [4, 9], "duration_s": [0.6, 1.2], "envelope": "sin(pi*t/d)",
                "choices": {"half_smile": {"mouthSmileLeft": 0.15, "mouthSmileRight": 0.12},
                            "brow_flash": {"browInnerUp": 0.25, "browOuterUpLeft": 0.2, "browOuterUpRight": 0.2},
                            "soft_squint": {"eyeSquintLeft": 0.15, "eyeSquintRight": 0.15,
                                            "cheekSquintLeft": 0.1, "cheekSquintRight": 0.1}},
                "resting_bias": {"mouthSmileLeft": 0.08, "mouthSmileRight": 0.08},
            },
            "gaze": {
                "follow_user": "aim eye bones at the camera/user point; head (mixamorig:Head/Neck) takes "
                               "30-40% of large offsets with 250 ms lag, eyes take the rest immediately",
                "fixation_s": [0.8, 2.5],
                "micro_saccade": {"amplitude_deg": [0.3, 0.8], "interval_s": [0.4, 1.2], "duration_ms": 25},
                "saccade": {"speed_deg_per_s": 400, "duration_ms": "20 + 2.5*amplitude_deg"},
                "look_away_while_thinking": {"yaw_deg": [-15, 15], "pitch_deg": [5, 12], "hold_s": [0.8, 2.0]},
                "vergence": "both eyes aim at the same 3D point (user ~0.5-0.7 m) -> slight convergence",
            },
            "speaking": {"smoothing": "critically damped, 25-30 1/s for mouth, 4 1/s for expressions",
                         "brow_accent_on_stressed_syllable": {"browInnerUp": 0.15, "duration_ms": 250}},
        },
        "materials": {
            "Masha_Eye": {"baseColor": "textures/masha_eye_basecolor.png",
                          "emissive": "textures/masha_eye_emissive.png", "emissiveStrength": EYE_EMISSION,
                          "roughness": 0.18,
                          "note": "only the iris emits; keep eye emission <= 1 and exclude eyes from strong "
                                  "bloom (old avatar used emissive 6 on whole eyeballs -> glowing balls)"},
            "Masha_Teeth": {"baseColor": "textures/masha_teeth_basecolor.png", "roughness": 0.35,
                            "note": "re-graded CC0 texture: off-white enamel, muted dark-pink gums"},
            "Masha_Tongue": {"baseColor": "textures/masha_tongue_basecolor.png", "roughness": 0.55},
            "Masha_MouthInterior": {"baseColor_linear": list(MOUTH_INTERIOR_RGB), "roughness": 0.6,
                                    "note": "mouth cavity faces of the skin (flat colour, no texture); keep it "
                                            "non-emissive / dim in the hologram shader"},
        },
        "head_breakdown": ctx.get("head_breakdown"),
        "eyes_mesh": {"name": "Masha_Eyes", "verts": len(ctx["eyes"].data.vertices),
                      "tris": common.tri_count(ctx["eyes"]), "morphs": 0},
        "qa_grids": {
            "grid_expressions.png": "rows of 4: blink(front,3/4) smile(front,3/4) / surprise thoughtful / listening",
            "grid_visemes_*.png": "rows of 5: aa E I O U / PP FF SS DD CH / jawOpen jawOpen@0.5 rest",
            "grid_eyes.png": "rows of 2: centre, yaw+20 (her left) / yaw-20, pitch+15 / pitch-15, "
                             "pitch-20+blink0.5 / blink, blink0.5",
            "grid_shapes.png": "rows of 6, every non-viseme kept morph at 1.0 in KEEP order",
        },
        "neck_seam": {k: v for k, v in (ctx.get("neck_seam") or {}).items()},
        "seam_max_morph_delta_m": ctx.get("_seam_shape_max_delta"),
        "assets": {"eyes": EYES, "iris": IRIS, "eyebrows": BROWS, "eyelashes": LASHES, "teeth": "teeth_base",
                   "tongue": "tongue01", "targets": list(FACE_TARGETS),
                   "license": "CC0 (MakeHuman system assets, faceunits01, visemes02 packs)"},
    }
    with open(os.path.join(ctx["out_dir"], "face_contract.json"), "w", encoding="utf-8") as f:
        json.dump(contract, f, indent=1, ensure_ascii=False)


# ---------------------------------------------------------------- QA
def _qa_skin():
    mat = bpy.data.materials.get("qa_skin") or bpy.data.materials.new("qa_skin")
    mat.use_nodes = True
    p = mat.node_tree.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = (0.78, 0.60, 0.50, 1)
    p.inputs["Roughness"].default_value = 0.5
    p.inputs["Subsurface Weight"].default_value = 0.15
    p.inputs["Subsurface Radius"].default_value = (0.02, 0.008, 0.004)
    return mat


def face_center(ctx):
    eyes = ctx["eyes"]
    cs = [eyes.matrix_world @ v.co for v in eyes.data.vertices]
    return Vector((0, sum(c.y for c in cs) / len(cs), sum(c.z for c in cs) / len(cs)))


def face_shots(ctx, dist=0.62, lens=90):
    ec = face_center(ctx)
    tgt = (0, ec.y + 0.03, ec.z - 0.03)
    z = ec.z - 0.01
    a = math.radians(35)
    return [
        ("front", (0, tgt[1] - dist, z), tgt, lens),
        ("threeq", (dist * math.sin(a), tgt[1] - dist * math.cos(a), z), tgt, lens),
        ("profile", (dist, tgt[1], z), tgt, lens),
    ]


def mouth_shots(ctx, dist=0.32, lens=100):
    ec = face_center(ctx)
    tgt = (0, ec.y - 0.02, ec.z - 0.063)
    a = math.radians(30)
    return [("mfront", (0, tgt[1] - dist, tgt[2] + 0.01), tgt, lens),
            ("mthreeq", (dist * math.sin(a), tgt[1] - dist * math.cos(a), tgt[2] + 0.01), tgt, lens)]


def eye_shots(ctx, dist=0.30, lens=100):
    ec = face_center(ctx)
    tgt = (0, ec.y, ec.z)
    a = math.radians(30)
    return [("efront", (0, tgt[1] - dist, tgt[2] + 0.005), tgt, lens),
            ("ethreeq", (dist * math.sin(a), tgt[1] - dist * math.cos(a), tgt[2] + 0.005), tgt, lens)]


def qa_setup(ctx):
    skin = _qa_skin()
    for key in ("body", "head"):
        o = ctx.get(key)
        if o is not None and o.type == "MESH":
            if not o.data.materials:
                o.data.materials.append(skin)
            for i, m in enumerate(o.data.materials):
                if m is None or m.name == "Masha_Skin":
                    o.data.materials[i] = skin


def qa_lights():
    scene = bpy.context.scene
    if bpy.data.objects.get("qa_cam") is None:
        cam = bpy.data.objects.new("qa_cam", bpy.data.cameras.new("qa_cam"))
        scene.collection.objects.link(cam)
    for o in [o for o in bpy.data.objects if o.type == "LIGHT"]:
        bpy.data.objects.remove(o, do_unlink=True)
    for name, loc, energy, size in (("fq_key", (0.9, -1.2, 2.2), 70, 0.9),
                                    ("fq_fill", (-1.2, -1.0, 1.7), 28, 1.2),
                                    ("fq_rim", (-0.6, 1.0, 2.0), 45, 0.6),
                                    ("fq_rim2", (0.7, 0.9, 1.8), 25, 0.6)):
        light = bpy.data.objects.new(name, bpy.data.lights.new(name, "AREA"))
        light.data.energy = energy
        light.data.size = size
        light.location = loc
        light.rotation_euler = (Vector((0, -0.1, 1.52)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        scene.collection.objects.link(light)
    scene.world = scene.world or bpy.data.worlds.new("qa")
    scene.view_settings.view_transform = "AgX"


def set_shape(obj, name, value):
    if obj is None or obj.data.shape_keys is None:
        return
    kb = obj.data.shape_keys.key_blocks.get(name)
    if kb is not None:
        kb.slider_min = min(kb.slider_min, value)
        kb.slider_max = max(kb.slider_max, value)
        kb.value = value


def face_objs(ctx):
    return [ctx.get(k) for k in ("body", "head", "brows", "lashes", "teeth", "tongue", "eyes")
            if ctx.get(k) is not None and ctx.get(k).name in bpy.data.objects]


def set_gaze(ctx, yaw=0.0, pitch=0.0):
    rig = ctx["rig"]
    for name in EYE_BONES.values():
        pb = rig.pose.bones.get(name)
        if pb is not None:
            pb.rotation_mode = "XYZ"
            pb.rotation_euler = (math.radians(pitch), 0.0, math.radians(yaw))


def apply_recipe(ctx, recipe, gaze=None):
    for o in face_objs(ctx):
        if o.data.shape_keys:
            for kb in o.data.shape_keys.key_blocks[1:]:
                kb.value = 0.0
            for k, v in recipe.items():
                set_shape(o, k, v)
    g = gaze or {}
    set_gaze(ctx, g.get("yaw_deg", 0.0), g.get("pitch_deg", 0.0))


def render_recipes(ctx, prefix, recipes, shots, res=(600, 600)):
    paths = []
    for name, recipe in recipes.items():
        gaze = None
        if isinstance(recipe, tuple):
            recipe, gaze = recipe
        apply_recipe(ctx, recipe, gaze)
        paths += common.qa_renders(ctx["qa_dir"], f"{prefix}_{name}", shots, res=res)
    apply_recipe(ctx, {})
    return paths


def grid(paths, out, cols):
    """Assemble renders (same size) into one PNG, row-major from the top-left."""
    ims = [bpy.data.images.load(p) for p in paths]
    w, h = ims[0].size
    rows = (len(ims) + cols - 1) // cols
    big = np.zeros((rows * h, cols * w, 4), dtype=np.float32)
    big[..., 3] = 1
    for i, im in enumerate(ims):
        a = np.array(im.pixels[:], dtype=np.float32).reshape(h, w, 4)
        r, c = i // cols, i % cols
        y0 = (rows - 1 - r) * h
        big[y0:y0 + h, c * w:(c + 1) * w] = a
        bpy.data.images.remove(im)
    img = bpy.data.images.new("qa_grid", cols * w, rows * h, alpha=True)
    img.pixels.foreach_set(big.ravel())
    img.filepath_raw = out
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)
    return out


def qa(ctx):
    qa_setup(ctx)
    qa_lights()
    d = ctx["qa_dir"]
    only = os.environ.get("FACE_QA_ONLY", "neutral,expr,visemes,teeth,eyes,shapes").split(",")
    if "neutral" in only:
        common.qa_renders(d, "neutral", face_shots(ctx), res=(900, 900))
        common.qa_renders(d, "neutral", mouth_shots(ctx) + eye_shots(ctx), res=(700, 500))
        z = (ctx.get("neck_seam") or {}).get("z_front", 1.39)
        seam = [("seam_front", (0.05, -0.55, z + 0.03), (0, -0.03, z + 0.01), 85),
                ("seam_side", (0.5, -0.25, z + 0.04), (0, -0.01, z + 0.01), 85),
                ("seam_back", (-0.3, 0.45, z + 0.06), (0, 0.0, z + 0.03), 85)]
        common.qa_renders(d, "neutral", seam, res=(700, 500))
    if "expr" in only:
        rec = {k: (v, RECIPE_GAZE.get(k)) for k, v in RECIPES.items()}
        p = render_recipes(ctx, "expr", rec, face_shots(ctx)[:2], res=(600, 600))
        grid(p, os.path.join(d, "grid_expressions.png"), 4)
    if "teeth" in only:
        ec = face_center(ctx)
        tgt = (0, ec.y - 0.03, ec.z - 0.066)
        shots = [("tclose", (0, tgt[1] - 0.16, tgt[2] + 0.004), tgt, 100)]
        rec = {n: {n: 1.0} for n in ("viseme_I", "viseme_SS", "viseme_DD", "viseme_CH", "viseme_E", "viseme_aa")}
        p = render_recipes(ctx, "teeth", rec, shots, res=(600, 360))
        grid(p, os.path.join(d, "grid_teeth_close.png"), 3)
    if "visemes" in only:
        vis = {n: {n: 1.0} for n in KEEP if n.startswith("viseme_")}
        vis["jawOpen"] = {"jawOpen": 1.0}
        vis["jawOpen_half"] = {"jawOpen": 0.5}
        vis["rest"] = {}
        p = render_recipes(ctx, "vis", vis, mouth_shots(ctx)[:1], res=(500, 400))
        grid(p, os.path.join(d, "grid_visemes_front.png"), 5)
        p = render_recipes(ctx, "vis", vis, mouth_shots(ctx)[1:], res=(500, 400))
        grid(p, os.path.join(d, "grid_visemes_threeq.png"), 5)
    if "eyes" in only:
        gz = {"c": {}, "L20": ({}, {"yaw_deg": 20}), "R20": ({}, {"yaw_deg": -20}),
              "U15": ({}, {"pitch_deg": 15}), "D15": ({}, {"pitch_deg": -15}),
              "D20blink50": ({"eyeBlinkLeft": 0.5, "eyeBlinkRight": 0.5}, {"pitch_deg": -20}),
              "blink": {"eyeBlinkLeft": 1.0, "eyeBlinkRight": 1.0},
              "blink_half": {"eyeBlinkLeft": 0.5, "eyeBlinkRight": 0.5}}
        p = render_recipes(ctx, "eye", gz, eye_shots(ctx)[:1], res=(700, 350))
        grid(p, os.path.join(d, "grid_eyes.png"), 2)
    if "shapes" in only:
        sh = {n: {n: 1.0} for n in KEEP if not n.startswith("viseme_")}
        p = render_recipes(ctx, "shape", sh, face_shots(ctx)[:1], res=(420, 420))
        grid(p, os.path.join(d, "grid_shapes.png"), 6)
    apply_recipe(ctx, {})
