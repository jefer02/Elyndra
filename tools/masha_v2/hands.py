"""Stage 3 - hands (owner: Agent 5).

What this stage does (deterministic, headless):
  1. Re-rolls mixamorig:{L,R}Hand and every finger bone so that ALL of them share one convention:
       local +Y  along the bone (towards the finger tip)
       local +X  flexion hinge  -> +X rotation curls towards the palm (wrist flexion for Hand)
       local +Z  points out of the palm (volar side)  -> Z rotation = spread / radial-ulnar deviation
     The flexion hinge of each finger is measured from the mesh (the nail of that finger marks its
     dorsal side), so a pure +X rotation curls a finger in its own plane without drifting sideways.
  2. Adds twist bones (deform, exported, no constraints), same orientation (roll) as the parent so
     their local Y is the limb axis:
       masha:upperarm_twist.L/.R     parent mixamorig:{L,R}Arm      head = Arm head,        factor -1/2 of Arm roll
       masha:forearm_twist_mid.L/.R  parent mixamorig:{L,R}ForeArm  head = 30 % of ForeArm, factor 1/3 of hand roll
       masha:forearm_twist.L/.R      parent mixamorig:{L,R}ForeArm  head = 62 % of ForeArm, factor 2/3 of hand roll
     (two forearm twist bones: with LBS the radius loss of a 50/50 blend is cos(delta/2); 1/3 steps keep
     it >= 0.97 at 90 deg pronation, one 1/2 bone gave 0.93.)
  3. Skin weights: ForeArm weight re-split along the forearm into ForeArm/twist_mid/twist (smoothstep
     ramp t=0.08..0.80), Arm weight split upperarm_twist/Arm (t=0.2..0.75), Thumb1 rebuilt as a radial
     falloff around the thumb metacarpal (thenar/web follow the thumb), then limit 4 + normalize.
  4. Hand pose library -> ctx["hand_poses"] and <out>/hand_poses.json
  5. Nails mask texture (body UV) -> <out>/textures/nails_mask.png

RUNTIME TWIST RULE (Kotlin, every frame, after the clip is sampled, before skinning):
  q_hand  = local rotation of mixamorig:*Hand (relative to its rest, i.e. glTF rotation * rest^-1)
  R0      = rest rotation of Hand relative to ForeArm  (constant, see hand_poses.json twist_rule.hand_rest_in_forearm)
  qf      = R0 * q_hand * R0^-1                         (hand rotation expressed in the forearm frame)
  a_hand  = 2*atan2(qf.y, qf.w)                         (swing-twist: twist angle about forearm Y)
  forearm_twist_mid.local = rest * axisAngle(Y, a_hand / 3)
  forearm_twist.local     = rest * axisAngle(Y, 2 * a_hand / 3)
  q_arm   = local rotation of mixamorig:*Arm;  a_arm = 2*atan2(q_arm.y, q_arm.w)
  upperarm_twist.local = rest * axisAngle(Y, -0.5 * a_arm)
If the runtime does nothing the twist bones stay at rest and the mesh behaves exactly like a rig
without twist bones (safe default). QA below implements the same rule in Python (apply_twist_rule).
"""
import json
import math
import os

import bpy
from mathutils import Matrix, Quaternion, Vector

import common

SIDES = (("L", "Left", 1.0), ("R", "Right", -1.0))
FINGERS = ("Thumb", "Index", "Middle", "Ring", "Pinky")
LONG_FINGERS = ("Index", "Middle", "Ring", "Pinky")
MAX_INF = 4
REST_COMP = 1.0     # pose flex values are anatomical (0 = straight finger): subtract the rest-pose curl

TWIST_FACTOR_UPPERARM = -0.5
# forearm twist chain: (bone base name, head position along ForeArm, fraction of the hand twist)
FA_TWISTS = {
    "1": [("forearm_twist", 0.55, 0.5)],
    "2": [("forearm_twist_mid", 0.30, 1 / 3), ("forearm_twist", 0.62, 2 / 3)],
}
FA_RAMP = {"1": (0.12, 0.72), "2": (0.08, 0.80)}   # t-range (along ForeArm) of the twist ramp


def fa_twists():
    return FA_TWISTS[common.parse_args({"hands_ntw": "2"})["hands_ntw"]]


def fa_ramp():
    return FA_RAMP[common.parse_args({"hands_ntw": "2"})["hands_ntw"]]


def mb(side, part):
    return f"mixamorig:{side}{part}"


def fa_twist(s, base="forearm_twist"):
    return f"masha:{base}.{s}"


def ua_twist(s):
    return f"masha:upperarm_twist.{s}"


def finger_bones(side):
    return [mb(side, f"Hand{f}{i}") for f in FINGERS for i in (1, 2, 3)]


# ----------------------------------------------------------------------------- helpers
def _smooth(e0, e1, x):
    if e1 == e0:
        return 1.0 if x >= e1 else 0.0
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def _seg_dist(p, a, b):
    ab = b - a
    t = max(0.0, min(1.0, (p - a).dot(ab) / ab.length_squared))
    return (p - (a + ab * t)).length, t


def _mode(obj, mode):
    common.select_only(obj)
    bpy.ops.object.mode_set(mode=mode)


class Weights:
    """Plain-python copy of the body's vertex weights (group name -> weight per vertex)."""

    def __init__(self, body):
        self.body = body
        self.names = {g.index: g.name for g in body.vertex_groups}
        self.w = [{self.names[g.group]: g.weight for g in v.groups} for v in body.data.vertices]

    def write(self, only=None, groups=None):
        body = self.body
        for name in groups or ():
            if body.vertex_groups.get(name) is None:
                body.vertex_groups.new(name=name)
        vg = {g.name: g for g in body.vertex_groups}
        idx = range(len(self.w)) if only is None else sorted(only)
        names = {g.index: g.name for g in body.vertex_groups}
        for i in idx:
            old = [names[g.group] for g in body.data.vertices[i].groups]
            for name in old:
                if name not in self.w[i]:
                    vg[name].remove([i])
            for name, wt in self.w[i].items():
                if wt <= 0.0:
                    if name in vg:
                        vg[name].remove([i])
                else:
                    vg[name].add([i], wt, "REPLACE")


# ----------------------------------------------------------------------------- 1. rolls
def _nail_dorsal(body, rig):
    """Per finger (side, finger) -> unit vector from the distal bone towards its nail (dorsal)."""
    me = body.data
    gi = body.vertex_groups["fingernails"].index
    mw = rig.matrix_world
    segs = {}
    for side, _, _ in (("Left", 0, 0), ("Right", 0, 0)):
        for f in FINGERS:
            b = rig.data.bones[mb(side, f"Hand{f}3")]
            segs[(side, f)] = (mw @ b.head_local, mw @ b.tail_local)
    acc = {k: [Vector(), 0] for k in segs}
    for v in me.vertices:
        if not any(g.group == gi and g.weight > 0.0 for g in v.groups):
            continue
        p = body.matrix_world @ v.co
        best = min(segs, key=lambda k: _seg_dist(p, *segs[k])[0])
        a, b = segs[best]
        ab = (b - a).normalized()
        off = (p - a) - ab * (p - a).dot(ab)
        acc[best][0] += off
        acc[best][1] += 1
    return {k: (v / n).normalized() for k, (v, n) in acc.items() if n}


def reroll(ctx):
    rig, body = ctx["rig"], ctx["body"]
    dorsal = _nail_dorsal(body, rig)
    _mode(rig, "EDIT")
    eb = rig.data.edit_bones
    inv = rig.matrix_world.inverted().to_3x3()
    for side, _, _ in (("L", 0, 0),):
        pass
    for s, side, _ in SIDES:
        palm = Vector()
        for f in FINGERS:
            volar = -(inv @ dorsal[(side, f)]).normalized()
            if f != "Thumb":
                palm += volar
            for i in (1, 2, 3):
                bone = eb[mb(side, f"Hand{f}{i}")]
                bone.align_roll(volar)
        bone = eb[mb(side, "Hand")]
        bone.align_roll(palm.normalized())
    bpy.ops.object.mode_set(mode="OBJECT")


# ----------------------------------------------------------------------------- 2. twist bones
def add_twist_bones(ctx):
    rig = ctx["rig"]
    _mode(rig, "EDIT")
    eb = rig.data.edit_bones
    info = {}
    for s, side, _ in SIDES:
        fa = eb[mb(side, "ForeArm")]
        for base, at, factor in fa_twists():
            b = eb.get(fa_twist(s, base)) or eb.new(fa_twist(s, base))
            b.head = fa.head.lerp(fa.tail, at)
            b.tail = fa.head.lerp(fa.tail, min(1.0, at + 0.3))
            b.align_roll(fa.z_axis)
            b.parent = fa
            b.use_connect = False
            b.use_deform = True
            info[b.name] = {"parent": fa.name, "head": tuple(round(x, 4) for x in b.head),
                            "tail": tuple(round(x, 4) for x in b.tail), "source": mb(side, "Hand"),
                            "factor": round(factor, 6), "axis": "Y"}
        ua = eb[mb(side, "Arm")]
        b = eb.get(ua_twist(s)) or eb.new(ua_twist(s))
        b.head = ua.head.copy()
        b.tail = ua.head.lerp(ua.tail, 0.5)
        b.align_roll(ua.z_axis)
        b.parent = ua
        b.use_connect = False
        b.use_deform = True
        info[b.name] = {"parent": ua.name, "head": tuple(round(x, 4) for x in b.head),
                        "tail": tuple(round(x, 4) for x in b.tail), "source": ua.name,
                        "factor": TWIST_FACTOR_UPPERARM, "axis": "Y"}
    bpy.ops.object.mode_set(mode="OBJECT")
    ctx["twist_bones"] = info
    return info


# ----------------------------------------------------------------------------- 3. weights
def _bone_seg(rig, name):
    b = rig.data.bones[name]
    mw = rig.matrix_world
    return mw @ b.head_local, mw @ b.tail_local


def weights_twist(ctx, W):
    """Split ForeArm -> ForeArm/forearm_twist and Arm -> upperarm_twist/Arm along the limb axis."""
    rig, body = ctx["rig"], ctx["body"]
    touched = set()
    for s, side, _ in SIDES:
        fa_h, fa_t = _bone_seg(rig, mb(side, "ForeArm"))
        ua_h, ua_t = _bone_seg(rig, mb(side, "Arm"))
        fa_d, ua_d = fa_t - fa_h, ua_t - ua_h
        FA, UA = mb(side, "ForeArm"), mb(side, "Arm")
        for i, v in enumerate(body.data.vertices):
            w = W.w[i]
            p = body.matrix_world @ v.co
            if w.get(FA, 0.0) > 0.0:
                t = (p - fa_h).dot(fa_d) / fa_d.length_squared
                chain = [(FA, 0.0)] + [(fa_twist(s, b), f) for b, _, f in fa_twists()]
                g = chain[-1][1] * _smooth(*fa_ramp(), t)     # wanted twist fraction of this vertex
                wf = w[FA]
                w[FA] = 0.0
                for k in range(len(chain) - 1):
                    (n0, f0), (n1, f1) = chain[k], chain[k + 1]
                    if f0 <= g <= f1:
                        a = (g - f0) / (f1 - f0)
                        w[n0] = w.get(n0, 0.0) + wf * (1 - a)
                        w[n1] = w.get(n1, 0.0) + wf * a
                        break
                touched.add(i)
            if w.get(UA, 0.0) > 0.0:
                t = (p - ua_h).dot(ua_d) / ua_d.length_squared
                k = 1.0 - _smooth(0.2, 0.75, t)
                wu = w[UA]
                w[UA] = wu * (1 - k)
                w[ua_twist(s)] = wu * k
                touched.add(i)
    return touched


THUMB_R = (0.010, 0.024)      # Thumb1 full weight within r_in of the metacarpal axis, 0 beyond r_out


def weights_thumb(ctx, W):
    """Rebuild Hand <-> Thumb1 split: the MPFB thumb metacarpal skin only follows Thumb1 at ~50 %,
    which folds the thenar/web when the thumb opposes. Thumb1 share now falls off radially from the
    metacarpal axis (smoothstep) and fades in from the CMC, keeping Thumb2/3 and finger weights."""
    rig, body = ctx["rig"], ctx["body"]
    touched = set()
    for s, side, _ in SIDES:
        T1, T2, T3 = (mb(side, f"HandThumb{k}") for k in (1, 2, 3))
        a, b = _bone_seg(rig, T1)
        d = b - a
        for i, v in enumerate(body.data.vertices):
            w = W.w[i]
            if sum(x for n, x in w.items() if "Hand" in n) <= 0.0:
                continue
            p = body.matrix_world @ v.co
            t = (p - a).dot(d) / d.length_squared
            if t < -0.4 or t > 1.25:
                continue
            q = a + d * max(0.0, min(1.0, t))
            r = (p - q).length
            k = (1.0 - _smooth(THUMB_R[0], THUMB_R[1], r)) * _smooth(-0.3, 0.35, t)
            fixed = w.get(T2, 0.0) + w.get(T3, 0.0)
            new1 = max(w.get(T1, 0.0), k * (1.0 - fixed))
            rest = [n for n, x in w.items() if x > 0 and n not in (T1, T2, T3)
                    and (n.startswith("mixamorig:") or n.startswith("masha:"))]
            tot = sum(w[n] for n in rest)
            room = max(0.0, 1.0 - fixed - new1)
            if tot > 0:
                for n in rest:
                    w[n] = w[n] * room / tot
            else:
                new1 = 1.0 - fixed
            w[T1] = new1
            touched.add(i)
    return touched


MCP_RAMP = (-0.35, 0.2)   # Hand -> Finger1 transition, in units of the proximal bone length (0 = MCP)


def weights_mcp(ctx, W):
    """MPFB leaves 20-60 % Hand weight on the proximal phalanx (noisy, non-monotonic), so MCP
    flexion bends the finger mid-phalanx and reads as a claw. Re-split Hand+Finger1 of each vertex
    with a monotonic smoothstep centred just before the knuckle (~3 edge loops wide)."""
    rig, body = ctx["rig"], ctx["body"]
    touched = set()
    for s, side, _ in SIDES:
        H = mb(side, "Hand")
        segs = {f: _bone_seg(rig, mb(side, f"Hand{f}1")) for f in LONG_FINGERS}
        for i, v in enumerate(body.data.vertices):
            w = W.w[i]
            if w.get(H, 0.0) <= 0.0 and not any(w.get(mb(side, f"Hand{f}1"), 0) > 0 for f in LONG_FINGERS):
                continue
            p = body.matrix_world @ v.co
            best = None
            for f, (a, b) in segs.items():
                d = b - a
                t = (p - a).dot(d) / d.length_squared
                q = a + d * max(-0.6, min(1.0, t))
                r = (p - q).length
                if best is None or r < best[0]:
                    best = (r, f, t)
            r, f, t = best
            if r > 0.014 or t < -0.6 or t > 1.0:
                continue
            F1 = mb(side, f"Hand{f}1")
            S = w.get(H, 0.0) + w.get(F1, 0.0)
            if S <= 0.0:
                continue
            k = _smooth(MCP_RAMP[0], MCP_RAMP[1], t)
            w[F1] = S * k
            w[H] = S * (1.0 - k)
            touched.add(i)
    return touched


def limit_normalize(ctx, W, only=None):
    rig = ctx["rig"]
    deform = {b.name for b in rig.data.bones if b.use_deform}
    idx = range(len(W.w)) if only is None else only
    for i in idx:
        w = W.w[i]
        d = sorted(((n, x) for n, x in w.items() if n in deform and x > 0.0), key=lambda kv: -kv[1])
        keep = [(n, x) for n, x in d[:MAX_INF] if x >= 0.004]
        tot = sum(x for _, x in keep)
        for n, _ in d:
            w[n] = 0.0
        if tot > 0:
            for n, x in keep:
                w[n] = x / tot


# ----------------------------------------------------------------------------- 4. poses
# Anatomical values per joint: (flex, spread, roll) in degrees.  flex > 0 curls towards the palm,
# spread > 0 moves the finger away from the middle finger (thumb: away from the index = abduction),
# roll = rotation about the bone. Hand: (flexion(+)/extension(-), radial(+)/ulnar(-) dev, pronation(+)).
POSES = {
    # ANATOMICAL values: flex 0 = straight joint (PIP/DIP rest curl subtracted, MCP taken from rest),
    # spread = absolute angle to the middle finger (index/ring/pinky, rest splay subtracted).
    # Relaxed cascade after reference hands: flexion shared by MCP/PIP, growing index -> pinky,
    # fingertips on a soft arc, fingers almost touching.
    "relaxed": {
        "Hand": (-12, 0, 0),
        "Index": [(17, 2, 0), (22, 0, 0), (9, 0, 0)],
        "Middle": [(25, 0, 0), (30, 0, 0), (10, 0, 0)],
        "Ring": [(30, 3, 0), (35, 0, 0), (12, 0, 0)],
        "Pinky": [(35, 8, 0), (40, 0, 0), (15, 0, 0)],
        "Thumb": [(14, -10, 10), (8, 0, 0), (12, 0, 0)],
    },
    "wave": {          # open hand, fingers almost together
        "Hand": (-8, 0, 0),
        "Index": [(4, 2, 0), (5, 0, 0), (3, 0, 0)],
        "Middle": [(4, 0, 0), (5, 0, 0), (3, 0, 0)],
        "Ring": [(5, 2, 0), (6, 0, 0), (3, 0, 0)],
        "Pinky": [(6, 4, 0), (7, 0, 0), (4, 0, 0)],
        "Thumb": [(2, 6, 0), (4, 0, 0), (4, 0, 0)],
    },
    "point": {         # index extended, others curled, thumb pad on the side of the middle finger
        "Hand": (-6, 0, 0),
        "Index": [(2, 1, 0), (4, 0, 0), (2, 0, 0)],
        "Middle": [(80, 0, 0), (95, 0, 0), (55, 0, 0)],
        "Ring": [(85, 0, 0), (95, 0, 0), (55, 0, 0)],
        "Pinky": [(88, 2, 0), (92, 0, 0), (50, 0, 0)],
        "Thumb": "ik:overmiddle",
    },
    "explain": {       # open, slightly cupped palm, fingers together
        "Hand": (-15, 0, 0),
        "Index": [(6, 2, 0), (9, 0, 0), (4, 0, 0)],
        "Middle": [(9, 0, 0), (11, 0, 0), (5, 0, 0)],
        "Ring": [(12, 1, 0), (13, 0, 0), (6, 0, 0)],
        "Pinky": [(15, 3, 0), (15, 0, 0), (7, 0, 0)],
        "Thumb": [(6, 2, 4), (6, 0, 0), (8, 0, 0)],
    },
    "fist": {          # soft fist
        "Hand": (0, 0, 0),
        "Index": [(78, 0, 0), (92, 0, 0), (55, 0, 0)],
        "Middle": [(82, 0, 0), (95, 0, 0), (55, 0, 0)],
        "Ring": [(85, 0, 0), (95, 0, 0), (55, 0, 0)],
        "Pinky": [(88, 2, 0), (92, 0, 0), (50, 0, 0)],
        "Thumb": "ik:over",
    },
    "thinking_chin": {  # loose curl, a bit more than relaxed, fingers together
        "Hand": (-10, 0, 0),
        "Index": [(35, 1, 0), (35, 0, 0), (13, 0, 0)],
        "Middle": [(40, 0, 0), (40, 0, 0), (15, 0, 0)],
        "Ring": [(45, 3, 0), (42, 0, 0), (15, 0, 0)],
        "Pinky": [(50, 8, 0), (45, 0, 0), (16, 0, 0)],
        "Thumb": [(16, -8, 10), (10, 0, 0), (14, 0, 0)],
    },
}


def ax(bone, k):
    """Armature-space axis k (0=X,1=Y,2=Z) of a rest Bone (Bone.x_axis etc. are parent-relative!)."""
    return bone.matrix_local.to_3x3().col[k].normalized()


def _spread_sign(rig, side, finger):
    """+1 if a +Z local rotation moves this finger away from the middle finger (thumb: from index)."""
    b = rig.data.bones[mb(side, f"Hand{finger}1")]
    ref = rig.data.bones[mb(side, "HandMiddle1" if finger != "Thumb" else "HandIndex1")]
    if finger == "Middle":
        ref = rig.data.bones[mb(side, "HandIndex1")]
    y = ax(b, 1)
    z = ax(b, 2)
    moved = (Quaternion(z, 0.1) @ y) - y          # tip displacement direction for +Z
    away = b.head_local - ref.head_local
    away -= y * away.dot(y)
    sgn = 1.0 if moved.dot(away) > 0 else -1.0
    if finger == "Middle":
        sgn = -sgn                                 # middle: + spread goes towards the radial side
    return sgn


def _hand_sign(rig, side):
    """+1 if a +Z local rotation of Hand is radial deviation (towards the thumb)."""
    b = rig.data.bones[mb(side, "Hand")]
    th = rig.data.bones[mb(side, "HandThumb1")].head_local - b.head_local
    moved = (Quaternion(ax(b, 2), 0.1) @ ax(b, 1)) - ax(b, 1)
    return 1.0 if moved.dot(th) > 0 else -1.0


def rest_flex(rig, side, finger, i):
    """Flexion (deg, + towards palm) that the rest pose already has at this joint.
    Reference for the MCP / thumb CMC is the metacarpal line wrist -> finger base."""
    b = rig.data.bones[mb(side, f"Hand{finger}{i}")]
    if i == 1:
        ref = (b.head_local - rig.data.bones[mb(side, "Hand")].head_local).normalized()
    else:
        ref = ax(b.parent, 1)
    x, y = ax(b, 0), ax(b, 1)
    r = (ref - x * ref.dot(x)).normalized()
    ang = math.degrees(r.angle(y))
    # +X rotation moves y towards +z(volar): flexed already if y leans volar relative to ref
    return ang if (y - r).dot(ax(b, 2)) > 0 else -ang


def rest_spread(rig, side, finger):
    """Angle (deg) between this finger's proximal bone and the middle finger's, in the palm plane,
    positive = away from the middle finger (index/ring/pinky)."""
    B = rig.data.bones
    hz = ax(B[mb(side, "Hand")], 2)
    y = ax(B[mb(side, f"Hand{finger}1")], 1)
    ym = ax(B[mb(side, "HandMiddle1")], 1)
    y = (y - hz * y.dot(hz)).normalized()
    ym = (ym - hz * ym.dot(hz)).normalized()
    away = B[mb(side, f"Hand{finger}1")].head_local - B[mb(side, "HandMiddle1")].head_local
    ang = math.degrees(y.angle(ym))
    return ang if (y - ym).dot(away) > 0 else -ang


def resolve_pose(rig, spec):
    out = {}
    for _, side, _ in SIDES:
        hs = _hand_sign(rig, side)
        fx, dev, pro = spec.get("Hand", (0, 0, 0))
        # pronation sign: +Y roll about the hand axis; mirrored side needs the opposite sign
        ps = 1.0 if side == "Left" else -1.0
        out[mb(side, "Hand")] = (float(fx), float(pro * ps), float(dev * hs))
        for f in FINGERS:
            sg = _spread_sign(rig, side, f)
            rs = 1.0 if side == "Left" else -1.0
            if isinstance(spec[f], str):
                continue
            for i, (flex, spread, roll) in enumerate(spec[f], start=1):
                # PIP/DIP: the rest pose already carries 5-13 deg of curl -> subtract it. MCP: the
                # wrist->knuckle line is not the metacarpal axis (palm arch), so measuring a rest
                # MCP curl from it double counts; the MPFB rest MCP is visually straight -> no comp.
                flex = flex - (REST_COMP * rest_flex(rig, side, f, i) if i > 1 else 0.0)
                if i == 1 and f in ("Index", "Ring", "Pinky"):
                    spread = spread - rest_spread(rig, side, f)   # spread is absolute vs the middle finger
                out[mb(side, f"Hand{f}{i}")] = (float(flex), float(roll * rs), float(spread * sg))
        if isinstance(spec["Thumb"], str):
            sol, c = solve_thumb(rig, side, out, thumb_target(rig, side, spec["Thumb"].split(":")[1]))
            print(f"thumb IK {side} {spec['Thumb']} cost={c:.4f} {sol}")
            out.update(sol)
    return {k: tuple(round(x, 2) for x in v) for k, v in out.items()}


# ---- thumb placement by IK (analytic FK on rest matrices, derivative-free search) ---------------
from mathutils import Euler  # noqa: E402


def _rotm(e):
    return Euler([math.radians(a) for a in e], "XYZ").to_matrix().to_4x4()


def fk(rig, side, pose):
    """Armature-space rest+pose matrices of Hand and every finger bone (forearm unposed)."""
    bones = rig.data.bones
    out = {}
    hb = bones[mb(side, "Hand")]
    out[hb.name] = hb.matrix_local @ _rotm(pose.get(hb.name, (0, 0, 0)))
    for f in FINGERS:
        par = hb
        for i in (1, 2, 3):
            b = bones[mb(side, f"Hand{f}{i}")]
            out[b.name] = out[par.name] @ (par.matrix_local.inverted() @ b.matrix_local) @ _rotm(pose.get(b.name, (0, 0, 0)))
            par = b
    return out


def _pt(M, bone, t, dz=0.0):
    """Point at fraction t along the bone, offset dz along its (posed) Z (volar)."""
    return (M @ Vector((0, bone.length * t, 0)).to_4d()).to_3d() + M.to_3x3().col[2].normalized() * dz


THUMB_LIMITS = [(-30, 70), (-20, 80), (-45, 45), (-10, 75), (-15, 85)]   # t1 x,y,z  t2 x  t3 x


def solve_thumb(rig, side, pose, target_fn, reg=None):
    """Find thumb1 (x,y,z), thumb2 x, thumb3 x minimizing target_fn(fk) + regularisation."""
    names = [mb(side, f"HandThumb{i}") for i in (1, 2, 3)]
    reg = reg or {}

    def params_to_pose(p):
        q = dict(pose)
        q[names[0]] = (p[0], p[1], p[2])
        q[names[1]] = (p[3], 0.0, 0.0)
        q[names[2]] = (p[4], 0.0, 0.0)
        return q

    def cost(p):
        c = 0.0
        for v, (lo, hi) in zip(p, THUMB_LIMITS):
            if v < lo:
                c += ((lo - v) * 0.01) ** 2
            if v > hi:
                c += ((v - hi) * 0.01) ** 2
        M = fk(rig, side, params_to_pose(p))
        c += target_fn(M)
        # keep the thumb joints moving together (natural coupling) and avoid wild roll
        c += reg.get("smooth", 2e-6) * ((p[3] - p[4] * 0.7) ** 2 + p[2] ** 2 * 0.3)
        return c

    best = [0.0, 0.0, 0.0, 10.0, 10.0]
    bc = cost(best)
    step = 16.0
    while step > 0.2:
        improved = False
        for k in range(5):
            for d in (step, -step):
                p = list(best)
                p[k] += d
                c = cost(p)
                if c < bc:
                    best, bc, improved = p, c, True
        if not improved:
            step *= 0.5
    return {n: tuple(round(v, 2) for v in r) for n, r in
            zip(names, ((best[0], best[1], best[2]), (best[3], 0.0, 0.0), (best[4], 0.0, 0.0)))}, bc


def thumb_target(rig, side, kind):
    """Objective builders for the thumb in 'fist'/'point' (pad over index+middle middle phalanges)
    and 'oppose' (pad to ring-finger pad)."""
    B = rig.data.bones
    t3 = B[mb(side, "HandThumb3")]
    I2, M2, R3 = B[mb(side, "HandIndex2")], B[mb(side, "HandMiddle2")], B[mb(side, "HandRing3")]

    def f(M):
        pad = _pt(M[t3.name], t3, 0.55, 0.004)
        if kind == "over":
            tgt = (_pt(M[I2.name], I2, 0.55, -0.017) + _pt(M[M2.name], M2, 0.35, -0.017)) * 0.5
            n = -(M[I2.name].to_3x3().col[2].normalized())
        elif kind == "overmiddle":
            # pad resting on the RADIAL SIDE of the middle finger's middle phalanx (less opposed)
            m2 = M[M2.name]
            c = _pt(m2, M2, 0.5)
            y2 = m2.to_3x3().col[1].normalized()
            side_v = _pt(M[I2.name], I2, 0.5) - c
            side_v = (side_v - y2 * side_v.dot(y2)).normalized()
            tgt = c + side_v * 0.011 - m2.to_3x3().col[2].normalized() * 0.006
            n = -side_v
        elif kind == "oppose":
            tgt = _pt(M[R3.name], R3, 0.6, 0.016)
            n = M[R3.name].to_3x3().col[2].normalized()
        else:
            return 0.0
        pad_n = M[t3.name].to_3x3().col[2].normalized()
        return (pad - tgt).length_squared * 1e4 + 0.02 * (1 + pad_n.dot(n)) ** 2
    return f


def build_poses(ctx):
    rig = ctx["rig"]
    for _, side, _ in SIDES:
        print("REST SPREAD", side, {f: round(rest_spread(rig, side, f), 1) for f in ("Index", "Ring", "Pinky")})
        print("REST FLEX", side, {f: [round(rest_flex(rig, side, f, i), 1) for i in (1, 2, 3)] for f in FINGERS})
    poses = {name: resolve_pose(rig, spec) for name, spec in POSES.items()}
    ctx["hand_poses"].update(poses)
    return poses


# ----------------------------------------------------------------------------- 5. nails mask
def nails_mask(ctx, path, size=2048):
    body = ctx["body"]
    me = body.data
    gi = body.vertex_groups["fingernails"].index
    nail = {v.index for v in me.vertices if any(g.group == gi and g.weight > 0 for g in v.groups)}
    uv = me.uv_layers.active.data
    tris = []
    for p in me.polygons:
        if sum(1 for x in p.vertices if x in nail) == len(p.vertices):
            loops = [uv[li].uv.copy() for li in p.loop_indices]
            for k in range(1, len(loops) - 1):
                tris.append((loops[0], loops[k], loops[k + 1]))
    import array
    buf = array.array("f", [0.0]) * (size * size)
    for a, b, c in tris:
        xs = [a.x * size, b.x * size, c.x * size]
        ys = [a.y * size, b.y * size, c.y * size]
        x0, x1 = int(max(0, min(xs) - 2)), int(min(size - 1, max(xs) + 2))
        y0, y1 = int(max(0, min(ys) - 2)), int(min(size - 1, max(ys) + 2))
        den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(den) < 1e-9:
            continue
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px, py = x + 0.5, y + 0.5
                l1 = ((ys[1] - ys[2]) * (px - xs[2]) + (xs[2] - xs[1]) * (py - ys[2])) / den
                l2 = ((ys[2] - ys[0]) * (px - xs[2]) + (xs[0] - xs[2]) * (py - ys[2])) / den
                l3 = 1 - l1 - l2
                m = min(l1, l2, l3)
                if m >= -0.02:
                    buf[y * size + x] = 1.0
    img = bpy.data.images.new("nails_mask", size, size, alpha=False, float_buffer=False)
    px = array.array("f", [0.0]) * (size * size * 4)
    for i, val in enumerate(buf):
        if val:
            px[i * 4] = px[i * 4 + 1] = px[i * 4 + 2] = val
        px[i * 4 + 3] = 1.0
    img.pixels.foreach_set(px)
    img.filepath_raw = path
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)
    return len(tris)


# ----------------------------------------------------------------------------- build
def build(ctx):
    args = common.parse_args({"hands_mode": "full"})
    mode = args["hands_mode"]
    rig, body = ctx["rig"], ctx["body"]
    reroll(ctx)
    if mode != "before":
        add_twist_bones(ctx)
        W = Weights(body)
        touched = weights_twist(ctx, W)
        if common.parse_args({"hands_thumb": "1"})["hands_thumb"] == "1":
            touched |= weights_thumb(ctx, W)
        if common.parse_args({"hands_mcp": "1"})["hands_mcp"] == "1":
            touched |= weights_mcp(ctx, W)
        limit_normalize(ctx, W)
        W.write(groups=[fa_twist(s, b) for s, _, _ in SIDES for b, _, _ in fa_twists()] + [ua_twist(s) for s, _, _ in SIDES])
    poses = build_poses(ctx)
    out = ctx["out_dir"]
    data = {
        "conventions": "Euler XYZ degrees in each bone's local space (glTF: rest * euler). Hand & finger "
                       "bones re-rolled: +X = flexion towards palm, +Z = volar normal, Y along bone.",
        "twist_rule": {
            "forearm": "for each forearm twist bone: local = rest * axisAngle(Y, factor * twistY(R0 * q_hand * R0^-1))",
            "upperarm": "upperarm_twist.local = rest * axisAngle(Y, -0.5 * twistY(q_arm))",
            "twistY(q)": "2*atan2(q.y, q.w)",
            "hand_rest_in_forearm": {},
            "bones": ctx.get("twist_bones", {}),
        },
        "poses": poses,
    }
    for s, side, _ in SIDES:
        fa = rig.data.bones[mb(side, "ForeArm")]
        hb = rig.data.bones[mb(side, "Hand")]
        r0 = (fa.matrix_local.to_3x3().inverted() @ hb.matrix_local.to_3x3()).to_quaternion()
        data["twist_rule"]["hand_rest_in_forearm"][s] = [round(x, 6) for x in r0]
    with open(os.path.join(out, "hand_poses.json"), "w") as fh:
        json.dump(data, fh, indent=1)
    tex = common.ensure_dir(os.path.join(out, "textures"))
    ntris = nails_mask(ctx, os.path.join(tex, "nails_mask.png"))
    print(f"hands: poses={list(poses)} nails_tris={ntris}")
    weight_report(ctx)


def weight_report(ctx):
    rig, body = ctx["rig"], ctx["body"]
    deform = {b.name for b in rig.data.bones if b.use_deform}
    names = {g.index: g.name for g in body.vertex_groups}
    helper = body.vertex_groups.get("HelperGeometry")
    mx, bad = 0, 0
    hand_v = set()
    for v in body.data.vertices:
        ws = [(names[g.group], g.weight) for g in v.groups if names[g.group] in deform and g.weight > 0]
        mx = max(mx, len(ws))
        tot = sum(w for _, w in ws)
        if ws and abs(tot - 1.0) > 1e-3:
            bad += 1
        if helper and any(g.group == helper.index for g in v.groups):
            continue
        if sum(w for n, w in ws if n.startswith("mixamorig:LeftHand")) > 0.01:
            hand_v.add(v.index)
    tris = sum(len(p.vertices) - 2 for p in body.data.polygons if all(i in hand_v for i in p.vertices))
    print(f"WEIGHTS max_influences={mx} unnormalized={bad} left_hand_region verts={len(hand_v)} tris={tris} "
          f"bones={len(rig.data.bones)}")
    ctx["hands_report"] = {"max_influences": mx, "unnormalized": bad, "hand_tris_per_side": tris}


# ----------------------------------------------------------------------------- QA
def apply_twist_rule(rig):
    for s, side, _ in SIDES:
        fa = rig.data.bones[mb(side, "ForeArm")]
        hb = rig.pose.bones[mb(side, "Hand")]
        r0 = (fa.matrix_local.to_3x3().inverted() @ hb.bone.matrix_local.to_3x3()).to_quaternion()
        q = hb.rotation_quaternion if hb.rotation_mode == "QUATERNION" else hb.rotation_euler.to_quaternion()
        qf = r0 @ q @ r0.inverted()
        a = 2 * math.atan2(qf.y, qf.w)
        for base, _, factor in fa_twists():
            tb = rig.pose.bones.get(fa_twist(s, base))
            if tb:
                tb.rotation_mode = "QUATERNION"
                tb.rotation_quaternion = Quaternion((0, 1, 0), factor * a)
        ub = rig.pose.bones.get(ua_twist(s))
        if ub:
            pa = rig.pose.bones[mb(side, "Arm")]
            q = pa.rotation_quaternion if pa.rotation_mode == "QUATERNION" else pa.rotation_euler.to_quaternion()
            a = 2 * math.atan2(q.y, q.w)
            ub.rotation_mode = "QUATERNION"
            ub.rotation_quaternion = Quaternion((0, 1, 0), TWIST_FACTOR_UPPERARM * a)


def reset_pose(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.rotation_euler = (0, 0, 0)
        pb.location = (0, 0, 0)
        pb.scale = (1, 1, 1)


def set_local_euler(rig, pose):
    for name, (x, y, z) in pose.items():
        pb = rig.pose.bones[name]
        pb.rotation_mode = "QUATERNION"
        q = Quaternion((1, 0, 0), 0)
        from mathutils import Euler
        pb.rotation_quaternion = Euler((math.radians(x), math.radians(y), math.radians(z)), "XYZ").to_quaternion()


def rot_world(rig, name, axis_world, deg):
    """Compose a rotation about a world axis onto a pose bone (parents already posed)."""
    bpy.context.view_layer.update()
    pb = rig.pose.bones[name]
    pb.rotation_mode = "QUATERNION"
    m3 = (rig.matrix_world @ pb.matrix).to_3x3().normalized()
    ax = (m3.inverted() @ Vector(axis_world)).normalized()
    pb.rotation_quaternion = pb.rotation_quaternion @ Quaternion(ax, math.radians(deg))


def rot_local(rig, name, axis, deg):
    pb = rig.pose.bones[name]
    pb.rotation_mode = "QUATERNION"
    pb.rotation_quaternion = pb.rotation_quaternion @ Quaternion(Vector(axis), math.radians(deg))


def world_pt(rig, name, tail=False):
    bpy.context.view_layer.update()
    pb = rig.pose.bones[name]
    return rig.matrix_world @ (pb.tail if tail else pb.head)


def _mask_arm(ctx, on):
    """QA helper: show only the arms (Mask modifier on a temp vertex group)."""
    body = ctx["body"]
    mod = body.modifiers.get("qa_arm_mask")
    if not on:
        if mod:
            body.modifiers.remove(mod)
        return
    if body.vertex_groups.get("qa_arm") is None:
        g = body.vertex_groups.new(name="qa_arm")
        names = {grp.index: grp.name for grp in body.vertex_groups}
        idx = []
        for v in body.data.vertices:
            s = sum(gg.weight for gg in v.groups if any(k in names[gg.group] for k in
                    ("ForeArm", "Hand", "forearm_twist", "LeftArm", "RightArm", "upperarm_twist")))
            if s > 0.02:
                idx.append(v.index)
        g.add(idx, 1.0, "REPLACE")
    if mod is None:
        mod = body.modifiers.new("qa_arm_mask", "MASK")
        mod.vertex_group = "qa_arm"
    for o in bpy.data.objects:
        if o.type == "MESH" and o is not body:
            o.hide_render = True


def _hand_frame(rig, side):
    bpy.context.view_layer.update()
    pb = rig.pose.bones[mb(side, "Hand")]
    m = rig.matrix_world @ pb.matrix
    pbs = rig.pose.bones
    c = rig.matrix_world @ ((pb.head + pbs[mb(side, "HandMiddle1")].head * 2 + pbs[mb(side, "HandMiddle2")].tail) * 0.25)
    return c, m.to_3x3().normalized()


def hand_shots(rig, side, tag, dist=0.36, lens=90, views=("dorsal", "volar", "radial", "ulnar")):
    c, m = _hand_frame(rig, side)
    x, y, z = m.col[0], m.col[1], m.col[2]
    dirs = {"dorsal": -z + 0.25 * x, "volar": z + 0.25 * x, "radial": None, "ulnar": None, "tip": y}
    th = world_pt(rig, mb(side, "HandThumb1")) - world_pt(rig, mb(side, "Hand"))
    rad = (th - y * th.dot(y) - z * th.dot(z)).normalized()
    dirs["radial"] = rad + 0.35 * -z
    dirs["ulnar"] = -rad + 0.35 * -z
    shots = []
    for v in views:
        d = dirs[v].normalized()
        shots.append((f"{tag}_{v}", tuple(c + d * dist), tuple(c), lens))
    return shots


def wrist_shots(rig, side, tag, dist=0.5, lens=90, views=("dorsal", "volar", "radial", "ulnar")):
    """Cameras fixed in the (posed) forearm frame, aimed at the wrist: twist / candy-wrapper check."""
    bpy.context.view_layer.update()
    mw = rig.matrix_world.to_3x3()
    fa = rig.pose.bones[mb(side, "ForeArm")]
    fm = (rig.matrix_world @ fa.matrix).to_3x3().normalized()
    rest_fa = fa.bone.matrix_local.to_3x3().normalized()
    hand = rig.data.bones[mb(side, "Hand")]
    # hand rest volar & radial expressed in forearm-local, then posed with the forearm
    vol_l = rest_fa.inverted() @ ax(hand, 2)
    th_l = rest_fa.inverted() @ (rig.data.bones[mb(side, "HandThumb1")].head_local - hand.head_local)
    fax = fm.col[1]
    vol = (fm @ vol_l)
    vol = (vol - fax * vol.dot(fax)).normalized()
    rad = (fm @ th_l)
    rad = (rad - fax * rad.dot(fax) - vol * rad.dot(vol)).normalized()
    c = rig.matrix_world @ fa.tail
    c = c - fax * 0.02
    dirs = {"dorsal": -vol, "volar": vol, "radial": rad, "ulnar": -rad}
    return [(f"{tag}_{v}", tuple(c + dirs[v] * dist), tuple(c), lens) for v in views]


def _qa_lights():
    """Own camera + shadowless lights (Eevee shadow-map acne streaks on thin fingers hide real defects)."""
    scene = bpy.context.scene
    if bpy.data.objects.get("qa_cam") is not None:
        return
    cam = bpy.data.objects.new("qa_cam", bpy.data.cameras.new("qa_cam"))
    cam.data.clip_start = 0.02
    scene.collection.objects.link(cam)
    for name, rot, energy in (("qa_key", (math.radians(40), 0, math.radians(30)), 3.2),
                              ("qa_fill", (math.radians(70), 0, math.radians(-120)), 1.4),
                              ("qa_under", (math.radians(150), 0, math.radians(200)), 1.0),
                              ("qa_rim", (math.radians(110), 0, math.radians(160)), 1.6)):
        light = bpy.data.objects.new(name, bpy.data.lights.new(name, "SUN"))
        light.data.energy = energy
        light.data.use_shadow = False
        light.rotation_euler = rot
        scene.collection.objects.link(light)


def _render(ctx, sub, prefix, shots, res=(900, 900)):
    _qa_lights()
    return common.qa_renders(os.path.join(ctx["qa_dir"], sub), prefix, shots, res=res)


def stress_tests(ctx, sub, only_elbow=False):
    rig = ctx["rig"]
    side = "Left"
    FA, H = mb(side, "ForeArm"), mb(side, "Hand")
    _mask_arm(ctx, True)
    tests = [
        ("rest", []),
        ("pron70", [(H, (0, 1, 0), 70)]),
        ("sup70", [(H, (0, 1, 0), -70)]),
        ("flex60", [(H, (1, 0, 0), 60)]),
        ("ext60", [(H, (1, 0, 0), -60)]),
        ("dev20", [(H, (0, 0, 1), 20)]),
        ("devm20", [(H, (0, 0, 1), -20)]),
    ]
    for name, ops in ([] if only_elbow else tests):
        reset_pose(rig)
        for b, ax, deg in ops:
            rot_local(rig, b, ax, deg)
        apply_twist_rule(rig)
        if name in ("rest", "pron70", "sup70"):
            views = ("dorsal", "volar", "radial", "ulnar")
        elif name.startswith(("flex", "ext")):
            views = ("radial", "dorsal")
        else:
            views = ("dorsal", "volar")
        _render(ctx, sub, f"stress_{name}", wrist_shots(rig, side, "w", views=views))
    # fist and thumb opposition
    for name, pose in (() if only_elbow else (("fist", "fist_hard"), ("oppose", "oppose"))):
        reset_pose(rig)
        set_local_euler(rig, stress_pose(rig, pose))
        apply_twist_rule(rig)
        _render(ctx, sub, f"stress_{name}", hand_shots(rig, side, "c", views=("dorsal", "volar", "radial", "ulnar")))
    # elbow 120 + pronation, wide arm shot
    reset_pose(rig)
    elbow_flex(rig, side, 120)
    rot_local(rig, H, (0, 1, 0), 60)
    apply_twist_rule(rig)
    e = world_pt(rig, FA)
    bpy.context.view_layer.update()
    pa, pf = rig.pose.bones[mb(side, "Arm")], rig.pose.bones[FA]
    hinge = (pa.tail - pa.head).normalized().cross((pf.tail - pf.head).normalized()).normalized()
    mid = (pa.tail - pa.head).normalized() * -1 + (pf.tail - pf.head).normalized()
    shots = [("side_a", tuple(e + hinge * 0.75), tuple(e + mid * 0.05), 60),
             ("side_b", tuple(e - hinge * 0.75), tuple(e + mid * 0.05), 60),
             ("inner", tuple(e + mid.normalized() * 0.6 + hinge * 0.2), tuple(e), 60),
             ("outer", tuple(e - mid.normalized() * 0.6 + hinge * 0.2), tuple(e), 60)]
    _render(ctx, sub, "stress_elbow120", shots)
    # upper arm twist
    reset_pose(rig)
    rot_local(rig, mb(side, "Arm"), (0, 1, 0), 60)
    apply_twist_rule(rig)
    e = world_pt(rig, mb(side, "Arm"))
    shots = [("wide_front", tuple(e + Vector((0.2, -0.9, 0.0))), tuple(e + Vector((0.08, 0, -0.1))), 60),
             ("wide_back", tuple(e + Vector((0.3, 0.9, 0.0))), tuple(e + Vector((0.08, 0, -0.1))), 60)]
    _render(ctx, sub, "stress_uatwist60", shots)
    reset_pose(rig)
    _mask_arm(ctx, False)


def elbow_flex(rig, side, total_deg):
    """Bend the elbow to total_deg flexion (angle between arm and forearm directions)."""
    bpy.context.view_layer.update()
    a = rig.pose.bones[mb(side, "Arm")]
    f = rig.pose.bones[mb(side, "ForeArm")]
    da = (a.tail - a.head).normalized()
    df = (f.tail - f.head).normalized()
    cur = math.degrees(da.angle(df))
    axis = da.cross(df).normalized()
    rot_world(rig, f.name, rig.matrix_world.to_3x3() @ axis, total_deg - cur)


def stress_pose(rig, name):
    if name == "fist_hard":
        spec = {"Hand": (0, 0, 0),
                "Index": [(85, 0, 0), (100, 0, 0), (60, 0, 0)],
                "Middle": [(88, 0, 0), (100, 0, 0), (60, 0, 0)],
                "Ring": [(90, 0, 0), (100, 0, 0), (60, 0, 0)],
                "Pinky": [(90, 0, 0), (95, 0, 0), (60, 0, 0)],
                "Thumb": "ik:over"}
    else:
        spec = {"Hand": (0, 0, 0),
                "Index": [(10, 0, 0), (15, 0, 0), (8, 0, 0)],
                "Middle": [(10, 0, 0), (15, 0, 0), (8, 0, 0)],
                "Ring": [(55, 0, 0), (50, 0, 0), (20, 0, 0)],
                "Pinky": [(60, 0, 0), (55, 0, 0), (25, 0, 0)],
                "Thumb": "ik:oppose"}
    return resolve_pose(rig, spec)


def _deformed(ctx):
    bpy.context.view_layer.update()
    body = ctx["body"]
    dg = bpy.context.evaluated_depsgraph_get()
    ev = body.evaluated_get(dg)
    me = ev.to_mesh()
    co = [body.matrix_world @ v.co for v in me.vertices]
    ev.to_mesh_clear()
    return co


def twist_metric(ctx, side="Left", bone="Hand", seg="ForeArm", angles=(-70, 70)):
    """Radius ratio (posed/rest) of the limb cross-section around the segment axis, per 10% bin.
    Rotating `bone` about its own Y is a pure twist, so ideal ratio = 1.0 everywhere."""
    rig, body = ctx["rig"], ctx["body"]
    mods = [m for m in body.modifiers if m.type == "MASK"]
    for m in mods:
        m.show_viewport = False
    reset_pose(rig)
    rest = _deformed(ctx)
    h, t = _bone_seg(rig, mb(side, seg))
    d = t - h
    L2 = d.length_squared
    dn = d.normalized()
    sel = []
    for i, p in enumerate(rest):
        u = (p - h).dot(d) / L2
        if -0.1 <= u <= 1.12:
            r = ((p - h) - dn * (p - h).dot(dn)).length
            if r < 0.07 and (bone != "Hand" or p.x * (1 if side == "Left" else -1) > 0.2):
                sel.append((i, u, r))
    res = {}
    for ang in angles:
        reset_pose(rig)
        rot_local(rig, mb(side, bone), (0, 1, 0), ang)
        apply_twist_rule(rig)
        co = _deformed(ctx)
        bins = {}
        for i, u, r0 in sel:
            p = co[i]
            r = ((p - h) - dn * (p - h).dot(dn)).length
            k = min(11, max(-1, int(u * 10)))
            bins.setdefault(k, []).append(r / max(r0, 1e-5))
        res[ang] = {k: (round(sum(v) / len(v), 3), round(min(v), 3)) for k, v in sorted(bins.items())}
        print(f"TWIST {side} {bone}/{seg} {ang:+d}: " + " ".join(f"{k}:{a:.3f}/{m:.2f}" for k, (a, m) in res[ang].items()))
    reset_pose(rig)
    for m in mods:
        m.show_viewport = True
    return res


_PALETTE = [(0.9, 0.1, 0.1), (0.1, 0.8, 0.1), (0.15, 0.3, 1.0), (1.0, 0.85, 0.0), (0.9, 0.1, 0.9),
            (0.0, 0.9, 0.9), (1.0, 0.5, 0.0), (0.5, 0.25, 0.9), (0.6, 0.6, 0.6), (0.3, 0.6, 0.3)]


def _heat(w):
    w = max(0.0, min(1.0, w))
    stops = [(0, (0.05, 0.05, 0.4)), (0.25, (0.0, 0.6, 1.0)), (0.5, (0.1, 0.9, 0.1)), (0.75, (1.0, 0.9, 0.0)), (1.0, (1.0, 0.1, 0.0))]
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        if w <= b:
            t = (w - a) / (b - a)
            return Vector(ca).lerp(Vector(cb), t)
    return Vector(stops[-1][1])


def weight_viz(ctx, sub, pose=None, tag="rest", only=None):
    """Blend colors of all influences (each bone a palette colour) -> reveals bleeding & gradients."""
    rig, body = ctx["rig"], ctx["body"]
    me = body.data
    names = {g.index: g.name for g in body.vertex_groups}
    bones = ["ForeArm", "Hand", "Thumb1", "Thumb2", "Thumb3", "Index1", "Index2", "Index3", "Middle1", "Middle2",
             "Middle3", "Ring1", "Ring2", "Ring3", "Pinky1", "Pinky2", "Pinky3", "forearm_twist_mid", "forearm_twist"]
    cols = {}
    fam = {"Thumb": 0, "Index": 1, "Middle": 2, "Ring": 3, "Pinky": 4}
    for b in bones:
        if b[:-1] in fam:
            base = Vector(_PALETTE[fam[b[:-1]]])
            k = int(b[-1])
            cols[b] = (base, base * 0.35 + Vector((0.65, 0.65, 0.65)), base * 0.3)[k - 1]
        elif b == "Hand":
            cols[b] = Vector((0.95, 0.95, 0.95))
        elif b == "ForeArm":
            cols[b] = Vector((0.25, 0.25, 0.25))
        elif b == "forearm_twist_mid":
            cols[b] = Vector(_PALETTE[6])
        else:
            cols[b] = Vector(_PALETTE[5])
    attr = me.color_attributes.get("qa_w") or me.color_attributes.new("qa_w", "FLOAT_COLOR", "POINT")
    for v in me.vertices:
        c = Vector((0, 0, 0))
        if only:
            w = sum(g.weight for g in v.groups if names[g.group] == only)
            c = _heat(w)
            attr.data[v.index].color = (c.x, c.y, c.z, 1.0)
            continue
        for g in v.groups:
            n = names[g.group]
            key = n.split("Hand", 1)[1] if "Hand" in n and n.split("Hand", 1)[1] else None
            if key is None:
                key = "Hand" if n.endswith("Hand") else ("ForeArm" if n.endswith("ForeArm") else
                                                           n.split(":")[-1].split(".")[0])
            if key in cols:
                c += cols[key] * g.weight
        attr.data[v.index].color = (c.x, c.y, c.z, 1.0)
    mat = bpy.data.materials.get("qa_w") or bpy.data.materials.new("qa_w")
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    a = nt.nodes.new("ShaderNodeVertexColor")
    a.layer_name = "qa_w"
    bsdf = nt.nodes.new("ShaderNodeBsdfDiffuse")
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(a.outputs["Color"], bsdf.inputs["Color"])
    nt.links.new(bsdf.outputs[0], out.inputs[0])
    me.materials.append(mat)
    for p in me.polygons:
        p.material_index = len(me.materials) - 1
    reset_pose(rig)
    if pose:
        set_local_euler(rig, pose)
        apply_twist_rule(rig)
    _mask_arm(ctx, True)
    _render(ctx, sub, f"wviz_{tag}", hand_shots(rig, "Left", "L", dist=0.45) +
            wrist_shots(rig, "Left", "W", views=("dorsal", "volar")))
    _mask_arm(ctx, False)
    me.materials.pop(index=len(me.materials) - 1)
    for p in me.polygons:
        p.material_index = 0
    reset_pose(rig)


def qa(ctx):
    args = common.parse_args({"hands_mode": "full", "hands_qa": "all"})
    sub = "hands_" + args["hands_mode"]
    which = args["hands_qa"]
    if which == "thumb":
        rig = ctx["rig"]
        _mask_arm(ctx, True)
        for name in ("point", "fist"):
            reset_pose(rig)
            set_local_euler(rig, ctx["hand_poses"][name])
            _render(ctx, sub, f"thumb_{name}", hand_shots(rig, "Left", "c", views=("radial", "volar")))
        reset_pose(rig)
        set_local_euler(rig, stress_pose(rig, "oppose"))
        _render(ctx, sub, "thumb_oppose", hand_shots(rig, "Left", "c", views=("radial", "volar")))
        _mask_arm(ctx, False)
        weight_viz(ctx, sub, None, "heat_HandThumb1", only=mb("Left", "HandThumb1"))
        return
    if which == "bleed":
        rig = ctx["rig"]
        _mask_arm(ctx, True)
        for f in ("Middle", "Ring"):
            spec = {"Hand": (0, 0, 0), "Thumb": [(0, 0, 0)] * 3}
            for g in LONG_FINGERS:
                spec[g] = [(0, 0, 0)] * 3 if g != f else [(90, 0, 0), (100, 0, 0), (60, 0, 0)]
            reset_pose(rig)
            set_local_euler(rig, resolve_pose(rig, spec))
            _render(ctx, sub, f"bleed_{f}", hand_shots(rig, "Left", "c", dist=0.3, views=("dorsal", "volar", "tip")))
        spec = {"Hand": (0, 0, 0), "Thumb": [(0, 0, 0)] * 3}
        for g in LONG_FINGERS:
            spec[g] = [(90, 0, 0), (0, 0, 0), (0, 0, 0)]
        reset_pose(rig)
        set_local_euler(rig, resolve_pose(rig, spec))
        _render(ctx, sub, "bleed_mcp90", hand_shots(rig, "Left", "c", dist=0.3, views=("dorsal", "radial", "tip")))
        _mask_arm(ctx, False)
        return
    if which == "elbow":
        stress_tests(ctx, sub, only_elbow=True)
        return
    if which == "fist":
        rig = ctx["rig"]
        _mask_arm(ctx, True)
        for name in ("fist_hard", "oppose"):
            reset_pose(rig)
            set_local_euler(rig, stress_pose(rig, name))
            apply_twist_rule(rig)
            _render(ctx, sub, f"stress_{name}", hand_shots(rig, "Left", "c", views=("dorsal", "volar", "radial", "ulnar", "tip")))
        _mask_arm(ctx, False)
        return
    if which in ("all", "metric", "stress"):
        twist_metric(ctx, angles=(-90, -70, 70, 90))
        twist_metric(ctx, bone="Arm", seg="Arm", angles=(-60, 60))
    if which.startswith("heat:"):
        for b in which[5:].split("+"):
            weight_viz(ctx, sub, None, "heat_" + b, only=mb("Left", b))
        return
    if which in ("all", "wviz"):
        weight_viz(ctx, sub)
        weight_viz(ctx, sub, stress_pose(ctx["rig"], "fist_hard"), "fist")
    if which in ("all", "stress"):
        stress_tests(ctx, sub)
    if which in ("all", "poses"):
        pose_renders(ctx, sub)


# In-context arm set-ups for the pose renders (QA only; world directions for the RIGHT side,
# mirrored in X for the left). Close to the old clips: wave = raised hand beside the head,
# point = arm forward, explain = palm-up offering, thinking_chin = hand under the chin.
ARM_CONTEXT = {
    "relaxed": {"arm": (-0.18, 0.02, -1.0), "fore": (-0.12, -0.35, -1.0), "palm": (1.0, 0.1, 0.0)},
    "fist": {"arm": (-0.18, 0.02, -1.0), "fore": (-0.12, -0.35, -1.0), "palm": (1.0, 0.1, 0.0)},
    "wave": {"arm": (-0.9, -0.2, -0.25), "fore": (-0.2, -0.25, 1.0), "palm": (0.0, -1.0, 0.1)},
    "point": {"arm": (-0.25, -0.6, -0.75), "fore": (0.05, -1.0, 0.15), "palm": (0.6, 0.0, -0.8)},
    "explain": {"arm": (-0.35, -0.2, -0.9), "fore": (-0.45, -0.9, 0.15), "palm": (0.15, 0.0, 1.0)},
    "thinking_chin": {"arm": (-0.05, -0.45, -0.9), "fore": (0.45, -0.3, 0.85), "palm": (0.2, 0.35, -0.9)},
}


def aim(rig, name, direction):
    bpy.context.view_layer.update()
    pb = rig.pose.bones[name]
    cur = (rig.matrix_world.to_3x3() @ (pb.tail - pb.head)).normalized()
    tgt = Vector(direction).normalized()
    axis = cur.cross(tgt)
    if axis.length < 1e-6:
        return
    rot_world(rig, name, axis.normalized(), math.degrees(cur.angle(tgt)))


def palm_to(rig, name, normal):
    """Roll `name` (Hand) about its own Y so its +Z (volar) faces `normal` as much as possible."""
    bpy.context.view_layer.update()
    pb = rig.pose.bones[name]
    m = (rig.matrix_world @ pb.matrix).to_3x3().normalized()
    y, z = m.col[1], m.col[2]
    n = Vector(normal).normalized()
    n = (n - y * n.dot(y)).normalized()
    ang = math.degrees(z.angle(n))
    sgn = 1.0 if z.cross(n).dot(y) > 0 else -1.0
    rot_local(rig, name, (0, 1, 0), sgn * ang)


def arm_context(rig, pose_name, pose):
    reset_pose(rig)
    ctxp = ARM_CONTEXT[pose_name]
    for s, side, _ in SIDES:
        mx = 1.0 if side == "Right" else -1.0
        mir = lambda v: (v[0] * mx, v[1], v[2])  # noqa: E731
        aim(rig, mb(side, "Arm"), mir(ctxp["arm"]))
        aim(rig, mb(side, "ForeArm"), mir(ctxp["fore"]))
        palm_to(rig, mb(side, "Hand"), mir(ctxp["palm"]))
        hx, hy, hz = pose[mb(side, "Hand")]
        rot_local(rig, mb(side, "Hand"), (1, 0, 0), hx)
        rot_local(rig, mb(side, "Hand"), (0, 0, 1), hz)
    fingers = {k: v for k, v in pose.items() if "Hand" in k and not k.endswith("Hand")}
    for name, (x, y, z) in fingers.items():
        rig.pose.bones[name].rotation_mode = "QUATERNION"
        rig.pose.bones[name].rotation_quaternion = Euler((math.radians(x), math.radians(y), math.radians(z)), "XYZ").to_quaternion()
    apply_twist_rule(rig)


def pose_renders(ctx, sub):
    rig = ctx["rig"]
    for name, pose in ctx["hand_poses"].items():
        reset_pose(rig)
        set_local_euler(rig, pose)
        apply_twist_rule(rig)
        _mask_arm(ctx, True)
        shots = hand_shots(rig, "Left", "L", views=("dorsal", "volar", "radial")) + \
            hand_shots(rig, "Right", "R", views=("radial",))
        _render(ctx, sub, f"pose_{name}", shots)
        _mask_arm(ctx, False)
        if name in ARM_CONTEXT:
            arm_context(rig, name, pose)
            for o in bpy.data.objects:
                if o.type == "MESH":
                    o.hide_render = False
            c, _ = _hand_frame(rig, "Right")
            shots = [("ctx_front", (0.25, -2.6, 1.25), (0, 0, 1.2), 50),
                     ("ctx_threeq", (-1.7, -1.9, 1.35), (0, 0, 1.2), 50),
                     ("ctx_hand", tuple(c + Vector((-0.25, -0.45, 0.1))), tuple(c), 85)]
            _render(ctx, sub, f"context_{name}", shots, res=(800, 1000))
    reset_pose(rig)
