"""Stage 4 - shoulders (owner: Agent 7). Shoulder / armpit skin weights for extreme arm poses.

    blender -b <masha_v2_hands.blend> -P tools/masha_v2/shoulders.py -- --out <dir> [--qa 1]
        -> <dir>/masha_v2_shoulders.blend   (tunables can be overridden: --sh_smooth 60 --sh_bend 15 ...)
    or through build.py: --stages body,face,hands,shoulders,export

Problem (masha_v2_hands.blend, Mixamo "Waving" arms overhead, runtime twist rule applied): axilla edges
stretched up to 5.7-6.0x their rest length (p95 of the axilla 4.2x), the whole 120-130 deg swing from
the A-pose rest was resolved in 2-3 edge rings ("bat wing" web from the chest/back to the upper arm,
flared neck base), the MPFB clavicle weight ended with a hard step on the sternum / spine midline, and
the lower neck carried upper-arm weight (mixamorig:*Arm on Masha_Head up to z=1.555, renamed to
upperarm_twist on the body side only by the hands stage: seam mismatch L1 = 0.17).

What this stage does (weights only: no new bones, no shape keys, topology/vertex order untouched):
  1. neck_fix: upper-arm weight on the neck (Masha_Head + body seam) moves to the clavicle, then every
     body seam vertex copies the weights of its coincident Masha_Head vertex (seam mismatch -> 0).
  2. Region: visible skin ('body' group) within REGION_R of the clavicle->arm line and t < REGION_T_MAX
     along the upper arm; the neck seam, Head, forearm/elbow, glute and hair weights stay frozen.
  3. Candidates per vertex, each a fixed-proportion bone group (the optimizer only moves weight
     BETWEEN groups):  TORSO = Spine/Spine1/Spine2/Neck in the input proportions,  {L,R}Shoulder,
     ARM.{L,R} = Arm/upperarm_twist split with the hands-stage ramp (upperarm_twist share
     1 - smoothstep(0.2, 0.75, t)) so the runtime twist rule behaves as before. Breast/glute/other
     weights are kept. A side's Shoulder/ARM weight never crosses the midline (MIDLINE).
  4. Projected Adam (deterministic, left/right mirror-symmetric) on the linear-blend-skinned mesh over
     the procedural training poses POSES (arms down, hand-on-hip-like, chest gesture, T, twisted T,
     forward, across the chest low/high, 135/170 deg overhead with a scapulo-humeral clavicle rhythm,
     170 deg without clavicle, two-hand overhead wave, one arm up, one arm across):
        sum_pose a_p [ sum_edge l0 (l/l0 - 1)^2                      skin strain
                       + BEND * sum_v (|L(x)| - |L(x0)|)^2 / h^2      creases / bumps (umbrella Laplacian)
                       + VOL * (V_upperarm / V0 - 1)^2 ]              deltoid / upper-arm volume
        + SMOOTH * sum_edge |w_i - w_j|^2                            heat-diffusion smooth weights
        + (PRIOR + EDGE_PRIOR * border + MID_PRIOR * near-midline) |w - w_input|^2
  5. <= 4 influences (Filament) with a re-fit on the kept support, normalized.

Measured on 23 poses (procedural + Mixamo Waving/Arm Stretching/talking/pointing/shrug/neck clips, the
Mixamo ones never used for training): mean max edge stretch 3.08 -> 2.01, Waving axilla max 5.7 -> 3.0,
axilla p95 4.2 -> 2.7, curvature p95 0.29 -> 0.25, upper-arm volume within 0.9-1.1, arms-down /
hand-on-hip / gestures unchanged or better.

Tunables: REGION_R, REGION_T_MAX, POSES (weights a_p), SMOOTH, BEND, VOL, PRIOR, EDGE_PRIOR/BORDER,
MID_PRIOR/MID_FADE/MIDLINE, ITERS, LR, CLAV_RHYTHM.
Runtime: nothing new. Mixamo clips already rotate mixamorig:*Shoulder (~40 deg clavicle elevation at
arms overhead in "Waving"); a 50 % swing helper bone (masha:shoulder_mid) was prototyped and gave no
measurable gain, so no runtime driver is needed.
"""
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector, kdtree

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import common  # noqa: E402

M = "mixamorig:"
SIDES = (("L", "Left", 1.0), ("R", "Right", -1.0))
MAX_INF = 4

# ----------------------------------------------------------------------------- tunables
REGION_R = 0.24            # m, region radius around the clavicle->arm joint line
REGION_T_MAX = 0.62        # along the upper arm (0 = shoulder joint, 1 = elbow)
SMOOTH = 60.0              # weight smoothness (graph Laplacian of the weights = heat-diffusion prior)
VOL = 30.0                 # upper-arm / deltoid volume preservation (fan volume about the arm head)
BEND = 15.0                # bending: change of the umbrella-Laplacian magnitude (creases, bumps)
PRIOR = 0.02               # pull towards the input weights
ITERS = (900, 300)         # Adam iterations: free fit, re-fit after the 4-influence limit
LR = 0.01
ARM_RAMP = (0.2, 0.75)     # hands.weights_twist ramp (Arm vs upperarm_twist split along the arm)
TWIST_UPPERARM = -0.5      # hands.TWIST_FACTOR_UPPERARM (runtime rule)
CLAV_RHYTHM = (60.0, 0.3)  # clavicle elevation = 0.3 * (arm elevation - 60 deg), as a scapulo-humeral rhythm
# training poses: name -> (arm elevation from straight down, elevation-plane angle forward (+) / back (-),
#                          clavicle extra elevation (None = rhythm), elbow flexion, arm twist, weight)
POSES = {
    "down":        (8, 5, 0, 0, 0, 2.0),
    "hiplike":     (35, -30, 0, 95, 0, 2.0),
    "chest":       (45, 50, 0, 80, 0, 2.0),
    "tpose":       (90, 0, None, 0, 0, 1.0),
    "tpose_tw":    (90, 0, None, 0, 45, 0.5),
    "forward":     (90, 85, 5, 0, 0, 1.0),
    "across":      ((-0.45, -0.88, -0.15), 15, 3, 110, 0, 1.0),
    "across_high": ((-0.60, -0.75, 0.10), 15, 8, 30, 0, 1.0),
    "up135":       (135, 30, None, 0, 0, 1.0),
    "up170":       (170, 15, None, 0, 0, 1.5),
    "up170_noclav": (170, 15, 0, 0, 0, 0.5),
    "wave2":       (150, 20, None, 55, 0, 1.5),
    "one_up":      (170, 15, None, 0, 0, 1.0, "L"),      # asymmetric: left arm up, right arm at rest
    "one_across":  ((-0.60, -0.75, 0.10), 15, 8, 30, 0, 0.5, "L"),
}
EDGE_PRIOR = 20.0          # prior strength at the region border (smooth fade from BORDER * REGION_R)
BORDER = 0.6
MID_PRIOR = 20.0           # prior strength for a side's Shoulder/Arm weight near the midline ...
MID_FADE = 0.06            # ... fading out at |x| = MID_FADE
MIDLINE = 0.015            # m, a side's Shoulder/Arm weight is allowed only up to this far across x=0
TORSO = [M + "Spine", M + "Spine1", M + "Spine2", M + "Neck"]   # one candidate, input proportions kept
SEAM_TOL = 5e-4           # m, body vert == head vert (neck seam)
SEAM_RINGS = 0             # extra frozen rings next to the seam
FREEZE_BONES = (M + "Head", M + "LeftForeArm", M + "RightForeArm",
                "masha:forearm_twist_mid.L", "masha:forearm_twist_mid.R", "masha:forearm_twist.L",
                "masha:forearm_twist.R", "masha:hair.0", "masha:glute.L", "masha:glute.R")


def ua_twist(s):
    return f"masha:upperarm_twist.{s}"


def _smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# ----------------------------------------------------------------------------- mesh / weights io
def _mesh(body):
    n = len(body.data.vertices)
    co = np.zeros(n * 3)
    body.data.vertices.foreach_get("co", co)
    co = co.reshape(-1, 3)
    mw = np.array(body.matrix_world)
    co = co @ mw[:3, :3].T + mw[:3, 3]
    body.data.calc_loop_triangles()
    tri = np.zeros(len(body.data.loop_triangles) * 3, dtype=np.int64)
    body.data.loop_triangles.foreach_get("vertices", tri)
    tri = tri.reshape(-1, 3)
    e = np.concatenate([tri[:, [0, 1]], tri[:, [1, 2]], tri[:, [2, 0]]])
    e = np.unique(np.sort(e, 1), axis=0)
    return co, tri, e


def _read(body):
    names = [g.name for g in body.vertex_groups]
    W = np.zeros((len(body.data.vertices), len(names)))
    for v in body.data.vertices:
        for g in v.groups:
            W[v.index, g.group] = g.weight
    return names, W


def _mirror_map(co, tol=2e-3):
    kd = kdtree.KDTree(len(co))
    for i, c in enumerate(co):
        kd.insert(c, i)
    kd.balance()
    mir = np.zeros(len(co), dtype=np.int64)
    err = 0.0
    for i, c in enumerate(co):
        _, j, d = kd.find((-c[0], c[1], c[2]))
        mir[i] = j
        err = max(err, d)
    return mir, err


# ----------------------------------------------------------------------------- procedural poses
def _upd():
    bpy.context.view_layer.update()


def _reset(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.location = (0, 0, 0)
        pb.scale = (1, 1, 1)
    _upd()


def _aim(rig, name, d, twist=0.0):
    _upd()
    pb = rig.pose.bones[name]
    Mx = pb.matrix.copy()
    q = Mx.col[1].xyz.normalized().rotation_difference(Vector(d).normalized())
    R = q.to_matrix() @ Mx.to_3x3()
    if twist:
        R = R @ Matrix.Rotation(math.radians(twist), 3, "Y")
    N = R.to_4x4()
    N.translation = Mx.translation
    pb.matrix = N
    _upd()


def _elbow(rig, side, deg):
    _upd()
    a, f = rig.pose.bones[M + side + "Arm"], rig.pose.bones[M + side + "ForeArm"]
    da, df = (a.tail - a.head).normalized(), (f.tail - f.head).normalized()
    axis = da.cross(df).normalized()
    cur = math.degrees(da.angle(df))
    m3 = f.matrix.to_3x3().normalized()
    ax = (m3.inverted() @ axis).normalized()
    from mathutils import Quaternion
    f.rotation_quaternion = f.rotation_quaternion @ Quaternion(ax, math.radians(deg - cur))
    _upd()


def _clav(rig, side, s, delta_elev, protract=0.0):
    from mathutils import Quaternion
    b = rig.data.bones[M + side + "Shoulder"]
    d = (b.tail_local - b.head_local).normalized()
    el = math.asin(d.z)
    h = Vector((d.x, d.y, 0)).normalized()
    if protract:
        h = Quaternion((0, 0, 1), -s * math.radians(protract)) @ h
    e = el + math.radians(delta_elev)
    _aim(rig, M + side + "Shoulder", h * math.cos(e) + Vector((0, 0, math.sin(e))))


def set_pose(rig, spec):
    theta, phi, clav, elbow, twist = spec[:5]
    only = spec[6] if len(spec) > 6 else "LR"
    _reset(rig)
    for code, side, s in SIDES:
        if code not in only:
            continue
        if isinstance(theta, tuple):          # explicit arm direction (her left side; mirrored), phi = protraction
            _clav(rig, side, s, clav, protract=phi)
            _aim(rig, M + side + "Arm", Vector((s * theta[0], theta[1], theta[2])))
        else:
            c = CLAV_RHYTHM[1] * max(0.0, theta - CLAV_RHYTHM[0]) if clav is None else clav
            _clav(rig, side, s, c, protract=10 if phi > 60 else 0)
            t, p = math.radians(theta), math.radians(phi)
            _aim(rig, M + side + "Arm", Vector((s * math.sin(t) * math.cos(p), -math.sin(t) * math.sin(p),
                                                -math.cos(t))), twist * s)
        if elbow:
            _elbow(rig, side, elbow)
    apply_twist(rig)


def apply_twist(rig):
    """Runtime rule for the upper-arm twist bones (same as hands.apply_twist_rule, upper arm part)."""
    from mathutils import Quaternion
    for s, side, _ in SIDES:
        ub = rig.pose.bones.get(ua_twist(s))
        if ub is None:
            continue
        q = rig.pose.bones[M + side + "Arm"].rotation_quaternion
        a = 2 * math.atan2(q.y, q.w)
        ub.rotation_mode = "QUATERNION"
        ub.rotation_quaternion = Quaternion((0, 1, 0), TWIST_UPPERARM * a)
    _upd()


def _skin_mats(rig, bone_names):
    """armature-space skinning matrices (posed @ rest^-1) for the listed bones, as numpy 4x4."""
    _upd()
    out = []
    for n in bone_names:
        pb = rig.pose.bones[n]
        out.append(np.array(pb.matrix @ pb.bone.matrix_local.inverted()))
    return np.array(out)


# ----------------------------------------------------------------------------- optimization
def _project_simplex(V, mass, support=None):
    """Euclidean projection of each row of V onto {x >= 0, sum x = mass_row} (optionally on a support)."""
    if support is not None:
        V = np.where(support, V, -1e9)
    n, k = V.shape
    U = -np.sort(-V, axis=1)
    css = np.cumsum(U, axis=1) - mass[:, None]
    ind = np.arange(1, k + 1)
    cond = U - css / ind > 0
    rho = k - 1 - np.argmax(cond[:, ::-1], axis=1)
    theta = css[np.arange(n), rho] / (rho + 1)
    X = np.maximum(V - theta[:, None], 0.0)
    if support is not None:
        X = np.where(support, X, 0.0)
    return X


class Problem:
    def __init__(self, ctx):
        self.body, self.rig = ctx["body"], ctx["rig"]
        body, rig = self.body, self.rig
        self.co, self.tri, self.edges = _mesh(body)
        self.gnames, self.W0 = _read(body)
        self.gi = {n: i for i, n in enumerate(self.gnames)}
        deform = [b.name for b in rig.data.bones if b.use_deform]
        self.deform = [n for n in deform if n in self.gi]
        co = self.co
        vis = self.W0[:, self.gi["body"]] > 0 if "body" in self.gi else np.ones(len(co), bool)
        self.mir, self.mir_err = _mirror_map(co)
        # arm param t and Arm/upperarm_twist split per side
        self.k = {}
        region = np.zeros(len(co), bool)
        border = np.zeros(len(co))
        for s, side, sg in SIDES:
            b = rig.data.bones[M + side + "Arm"]
            sh = rig.data.bones[M + side + "Shoulder"]
            ah, at = np.array(b.head_local), np.array(b.tail_local)
            d = at - ah
            t = ((co - ah) @ d) / (d @ d)
            self.k[s] = 1.0 - _smooth(*ARM_RAMP, t)
            # distance to the clavicle->arm segment
            a0 = np.array(sh.head_local)
            seg = ah + 0.25 * d - a0
            u = np.clip(((co - a0) @ seg) / (seg @ seg), 0, 1)
            dist = np.linalg.norm(co - (a0 + u[:, None] * seg), axis=1)
            inside = (dist < REGION_R) & (t < REGION_T_MAX)
            region |= inside
            # 0 in the core, 1 at the region border (weights must fade back to the input there)
            b_ = np.maximum(_smooth(BORDER * REGION_R, REGION_R, dist),
                            _smooth(REGION_T_MAX - 0.15, REGION_T_MAX, t))
            border = np.where(inside, np.maximum(border, b_) if s == "R" else b_, border)
        self.border = border
        frz = np.zeros(len(co), bool)
        for n in FREEZE_BONES:
            if n in self.gi:
                frz |= self.W0[:, self.gi[n]] > 1e-4
        # Masha_Head seam: body verts that coincide with head verts (+ SEAM_RINGS rings) keep their weights
        head = ctx.get("head") or bpy.data.objects.get("Masha_Head")
        seam = np.zeros(len(co), bool)
        if head is not None:
            hco = np.array([head.matrix_world @ v.co for v in head.data.vertices])
            kd = kdtree.KDTree(len(hco))
            for i, c in enumerate(hco):
                kd.insert(c, i)
            kd.balance()
            near = np.where(co[:, 2] > 1.3)[0]
            for i in near:
                if kd.find(co[i])[2] < SEAM_TOL:
                    seam[i] = True
            e = self.edges
            for _ in range(SEAM_RINGS):
                grow = seam.copy()
                grow[e[seam[e[:, 0]], 1]] = True
                grow[e[seam[e[:, 1]], 0]] = True
                seam = grow
        self.seam_n = int(seam.sum())
        frz |= seam
        region &= vis & ~frz
        region &= region[self.mir]            # symmetric region
        self.free = np.where(region)[0]
        self.region = region
        # candidates = bone groups with a fixed per-vertex split: {bone: share (n,)}
        #   TORSO : Spine/Spine1/Spine2/Neck in the input (MPFB) proportions - the training poses do
        #           not bend the spine, so the optimizer must not redistribute among them
        #   ARM.* : Arm/upperarm_twist with the hands-stage twist ramp
        n = len(co)
        tw = np.stack([self.W0[:, self.gi[b]] for b in TORSO], 1)
        ts = tw.sum(1)
        ratio = np.where(ts[:, None] > 1e-6, tw / np.maximum(ts, 1e-12)[:, None], 0.0)
        known = ts > 1e-6
        e = self.edges
        for _ in range(400):                      # diffuse the split into verts without torso weight
            if known.all():
                break
            S = np.zeros_like(ratio)
            cnt = np.zeros(n)
            kk = known.astype(float)
            np.add.at(S, e[:, 0], ratio[e[:, 1]] * kk[e[:, 1], None])
            np.add.at(S, e[:, 1], ratio[e[:, 0]] * kk[e[:, 0], None])
            np.add.at(cnt, e[:, 0], kk[e[:, 1]])
            np.add.at(cnt, e[:, 1], kk[e[:, 0]])
            new = (~known) & (cnt > 0)
            ratio[new] = S[new] / cnt[new, None]
            known = known | new
        ratio[~known] = np.eye(len(TORSO))[TORSO.index(M + "Spine2")]
        self.groups = [("TORSO", {b: ratio[:, i] for i, b in enumerate(TORSO)})]
        for s, side, _ in SIDES:
            self.groups.append((M + side + "Shoulder", {M + side + "Shoulder": np.ones(n)}))
            self.groups.append((f"ARM.{s}", {ua_twist(s): self.k[s], M + side + "Arm": 1.0 - self.k[s]}))
        cols = [sum(self.W0[:, self.gi[b]] for b in g) for _, g in self.groups]
        self.C0 = np.stack(cols, 1)
        cand_bones = {b for _, g in self.groups for b in g}
        self.fixed_names = [nm for nm in self.deform if nm not in cand_bones]
        self.F0 = np.stack([self.W0[:, self.gi[nm]] for nm in self.fixed_names], 1)
        self.mass = np.clip(1.0 - self.F0.sum(1), 0.0, 1.0)
        # mirror permutation of the candidate columns
        swap = {M + "LeftShoulder": M + "RightShoulder", M + "RightShoulder": M + "LeftShoulder",
                "ARM.L": "ARM.R", "ARM.R": "ARM.L"}
        names = [g for g, _ in self.groups]
        self.perm = np.array([names.index(swap.get(c, c)) for c in names])
        # optimisation edges: any edge touching a free vertex (frozen neighbours act as boundary)
        e = self.edges
        self.opt_e = e[region[e[:, 0]] | region[e[:, 1]]]
        self.l0 = np.linalg.norm(co[self.opt_e[:, 0]] - co[self.opt_e[:, 1]], axis=1)
        self.act = np.unique(self.opt_e)   # vertices whose positions matter
        print(f"SHOULDERS seam frozen={self.seam_n} region free={len(self.free)} active={len(self.act)} edges={len(self.opt_e)} "
              f"mirror_err={self.mir_err * 1000:.3f} mm")

    def candidate_positions(self, rig):
        """Y[b, v, 3] posed positions of the active verts under each candidate, Fx[v, 3] fixed part."""
        co = np.concatenate([self.co[self.act], np.ones((len(self.act), 1))], 1)
        names = [b for _, g in self.groups for b in g]
        T = _skin_mats(rig, names)
        P = {nm: (co @ T[i].T)[:, :3] for i, nm in enumerate(names)}
        Y = [sum(r[self.act][:, None] * P[b] for b, r in g.items()) for _, g in self.groups]
        Tf = _skin_mats(rig, self.fixed_names)
        F = self.F0[self.act]
        Fx = np.einsum("vb,bvi->vi", F, np.einsum("bij,vj->bvi", Tf, co)[:, :, :3])
        return np.stack(Y, 0), Fx

    def run(self):
        rig = self.rig
        data = []
        for name, spec in POSES.items():
            set_pose(rig, spec)
            Y, Fx = self.candidate_positions(rig)
            heads = {s_: np.array(rig.pose.bones[M + side + "Arm"].head) for s_, side, _ in SIDES}
            data.append((name, spec[5], Y, Fx, heads))
        _reset(rig)
        apply_twist(rig)
        self.data = data
        act = self.act
        loc = -np.ones(len(self.co), dtype=np.int64)
        loc[act] = np.arange(len(act))
        self.le = loc[self.opt_e]
        freeloc = loc[self.free]
        # umbrella Laplacian on the free vertices (all their neighbours are active)
        a, b = self.le[:, 0], self.le[:, 1]
        self.deg = np.zeros(len(act))
        np.add.at(self.deg, a, 1.0)
        np.add.at(self.deg, b, 1.0)
        self.deg = np.maximum(self.deg, 1.0)
        hl = np.zeros(len(act))
        np.add.at(hl, a, self.l0)
        np.add.at(hl, b, self.l0)
        self.h2 = (hl / self.deg) ** 2
        self.lmask = np.zeros(len(act), bool)
        self.lmask[freeloc] = True
        self.L0n = np.linalg.norm(self._lap(self.co[act]), axis=1)
        # upper-arm / deltoid volume: fan volume of the input-weight upper arm about the arm head
        self.vol = []
        tri_ok = np.all(loc[self.tri] >= 0, axis=1)
        for s_, side, _ in SIDES:
            b = self.rig.data.bones[M + side + "Arm"]
            ah, at = np.array(b.head_local), np.array(b.tail_local)
            d = at - ah
            t = ((self.co - ah) @ d) / (d @ d)
            armw = self.W0[:, self.gi[M + side + "Arm"]] + self.W0[:, self.gi[ua_twist(s_)]]
            up = (armw > 0.3) & (t > -0.05) & (t < 0.55)
            tl = loc[self.tri[tri_ok & up[self.tri].all(1)]]
            X = self.co[act]
            v0 = self._fan(X, tl, ah)[0]
            self.vol.append((s_, tl, v0))
        C = self.C0[act].copy()
        C0 = C.copy()
        mass = self.mass[act]
        isfree = np.zeros(len(act), bool)
        isfree[freeloc] = True
        # symmetric start (project the input weights too)
        C[isfree] = self._sym(C, loc)[isfree]
        # a side's clavicle / arm never reaches across the midline
        X = self.co[act, 0]
        allowed = np.ones_like(C, dtype=bool)
        for j, (gname, _) in enumerate(self.groups):
            if gname.endswith((".L", "LeftShoulder")):
                allowed[:, j] = X > -MIDLINE
            elif gname.endswith((".R", "RightShoulder")):
                allowed[:, j] = X < MIDLINE
        support = allowed
        # per-entry prior strength: base + region border + a side's groups close to the midline
        Pw = PRIOR + EDGE_PRIOR * self.border[act][:, None] * np.ones_like(C)
        for j, (gname, _) in enumerate(self.groups):
            sg = 1.0 if gname.endswith((".L", "LeftShoulder")) else -1.0 if gname.endswith((".R", "RightShoulder")) else 0.0
            if sg:
                Pw[:, j] += MID_PRIOR * (1.0 - _smooth(-MIDLINE, MID_FADE, sg * X))
        C[isfree] = _project_simplex(C[isfree], mass[isfree], support[isfree])
        e0 = self.energy(C, grad=False)
        print(f"SHOULDERS energy before: {e0}")
        for stage, iters in enumerate(ITERS):
            m = np.zeros_like(C)
            v = np.zeros_like(C)
            for it in range(1, iters + 1):
                _, g = self.energy(C)
                g = g + Pw * 2 * (C - C0)
                # tangent to sum(C_row) = mass: moving all weights of a vertex together moves it by
                # its absolute position (huge, meaningless curvature) - remove that component
                sup = np.ones_like(C, dtype=bool) if support is None else support
                g = np.where(sup, g - (g * sup).sum(1, keepdims=True) / sup.sum(1, keepdims=True), 0.0)
                m = 0.9 * m + 0.1 * g
                v = 0.999 * v + 0.001 * g * g
                step = LR * (m / (1 - 0.9 ** it)) / (np.sqrt(v / (1 - 0.999 ** it)) + 1e-8)
                Cn = C - step
                Cn = self._sym(Cn, loc)
                Cn[~isfree] = C[~isfree]
                Cn[isfree] = _project_simplex(Cn[isfree], mass[isfree],
                                              None if support is None else support[isfree])
                C = Cn
                if it % 150 == 0:
                    print(f"SHOULDERS stage {stage} it {it} energy {self.energy(C, grad=False)}")
            if stage == 0:
                support = self._limit_support(C, act) & allowed
                C[isfree] = _project_simplex(C[isfree], mass[isfree], support[isfree])
        self.C = C
        print(f"SHOULDERS energy after: {self.energy(C, grad=False)}")
        return C

    def _sym(self, C, loc):
        """average each active vertex with its mirror (candidate columns permuted)."""
        mj = loc[self.mir[self.act]]
        ok = mj >= 0
        S = C.copy()
        S[ok] = 0.5 * (C[ok] + C[mj[ok]][:, self.perm])
        return S

    def _limit_support(self, C, act):
        """support keeping <= MAX_INF influences in total (fixed bones count; ARM counts 2 where split)."""
        F = self.F0[act]
        nf = (F > 1e-3).sum(1)
        cost = np.stack([np.maximum(sum((r[act] > 0.02).astype(np.int64) for r in g.values()), 1)
                         for _, g in self.groups], 1)
        sup = np.zeros_like(C, dtype=bool)
        order = np.argsort(-C, axis=1)
        for i in range(len(act)):
            budget = MAX_INF - nf[i]
            for j in order[i]:
                if C[i, j] < 1e-3:
                    break
                if cost[i, j] <= budget:
                    sup[i, j] = True
                    budget -= cost[i, j]
            if not sup[i].any():
                sup[i, order[i, 0]] = True
        # mirror-consistent support
        loc = -np.ones(len(self.co), dtype=np.int64)
        loc[act] = np.arange(len(act))
        mj = loc[self.mir[act]]
        ok = mj >= 0
        sup[ok] = sup[ok] & sup[mj[ok]][:, self.perm]
        none = ~sup.any(1)
        sup[none, np.argmax(C[none], 1)] = True
        return sup

    @staticmethod
    def _fan(P, tl, c, grad=False):
        A, B, D = P[tl[:, 0]] - c, P[tl[:, 1]] - c, P[tl[:, 2]] - c
        V = float(np.einsum("ij,ij->i", A, np.cross(B, D)).sum() / 6.0)
        if not grad:
            return V, None
        return V, (np.cross(B, D) / 6.0, np.cross(D, A) / 6.0, np.cross(A, B) / 6.0)

    def _lap(self, P):
        a, b = self.le[:, 0], self.le[:, 1]
        S = np.zeros_like(P)
        np.add.at(S, a, P[b])
        np.add.at(S, b, P[a])
        return P - S / self.deg[:, None]

    def energy(self, C, grad=True):
        a, b = self.le[:, 0], self.le[:, 1]
        E = 0.0
        G = np.zeros_like(C) if grad else None
        for name, wp, Y, Fx, heads in self.data:
            P = np.einsum("vb,bvi->vi", C, Y) + Fx
            d = P[a] - P[b]
            l = np.linalg.norm(d, axis=1) + 1e-12
            r = l / self.l0 - 1.0
            E += wp * float((self.l0 * r * r).sum())
            # bending: change of the umbrella-Laplacian magnitude (creases / bumps), dimensionless
            Lp = self._lap(P)
            ln = np.linalg.norm(Lp, axis=1) + 1e-12
            q = np.where(self.lmask, (ln - self.L0n), 0.0)
            E += BEND * wp * float((q * q / self.h2).sum()) * 1e-2
            if grad:
                gp = (wp * 2 * r / l)[:, None] * d          # dE/dP[a] (l0 * 2r * (1/l0) * d/l)
                GP = np.zeros_like(P)
                np.add.at(GP, a, gp)
                np.add.at(GP, b, -gp)
                GL = (BEND * wp * 1e-2 * 2 * q / self.h2 / ln)[:, None] * Lp
                GP += GL
                GLd = GL / self.deg[:, None]
                np.add.at(GP, b, -GLd[a])
                np.add.at(GP, a, -GLd[b])
            for s_, tl, v0 in self.vol:
                V, dV = self._fan(P, tl, heads[s_], grad)
                qv = V / v0 - 1.0
                E += VOL * wp * qv * qv
                if grad:
                    f = VOL * wp * 2 * qv / v0
                    for k in range(3):
                        np.add.at(GP, tl[:, k], f * dV[k])
            if grad:
                G += np.einsum("vi,bvi->vb", GP, Y)
        dw = C[a] - C[b]
        E += SMOOTH * float((dw * dw).sum()) * 1e-2
        if grad:
            gs = SMOOTH * 1e-2 * 2 * dw
            np.add.at(G, a, gs)
            np.add.at(G, b, -gs)
            return E, G
        return E

    def write(self):
        body = self.body
        act = self.act
        C = self.C
        W = self.W0.copy()
        rows = act[np.isin(act, self.free)]
        loc = -np.ones(len(self.co), dtype=np.int64)
        loc[act] = np.arange(len(act))
        Cr = C[loc[rows]]
        for j, (_, g) in enumerate(self.groups):
            for b, r in g.items():
                W[rows, self.gi[b]] = Cr[:, j] * r[rows]
        # final limit + normalize over deform bones (rows only)
        di = np.array([self.gi[n] for n in self.deform])
        D = W[np.ix_(rows, di)]
        D[D < 2e-3] = 0.0
        order = np.argsort(-D, axis=1)
        np.put_along_axis(D, order[:, MAX_INF:], 0.0, axis=1)
        D /= np.maximum(D.sum(1, keepdims=True), 1e-12)
        W[np.ix_(rows, di)] = D
        vg = body.vertex_groups
        changed = 0
        for r in rows:
            for j in di:
                old, new = self.W0[r, j], W[r, j]
                if abs(old - new) < 1e-6:
                    continue
                changed += 1
                if new > 0:
                    vg[j].add([int(r)], float(new), "REPLACE")
                else:
                    vg[j].remove([int(r)])
        self.W = W
        print(f"SHOULDERS wrote {len(rows)} verts, {changed} weight entries changed")
        return rows


def weight_report(body, rig):
    deform = {b.name for b in rig.data.bones if b.use_deform}
    names = {g.index: g.name for g in body.vertex_groups}
    mx, bad = 0, 0
    for v in body.data.vertices:
        ws = [g.weight for g in v.groups if names[g.group] in deform and g.weight > 0]
        mx = max(mx, len(ws))
        if ws and abs(sum(ws) - 1.0) > 1e-3:
            bad += 1
    print(f"SHOULDERS weights max_influences={mx} unnormalized={bad}")
    return mx, bad


def _vg_dict(obj):
    nm = {g.index: g.name for g in obj.vertex_groups}
    return [{nm[g.group]: g.weight for g in v.groups} for v in obj.data.vertices]


def _set_weights(obj, i, new, only):
    """replace the weights of vertex i for the group names in `only` by `new` (dict)."""
    for name in only:
        g = obj.vertex_groups.get(name)
        w = new.get(name, 0.0)
        if w > 0.0:
            if g is None:
                g = obj.vertex_groups.new(name=name)
            g.add([i], float(w), "REPLACE")
        elif g is not None:
            g.remove([i])


def neck_fix(ctx):
    """Neck base: no upper-arm weight on the neck (MPFB left mixamorig:*Arm weight on Masha_Head's lower
    neck, up to z=1.555, and the hands stage renamed it to upperarm_twist on the body side only, so the
    two sides of the seam no longer matched).  The arm share moves to the clavicle (mixamorig:*Shoulder)
    on both meshes, then every body seam vertex copies the weights of its coincident head vertex."""
    body, rig = ctx["body"], ctx["rig"]
    head = ctx.get("head") or bpy.data.objects.get("Masha_Head")
    if head is None:
        return {}
    deform = {b.name for b in rig.data.bones if b.use_deform}
    HW = _vg_dict(head)
    moved = 0
    for i, w in enumerate(HW):
        new = dict(w)
        for s, side, _ in SIDES:
            a = new.pop(M + side + "Arm", 0.0) + new.pop(ua_twist(s), 0.0)
            if a > 0.0:
                new[M + side + "Shoulder"] = new.get(M + side + "Shoulder", 0.0) + a
                moved += 1
        if new != w:
            _set_weights(head, i, new, {n for n in set(w) | set(new) if n in deform})
            HW[i] = new
    # body seam verts := head verts
    hco = [head.matrix_world @ v.co for v in head.data.vertices]
    kd = kdtree.KDTree(len(hco))
    for i, c in enumerate(hco):
        kd.insert(c, i)
    kd.balance()
    BW = _vg_dict(body)
    n_seam, mism = 0, 0.0
    for v in body.data.vertices:
        p = body.matrix_world @ v.co
        if p.z < 1.3:
            continue
        _, j, d = kd.find(p)
        if d >= SEAM_TOL:
            continue
        n_seam += 1
        bw = {k: x for k, x in BW[v.index].items() if k in deform}
        hw = {k: x for k, x in HW[j].items() if k in deform}
        mism = max(mism, sum(abs(bw.get(k, 0.0) - hw.get(k, 0.0)) for k in set(bw) | set(hw)))
        _set_weights(body, v.index, hw, set(bw) | set(hw))
    print(f"SHOULDERS neck_fix: head verts with arm weight moved to clavicle={moved}, body seam verts="
          f"{n_seam}, max seam weight mismatch before (L1)={mism:.3f}")
    return {"head_arm_verts": moved, "seam_verts": n_seam, "seam_mismatch_before": round(mism, 4)}


TUNABLE_ARGS = {"sh_smooth": "SMOOTH", "sh_bend": "BEND", "sh_prior": "PRIOR", "sh_region": "REGION_R",
                "sh_lr": "LR", "sh_vol": "VOL", "sh_edge": "EDGE_PRIOR", "sh_mid": "MID_PRIOR"}


def build(ctx):
    args = common.parse_args({})
    for k, g in TUNABLE_ARGS.items():
        if k in args:
            globals()[g] = float(args[k])
            print(f"SHOULDERS tunable {g} = {args[k]}")
    O = bpy.data.objects
    ctx.setdefault("body", O.get("Masha_Body"))
    ctx.setdefault("rig", O.get("Masha_Rig"))
    if ctx["body"] is None:
        ctx["body"] = O["Masha_Body"]
    if ctx["rig"] is None:
        ctx["rig"] = O["Masha_Rig"]
    rig = ctx["rig"]
    keep = {pb.name: (pb.rotation_mode, tuple(pb.rotation_quaternion), tuple(pb.rotation_euler),
                      tuple(pb.location), tuple(pb.scale)) for pb in rig.pose.bones}
    neck = neck_fix(ctx)
    prob = Problem(ctx)
    prob.run()
    rows = prob.write()
    for pb in rig.pose.bones:
        mode, q, e, loc, sc = keep[pb.name]
        pb.rotation_mode = mode
        pb.rotation_quaternion, pb.rotation_euler, pb.location, pb.scale = q, e, loc, sc
    _upd()
    mx, bad = weight_report(ctx["body"], rig)
    ctx["shoulders_report"] = {"verts": int(len(rows)), "max_influences": mx, "unnormalized": bad, "neck": neck}


def qa(ctx):
    """Workbench renders of the training poses (front + left armpit) into <qa_dir>/shoulders."""
    rig, body = ctx["rig"], ctx["body"]
    out = common.ensure_dir(os.path.join(ctx["qa_dir"], "shoulders"))
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light, sh.color_type, sh.show_cavity = "STUDIO", "SINGLE", True
    sc.render.resolution_x, sc.render.resolution_y = 600, 800
    cam = O_cam = bpy.data.objects.get("shoulders_cam")
    if cam is None:
        cam = O_cam = bpy.data.objects.new("shoulders_cam", bpy.data.cameras.new("shoulders_cam"))
        sc.collection.objects.link(cam)
    sc.camera = O_cam
    for name, spec in POSES.items():
        set_pose(rig, spec)
        ah = rig.pose.bones[M + "LeftArm"].head
        for view, loc, tgt in (("front", Vector((0, -2.1, 1.45)), Vector((0, 0, 1.38))),
                               ("shoulder", ah + Vector((0.12, -0.55, 0.08)), ah),
                               ("back", ah + Vector((0.12, 0.55, 0.08)), ah)):
            cam.location = loc
            cam.rotation_euler = (tgt - loc).to_track_quat("-Z", "Y").to_euler()
            cam.data.lens = 35 if view == "front" else 40
            sc.render.filepath = os.path.join(out, f"{name}_{view}.png")
            bpy.ops.render.render(write_still=True)
    _reset(rig)


def main():
    args = common.parse_args({"out": os.path.join(HERE, "out"), "qa": "0"})
    ctx = {"out_dir": common.ensure_dir(args["out"]),
           "qa_dir": common.ensure_dir(os.path.join(args["out"], "qa"))}
    build(ctx)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(ctx["out_dir"], "masha_v2_shoulders.blend"))
    if args["qa"] == "1":
        qa(ctx)


if __name__ == "__main__":
    main()
