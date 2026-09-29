"""Layering toolkit for Masha's clips (owner: Agent 4). Pure math on rigmath.Pose; no bpy state changes.

Building blocks used by clips.py:
  Source    - a retargeted Mixamo Motion, lightly smoothed, sampled as per-bone DELTAS relative to a
              reference time (additive layering), or as absolute locals. Mixamo returned the leaf bones
              (Head, ToeBase, finger 3rd phalanges) without animation (they were leaves in the uploaded
              skeleton), so Head is synthesised from the Neck (lagged) and 3rd phalanges from the 2nd.
  Track     - keyed channel (float or tuple), cubic Hermite through the keys (finite-difference
              tangents, zero tangents at the ends / on `hold` keys): smooth, no pose-to-pose stops.
  Solver    - holds the base pose + its derived IK targets and applies the constraints per frame:
              planted feet (analytic two-bone IK to the base ankle, base foot orientation), hip hand
              (IK to the base wrist carried by the pelvis; hand orientation carried by the pelvis),
              keyed arm IK (wrist target + pole + hand orientation), world-axis rotations.
Envelope helpers: env(t, T, tin, tout) = smootherstep ramps (weight 0 at both ends -> exact base pose).
"""
import math

from mathutils import Euler, Matrix, Quaternion, Vector

import rigmath
from rigmath import HIPS, M, X, Y, Z, qpow, smootherstep

FPS = 30
SIDES = ("Left", "Right")
FINGERS = ("Thumb", "Index", "Middle", "Ring", "Pinky")
TORSO = [M + n for n in ("Hips", "Spine", "Spine1", "Spine2", "Neck", "Head", "LeftShoulder", "RightShoulder")]


def arm_bones(side):
    return [f"{M}{side}{b}" for b in ("Arm", "ForeArm", "Hand")]


def finger_bones(side):
    return [f"{M}{side}Hand{f}{i}" for f in FINGERS for i in (1, 2, 3)]


def leg_bones(side):
    return [f"{M}{side}{b}" for b in ("UpLeg", "Leg", "Foot", "ToeBase")]


def env(t, T, tin, tout):
    return smootherstep(t / tin if tin > 0 else 1.0) * smootherstep((T - t) / tout if tout > 0 else 1.0)


def window(t, t0, t1, rin, rout):
    """1 inside [t0, t1], smootherstep ramps of rin before t0 and rout after t1."""
    a = smootherstep((t - (t0 - rin)) / rin) if rin > 0 else float(t >= t0)
    b = smootherstep(((t1 + rout) - t) / rout) if rout > 0 else float(t <= t1)
    return min(a, b)


def bump(t, center, rise, fall, period=None):
    """Asymmetric smooth bump (0..1..0): rise s before `center`, fall s after. Periodic if period."""
    if period:
        d = (t - center + period / 2) % period - period / 2
    else:
        d = t - center
    if d < 0:
        return smootherstep(1 + d / rise) if d > -rise else 0.0
    return smootherstep(1 - d / fall) if d < fall else 0.0


# ============================================================================ Source (mocap)
def _smooth_series(qs, sigma):
    if sigma <= 0 or len(qs) < 3:
        return qs
    r = int(math.ceil(3 * sigma))
    ker = [math.exp(-0.5 * (k / sigma) ** 2) for k in range(-r, r + 1)]
    out = []
    n = len(qs)
    for i in range(n):
        acc = [0.0, 0.0, 0.0, 0.0]
        ws = 0.0
        ref = qs[i]
        for k, w in zip(range(-r, r + 1), ker):
            j = min(max(i + k, 0), n - 1)
            q = qs[j]
            s = 1.0 if q.dot(ref) >= 0 else -1.0
            for c in range(4):
                acc[c] += w * s * q[c]
            ws += w
        out.append(Quaternion([a / ws for a in acc]).normalized())
    return out


class Source:
    def __init__(self, motion, sigma=1.0, head_gain=1.0, head_lag=2, mirror=False):
        self.m = motion
        self.fps = motion.fps
        rot = {b: _smooth_series(qs, sigma) for b, qs in motion.rot.items()}
        loc = motion.hips_loc
        if mirror:
            rot, loc = _mirror(rot, loc)
        self.rot = rot
        self.loc = loc
        self.n = motion.frames
        self.head_gain = head_gain
        self.head_lag = head_lag
        self.T = (self.n - 1) / self.fps

    def _at(self, series, s):
        x = min(max(s * self.fps, 0.0), self.n - 1.0)
        i = int(math.floor(x))
        j = min(i + 1, self.n - 1)
        a, b = series[i], series[j]
        if isinstance(a, Quaternion):
            return a.slerp(b, x - i)
        return a.lerp(b, x - i)

    def abs(self, bone, s):
        """Absolute (retargeted) local rotation at source time s."""
        if bone == M + "Head":
            return self.delta(bone, s, 0.0)   # rest-relative: only meaningful as a delta
        if bone.endswith("3") and "Hand" in bone:
            return self.abs(bone[:-1] + "2", s)
        return self._at(self.rot[bone], s)

    def delta(self, bone, s, ref):
        """Local delta rotation from source time ref to s (additive layer: base @ delta)."""
        if bone == M + "Head":
            lag = self.head_lag / self.fps
            d = self.delta(M + "Neck", s - lag, ref - lag if ref > lag else ref)
            return qpow(d, self.head_gain)
        if bone.endswith("3") and "Hand" in bone:
            return qpow(self.delta(bone[:-1] + "2", s, ref), 0.8)
        if bone.endswith("ToeBase"):
            return Quaternion()
        a = self._at(self.rot[bone], ref)
        b = self._at(self.rot[bone], s)
        return (a.inverted() @ b).normalized()

    def dloc(self, s, ref):
        return self._at(self.loc, s) - self._at(self.loc, ref)


def _mirror(rot, loc):
    """Left<->right mirror for a symmetric rig (bone rolls mirrored): q(w,x,y,z) -> (w,x,-y,-z)."""
    out = {}
    for b, qs in rot.items():
        o = b.replace("Left", "#").replace("Right", "Left").replace("#", "Right")
        out[o] = [Quaternion((q.w, q.x, -q.y, -q.z)) for q in qs]
    return out, [Vector((-v.x, v.y, v.z)) for v in loc]


# ============================================================================ Track (keys)
class Track:
    """keys: [(t, value)] or [(t, value, "hold")]; value float or tuple. Cubic Hermite interpolation."""

    def __init__(self, keys):
        self.t = [k[0] for k in keys]
        self.scalar = not isinstance(keys[0][1], (tuple, list, Vector))
        self.v = [self._vec(k[1]) for k in keys]
        hold = [len(k) > 2 and k[2] == "hold" for k in keys]
        n = len(keys)
        self.m = []
        for i in range(n):
            if i == 0 or i == n - 1 or hold[i]:
                self.m.append(self.v[i] * 0.0)
            else:
                self.m.append((self.v[i + 1] - self.v[i - 1]) / (self.t[i + 1] - self.t[i - 1]))

    @staticmethod
    def _vec(v):
        return Vector(v) if isinstance(v, (tuple, list, Vector)) else Vector((v, 0.0))

    def __call__(self, t):
        T, V, Mt = self.t, self.v, self.m
        if t <= T[0]:
            r = V[0]
        elif t >= T[-1]:
            r = V[-1]
        else:
            i = max(k for k in range(len(T) - 1) if T[k] <= t)
            h = T[i + 1] - T[i]
            u = (t - T[i]) / h
            h00 = 2 * u ** 3 - 3 * u ** 2 + 1
            h10 = u ** 3 - 2 * u ** 2 + u
            h01 = -2 * u ** 3 + 3 * u ** 2
            h11 = u ** 3 - u ** 2
            r = h00 * V[i] + h10 * h * Mt[i] + h01 * V[i + 1] + h11 * h * Mt[i + 1]
        return r[0] if self.scalar else r.copy()


# ============================================================================ Solver
class Solver:
    def __init__(self, model, base, hand_poses):
        self.prev = None
        self.proxy = None
        self.avoid_log = []
        self.model = model
        self.base = base
        self.hp = hand_poses
        bm = model.fk(base)
        self.bm = bm
        self.foot = {}
        for s in SIDES:
            f = f"{M}{s}Foot"
            self.foot[s] = {"ankle": bm[f].translation.copy(), "q": bm[f].to_quaternion(),
                            "knee": bm[f"{M}{s}Leg"].translation.copy(),
                            "ball": bm[f"{M}{s}ToeBase"].translation.copy()}
        hb = bm[HIPS]
        self.hip_inv = hb.inverted()
        lh = f"{M}LeftHand"
        self.hip_hand = {"wrist": hb.inverted() @ bm[lh].translation,
                         "elbow": hb.inverted() @ bm[f"{M}LeftForeArm"].translation,
                         "q": hb.to_quaternion().inverted() @ bm[lh].to_quaternion()}
        self.hinge = {}
        for sd in SIDES:
            q = base.q(f"{M}{sd}ForeArm")
            ax = Vector((q.x, q.y, q.z))
            if q.w < 0:
                ax = -ax
            self.hinge[sd] = ax.normalized() if ax.length > 1e-6 else X.copy()
            self.hinge_th = getattr(self, "hinge_th", {})
            self.hinge_th[sd] = 2 * math.acos(min(1.0, abs(q.w)))
        self.relax = {"wrist": bm[f"{M}RightHand"].translation.copy(),
                      "elbow": bm[f"{M}RightForeArm"].translation.copy(),
                      "shoulder": bm[f"{M}RightArm"].translation.copy(),
                      "q": bm[f"{M}RightHand"].to_quaternion()}

    def fresh(self):
        return self.base.copy()

    # ---- basic ops
    def add(self, pose, bone, delta, k=1.0):
        if k == 0:
            return
        pose.rot[bone] = (pose.q(bone) @ qpow(delta, k)).normalized()

    def rot_world(self, pose, bone, rots):
        """Rotate `bone` about world/character axes [(axis, deg)] (children follow)."""
        R = rigmath.charq(rots)
        if R.angle < 1e-9:
            return
        W = self.model.world_q(pose, bone)
        pose.rot[bone] = self.model.local_from_world(pose, bone, R @ W)

    def hand_pose(self, pose, name, side, weight, bones=None):
        """Blend a hand_poses.json pose (finger bones, optionally the Hand) towards weight."""
        if weight <= 0:
            return
        for bone, e in self.hp[name].items():
            if f"{M}{side}Hand" not in bone:
                continue
            if bone == f"{M}{side}Hand" and (bones is None or bone not in bones):
                continue
            q = Euler([math.radians(v) for v in e], "XYZ").to_quaternion()
            pose.rot[bone] = pose.q(bone).slerp(q, min(1.0, weight))

    def slerp_bones(self, pose, other, bones, w):
        if w <= 0:
            return
        for b in bones:
            pose.rot[b] = pose.q(b).slerp(other.q(b), min(1.0, w)) if w < 1 else other.q(b).copy()

    # ---- constraints
    def plant_feet(self, pose, sides=SIDES, pivot=None):
        """Feet back on their base targets. pivot: {side: deg} extra foot pitch about the ball of the
        foot (e.g. lower the free heel when weight moves onto it)."""
        res = 0.0
        self.clamp_hips(pose)
        for s in sides:
            F = self.foot[s]
            ankle, q = F["ankle"], F["q"]
            if pivot and abs(pivot.get(s, 0.0)) > 1e-6:
                R = Quaternion(self._foot_side_axis(s), math.radians(pivot[s]))
                ankle = F["ball"] + R @ (ankle - F["ball"])
                q = R @ q
            mats = self.model.fk(pose, [f"{M}{s}Leg"])
            knee = mats[f"{M}{s}Leg"].translation
            hip = mats[f"{M}{s}UpLeg"].translation
            # pole: keep the current knee plane, pushed a little forward to stay stable
            pole = knee + (knee - (hip + ankle) * 0.5).normalized() * 0.3 + Vector((0, -0.05, 0))
            res = max(res, self.model.two_bone(pose, f"{M}{s}UpLeg", f"{M}{s}Leg", ankle, pole))
            self.model.set_world(pose, f"{M}{s}Foot", q)
            pose.rot[f"{M}{s}ToeBase"] = self.base.q(f"{M}{s}ToeBase").copy()
        return res

    def clamp_hips(self, pose, reach_frac=0.9995):
        """Lower the pelvis just enough that both planted ankles stay reachable (no over-extension,
        no foot sliding from the IK's soft clamp)."""
        mats = self.model.fk(pose, [f"{M}Left UpLeg".replace(" ", ""), f"{M}RightUpLeg"])
        dz = 0.0
        for s in SIDES:
            h = mats[f"{M}{s}UpLeg"].translation
            a = self.foot[s]["ankle"]
            reach = (self.model.length[f"{M}{s}UpLeg"] + self.model.length[f"{M}{s}Leg"]) * reach_frac
            v = h - a
            hz2 = reach * reach - (v.x * v.x + v.y * v.y)
            if hz2 <= 0:
                continue
            need = v.z - math.sqrt(hz2)
            dz = max(dz, need)
        if dz > 0:
            rr = self.model.rest[HIPS].to_3x3()
            pose.loc = pose.loc + rr.inverted() @ Vector((0, 0, -dz))
        return dz

    def _foot_side_axis(self, s):
        q = self.foot[s]["q"]
        return (q @ X).normalized()

    def hip_hand_ik(self, pose, w=1.0):
        """Left hand back on the hip (carried by the pelvis). w<1 blends from the current arm."""
        if w <= 0:
            return 0.0
        mats = self.model.fk(pose, [HIPS])
        hb = mats[HIPS]
        target = hb @ self.hip_hand["wrist"]
        pole = hb @ (self.hip_hand["elbow"] + (self.hip_hand["elbow"] - self.hip_hand["wrist"]) * 0.0)
        # pole a little further out than the elbow itself
        mats_sh = self.model.fk(pose, [f"{M}LeftArm"])
        sh = mats_sh[f"{M}LeftArm"].translation
        pole = pole + (pole - sh) * 0.5
        keep = {b: pose.q(b).copy() for b in arm_bones("Left")}
        r = self.model.hinge_ik(pose, f"{M}LeftArm", f"{M}LeftForeArm", target, pole, self.hinge["Left"],
                                  self.hinge_th["Left"])
        self.model.set_world(pose, f"{M}LeftHand", hb.to_quaternion() @ self.hip_hand["q"])
        if w < 1:
            for b in arm_bones("Left"):
                pose.rot[b] = keep[b].slerp(pose.q(b), w)
        return r

    def arm_ik(self, pose, side, wrist, pole, hand_q=None, w=1.0, pole_w=1.0, seed=None):
        """pole_w < 1 blends the pole from the arm's CURRENT bend plane (no snap when a keyed arm
        leaves its pose: at pole_w=0 and wrist = current wrist the arm is unchanged)."""
        if w <= 0:
            return 0.0
        keep = {b: pose.q(b).copy() for b in arm_bones(side)}
        if pole_w < 1:
            # rotate the bend plane about the shoulder->wrist axis from the CURRENT elbow plane to the
            # target pole's plane (a lerp of the two points could cross the axis and flip the elbow)
            mats = self.model.fk(pose, [f"{M}{side}Hand"])
            sh = mats[f"{M}{side}Arm"].translation
            el = mats[f"{M}{side}ForeArm"].translation
            d = (wrist - sh)
            if d.length > 1e-6:
                d.normalize()
                c = (el - sh) - (el - sh).dot(d) * d
                g = (pole - sh) - (pole - sh).dot(d) * d
                if c.length > 1e-6 and g.length > 1e-6:
                    c.normalize()
                    g.normalize()
                    ang = math.atan2(d.dot(c.cross(g)), c.dot(g))
                    v = Quaternion(d, ang * smootherstep(pole_w)) @ c
                    pole = sh + d * 0.15 + v * 0.3
        if seed is not None:
            # temporal coherence: start from the previous frame's arm so the minimal-swing IK never
            # has to swing ~180 deg in one go (twist would be ill-conditioned and flip)
            for b in (f"{M}{side}Arm", f"{M}{side}ForeArm"):
                pose.rot[b] = seed.q(b).copy()
        r = self.model.hinge_ik(pose, f"{M}{side}Arm", f"{M}{side}ForeArm", wrist, pole, self.hinge[side],
                                  self.hinge_th[side])
        if hand_q is not None:
            self.model.set_world(pose, f"{M}{side}Hand", hand_q)
        if w < 1:
            for b in arm_bones(side):
                pose.rot[b] = keep[b].slerp(pose.q(b), w)
        return r

    def world(self, pose, bone):
        return self.model.fk(pose, [bone])[bone]


def hand_frame(y, z):
    """Quaternion of a hand whose +Y (fingers) = y and +Z (volar normal) = z (world)."""
    y = y.normalized()
    z = (z - z.dot(y) * y).normalized()
    x = y.cross(z)
    return Matrix((x, y, z)).transposed().to_quaternion()


# ============================================================================ torso collision proxy
class TorsoProxy:
    """Base-pose torso (+ thighs) of the evaluated Masha_Body, split by anchor bone (Hips, Spine,
    Spine1, Spine2) and stored in each anchor's base-local frame, so a query point is mapped through
    the anchor's CURRENT transform (rigid-per-bone approximation of the skin; good to ~1 cm)."""

    ANCHORS = (HIPS, M + "Spine", M + "Spine1", M + "Spine2")

    def __init__(self, rig_ob, body, model, base):
        import bpy
        from mathutils.bvhtree import BVHTree
        import base_pose
        rigmath.apply_to_bpy(rig_ob, base)
        bpy.context.view_layer.update()
        reg = base_pose.Regions(body)
        co = reg.coords()
        verts = reg.region("torso") | reg.region("thigh", "Left") | reg.region("thigh", "Right")
        bm = model.fk(base)
        self.model = model

        def anchor(d):
            if d in self.ANCHORS:
                return d
            if d.startswith("masha:breast"):
                return M + "Spine2"
            return HIPS   # glutes, thighs

        self.bvh = {}
        self.inv = {}
        for a in self.ANCHORS:
            vs = {i for i in verts if anchor(reg.dom[i]) == a}
            # faces whose verts are all in the torso set, assigned to the anchor of most of their verts
            fs = []
            for f in reg.polys:
                if all(i in verts for i in f):
                    k = sum(1 for i in f if i in vs)
                    if k * 2 >= len(f):
                        fs.append(f)
            if not fs:
                continue
            Minv = bm[a].inverted()
            loc = [Minv @ c for c in co]
            self.bvh[a] = BVHTree.FromPolygons(loc, fs, all_triangles=False, epsilon=0.0)
        self.base_mats = bm

    def signed(self, mats, p):
        """(signed distance, push direction in world) of world point p vs the posed torso proxy.
        Inside/outside by ray parity along the radial direction from the anchor bone's axis (robust
        for deep points, where the nearest-face normal is not); push direction = that radial
        direction (horizontal-ish, away from the spine / pelvis axis)."""
        best = None
        for a, bvh in self.bvh.items():
            q = mats[a].inverted() @ p
            hit = bvh.find_nearest(q)
            if hit[0] is None:
                continue
            if best is None or hit[3] < best[0]:
                best = (hit[3], a, q)
        if best is None:
            return 1e9, Vector((0, 0, 1))
        dist, a, q = best
        radial = q - q.dot(Y) * Y
        if radial.length < 1e-6:
            radial = Vector((0, -1, 0))
        radial.normalize()
        inside = False
        for bv in self.bvh.values() if False else (self.bvh[a],):
            loc, nrm, _, _ = bv.ray_cast(q, radial)
            if loc is not None:
                inside = True
        # also check neighbouring anchors (the skin above/below may belong to them)
        if not inside:
            for b2, bv in self.bvh.items():
                if b2 == a:
                    continue
                q2 = mats[b2].inverted() @ p
                r2 = q2 - q2.dot(Y) * Y
                if r2.length < 1e-6:
                    continue
                loc, _, _, d2 = bv.ray_cast(q2, r2.normalized(), 0.25)
                if loc is not None and bv.find_nearest(q2)[3] < 0.03:
                    inside = True
                    break
        n = (mats[a].to_3x3() @ radial).normalized()
        return (-dist if inside else dist), n


def _arm_points(model, mats, side):
    fa = f"{M}{side}ForeArm"
    hand = f"{M}{side}Hand"
    pts = [mats[fa].translation.copy(),
           mats[fa].translation.lerp(mats[hand].translation, 0.25),
           mats[fa].translation.lerp(mats[hand].translation, 0.5),
           mats[fa].translation.lerp(mats[hand].translation, 0.8),
           mats[hand].translation.copy(),
           mats[hand] @ Vector((0, 0.05, 0)),
           model.tail(mats, f"{M}{side}HandMiddle1"),
           model.tail(mats, f"{M}{side}HandIndex3"),
           model.tail(mats, f"{M}{side}HandMiddle3"),
           model.tail(mats, f"{M}{side}HandThumb3")]
    return pts


def avoid_torso(solver, pose, side, margin=0.010, iters=8, scale=1.0):
    """Rotate the upper arm (about the shoulder) until forearm/hand/finger points clear the torso by
    `margin`. Returns the worst signed distance found before correcting (m)."""
    proxy = getattr(solver, "proxy", None)
    if proxy is None or scale <= 0:
        return None
    model = solver.model
    first = None
    for _ in range(iters):
        mats = model.fk(pose)
        sh = mats[f"{M}{side}Arm"].translation
        worst = None
        for p in _arm_points(model, mats, side):
            d, n = proxy.signed(mats, p)
            if worst is None or d < worst[0]:
                worst = (d, n, p)
        if first is None:
            first = worst[0]
            solver.avoid_log.append((side, round(first * 1000, 1)))
        deficit = (margin - worst[0]) * scale
        if deficit <= 1e-4:
            break
        d, n, p = worst
        r = p - sh
        axis = r.cross(n)
        if axis.length < 1e-6 or r.length < 1e-3:
            break
        ang = min(0.25, 1.05 * deficit / r.length)
        W = mats[f"{M}{side}Arm"].to_quaternion()
        pose.rot[f"{M}{side}Arm"] = model.local_from_world(pose, f"{M}{side}Arm",
                                                          Quaternion(axis.normalized(), ang) @ W, mats)
    return first
