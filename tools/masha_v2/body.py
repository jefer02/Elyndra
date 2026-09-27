"""Stage 1 - body (owner: Agent 3).  Masha v2, deterministic + headless.

build(ctx) does, in order:
  1. MPFB human from MACRO + DETAIL_TARGETS, baked (no shape keys left), feet re-grounded at z=0.
  2. Suit sculpt (positions only - vertex count/order of Masha_Body never changes):
     nipples/areola + navel -> quadratic dome fit with tangential relaxation (no cone, no folds),
     crotch -> bi-Laplacian fairing (smooth gusset), intergluteal cleft softened,
     toes -> spatial Gaussian fusion + fairing = smooth boot tip.
  3. mixamo rig + CC0 assets (eyes, brows, lashes, teeth, tongue, ponytail01 hair).
  4. Joint weight relaxation (shoulder, hip, knee, neck).
  5. Soft bones masha:breast.L/R (Spine2), masha:glute.L/R (Hips), masha:hair.0..3 chain (Head),
     weights taken smoothly from the parent region; ctx["soft_bones"] registered.
  6. Final weight pass: <= 4 influences, normalized, on every skinned mesh.
  7. Holographic suit textures -> <out>/textures/ (see "Suit textures" below).
make_lod(ctx, ratio) is called by the export stage (lite tier). qa(ctx) renders QA shots
(env MASHA_QA=wb,soft,pose,suit selects the groups).

Tunables (all module-level dicts; lengths in metres unless noted)
------------------------------------------------------------------------------------------------
MACRO            gender 0 (female), age 0.52 (~26 y, clearly adult), muscle 0.6 (athletic),
                 weight 0.45, proportions 1.0 (idealised), height 0.6 (~1.70 m),
                 cupsize 0.62 / firmness 0.8 (moderate, natural rounded shape), race mix 1/3 each.
DETAIL_TARGETS   MPFB detail targets (name -> weight, baked): narrower waist 0.35, hips +0.10,
                 buttocks -0.15, shoulders +0.25, longer upper/lower legs 0.25/0.20,
                 neck longer 0.20 / slimmer 0.15, breast lift 0.25, breast-point-decr 1.0,
                 nipple point/size decr 1.0, flatter stomach 0.25, slight V-taper 0.10,
                 slimmer knees 0.20 / ankles 0.30, upper-arm fat -0.30.  Genital targets unused (0).
ASSETS           (subdir, file, MPFB type, ctx key, object name) - all CC0 MakeHuman system assets.
SUIT             nipple_core/blend/fit  dome-fit radii (inner full / blend edge / fitting ring)
                 ring_fair_iters        fairing of the dome blend ring
                 navel_core/blend/fit   same for the navel
                 crotch_core/fall/iters fairing region around the genital helper + iterations
                 cleft_core/fall/iters/strength  intergluteal cleft softening
                 toe_core/fall          boot-tip region around the toenails
                 toe_sigma/spatial_iters  Gaussian fusion of the toes; toe_iters fairing passes
SOFT             breast_depth/inset_x/len  breast bone head = nipple + (-inset_x, +depth, -5mm), tail
                                           len forward (-Y); breast_radii/center/soft/max = weight
                                           ellipsoid, where along the bone, plateau softness, max share
                 glute_zwin/depth/len      glute apex search window (z), head = apex - depth (inside),
                                           tail +len backward; glute_radii/center/soft/max, glute_from_leg
                                           (share also taken from UpLeg; 0 keeps hip flexion clean)
                 hair_bones/back_y/tie_z   number of ponytail bones, y threshold of back hair, tie height
WEIGHT_FIX       joint -> (bone at joint, ellipsoid radii, diffusion iterations)
TEX              res (2048; a 1024 downscale is also written), seed, pad_px (UV padding),
                 normal_strength, seam_half_mm, trace_half_mm, pad_r_mm, flow_len (m of body path
                 mapped to B = 0..1), collar_band, belt_z_offset/belt_v/belt_width, yoke_above_bust,
                 yoke_v, back_yoke, shoulder_r (raglan radius), cuff_from_wrist/cuff_width,
                 glove_alpha, boot_frac/boot_v/boot_band, knee_r, sole_z, *_traces (circuit layout).
NECK_SEAM_VERTS  base-mesh indices of Agent 4's head split loop (collar seam is drawn on it).

Suit textures (UVMap of Masha_Body, linear/non-colour PNG, straight alpha)
  masha_suit_mask_{2048,1024}.png
     R = panel seams (collar + collar band, raglan shoulders, chest V-yoke, back yoke, side seams,
         belt band, inner-arm seams, cuffs, outer-leg seams, knee arcs, boot line + band, sole line)
     G = circuit traces with pads/vias (sternum pair, side pairs, spine pair, 2 per arm, 3 per leg)
     B = flow phase 0..1: normalized body-path length from the collar outwards (torso down, arms
         from the shoulder, legs from the belt) - animate pulses with fract(B*k - t*speed)
     A = suit coverage / density: 1 suit + boots, 0.3 hands ("glove"), 0 above the collar
  masha_suit_normal_{2048,1024}.png  tangent-space normal (OpenGL/glTF +Y) - seam grooves,
     slightly raised belt band / boots / traces.
"""
import math
import os

import bpy
import numpy as np
from mathutils import Vector, kdtree

import common

MACRO = {"gender": 0.0, "age": 0.52, "muscle": 0.6, "weight": 0.45, "proportions": 1.0,
         "height": 0.6, "cupsize": 0.62, "firmness": 0.8,
         "race": {"asian": 0.33, "caucasian": 0.34, "african": 0.33}}

DETAIL_TARGETS = {
    "measure-waist-circ-decr": 0.35,
    "measure-hips-circ-incr": 0.10,
    "buttocks-volume-decr": 0.15,
    "measure-shoulder-dist-incr": 0.25,
    "upperlegs-height-incr": 0.25,
    "lowerlegs-height-incr": 0.20,
    "measure-neck-height-incr": 0.20,
    "measure-neck-circ-decr": 0.15,
    "breast-trans-up": 0.25,
    "breast-point-decr": 1.0,
    "nipple-point-decr": 1.0,
    "nipple-size-decr": 1.0,
    "stomach-pregnant-decr": 0.25,
    "torso-vshape-incr": 0.10,
    "measure-knee-circ-decr": 0.20,
    "measure-ankle-circ-decr": 0.30,
    "l-upperarm-fat-decr": 0.3, "r-upperarm-fat-decr": 0.3,
}

ASSETS = [
    ("eyes", "low-poly.mhclo", "Eyes", "eyes", "Masha_Eyes"),
    ("eyebrows", "eyebrow001.mhclo", "Eyebrows", "brows", "Masha_Brows"),
    ("eyelashes", "eyelashes01.mhclo", "Eyelashes", "lashes", "Masha_Lashes"),
    ("teeth", "teeth_base.mhclo", "Teeth", "teeth", "Masha_Teeth"),
    ("tongue", "tongue01.mhclo", "Tongue", "tongue", "Masha_Tongue"),
    ("hair", "ponytail01.mhclo", "Hair", "hair", "Masha_Hair"),
]

# suit sculpt: (region, iterations, strength)
SUIT = {"nipple_core": 0.015, "nipple_blend": 0.035, "nipple_fit": 0.06,
        "ring_fair_iters": 150, "navel_core": 0.01, "navel_blend": 0.025, "navel_fit": 0.045,
        "crotch_core": 0.03, "crotch_fall": 0.04, "crotch_iters": 1500,
        "cleft_core": 0.015, "cleft_fall": 0.03, "cleft_iters": 300, "cleft_strength": 1.0,
        "toe_core": 0.03, "toe_fall": 0.025, "toe_iters": 1200, "toe_sigma": 0.009, "toe_spatial_iters": 5}

SOFT = {"breast_depth": 0.07, "breast_inset_x": 0.01, "breast_len": 0.05,
        "breast_radii": (0.085, 0.09, 0.085), "breast_max": 0.8, "breast_center": 0.5, "breast_soft": 0.7,
        "glute_zwin": (0.72, 0.98), "glute_depth": 0.08, "glute_len": 0.06,
        "glute_radii": (0.095, 0.11, 0.11), "glute_max": 0.75, "glute_from_leg": 0.4, "glute_center": 0.6,
        "glute_soft": 0.7,
        "hair_bones": 4, "hair_back_y": 0.03, "hair_tie_z": 1.61}


# joint weight relaxation: name -> (bone at the joint, ellipsoid radii (m), diffusion iterations)
WEIGHT_FIX = {"shoulder": ("mixamorig:{S}Arm", (0.09, 0.09, 0.10), 25),
              "hip": ("mixamorig:{S}UpLeg", (0.11, 0.11, 0.09), 25),
              "knee": ("mixamorig:{S}Leg", (0.08, 0.08, 0.08), 20),
              "neck": ("mixamorig:Neck", (0.08, 0.08, 0.055), 30)}


# suit textures (lengths in metres unless *_mm / *_px)
TEX = {"res": 2048, "seed": 7, "pad_px": 8, "normal_strength": 2.5,
       "seam_half_mm": 1.1, "trace_half_mm": 0.95, "pad_r_mm": 3.6, "flow_len": 1.6,
       "collar_band": 0.022, "belt_z_offset": -0.035, "belt_v": 0.025, "belt_width": 0.045,
       "yoke_above_bust": 0.075, "yoke_v": 0.05, "back_yoke": 0.06, "shoulder_r": 0.085,
       "cuff_from_wrist": 0.025, "cuff_width": 0.03, "glove_alpha": 0.3,
       "boot_frac": 0.45, "boot_v": 0.02, "boot_band": 0.025, "knee_r": 0.05, "sole_z": 0.02,
       # torso traces: (theta0 rad (0 = front centre, pi/2 = side), jog rad, step m, |theta| lo, hi); mirrored L/R
       "torso_traces": [(0.13, 0.07, 0.06, 0.08, 0.3), (1.45, 0.12, 0.07, 1.2, 1.8), (2.92, 0.08, 0.07, 2.6, 3.06)],
       # limb traces: (theta0 (0 front, +pi/2 inner, -pi/2 outer, pi back), jog, step, lo, hi)
       "arm_traces": [(-1.4, 0.18, 0.07, -1.9, -0.9), (0.35, 0.18, 0.08, 0.1, 0.8)],
       "leg_traces": [(-1.35, 0.15, 0.08, -1.8, -0.9), (-0.3, 0.15, 0.09, -0.6, 0.2), (2.75, 0.15, 0.09, 2.4, 3.0)]}

# base-mesh indices of the neck edge loop where Agent 4 splits Masha_Head (face_contract.json:neck_seam)
NECK_SEAM_VERTS = [759, 760, 773, 774, 779, 796, 811, 812, 813, 815, 816, 822, 828, 834, 840, 846, 852, 858, 931,
                   1017, 1045, 3688, 7478, 7479, 7492, 7493, 7498, 7511, 7523, 7524, 7525, 7527, 7533, 7539, 7545,
                   7551, 7557, 7563, 7633, 7709, 7737, 10356]


# ------------------------------------------------------------------ helpers
def vg_weights(obj, name):
    n = len(obj.data.vertices)
    w = np.zeros(n)
    g = obj.vertex_groups.get(name)
    if g is None:
        return w
    gi = g.index
    for v in obj.data.vertices:
        for e in v.groups:
            if e.group == gi:
                w[v.index] = e.weight
    return w


def mesh_co(obj):
    a = np.zeros(len(obj.data.vertices) * 3)
    obj.data.vertices.foreach_get("co", a)
    return a.reshape(-1, 3)


def set_mesh_co(obj, co):
    obj.data.vertices.foreach_set("co", co.reshape(-1).astype(np.float32))
    obj.data.update()


def mesh_edges(obj, vmask=None):
    e = np.zeros(len(obj.data.edges) * 2, dtype=np.int64)
    obj.data.edges.foreach_get("vertices", e)
    e = e.reshape(-1, 2)
    if vmask is not None:
        e = e[vmask[e[:, 0]] & vmask[e[:, 1]]]
    return e


def components(n, edges):
    """connected-component label per vertex (union-find)"""
    parent = np.arange(n)

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for a, b in edges:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb
    return np.array([find(i) for i in range(n)])


def spatial_smooth(co, pool, weight, sigma, iters):
    """Euclidean Gaussian smoothing of the weighted vertices against the point pool (ignores topology)."""
    co = co.copy()
    act = np.where(weight > 1e-4)[0]
    for _ in range(iters):
        idx = np.where(pool & (np.linalg.norm(co - co[act].mean(0), axis=1) < 1.0))[0]
        kd = kdtree.KDTree(len(idx))
        for k, i in enumerate(idx):
            kd.insert(co[i], k)
        kd.balance()
        out = co.copy()
        for i in act:
            hits = kd.find_range(co[i], 2.5 * sigma)
            if not hits:
                continue
            pts = np.array([co[idx[h[1]]] for h in hits])
            g = np.exp(-0.5 * (np.array([h[2] for h in hits]) / sigma) ** 2)
            out[i] = co[i] + weight[i] * ((pts * g[:, None]).sum(0) / g.sum() - co[i])
        co = out
    return co


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


def dist_to_set(co, seed_co):
    """euclidean distance from every co to the nearest seed point"""
    kd = kdtree.KDTree(len(seed_co))
    for i, c in enumerate(seed_co):
        kd.insert(c, i)
    kd.balance()
    return np.array([kd.find(c)[2] for c in co])


def laplacian(co, edges, weight, iters, lam=0.5, mu=None):
    """weighted (Taubin if mu) smoothing; weight in [0,1] per vertex"""
    co = co.copy()
    n = len(co)
    deg = np.zeros(n)
    np.add.at(deg, edges[:, 0], 1)
    np.add.at(deg, edges[:, 1], 1)
    deg = np.maximum(deg, 1)[:, None]
    w = weight[:, None]

    def step(c, f):
        s = np.zeros_like(c)
        np.add.at(s, edges[:, 0], c[edges[:, 1]])
        np.add.at(s, edges[:, 1], c[edges[:, 0]])
        return c + f * w * (s / deg - c)

    for _ in range(iters):
        co = step(co, lam)
        if mu is not None:
            co = step(co, mu)
    return co


def fair(co, edges, weight, iters, step=0.2):
    """bi-Laplacian fairing: x -= step*w*L(L(x)).  Removes bumps/creases inside the weighted region
    while keeping position AND tangent continuity with the untouched surface (domes stay domes)."""
    co = co.copy()
    n = len(co)
    deg = np.zeros(n)
    np.add.at(deg, edges[:, 0], 1)
    np.add.at(deg, edges[:, 1], 1)
    deg = np.maximum(deg, 1)[:, None]
    w = weight[:, None]

    def lap(c):
        s = np.zeros_like(c)
        np.add.at(s, edges[:, 0], c[edges[:, 1]])
        np.add.at(s, edges[:, 1], c[edges[:, 0]])
        return s / deg - c

    for _ in range(iters):
        co = co - step * w * lap(lap(co))
    return co


def dome_fit(co, center, normal, r_in, r_blend, r_fit, bodym, edges=None, relax=60):
    """Replace the surface within r_blend of center by a quadratic height field fitted (least squares)
    to the ring r_blend..r_fit. Removes nipples / navel / cones while keeping a natural dome that
    continues the surrounding curvature. With `edges`, vertices are also relaxed tangentially and
    re-projected onto the dome, so folded pit/nipple walls unfold into an even, fold-free patch."""
    n = Vector(normal).normalized()
    u = n.orthogonal().normalized()
    v = n.cross(u)
    U, V, N = np.array(u), np.array(v), np.array(n)
    C = np.array(center)

    def frame(c):
        d = c - C
        return d @ U, d @ V, d @ N

    pu, pv, ph = frame(co)
    r = np.sqrt(pu * pu + pv * pv)
    front = (ph > -0.04) & (bodym > 0)
    ring = front & (r >= r_blend) & (r <= r_fit)
    A = np.stack([np.ones(ring.sum()), pu[ring], pv[ring], pu[ring] ** 2, pu[ring] * pv[ring], pv[ring] ** 2], 1)
    coef, *_ = np.linalg.lstsq(A, ph[ring], rcond=None)
    hfun = lambda a, b: np.stack([np.ones_like(a), a, b, a * a, a * b, b * b], 1) @ coef
    w = smoothstep((r_blend - r) / max(r_blend - r_in, 1e-6)) * front
    co = co + (w * (hfun(pu, pv) - ph))[:, None] * N[None, :]
    if edges is not None and relax > 0:
        act = w > 1e-3
        e = edges[act[edges[:, 0]] | act[edges[:, 1]]]
        deg = np.zeros(len(co))
        np.add.at(deg, e[:, 0], 1)
        np.add.at(deg, e[:, 1], 1)
        deg = np.maximum(deg, 1)[:, None]
        for _ in range(relax):
            s_ = np.zeros_like(co)
            np.add.at(s_, e[:, 0], co[e[:, 1]])
            np.add.at(s_, e[:, 1], co[e[:, 0]])
            co = co + 0.5 * w[:, None] * (s_ / deg - co)
            pu, pv, ph = frame(co)
            co = co + (w * (hfun(pu, pv) - ph))[:, None] * N[None, :]
    return co


# ------------------------------------------------------------------ stages
def _load_details(body, table):
    TargetService = common.mpfb("TargetService")
    for name, w in table.items():
        if abs(w) < 1e-6:
            continue
        path = TargetService.target_full_path(name)
        if path is None or not os.path.basename(path).startswith(name):
            raise RuntimeError(f"target not found: {name} -> {path}")
        TargetService.load_target(body, path, weight=float(w), name="d_" + name)


def _ground(body):
    co = mesh_co(body)
    bodym = vg_weights(body, "body") > 0
    co[:, 2] -= co[bodym, 2].min()
    set_mesh_co(body, co)


def _region(co, seed, core, fall):
    d = dist_to_set(co, co[seed])
    return smoothstep(1.0 - (d - core) / fall)


def _target_vertices(name):
    """vertex indices touched by an MPFB target file (used as anatomical landmarks)"""
    import gzip
    path = common.mpfb("TargetService").target_full_path(name)
    idx = []
    with gzip.open(path, "rt") as f:
        for line in f:
            parts = line.split()
            if len(parts) == 4 and parts[0].isdigit():
                idx.append(int(parts[0]))
    return np.array(idx)


def _suit_sculpt(body):
    """Play-Store safety: the suit hides anatomy. Positions only; vertex count/order untouched."""
    co = mesh_co(body)
    bodym = (vg_weights(body, "body") > 0).astype(float)
    edges = mesh_edges(body, bodym > 0)
    tip = vg_weights(body, "nippleTip") > 0
    for side in (1, -1):
        sel = tip & (co[:, 0] * side > 0)
        c = co[sel].mean(0)
        nrm = (c - np.array([0.0, 0.02, c[2] - 0.02]))      # radial from the ribcage axis, slight downward
        nrm = nrm * np.array([0.45, 1.0, 1.0])               # mostly forward
        co = dome_fit(co, c, nrm, SUIT["nipple_core"], SUIT["nipple_blend"], SUIT["nipple_fit"], bodym, edges)
        # soften the blend ring (annulus only; the centre stays fixed so no cone can re-form)
        d = np.linalg.norm(co - c, axis=1)
        ann = smoothstep((d - SUIT["nipple_core"]) / 0.01) * smoothstep((SUIT["nipple_fit"] - d) / 0.015) * bodym
        co = fair(co, edges, ann, SUIT["ring_fair_iters"])
    # navel: the suit bridges it -> same geometric dome fit (seed = vertices of MPFB's navel target)
    nav = _target_vertices("stomach-navel-in")
    c = co[nav].mean(0)
    co = dome_fit(co, c, (0, -1, 0.1), SUIT["navel_core"], SUIT["navel_blend"], SUIT["navel_fit"], bodym, edges)
    gen = vg_weights(body, "helper-genital") > 0
    co = fair(co, edges, _region(co, gen, SUIT["crotch_core"], SUIT["crotch_fall"]) * bodym, SUIT["crotch_iters"])
    # intergluteal cleft: soften (fabric bridges it) but keep the glute separation readable
    hip_z = co[gen, 2].min()
    cleft = (bodym > 0) & (np.abs(co[:, 0]) < 0.012) & (co[:, 1] > 0.0) & (co[:, 2] > hip_z - 0.02) & (co[:, 2] < hip_z + 0.16)
    if cleft.any():
        co = fair(co, edges, _region(co, cleft, SUIT["cleft_core"], SUIT["cleft_fall"]) * bodym * SUIT["cleft_strength"],
                  SUIT["cleft_iters"])
    # toes -> smooth boot tip
    toe = vg_weights(body, "toenails") > 0
    if SUIT["toe_iters"] > 0:
        # Graph smoothing collapses thin toes into needles, so first smooth in *space*: every toe-box
        # vertex moves to the Gaussian-weighted mean of all skin points around it (neighbouring toes
        # included), which fuses the toes into one mass; then bi-Laplacian fairing cleans the surface.
        wt = _region(co, toe, SUIT["toe_core"], SUIT["toe_fall"]) * bodym
        new = spatial_smooth(co, bodym > 0, wt, SUIT["toe_sigma"], SUIT["toe_spatial_iters"])
        co = fair(new, edges, wt, SUIT["toe_iters"])
    set_mesh_co(body, co)


# ------------------------------------------------------------------ weights / soft bones
def bone_names(rig):
    return [b.name for b in rig.data.bones]


def read_weights(obj, names):
    """dense [n_verts, len(names)] matrix of the listed vertex groups"""
    col = {}
    for j, nm in enumerate(names):
        g = obj.vertex_groups.get(nm)
        if g is not None:
            col[g.index] = j
    W = np.zeros((len(obj.data.vertices), len(names)))
    for v in obj.data.vertices:
        for e in v.groups:
            j = col.get(e.group)
            if j is not None:
                W[v.index, j] = e.weight
    return W


def write_weights(obj, names, W, max_inf=4, eps=1e-3):
    """Replace the listed groups by W, keeping the max_inf largest per vertex, normalized to 1."""
    W = W.copy()
    W[W < eps] = 0.0
    if W.shape[1] > max_inf:
        order = np.argsort(-W, axis=1)
        np.put_along_axis(W, order[:, max_inf:], 0.0, axis=1)
    s = W.sum(1, keepdims=True)
    W = np.where(s > 0, W / np.maximum(s, 1e-12), 0.0)
    all_idx = list(range(len(obj.data.vertices)))
    for j, nm in enumerate(names):
        g = obj.vertex_groups.get(nm) or obj.vertex_groups.new(name=nm)
        g.remove(all_idx)
        for i in np.nonzero(W[:, j])[0]:
            g.add([int(i)], float(W[i, j]), "REPLACE")
    return W


def _add_bone(rig, name, head, tail, parent, roll_up=(0, 0, 1)):
    eb = rig.data.edit_bones.new(name)
    eb.head = Vector(head)
    eb.tail = Vector(tail)
    eb.align_roll(Vector(roll_up))
    eb.parent = rig.data.edit_bones[parent]
    eb.use_connect = False
    eb.use_deform = True
    return eb


def _soft_bone_layout(body, hair):
    """World-space (head, tail, parent) for every soft bone, measured on the final (sculpted) geometry."""
    co = mesh_co(body)
    bodym = vg_weights(body, "body") > 0
    tip = vg_weights(body, "nippleTip") > 0
    out = {}
    for side, sx in (("L", 1), ("R", -1)):
        c = co[tip & (co[:, 0] * sx > 0)].mean(0)
        base = c + np.array([-sx * SOFT["breast_inset_x"], SOFT["breast_depth"], -0.005])
        out[f"masha:breast.{side}"] = (base, base + np.array([0, -SOFT["breast_len"], 0]), "mixamorig:Spine2")
    for side, sx in (("L", 1), ("R", -1)):
        g = bodym & (co[:, 0] * sx > 0.035) & (co[:, 0] * sx < 0.14) & (co[:, 2] > SOFT["glute_zwin"][0]) \
            & (co[:, 2] < SOFT["glute_zwin"][1])
        cg = co[g]
        apex = cg[cg[:, 1] > cg[:, 1].max() - 0.012].mean(0)   # centre of the most posterior skin cap
        head = apex + np.array([0, -SOFT["glute_depth"], 0.0])
        out[f"masha:glute.{side}"] = (head, head + np.array([0, SOFT["glute_len"], -0.01]), "mixamorig:Hips")
    # hair chain: centroids of back-hair slices from the ponytail tie down to its tip
    hc = mesh_co(hair)
    back = hc[:, 1] > SOFT["hair_back_y"]
    z_tie = SOFT["hair_tie_z"]
    z_tip = hc[back & (hc[:, 2] < z_tie + 0.01), 2].min() + 0.01
    n = SOFT["hair_bones"]
    pts = []
    for z in np.linspace(z_tie, z_tip, n + 1):
        p = hc[back & (np.abs(hc[:, 2] - z) < 0.02)].mean(0)
        p[0] = 0.0
        pts.append(p)
    pts[0] = pts[0] + np.array([0, -0.01, 0])            # root slightly inside the tie
    for k in range(n):
        parent = "mixamorig:Head" if k == 0 else f"masha:hair.{k - 1}"
        out[f"masha:hair.{k}"] = (pts[k], pts[k + 1], parent)
    return out


def _chain_param(p, pts):
    """continuous parameter of p along polyline pts (0 root .. n tip, negative before the root)"""
    best, bs = 1e9, 0.0
    for k in range(len(pts) - 1):
        a, b = pts[k], pts[k + 1]
        ab = b - a
        t = np.clip(np.dot(p - a, ab) / np.dot(ab, ab), 0, 1)
        d = np.linalg.norm(a + t * ab - p)
        if d < best:
            best, bs = d, k + t
    if bs < 1e-6:
        ab = pts[1] - pts[0]
        bs = min(0.0, np.dot(p - pts[0], ab) / np.dot(ab, ab))
    return bs


def smooth_weights_region(W, edges, co, center, radii, iters, lam=0.5):
    """Laplacian diffusion of all weight columns inside an ellipsoidal region (smooth falloff);
    rows stay normalized because every neighbour row sums to 1."""
    r = np.sqrt((((co - np.array(center)) / np.array(radii)) ** 2).sum(1))
    m = smoothstep((1.0 - r) / 0.5)[:, None]
    n = len(co)
    deg = np.zeros(n)
    np.add.at(deg, edges[:, 0], 1)
    np.add.at(deg, edges[:, 1], 1)
    deg = np.maximum(deg, 1)[:, None]
    for _ in range(iters):
        s = np.zeros_like(W)
        np.add.at(s, edges[:, 0], W[edges[:, 1]])
        np.add.at(s, edges[:, 1], W[edges[:, 0]])
        W = W + lam * m * (s / deg - W)
    return W


def fix_main_weights(body, rig):
    """Joint-region weight relaxation for torso/legs/shoulders (hands & forearm twist: Agent 5)."""
    names = bone_names(rig)
    W = read_weights(body, names)
    co = mesh_co(body)
    bodym = vg_weights(body, "body") > 0
    edges = mesh_edges(body, bodym)
    bones = {b.name: b for b in rig.data.bones}
    mw = rig.matrix_world
    for jname, (bone, radii, iters) in WEIGHT_FIX.items():
        for side in (("Left", "Right") if "{S}" in bone else ("",)):
            b = bones[bone.replace("{S}", side)]
            c = mw @ ((b.head_local + b.tail_local) * 0.5) if jname == "neck" else mw @ b.head_local
            W = smooth_weights_region(W, edges, co, c, radii, iters)
    write_weights(body, names, W)


def add_soft_bones(ctx):
    body, rig, hair = ctx["body"], ctx["rig"], ctx["hair"]
    layout = _soft_bone_layout(body, hair)
    inv = rig.matrix_world.inverted()
    common.select_only(rig)
    bpy.ops.object.mode_set(mode="EDIT")
    for name, (h, t, parent) in layout.items():
        roll = (0, 0, 1) if "hair" not in name else (0, -1, 0)
        _add_bone(rig, name, inv @ Vector(h), inv @ Vector(t), parent, roll)
    bpy.ops.object.mode_set(mode="OBJECT")
    for name, (h, t, parent) in layout.items():
        kind = name.split(":")[1].split(".")[0]
        ctx["soft_bones"][name] = {"kind": kind, "parent": parent}
        print(f"SOFTBONE {name} parent={parent} head={np.round(h, 3).tolist()} tail={np.round(t, 3).tolist()}")

    # ---- body: soft bones take a smooth share of their parent region's weight
    names = bone_names(rig)
    col = {n: i for i, n in enumerate(names)}
    W = read_weights(body, names)
    co = mesh_co(body)
    bodym = vg_weights(body, "body") > 0
    for side, sx in (("L", 1), ("R", -1)):
        h, t, _ = layout[f"masha:breast.{side}"]
        c = t + (h - t) * SOFT["breast_center"]
        r = np.sqrt((((co - c) / np.array(SOFT["breast_radii"])) ** 2).sum(1))
        f = smoothstep((1.0 - r) / SOFT["breast_soft"]) * SOFT["breast_max"] * (co[:, 0] * sx > -0.005) * bodym
        f *= smoothstep((c[1] + 0.03 - co[:, 1]) / 0.03)          # front of the ribcage only
        src = [col["mixamorig:Spine2"], col["mixamorig:Spine1"],
               col["mixamorig:LeftShoulder" if sx > 0 else "mixamorig:RightShoulder"]]
        take = f[:, None] * W[:, src]
        W[:, src] -= take
        W[:, col[f"masha:breast.{side}"]] += take.sum(1)

        h, t, _ = layout[f"masha:glute.{side}"]
        c = h + (t - h) * SOFT["glute_center"]
        r = np.sqrt((((co - c) / np.array(SOFT["glute_radii"])) ** 2).sum(1))
        f = smoothstep((1.0 - r) / SOFT["glute_soft"]) * SOFT["glute_max"] * (co[:, 0] * sx > -0.005) * bodym
        f *= smoothstep((co[:, 1] - (h[1] - 0.01)) / 0.04)         # back only
        src = [col["mixamorig:Hips"], col["mixamorig:LeftUpLeg" if sx > 0 else "mixamorig:RightUpLeg"]]
        share = np.array([1.0, SOFT["glute_from_leg"]])
        take = f[:, None] * W[:, src] * share[None, :]
        W[:, src] -= take
        W[:, col[f"masha:glute.{side}"]] += take.sum(1)
    write_weights(body, names, W)

    # ---- hair: Head on the scalp, smooth gradient down the chain
    n = SOFT["hair_bones"]
    hn = ["mixamorig:Head"] + [f"masha:hair.{k}" for k in range(n)]
    for g in list(hair.vertex_groups):
        hair.vertex_groups.remove(g)
    hc = mesh_co(hair)
    pts = [np.array(layout[f"masha:hair.{k}"][0]) for k in range(n)] + [np.array(layout[f"masha:hair.{n - 1}"][1])]
    centers = np.array([-0.25] + [k + 0.5 for k in range(n)])
    HW = np.zeros((len(hc), len(hn)))
    for i, p in enumerate(hc):
        s = _chain_param(p, pts)
        g = smoothstep((p[1] - SOFT["hair_back_y"] + 0.02) / 0.05) * smoothstep((SOFT["hair_tie_z"] + 0.03 - p[2]) / 0.04)
        s = centers[0] + g * (s - centers[0])
        if s <= centers[0]:
            HW[i, 0] = 1.0
        elif s >= centers[-1]:
            HW[i, -1] = 1.0
        else:
            k = int(np.searchsorted(centers, s)) - 1
            f = smoothstep((s - centers[k]) / (centers[k + 1] - centers[k]))
            HW[i, k] = 1 - f
            HW[i, k + 1] = f
    write_weights(hair, hn, HW)
    return layout


# ------------------------------------------------------------------ holographic suit textures
PARTS = ["torso", "head", "armL", "armR", "handL", "handR", "legL", "legR", "footL", "footR"]


def _part_of_bone(name):
    n = name.replace("mixamorig:", "")
    if name.startswith("masha:"):
        return "torso"
    if n in ("Neck", "Head") or "Eye" in n or "Jaw" in n:
        return "head"
    side = "L" if n.startswith("Left") else ("R" if n.startswith("Right") else "")
    if "Hand" in n:
        return "hand" + side
    if n.endswith("ForeArm") or n.endswith("Arm"):
        return "arm" + side
    if "Foot" in n or "Toe" in n:
        return "foot" + side
    if "UpLeg" in n or n.endswith("Leg"):
        return "leg" + side
    return "torso"


def _loop_order(pts):
    """order a closed loop of points by angle around its centroid (horizontal plane)"""
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 0] - c[0], -(pts[:, 1] - c[1]))
    return pts[np.argsort(ang)]


def _resample_loop(pts, n):
    p = np.vstack([pts, pts[:1]])
    seg = np.linalg.norm(np.diff(p, axis=0), axis=1)
    cum = np.concatenate([[0], np.cumsum(seg)])
    t = np.linspace(0, cum[-1], n, endpoint=False)
    return np.stack([np.interp(t, cum, p[:, k]) for k in range(3)], 1)


def _rasterize(body, res):
    """per-texel triangle id + barycentrics for all body (non-helper) triangles of UVMap"""
    me = body.data
    me.calc_loop_triangles()
    bodym = vg_weights(body, "body") > 0
    nt = len(me.loop_triangles)
    tl = np.zeros(nt * 3, dtype=np.int64)
    me.loop_triangles.foreach_get("loops", tl)
    tl = tl.reshape(-1, 3)
    tv = np.zeros(nt * 3, dtype=np.int64)
    me.loop_triangles.foreach_get("vertices", tv)
    tv = tv.reshape(-1, 3)
    uv = np.zeros(len(me.loops) * 2)
    me.uv_layers["UVMap"].data.foreach_get("uv", uv)
    uv = uv.reshape(-1, 2)
    keep = bodym[tv].all(1)
    tl, tv = tl[keep], tv[keep]
    tuv = uv[tl] * res - 0.5                      # pixel centre coordinates
    tid = -np.ones((res, res), dtype=np.int64)
    bary = np.zeros((res, res, 3), dtype=np.float32)
    for t in range(len(tv)):
        a, b, c = tuv[t]
        x0, y0 = np.floor(np.minimum(np.minimum(a, b), c)).astype(int)
        x1, y1 = np.ceil(np.maximum(np.maximum(a, b), c)).astype(int)
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, res - 1), min(y1, res - 1)
        if x1 < x0 or y1 < y0:
            continue
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1), np.arange(y0, y1 + 1))
        den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(den) < 1e-12:
            continue
        l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / den
        l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / den
        l2 = 1 - l0 - l1
        e = -1e-4
        inside = (l0 >= e) & (l1 >= e) & (l2 >= e)
        if not inside.any():
            continue
        yy, xx = ys[inside], xs[inside]
        tid[yy, xx] = t
        bary[yy, xx] = np.stack([l0[inside], l1[inside], l2[inside]], 1)
    return tid, bary, tv, tuv


def _dilate(arrs, mask, iters):
    """grow texel data into empty texels (UV padding) by repeated 4-neighbour copies"""
    mask = mask.copy()
    for _ in range(iters):
        grow = np.zeros_like(mask)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            src = np.roll(np.roll(mask, dy, 0), dx, 1)
            new = src & ~mask & ~grow
            if new.any():
                for a in arrs:
                    a[new] = np.roll(np.roll(a, dy, 0), dx, 1)[new]
                grow |= new
        mask |= grow
    return mask


def _seg_dist(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy + 1e-12
    t = np.clip(((px - ax) * dx + (py - ay) * dy) / L2, 0, 1)
    return np.hypot(px - ax - t * dx, py - ay - t * dy), t


def _line(d, half, aa):
    """anti-aliased line profile from a metric distance"""
    return 1.0 - smoothstep((d - half) / np.maximum(aa, 1e-6) + 0.5)


class _Limb:
    """cylindrical coordinates along a bone polyline: s (m from root), theta (0 front, +pi/2 inner side)"""

    def __init__(self, pts, mirror):
        self.pts = [np.array(p) for p in pts]
        self.mirror = mirror
        self.cum = np.concatenate([[0], np.cumsum([np.linalg.norm(self.pts[i + 1] - self.pts[i])
                                                   for i in range(len(self.pts) - 1)])])

    def coords(self, P):
        best = np.full(len(P), 1e9)
        s = np.zeros(len(P))
        th = np.zeros(len(P))
        r = np.zeros(len(P))
        fwd = np.array([0.0, -1.0, 0.0])
        for i in range(len(self.pts) - 1):
            a, b = self.pts[i], self.pts[i + 1]
            L = np.linalg.norm(b - a)
            d = (b - a) / L
            t = (P - a) @ d
            tc = np.clip(t, 0 if i > 0 else -1.0, L if i < len(self.pts) - 2 else L + 1.0)
            q = P - (a + tc[:, None] * d)
            dist = np.linalg.norm(q, axis=1)
            u = fwd - d * (fwd @ d)
            u /= np.linalg.norm(u)
            v = np.cross(d, u) if not self.mirror else np.cross(u, d)
            sel = dist < best
            best[sel] = dist[sel]
            s[sel] = self.cum[i] + tc[sel]
            th[sel] = np.arctan2(q[sel] @ v, q[sel] @ u)
            r[sel] = dist[sel]
        return s, th, r


def _trace_polylines(rng, s0, s1, theta0, jog, step, lo=-10.0, hi=10.0):
    """one circuit trace in (s, theta): straight runs with 45-degree jogs; returns vertices + pad flags"""
    pts = [(s0, theta0)]
    pads = [True]
    s, th = s0, theta0
    while s < s1:
        run = step * (0.6 + 0.8 * rng.random())
        s = min(s + run, s1)
        pts.append((s, th))
        pads.append(rng.random() < 0.25)
        if s >= s1:
            break
        if rng.random() < 0.55:
            dth = jog * (1 if rng.random() < 0.5 else -1)
            if not (lo <= th + dth <= hi):
                dth = -dth
            th = th + dth
            s = min(s + abs(dth) * 0.1, s1)      # ~45 deg in metric space for r ~ 0.1 m
            pts.append((s, th))
            pads.append(False)
    pads[-1] = True
    return np.array(pts), np.array(pads)


def make_suit_textures(ctx, res=None):
    """Procedural suit masks in the body UV layout (see module docstring for the channel layout).
    Writes masha_suit_mask_{2048,1024}.png and masha_suit_normal_{2048,1024}.png."""
    res = res or TEX["res"]
    body, rig = ctx["body"], ctx["rig"]
    out = common.ensure_dir(os.path.join(ctx["out_dir"], "textures"))
    tid, bary, tv, tuv = _rasterize(body, res)
    filled = tid >= 0
    ii = np.nonzero(filled)
    T = tid[ii]
    Bc = bary[ii].astype(np.float64)
    co = mesh_co(body)                               # rest pose, metres (rig at origin)
    P = (co[tv[T]] * Bc[:, :, None]).sum(1)
    # texel size in metres per triangle (3D edge length / UV edge length)
    e3 = np.linalg.norm(co[tv[:, 1]] - co[tv[:, 0]], axis=1) + np.linalg.norm(co[tv[:, 2]] - co[tv[:, 0]], axis=1)
    e2 = np.linalg.norm(tuv[:, 1] - tuv[:, 0], axis=1) + np.linalg.norm(tuv[:, 2] - tuv[:, 0], axis=1)
    px_m = (e3 / np.maximum(e2, 1e-6))[T]
    aa = px_m * 1.0
    # part id from interpolated bone weights
    names = bone_names(rig)
    Wb = read_weights(body, names)
    pcol = np.array([PARTS.index(_part_of_bone(n)) for n in names])
    Wp = np.zeros((len(co), len(PARTS)))
    for j in range(len(names)):
        Wp[:, pcol[j]] += Wb[:, j]
    part = np.argmax((Wp[tv[T]] * Bc[:, :, None]).sum(1), 1)

    bones = {b.name: b.head_local.copy() for b in rig.data.bones}
    tails = {b.name: b.tail_local.copy() for b in rig.data.bones}
    H = lambda n: np.array(bones["mixamorig:" + n])
    n = len(P)
    R = np.zeros(n)
    G = np.zeros(n)
    Bf = np.zeros(n)
    A = np.ones(n)
    NH = np.zeros(n)                                  # height for the normal map (grooves < 0)
    seam_w, trace_w = TEX["seam_half_mm"] * 1e-3, TEX["trace_half_mm"] * 1e-3

    def add_seam(mask, d, groove=True, strength=1.0):
        if mask.dtype == bool and len(d) == n:
            d = d[mask]
        v = _line(d, seam_w, aa[mask]) * strength
        R[mask] = np.maximum(R[mask], v)
        if groove:
            NH[mask] -= v

    # ---- landmarks
    seam_idx = NECK_SEAM_VERTS
    loop = _resample_loop(_loop_order(co[seam_idx]), 720)
    kd = kdtree.KDTree(len(loop))
    for k, p in enumerate(loop):
        kd.insert(p, k)
    kd.balance()
    near = np.array([kd.find(p) for p in P], dtype=object)
    d_col = np.array([x[2] for x in near])
    z_col = loop[np.array([x[1] for x in near]), 2]
    below_col = P[:, 2] < z_col
    tip = vg_weights(body, "nippleTip") > 0
    z_bust = co[tip, 2].mean()
    z_waist = H("Spine1")[2] + TEX["belt_z_offset"]
    z_arm = H("LeftArm")[2]

    # ---- coverage (A): suit everywhere below the collar; head 0; hands = soft "glove" density
    isT = PARTS.index
    nc = loop.mean(0)
    r_loop = np.hypot(loop[:, 0] - nc[0], loop[:, 1] - nc[1]).max()
    r_h = np.hypot(P[:, 0] - nc[0], P[:, 1] - nc[1])
    headm = ~below_col & ((r_h < r_loop + 0.012) | (P[:, 2] > loop[:, 2].max() + 0.03))
    A[headm] = 0.0
    # soft 3 mm transition just above the collar seam
    A = np.where(~below_col & (d_col < 0.006), np.maximum(A, 1 - d_col / 0.006), A)

    # ---- collar (seam + band) -------------------------------------------------------------
    add_seam(np.ones(n, bool), d_col)
    add_seam(below_col, np.abs(d_col - TEX["collar_band"]))

    # ---- torso ----------------------------------------------------------------------------
    tor = part == isT("torso")
    zt = P[:, 2]
    yc = np.interp(zt, [H("Hips")[2], H("Spine1")[2], H("Neck")[2]], [H("Hips")[1], H("Spine1")[1], H("Neck")[1]])
    th_t = np.arctan2(P[:, 0], -(P[:, 1] - yc))
    r_t = np.hypot(P[:, 0], P[:, 1] - yc)
    # belt: two lines with a shallow V at the front
    zb = z_waist - TEX["belt_v"] * np.clip(np.cos(th_t), 0, 1) ** 2
    beltm = tor | (part == isT("legL")) | (part == isT("legR"))
    add_seam(beltm, np.abs(zt - zb))
    add_seam(beltm, np.abs(zt - (zb - TEX["belt_width"])))
    belt_band = beltm & (zt < zb) & (zt > zb - TEX["belt_width"])
    NH[belt_band] += 0.35
    # side seams (armpit -> belt) and hip seams (belt -> leg side handled in legs)
    side = tor & (zt < z_arm - 0.07) & (zt > zb - TEX["belt_width"])
    add_seam(side, r_t * np.abs(np.abs(th_t) - np.pi / 2))
    # chest yoke: V from the shoulder seams to the sternum, above the bust
    front = tor & (np.cos(th_t) > 0.15) & (zt > z_bust)
    zy = z_bust + TEX["yoke_above_bust"] + TEX["yoke_v"] * (np.abs(th_t) / (np.pi / 2) - 1)
    add_seam(front, np.abs(zt - zy))
    # back yoke (scapula height) with a soft curve
    back = tor & (np.cos(th_t) < -0.15) & (zt > z_bust)
    zby = z_bust + TEX["back_yoke"] - 0.03 * (1 - np.abs(np.abs(th_t) - np.pi) / (np.pi / 2))
    add_seam(back, np.abs(zt - zby))
    # raglan shoulder panels: sphere around each shoulder joint
    for sd in ("Left", "Right"):
        c = H(sd + "Arm")
        dd = np.linalg.norm(P - c, axis=1)
        m = (tor | (part == isT("armL" if sd == "Left" else "armR"))) & (zt > z_arm - 0.09) & below_col
        add_seam(m, np.abs(dd - TEX["shoulder_r"]))

    # ---- limbs ----------------------------------------------------------------------------
    limbs = {}
    for sd, ps in (("L", "Left"), ("R", "Right")):
        limbs["arm" + sd] = _Limb([H(ps + "Arm"), H(ps + "ForeArm"), H(ps + "Hand")], sd == "R")
        limbs["leg" + sd] = _Limb([H(ps + "UpLeg"), H(ps + "Leg"), H(ps + "Foot")], sd == "R")
    lc = {}
    for key, lb in limbs.items():
        sd = key[-1]
        m = (part == isT(key)) | (part == isT(("hand" if key.startswith("arm") else "foot") + sd))
        if key.startswith("leg"):
            m |= tor & (np.abs(P[:, 0]) > 0.02) & (zt < z_waist) & (np.sign(P[:, 0]) == (1 if sd == "L" else -1))
        s, th, r = lb.coords(P[m])
        lc[key] = (m, s, th, r)
        L = lb.cum
        if key.startswith("arm"):
            s_cuff = L[2] - TEX["cuff_from_wrist"]
            idx = np.nonzero(m)[0]
            add_seam(m, np.abs(s - s_cuff))
            add_seam(m, np.abs(s - (s_cuff - TEX["cuff_width"])))
            beyond = idx[s > s_cuff + 0.002]
            A[beyond] = np.minimum(A[beyond], TEX["glove_alpha"])
            # inner-arm seam from armpit to cuff
            sel = (s > 0.07) & (s < s_cuff - TEX["cuff_width"])
            add_seam(idx[sel], (r * np.abs(th - np.pi / 2))[sel])
        else:
            idx = np.nonzero(m)[0]
            s_boot = L[1] + TEX["boot_frac"] * (L[2] - L[1])
            sb = s_boot + TEX["boot_v"] * np.cos(th)
            add_seam(m, np.abs(s - sb))
            add_seam(m, np.abs(s - (sb - TEX["boot_band"])))
            # outer leg seam from belt to boot line
            sel = (s > 0.0) & (s < s_boot - TEX["boot_band"] - 0.01)
            add_seam(idx[sel], (r * np.abs(th + np.pi / 2))[sel])
            # knee arc (front)
            knee = np.array(bones["mixamorig:" + ("Left" if key == "legL" else "Right") + "Leg"])
            kfront = knee + np.array([0, -0.045, 0.01])
            dk = np.linalg.norm(P[idx] - kfront, axis=1)
            sel = np.cos(th) > 0.1
            add_seam(idx[sel], np.abs(dk - TEX["knee_r"])[sel], strength=0.8)
            # boot: sole line + everything below the boot line reads as the boot (slightly raised)
            boot = idx[s > sb + 0.002]
            NH[boot] += 0.25
            sole = (part == isT("foot" + sd))
            add_seam(sole, np.abs(zt - TEX["sole_z"]))

    # ---- circuit traces (G) + flow phase (B) -----------------------------------------------
    rng = np.random.default_rng(TEX["seed"])
    Lmax = TEX["flow_len"]

    def draw(mask_idx, s_arr, a_arr, r_arr, poly, pads, flow0):
        """rasterize one polyline (in s, theta) on the texels mask_idx; flow = flow0 + arc length"""
        acc_len = 0.0
        best = np.full(len(mask_idx), 1e9)
        flow = np.zeros(len(mask_idx))
        for k in range(len(poly) - 1):
            (s0, t0), (s1, t1) = poly[k], poly[k + 1]
            rr = r_arr
            d, t = _seg_dist(s_arr, a_arr * rr, s0, t0 * rr, s1, t1 * rr)
            seglen = np.hypot(s1 - s0, (t1 - t0) * 0.1)
            sel = d < best
            best[sel] = d[sel]
            flow[sel] = acc_len + t[sel] * seglen
            acc_len += seglen
        v = _line(best, trace_w, aa[mask_idx])
        for (ps, pt), isp in zip(poly, pads):
            if not isp:
                continue
            dp = np.hypot(s_arr - ps, (a_arr - pt) * r_arr)
            ring = _line(np.abs(dp - TEX["pad_r_mm"] * 1e-3), trace_w * 0.8, aa[mask_idx])
            dot = _line(dp, TEX["pad_r_mm"] * 0.45e-3, aa[mask_idx])
            v = np.maximum(v, np.maximum(ring, dot))
            best = np.minimum(best, dp)
        on = v > 0.01
        G[mask_idx[on]] = np.maximum(G[mask_idx[on]], v[on])
        NH[mask_idx[on]] += 0.25 * v[on]
        near_ = best < TEX["pad_r_mm"] * 1e-3 + 3 * trace_w
        Bf[mask_idx[near_]] = np.clip((flow0 + flow[near_]) / Lmax, 0, 1)
        return acc_len

    # torso: parameter s = distance below the collar front along z
    tidx = np.nonzero(tor & below_col)[0]
    z_top = loop[:, 2].max()
    s_t = z_top - zt[tidx]
    s_bot = z_top - (z_waist + 0.012)
    for (th0, jog, step, lo, hi) in TEX["torso_traces"]:
        # start just under the collar band at this angle
        zc = np.interp(abs(th0), [0, np.pi], [loop[:, 2].min(), loop[:, 2].max()])
        s0 = z_top - (zc - TEX["collar_band"] - 0.012)
        if th0 > 1.2 and th0 < 1.9:                      # side traces start under the armpit
            s0 = z_top - (z_arm - 0.09)
        for sgn in (1, -1):
            poly, pads = _trace_polylines(rng, s0, s_bot, th0, jog, step, lo, hi)
            poly[:, 1] *= sgn
            draw(tidx, s_t, th_t[tidx], np.maximum(r_t[tidx], 0.05), poly, pads, 0.0)
    # limbs
    for key, (m, s, th, r) in lc.items():
        idx = np.nonzero(m)[0]
        L = limbs[key].cum
        if key.startswith("arm"):
            s_from, s_to, f0 = TEX["shoulder_r"] + 0.01, L[2] - TEX["cuff_from_wrist"] - TEX["cuff_width"] - 0.004, 0.18
            specs = TEX["arm_traces"]
        else:
            hipz = limbs[key].pts[0][2]
            s_from = -(z_waist - TEX["belt_width"] - 0.012 - hipz)
            s_to = L[1] + TEX["boot_frac"] * (L[2] - L[1]) - TEX["boot_band"] - 0.03
            f0 = (z_top - z_waist) + TEX["belt_width"]
            specs = TEX["leg_traces"]
        for th0, jog, step, lo, hi in specs:
            poly, pads = _trace_polylines(rng, s_from, s_to, th0, jog, step, lo, hi)
            draw(idx, s, th, np.maximum(r, 0.03), poly, pads, f0)
        # flow phase everywhere on the limb (for full-suit scan waves): continuous along the limb
        fill = Bf[idx] == 0
        Bf[idx[fill]] = np.clip((f0 + np.maximum(s[fill], 0)) / Lmax, 0, 1)
    # torso flow elsewhere: distance from the collar downward
    fill = tor & (Bf == 0)
    Bf[fill] = np.clip((z_top - zt[fill]) / Lmax, 0, 1)
    G[A < 0.5] *= 0.0
    R[A == 0] *= 0.0

    # ---- assemble images ------------------------------------------------------------------
    img = np.zeros((res, res, 4), dtype=np.float32)
    img[ii[0], ii[1], 0] = np.clip(R, 0, 1)
    img[ii[0], ii[1], 1] = np.clip(G, 0, 1)
    img[ii[0], ii[1], 2] = Bf
    img[ii[0], ii[1], 3] = A
    hmap = np.zeros((res, res), dtype=np.float32)
    hmap[ii] = NH
    _dilate([img, hmap], filled, TEX["pad_px"])
    # tangent-space normal map from the height field (OpenGL/glTF convention, +Y = +V)
    k = TEX["normal_strength"]
    hs = hmap
    dx = (np.roll(hs, -1, 1) - np.roll(hs, 1, 1)) * 0.5
    dy = (np.roll(hs, -1, 0) - np.roll(hs, 1, 0)) * 0.5
    nx, ny, nz = -dx * k, -dy * k, np.ones_like(hs)
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nimg = np.stack([nx / ln * 0.5 + 0.5, ny / ln * 0.5 + 0.5, nz / ln * 0.5 + 0.5, np.ones_like(hs)], 2)
    paths = {}
    for label, arr in (("mask", img), ("normal", nimg)):
        for r_ in (res, res // 2):
            a = arr if r_ == res else arr.reshape(r_, 2, r_, 2, 4).mean((1, 3))
            name = f"masha_suit_{label}_{r_}.png"
            im = bpy.data.images.new(name, r_, r_, alpha=True, float_buffer=False)
            im.colorspace_settings.name = "Non-Color"
            im.alpha_mode = "STRAIGHT"
            im.pixels.foreach_set(a.astype(np.float32).ravel())
            im.filepath_raw = os.path.join(out, name)
            im.file_format = "PNG"
            im.save()
            paths[f"{label}_{r_}"] = im.filepath_raw
            bpy.data.images.remove(im)
    ctx["suit_textures"] = paths
    print("SUIT_TEX", paths)
    return paths


def limit_all_weights(ctx, max_inf=4):
    """final pass: every skinned mesh gets <= max_inf bone influences per vertex, normalized (Filament)"""
    names = bone_names(ctx["rig"])
    for key in ("body", "eyes", "brows", "lashes", "teeth", "tongue", "hair"):
        obj = ctx.get(key)
        if obj is None:
            continue
        present = [n for n in names if obj.vertex_groups.get(n) is not None]
        W = read_weights(obj, present)
        over = int(((W > 1e-3).sum(1) > max_inf).sum())
        write_weights(obj, present, W, max_inf=max_inf)
        print(f"WEIGHTS {obj.name}: {over} verts had >{max_inf} influences -> limited+normalized")


# ------------------------------------------------------------------ lite-tier LOD
def make_lod(ctx, ratio=0.5, obj=None, protect_rings=2):
    """Decimate Masha_Body for the lite tier. Runs in the export stage on a body WITHOUT helper geometry
    (and, after Agent 4's split, without the head). Head/face (anything weighted to Neck/Head), hands
    (Hand + fingers) and every open boundary (e.g. the neck seam shared with Masha_Head) plus
    `protect_rings` rings around them are left untouched, so seams stay crack-free; torso, limbs and
    boots are collapse-decimated to `ratio` of their faces. UVs and vertex groups are interpolated by
    Blender's collapse, then weights are re-limited to 4 and renormalized. Returns the object."""
    import bmesh
    obj = obj or ctx["body"]
    rig = ctx.get("rig")
    names = bone_names(rig) if rig else [g.name for g in obj.vertex_groups]
    W = read_weights(obj, names)
    prot_cols = [j for j, n in enumerate(names) if any(k in n for k in ("Neck", "Head", "Hand", "Eye", "Jaw"))]
    protect = W[:, prot_cols].sum(1) > 0.02 if prot_cols else np.zeros(len(obj.data.vertices), bool)
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bm.verts.ensure_lookup_table()
    for v in bm.verts:
        if v.is_boundary:
            protect[v.index] = True
    for _ in range(protect_rings):
        grow = protect.copy()
        for e in bm.edges:
            a, b = e.verts[0].index, e.verts[1].index
            if protect[a] or protect[b]:
                grow[a] = grow[b] = True
        protect = grow
    bm.free()
    common.select_only(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_mode(type="FACE")
    bpy.ops.mesh.select_all(action="DESELECT")
    bpy.ops.object.mode_set(mode="OBJECT")
    for p in obj.data.polygons:
        p.select = not any(protect[i] for i in p.vertices)
    before = common.tri_count(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.decimate(ratio=ratio, use_symmetry=True, symmetry_axis="X")
    bpy.ops.mesh.select_all(action="DESELECT")
    bpy.ops.object.mode_set(mode="OBJECT")
    present = [n for n in names if obj.vertex_groups.get(n) is not None]
    write_weights(obj, present, read_weights(obj, present))
    print(f"LOD {obj.name}: tris {before} -> {common.tri_count(obj)} (ratio {ratio}, protected verts kept)")
    return obj


def build(ctx):
    HumanService = common.mpfb("HumanService")
    TargetService = common.mpfb("TargetService")
    AssetService = common.mpfb("AssetService")
    body = HumanService.create_human(mask_helpers=True, detailed_helpers=True, extra_vertex_groups=True,
                                     feet_on_ground=True, scale=0.1, macro_detail_dict=MACRO)
    _load_details(body, DETAIL_TARGETS)
    TargetService.bake_targets(body)
    _ground(body)
    _suit_sculpt(body)
    rig = HumanService.add_builtin_rig(body, "mixamo")
    new = {}
    before = set(bpy.data.objects)
    for sub, fname, atype, key, obj_name in ASSETS:
        path = AssetService.find_asset_absolute_path(fname, asset_subdir=sub)
        HumanService.add_mhclo_asset(path, body, asset_type=atype, subdiv_levels=0, material_type="GAMEENGINE")
        added = [o for o in bpy.data.objects if o not in before and o.type == "MESH"]
        before = set(bpy.data.objects)
        obj = added[0]
        obj.name = obj_name
        obj.data.name = obj_name
        new[key] = obj
    body.name = body.data.name = "Masha_Body"
    rig.name = rig.data.name = "Masha_Rig"
    ctx.update(body=body, rig=rig, **new)
    fix_main_weights(body, rig)
    ctx["soft_layout"] = add_soft_bones(ctx)
    limit_all_weights(ctx)
    make_suit_textures(ctx)


SHOTS = [
    ("front", (0, -4.5, 0.9), (0, 0, 0.87), 75),
    ("profile", (4.5, 0, 0.9), (0, 0, 0.87), 75),
    ("threeq", (3.2, -3.2, 0.95), (0, 0, 0.87), 75),
    ("back", (0, 4.5, 0.9), (0, 0, 0.87), 75),
    ("torso", (0.6, -1.9, 1.15), (0, 0, 1.05), 70),
]
DEV_SHOTS = [
    ("chest", (0.0, -1.1, 1.3), (0, 0, 1.22), 70),
    ("chest_side", (0.9, -0.7, 1.3), (0, 0, 1.22), 70),
    ("crotch", (0.0, -1.1, 0.95), (0, 0, 0.86), 70),
    ("crotch_low", (0.3, -1.0, 0.55), (0, 0, 0.85), 60),
    ("crotch_back", (0.2, 1.1, 0.75), (0, 0, 0.88), 60),
    ("feet", (0.5, -1.0, 0.35), (0, -0.05, 0.08), 60),
]


def render_shots(out_dir, prefix, shots, engine="BLENDER_WORKBENCH", res=(700, 1000), color_type="MATERIAL"):
    """Own QA renderer (clean shape reading). Workbench studio light or Eevee via common.qa_renders."""
    if engine != "BLENDER_WORKBENCH":
        return common.qa_renders(out_dir, prefix, shots, engine=engine, res=res)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    sh = scene.display.shading
    sh.light = "STUDIO"
    sh.color_type = color_type
    sh.show_cavity = False
    sh.show_shadows = False
    sh.show_specular_highlight = True
    scene.display.render_aa = "8"
    if scene.world is None:
        scene.world = bpy.data.worlds.new("qa")
    scene.world.color = (0.05, 0.05, 0.06)
    scene.render.resolution_x, scene.render.resolution_y = res
    cam = bpy.data.objects.get("qa_cam_wb")
    if cam is None:
        cam = bpy.data.objects.new("qa_cam_wb", bpy.data.cameras.new("qa_cam_wb"))
        scene.collection.objects.link(cam)
    scene.camera = cam
    common.ensure_dir(out_dir)
    paths = []
    for name, loc, target, lens in shots:
        common._look(cam, loc, target)
        cam.data.lens = lens
        cam.data.clip_start = 0.02
        scene.render.filepath = os.path.join(out_dir, f"{prefix}_{name}.png")
        bpy.ops.render.render(write_still=True)
        paths.append(scene.render.filepath)
    return paths


def rotate_world(rig, bone, axis, deg):
    """rotate a pose bone about a world axis through its head (applied on top of its current pose)"""
    from mathutils import Matrix
    pb = rig.pose.bones[bone]
    bpy.context.view_layer.update()
    head = pb.head.copy()
    if isinstance(axis, str):            # ("flex", toward): hinge that swings the bone toward a direction
        d = (pb.tail - pb.head).normalized()
        axis = d.cross(Vector(FLEX_DIR[axis])).normalized()
    R = Matrix.Rotation(math.radians(deg), 4, Vector(axis))
    pb.matrix = Matrix.Translation(head) @ R @ Matrix.Translation(-head) @ pb.matrix
    bpy.context.view_layer.update()


def reset_pose(rig):
    for pb in rig.pose.bones:
        pb.location = (0, 0, 0)
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.rotation_euler = (0, 0, 0)
        pb.scale = (1, 1, 1)
    bpy.context.view_layer.update()


# (name, [(bone, world_axis, deg)...], shots)
FB = [("f", (0, -4.2, 0.95), (0, 0, 0.9), 70), ("q", (3.0, -3.0, 1.0), (0, 0, 0.9), 70)]
TORSO = [("f", (0, -2.0, 1.2), (0, 0, 1.15), 60), ("q", (1.5, -1.4, 1.25), (0, 0, 1.15), 60),
         ("b", (1.0, 1.8, 1.2), (0, 0, 1.1), 60)]
FLEX_DIR = {"fwd": (0, -1, 0), "back": (0, 1, 0), "up": (0, 0, 1)}
POSE_TESTS = [
    ("arms_up", [("mixamorig:LeftShoulder", (0, 1, 0), -22), ("mixamorig:RightShoulder", (0, 1, 0), 22),
                 ("mixamorig:LeftArm", (0, 1, 0), -85), ("mixamorig:RightArm", (0, 1, 0), 85)], TORSO),
    ("arms_fwd", [("mixamorig:LeftArm", (0, 1, 0), -45), ("mixamorig:LeftArm", (0, 0, 1), -80),
                  ("mixamorig:RightArm", (0, 1, 0), 45), ("mixamorig:RightArm", (0, 0, 1), 80)], TORSO),
    ("elbows90", [("mixamorig:LeftForeArm", "fwd", 90), ("mixamorig:RightForeArm", "fwd", 90)], TORSO),
    ("knees90", [("mixamorig:LeftUpLeg", "fwd", 90), ("mixamorig:LeftLeg", "back", 90),
                 ("mixamorig:RightUpLeg", "fwd", 30), ("mixamorig:RightLeg", "back", 60)],
     [("s", (2.4, -1.2, 0.8), (0, -0.1, 0.75), 60), ("f", (0.4, -2.4, 0.8), (0, 0, 0.75), 60),
      ("b", (0.8, 2.2, 0.85), (0, 0, 0.8), 60), ("hip", (0.9, -1.0, 0.95), (0.05, -0.1, 0.85), 60)]),
    ("twist30", [("mixamorig:Spine", (0, 0, 1), 10), ("mixamorig:Spine1", (0, 0, 1), 10),
                 ("mixamorig:Spine2", (0, 0, 1), 10)], TORSO),
    ("head_turn", [("mixamorig:Neck", (0, 0, 1), 20), ("mixamorig:Head", (0, 0, 1), 30),
                   ("mixamorig:Head", (1, 0, 0), 12)],
     [("f", (0, -1.2, 1.5), (0, 0, 1.45), 60), ("q", (0.9, -0.9, 1.5), (0, 0, 1.45), 60)]),
]


def eval_co(obj):
    """evaluated (deformed) vertex positions with vertex count preserved (MASK modifiers bypassed)"""
    masks = [m for m in obj.modifiers if m.type == "MASK" and m.show_viewport]
    for m in masks:
        m.show_viewport = False
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    ev = obj.evaluated_get(dg)
    m = ev.to_mesh()
    a = np.zeros(len(m.vertices) * 3)
    m.vertices.foreach_get("co", a)
    ev.to_mesh_clear()
    for m in masks:
        m.show_viewport = True
    bpy.context.view_layer.update()
    return a.reshape(-1, 3)


def _heat(obj, val):
    """store a 0..1 scalar as a point color attribute (blue->red) for Workbench VERTEX shading"""
    name = "qa_heat"
    if name in obj.data.color_attributes:
        obj.data.color_attributes.remove(obj.data.color_attributes[name])
    att = obj.data.color_attributes.new(name, "FLOAT_COLOR", "POINT")
    v = np.clip(val, 0, 1)
    col = np.stack([v, 0.15 + 0.5 * v * (1 - v), 1 - v, np.ones_like(v)], 1) * np.array([1, 1, 1, 1])
    col[:, :3] = 0.15 + 0.8 * col[:, :3]
    att.data.foreach_set("color", col.reshape(-1).astype(np.float32))
    obj.data.color_attributes.active_color = att


def soft_tests(ctx, rot=10.0, loc=0.01):
    """Pose each soft bone (rot 10 deg / translate 1 cm), render geometry + displacement heat map,
    and print locality/smoothness numbers: deformation must be smooth and local."""
    from mathutils import Matrix
    rig, body, hair = ctx["rig"], ctx["body"], ctx["hair"]
    tests = [
        ("breast_rot", [("masha:breast.L", (1, 0, 0), rot), ("masha:breast.R", (1, 0, 0), rot)], "rot", body),
        ("breast_loc", [("masha:breast.L", (0, 0, -loc)), ("masha:breast.R", (0, 0, -loc))], "loc", body),
        ("glute_rot", [("masha:glute.L", (1, 0, 0), rot), ("masha:glute.R", (1, 0, 0), rot)], "rot", body),
        ("glute_loc", [("masha:glute.L", (0, 0, loc)), ("masha:glute.R", (0, 0, loc))], "loc", body),
        ("hair_swing", [(f"masha:hair.{k}", (0, 1, 0), rot * 2) for k in range(SOFT["hair_bones"])], "rot", hair),
    ]
    shots = {"breast": [("q", (0.55, -0.75, 1.3), (0, 0, 1.24), 60), ("s", (0.85, -0.15, 1.26), (0, -0.07, 1.24), 60)],
             "glute": [("b", (0.25, 1.0, 0.92), (0, 0, 0.88), 60), ("s", (0.95, 0.35, 0.9), (0, 0.05, 0.88), 60)],
             "hair": [("b", (0, 0.95, 1.5), (0, 0.05, 1.5), 60), ("s", (0.9, 0.3, 1.5), (0, 0.05, 1.5), 60)]}
    reset_pose(rig)
    rest = {body.name: eval_co(body), hair.name: eval_co(hair)}
    edges = mesh_edges(body, vg_weights(body, "body") > 0)
    stats = {}
    for name, ops, mode, obj in tests:
        reset_pose(rig)
        for op in ops:
            if mode == "rot":
                rotate_world(rig, *op)
            else:
                pb = rig.pose.bones[op[0]]
                pb.matrix = Matrix.Translation(Vector(op[1])) @ pb.matrix
                bpy.context.view_layer.update()
        d = np.linalg.norm(eval_co(obj) - rest[obj.name], axis=1)
        grp = name.split("_")[0]
        render_shots(ctx["qa_dir"], "soft_" + name, shots[grp], res=(600, 700))
        _heat(obj, d / max(d.max(), 1e-9))
        render_shots(ctx["qa_dir"], "soft_heat_" + name, shots[grp], res=(600, 700), color_type="VERTEX")
        if obj is body:
            e = edges
            el = np.linalg.norm(rest[obj.name][e[:, 0]] - rest[obj.name][e[:, 1]], axis=1)
            strain = np.abs(d[e[:, 0]] - d[e[:, 1]]) / np.maximum(el, 1e-5)
            stats[name] = dict(max_disp_mm=round(d.max() * 1000, 2), moved_verts=int((d > 1e-4).sum()),
                               max_grad=round(float(strain.max()), 3), p99_grad=round(float(np.percentile(strain[strain > 0], 99)), 3))
        else:
            stats[name] = dict(max_disp_mm=round(d.max() * 1000, 2), moved_verts=int((d > 1e-4).sum()))
    reset_pose(rig)
    for g in ("breast", "glute", "hair"):
        render_shots(ctx["qa_dir"], "soft_rest_" + g, shots[g], res=(600, 700))
    print("SOFT_STATS", stats)
    return stats


def pose_tests(ctx, only=None):
    rig = ctx["rig"]
    for name, ops, shots in POSE_TESTS:
        if only and name not in only:
            continue
        reset_pose(rig)
        for op in ops:
            rotate_world(rig, *op)
        render_shots(ctx["qa_dir"], "pose_" + name, shots, res=(600, 750))
    reset_pose(rig)


def suit_preview(ctx, time=0.3):
    """QA only: emissive hologram-ish material driven by the mask (not kept in the build)."""
    body = ctx["body"]
    tex = ctx.get("suit_textures") or {}
    mpath = tex.get("mask_2048") or os.path.join(ctx["out_dir"], "textures", "masha_suit_mask_2048.png")
    npath = tex.get("normal_2048") or os.path.join(ctx["out_dir"], "textures", "masha_suit_normal_2048.png")
    mat = bpy.data.materials.new("QA_SuitPreview")
    mat.use_nodes = True
    nt = mat.node_tree
    for nd in list(nt.nodes):
        nt.nodes.remove(nd)
    N = nt.nodes.new
    L = nt.links.new
    out = N("ShaderNodeOutputMaterial")
    img = N("ShaderNodeTexImage")
    img.image = bpy.data.images.load(mpath, check_existing=True)
    img.image.colorspace_settings.name = "Non-Color"
    img.image.alpha_mode = "CHANNEL_PACKED"
    nimg = N("ShaderNodeTexImage")
    nimg.image = bpy.data.images.load(npath, check_existing=True)
    nimg.image.colorspace_settings.name = "Non-Color"
    nmap = N("ShaderNodeNormalMap")
    L(nimg.outputs["Color"], nmap.inputs["Color"])
    sep = N("ShaderNodeSeparateColor")
    L(img.outputs["Color"], sep.inputs["Color"])
    # pulse = smooth band travelling along B
    mth = []

    def math(op, a, b=None, v=None):
        m = N("ShaderNodeMath")
        m.operation = op
        for k, x in enumerate((a, b)):
            if x is None:
                continue
            if isinstance(x, (int, float)):
                m.inputs[k].default_value = x
            else:
                L(x, m.inputs[k])
        return m.outputs[0]
    ph = math("FRACT", math("SUBTRACT", math("MULTIPLY", sep.outputs["Blue"], 6.0), time))
    pulse = math("POWER", math("SUBTRACT", 1.0, math("ABSOLUTE", math("SUBTRACT", math("MULTIPLY", ph, 2.0), 1.0))), 6.0)
    trace = math("MULTIPLY", sep.outputs["Green"], math("ADD", 0.45, math("MULTIPLY", pulse, 2.5)))
    seam = math("MULTIPLY", sep.outputs["Red"], 1.6)
    lw = N("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.35
    L(nmap.outputs["Normal"], lw.inputs["Normal"])
    rim = math("MULTIPLY", lw.outputs["Facing"], 0.9)
    base = math("MULTIPLY", img.outputs["Alpha"], 0.10)
    # colors
    def rgb(c):
        n_ = N("ShaderNodeRGB")
        n_.outputs[0].default_value = (*c, 1)
        return n_.outputs[0]

    def mixadd(col, fac, acc):
        m = N("ShaderNodeMix")
        m.data_type = "RGBA"
        m.blend_type = "ADD"
        m.inputs["Factor"].default_value = 1.0
        mul = N("ShaderNodeMix")
        mul.data_type = "RGBA"
        mul.blend_type = "MULTIPLY"
        mul.inputs["Factor"].default_value = 1.0
        L(col, mul.inputs[6])
        cf = N("ShaderNodeCombineColor")
        for k in ("Red", "Green", "Blue"):
            L(fac, cf.inputs[k])
        L(cf.outputs[0], mul.inputs[7])
        L(acc, m.inputs[6])
        L(mul.outputs[2], m.inputs[7])
        return m.outputs[2]
    acc = rgb((0, 0, 0))
    acc = mixadd(rgb((0.10, 0.55, 0.85)), base, acc)
    acc = mixadd(rgb((0.25, 0.8, 1.0)), rim, acc)
    acc = mixadd(rgb((0.55, 0.95, 1.0)), seam, acc)
    acc = mixadd(rgb((1.0, 0.35, 0.95)), trace, acc)
    skin = math("MULTIPLY", math("SUBTRACT", 1.0, img.outputs["Alpha"]), 0.18)
    acc = mixadd(rgb((0.9, 0.75, 0.95)), skin, acc)
    em = N("ShaderNodeEmission")
    L(acc, em.inputs["Color"])
    em.inputs["Strength"].default_value = 1.6
    L(em.outputs[0], out.inputs["Surface"])
    old = list(body.data.materials)
    body.data.materials.clear()
    body.data.materials.append(mat)
    scene = bpy.context.scene
    scene.use_nodes = True
    ctree = scene.node_tree
    for nd in list(ctree.nodes):
        ctree.nodes.remove(nd)
    rl = ctree.nodes.new("CompositorNodeRLayers")
    gl = ctree.nodes.new("CompositorNodeGlare")
    gl.glare_type = "FOG_GLOW"
    gl.threshold = 0.6
    gl.size = 7
    co_ = ctree.nodes.new("CompositorNodeComposite")
    ctree.links.new(rl.outputs["Image"], gl.inputs["Image"])
    ctree.links.new(gl.outputs["Image"], co_.inputs["Image"])
    scene.view_settings.view_transform = "Standard"
    shots = [("front", (0, -4.3, 0.95), (0, 0, 0.88), 75), ("back", (0, 4.3, 0.95), (0, 0, 0.88), 75),
             ("threeq", (3.0, -3.0, 1.0), (0, 0, 0.88), 75), ("side", (4.3, 0, 0.95), (0, 0, 0.88), 75),
             ("torso", (0.5, -1.5, 1.25), (0, 0, 1.15), 60), ("torso_back", (-0.5, 1.5, 1.2), (0, 0, 1.1), 60),
             ("legs", (0.6, -1.9, 0.5), (0, 0, 0.45), 55)]
    paths = common.qa_renders(ctx["qa_dir"], "suit", shots, res=(800, 1100))
    # restore
    body.data.materials.clear()
    for m in old:
        body.data.materials.append(m)
    scene.use_nodes = False
    return paths


def qa(ctx):
    which = os.environ.get("MASHA_QA", "wb,soft,pose,suit").split(",")
    if "wb" in which:
        render_shots(ctx["qa_dir"], "wb", SHOTS + DEV_SHOTS)
    if "soft" in which:
        soft_tests(ctx)
    if "pose" in which:
        pose_tests(ctx)
    if "suit" in which:
        suit_preview(ctx)
