"""Masha's clip library (owner: Agent 4): authoring + baking into NLA tracks of Masha_Rig.

    import clips
    lib = clips.Library(rig_ob, base_pose_json, hand_poses)   # hand_poses: {"poses": ..., ...} json
    made = lib.build(names=None)       # -> [Clip] (frames = rigmath.Pose list at 30 fps)
    clips.bake(rig_ob, made)           # one action + one muted NLA track per clip, keys from frame 0

Runtime contract (MashaMotion.kt / MashaAnimator.kt): clips are found by name. Every one-shot clip starts
and ends EXACTLY in the base pose (first/last frame = base_pose.json); loops (Idle, Listen, Think) have
first frame == last frame. Only mixamorig:* bones are keyed (all of them, every clip: the runtime falls
back to the REST pose for unkeyed bones) + Hips location; masha:* bones never.

Layering (mocap clips, see layers.py):
  torso/legs/head   base @ delta^(tone*w)      delta = source local rotation relative to the clip's
                                               own reference frame; w = envelope (0 at both ends)
  Hips location     base + dloc*tone*w
  gesturing arm     additive like the torso ("add") or slerp(base, retargeted absolute, k*w) ("abs")
  hip (left) arm    IK: wrist + hand orientation carried by the pelvis; leaves the hip only inside
                    explicit windows (two-hand gestures), blended back with smootherstep ramps
  feet              analytic two-bone IK back to the base ankle + base foot orientation (planted)
  Head              synthesised from the Neck (Mixamo returned the Head unanimated)
Keyed clips (Base, Idle, Listen, Think micro motion, Wave, Point, Var_Hair, Var_GlanceSmile) use Tracks
(cubic Hermite) on character axes and IK targets.
"""
import math
import os

from mathutils import Quaternion, Vector

import layers
import retarget
import rigmath
from layers import (FPS, SIDES, Solver, Source, Track, arm_bones, bump, env, finger_bones, hand_frame,
                    leg_bones, window)
from rigmath import HIPS, M, X, Y, Z, qpow, smootherstep

L, R = "Left", "Right"


class Clip:
    def __init__(self, name, frames, loop=False, source="", notes="", plant_w=None, hip_w=None):
        self.name = name
        self.frames = frames
        self.loop = loop
        self.source = source
        self.notes = notes
        self.plant_w = plant_w or [1.0] * len(frames)
        self.hip_w = hip_w

    @property
    def seconds(self):
        return (len(self.frames) - 1) / FPS

    def qa_dict(self):
        return {"name": self.name, "frames": self.frames, "loop": self.loop, "plant_w": self.plant_w,
                "hip_w": self.hip_w}


# ============================================================================ generic mocap layering
GROUPS = {
    "hips": [HIPS],
    "spine": [M + "Spine", M + "Spine1", M + "Spine2"],
    "neck": [M + "Neck", M + "Head"],
    "shoulders": [M + "LeftShoulder", M + "RightShoulder"],
    "legs": leg_bones(L) + leg_bones(R),
}
# overlap: sample delays (frames) behind the parent chain
DELAY = {M + "Spine2": 1, M + "Neck": 2, M + "RightHand": 2, M + "LeftHand": 2}
for _s in SIDES:
    for _b in layers.finger_bones(_s):
        DELAY[_b] = 3


class Spec(dict):
    """Mocap clip recipe (all times in seconds). Keys:
    name, src, seg=(a, b), speed=1, ref=None (default a), tin=0.5, tout=0.7,
    tone={hips, spine, neck, shoulders, legs, loc} (defaults 0.8), head_gain=1.0,
    right=("add"|"abs", k), right_fingers=0.6, right_pose=(pose, weight track or const),
    left="hip" | ("abs"|"add", k, [(t0, t1, rin, rout)]) windows in OUTPUT time,
    left_fingers=0.3, loop=False, xfade=0.8, tweak=fn(lib, t, w, pose), pivot=fn(t)->{side: deg},
    mirror=False, sigma=1.0, notes=""."""


class Library:
    def __init__(self, rig_ob, base_data, hand_poses, mixamo_dir=None, body=None):
        self.rig = rig_ob
        self.model = rigmath.RigModel(rig_ob)
        self.base = rigmath.pose_from_base(base_data)
        for n in self.model.mixamo:            # every mixamorig bone keyed, base value if absent
            self.base.rot.setdefault(n, Quaternion())
        self.hp_all = hand_poses
        self.S = Solver(self.model, self.base, hand_poses["poses"])
        self.mixamo_dir = mixamo_dir
        self._src = {}
        if body is not None:
            self.S.proxy = layers.TorsoProxy(rig_ob, body, self.model, self.base)

    # ---------------------------------------------------------------- sources
    def src(self, name, mirror=False, sigma=1.0, head_gain=1.0):
        key = (name, mirror, sigma, head_gain)
        if key not in self._src:
            mo = retarget.load_clip(self.rig, name, self.mixamo_dir)
            self._src[key] = Source(mo, sigma=sigma, head_gain=head_gain, mirror=mirror)
        return self._src[key]

    # ---------------------------------------------------------------- mocap clip
    def mocap(self, sp):
        S = self.S
        src = self.src(sp["src"], sp.get("mirror", False), sp.get("sigma", 1.0), sp.get("head_gain", 1.0))
        a, b = sp.get("seg", (0.0, src.T))
        b = min(b, src.T)
        speed = sp.get("speed", 1.0)
        ref = sp.get("ref", a)
        loop = sp.get("loop", False)
        xf = sp.get("xfade", 0.8) if loop else 0.0
        T = (b - a) / speed - xf
        n = int(round(T * FPS)) + 1
        T = (n - 1) / FPS
        tone = {"hips": 0.8, "spine": 0.8, "neck": 0.8, "shoulders": 0.8, "legs": 0.8, "loc": 0.8}
        tone.update(sp.get("tone", {}))
        tin, tout = sp.get("tin", 0.5), sp.get("tout", 0.7)
        right = sp.get("right", ("add", 0.85))
        left = sp.get("left", "hip")
        lwin = left[2] if isinstance(left, tuple) else []

        def lw(t):
            return max([window(t, *wd) for wd in lwin] + [0.0])

        def frame(t):
            s = a + t * speed
            w = 1.0 if loop else env(t, T, tin, tout)
            p = S.fresh()
            # torso, head, legs: additive
            for g, bones in GROUPS.items():
                k = tone[g] * w
                for bn in bones:
                    d = DELAY.get(bn, 0) / FPS
                    S.add(p, bn, src.delta(bn, s - d, ref), k)
            p.loc = self.base.loc + src.dloc(s, ref) * tone["loc"] * w
            # right arm
            mode, k = right
            for bn in arm_bones(R):
                d = DELAY.get(bn, 0) / FPS
                kb = k * (sp.get("hand_k", 1.0) if bn.endswith("Hand") else 1.0)
                if mode == "add":
                    S.add(p, bn, src.delta(bn, s - d, ref), kb * w)
                elif mode == "abs":
                    p.rot[bn] = p.q(bn).slerp(src.abs(bn, s - d), k * w)
            kf = sp.get("right_fingers", 0.6) * w
            for bn in finger_bones(R):
                S.add(p, bn, src.delta(bn, s - DELAY.get(bn, 0) / FPS, ref), kf)
            rp = sp.get("right_pose")
            if rp:
                wt = rp[1](t) if callable(rp[1]) else rp[1] * w
                S.hand_pose(p, rp[0], R, wt)
            # left arm: on the hip unless inside a window
            ww = lw(t) * w if lwin else 0.0
            if ww > 0:
                lmode, lk = left[0], left[1]
                other = p.copy()
                for bn in arm_bones(L):
                    d = DELAY.get(bn, 0) / FPS
                    if lmode == "abs":
                        other.rot[bn] = src.abs(bn, s - d)
                    else:
                        S.add(other, bn, src.delta(bn, s - d, ref), 1.0)
                kf = sp.get("left_fingers", 0.5)
                for bn in finger_bones(L):
                    S.add(other, bn, src.delta(bn, s - DELAY.get(bn, 0) / FPS, ref), kf)
            if sp.get("adduct"):
                # feminise: gesturing elbow a little closer to the body (about the forward axis)
                S.rot_world(p, M + "RightArm", [(Y, -sp["adduct"] * w)])
            tw = sp.get("tweak")
            if tw:
                tw(self, t, w, p)
            piv = sp.get("pivot")
            S.plant_feet(p, pivot=piv(t) if piv else None)
            S.hip_hand_ik(p, 1.0)
            if ww > 0:
                # other arm computed on the same torso: recompute after torso tweaks
                S.slerp_bones(p, other, arm_bones(L) + finger_bones(L), ww * lk)
            post = sp.get("post")
            if post:
                post(self, t, w, p)
            layers.avoid_torso(S, p, R)
            if ww > 0.3:
                layers.avoid_torso(S, p, L, scale=min(1.0, (ww - 0.3) / 0.4))
            return p, (1.0 - ww)

        frames, hipw = [], []
        for i in range(n):
            p, hw = frame(i / FPS)
            frames.append(p)
            hipw.append(hw)
        piv = sp.get("pivot")
        plant = [0.0 if piv and any(abs(v) > 1e-3 for v in piv(i / FPS).values()) else 1.0 for i in range(n)]
        if loop:
            # cross-fade the seam: the first xf seconds blend from the continuation past the end
            nx = int(round(xf * FPS))
            tail = [frame(T + (i + 1) / FPS)[0] for i in range(nx)]
            for i in range(nx):
                u = smootherstep((i + 1) / (nx + 1))
                frames[i] = blend(tail[i], frames[i], u) if i < len(tail) else frames[i]
            frames[-1] = frames[0].copy()
            # first frame of the loop == continuation after the last: first = tail blend at u~0
        else:
            frames[0] = self.base.copy()
            frames[-1] = self.base.copy()
        return Clip(sp["name"], frames, loop=loop, source=f"{sp['src']} [{a:.2f}-{b:.2f}s] x{speed}",
                    notes=sp.get("notes", ""), hip_w=hipw, plant_w=plant)

    # ---------------------------------------------------------------- keyed clip helper
    def keyed(self, name, T, fn, loop=False, notes="", hip_w=None, source="hand-keyed"):
        n = int(round(T * FPS)) + 1
        frames = []
        hw = []
        self.S.prev = None
        for i in range(n):
            t = i / FPS
            p = self.S.fresh()
            h = fn(t, p)
            layers.avoid_torso(self.S, p, R)
            frames.append(p)
            self.S.prev = p
            hw.append(1.0 if h is None else h)
        if not loop:
            frames[0] = self.base.copy()
            frames[-1] = self.base.copy()
        else:
            frames[-1] = frames[0].copy()
        return Clip(name, frames, loop=loop, source=source, notes=notes, hip_w=hw)

    # ---------------------------------------------------------------- catalogue
    def build(self, names=None):
        out = []
        for name, maker in catalogue(self):
            if names and name not in names:
                continue
            c = maker()
            print(f"CLIPS built {c.name}: {c.seconds:.2f}s {'loop' if c.loop else 'one-shot'} <- {c.source}")
            out.append(c)
        return out


def blend(a, b, u):
    """Pose slerp a->b by u."""
    p = rigmath.Pose({k: a.q(k).slerp(b.q(k), u) for k in set(a.rot) | set(b.rot)}, a.loc.lerp(b.loc, u))
    return p


# ============================================================================ keyed building blocks
def idle_layer(lib, p, t, period, amp=1.0, hip_hand=True):
    """Very subtle hand/finger micro motion + tiny asymmetric settles (periodic in `period`)."""
    S = lib.S
    two = 2 * math.pi / period

    def sn(k, ph):
        return math.sin(two * k * t + ph)

    # right hand: fingers breathe in and out a little, index/middle lead (overlap)
    for f, ph in (("Index", 0.0), ("Middle", 0.35), ("Ring", 0.7), ("Pinky", 1.0), ("Thumb", 0.5)):
        c = amp * (2.2 * sn(1, ph) + 1.2 * sn(2, ph * 1.7 + 0.4))
        for i, g in ((1, 1.0), (2, 0.8), (3, 0.6)):
            S.add(p, f"{M}RightHand{f}{i}", Quaternion(X, math.radians(c * g)))
    # right wrist + forearm roll, tiny
    S.add(p, M + "RightHand", Quaternion(X, math.radians(amp * 1.8 * sn(1, 1.2))))
    S.add(p, M + "RightHand", Quaternion(Z, math.radians(amp * 1.2 * sn(1, 2.5))))
    S.add(p, M + "RightForeArm", Quaternion(Y, math.radians(amp * 2.0 * sn(1, 0.9))))
    # a settle of the free arm (elbow softens then recovers): asymmetric bump
    e = bump(t, period * 0.55, 0.5, 1.6, period)
    S.rot_world(p, M + "RightArm", [(X, -1.2 * e * amp), (Y, -0.8 * e * amp)])
    S.add(p, M + "RightForeArm", Quaternion(X, math.radians(3.0 * e * amp)))
    # hip hand: a small finger lift (index/middle) as if adjusting the grip
    g = bump(t, period * 0.2, 0.35, 0.9, period) * amp
    for f, k in (("Index", 1.0), ("Middle", 0.7), ("Ring", 0.4)):
        S.add(p, f"{M}LeftHand{f}1", Quaternion(X, math.radians(-7 * k * g)))
        S.add(p, f"{M}LeftHand{f}2", Quaternion(X, math.radians(-3 * k * g)))
    # shoulders settle asymmetrically
    sL = bump(t, period * 0.3, 0.6, 1.8, period) * amp
    sR = bump(t, period * 0.8, 0.5, 1.5, period) * amp
    S.rot_world(p, M + "LeftShoulder", [(Y, 0.9 * sL)])
    S.rot_world(p, M + "RightShoulder", [(Y, 0.8 * sR)])


def head_axes(lib, p):
    """Posed head frame helpers (world)."""
    return lib.S.world(p, M + "Head")


# ============================================================================ catalogue
def catalogue(lib):
    S = lib.S
    C = []

    def add(name, maker):
        C.append((name, maker))

    # ------------------------------------------------ Base / Idle
    add("Base", lambda: lib.keyed("Base", 1.0, lambda t, p: None, notes="static base pose"))

    def idle(t, p):
        idle_layer(lib, p, t, 8.0)
        S.plant_feet(p)
        S.hip_hand_ik(p)
    add("Idle", lambda: lib.keyed("Idle", 8.0, idle, loop=True,
                                  notes="finger/wrist micro motion + asymmetric settles (runtime adds breath/sway/gaze)"))

    # ------------------------------------------------ Listen (attentive hold loop)
    def listen_maker():
        src = lib.src("Thoughtful Head Nod")
        T = 6.0

        def fn(t, p):
            # attentive: slight forward lean, head tilt, plus calm neck motion from the mocap
            S.rot_world(p, M + "Spine1", [(X, 1.2)])
            S.rot_world(p, M + "Spine2", [(X, 2.0), (Z, 1.0)])
            S.rot_world(p, M + "Neck", [(X, 2.0)])
            S.rot_world(p, M + "Head", [(Y, 4.5), (X, 1.5), (Z, -1.0)])
            # mocap: nod segment 0.3..2.7 s played as a palindrome-free loop: sample with wrap and
            # cross-fade over the loop end
            u = t / T
            s1 = 0.3 + 2.4 * ((t / T * 2.4 / 2.4) % 1.0)
            k = 0.35
            for bn in (M + "Neck", M + "Head"):
                d0 = src.delta(bn, 0.3 + 2.4 * u, 0.3)
                S.add(p, bn, d0, k * math.sin(math.pi * u) ** 2)
            idle_layer(lib, p, t, T, amp=0.8)
            S.plant_feet(p)
            S.hip_hand_ik(p)
        return lib.keyed("Listen", T, fn, loop=True, source="keyed + Thoughtful Head Nod (neck, x0.35)",
                         notes="loop of the attentive hold (fades in from Idle at runtime)")
    add("Listen", listen_maker)

    # ------------------------------------------------ Think (hold loop)
    add("Think", lambda: think(lib))

    # ------------------------------------------------ mocap variations
    add("Var_LookAround", lambda: lib.mocap(Spec(
        name="Var_LookAround", src="Looking Around", seg=(0.0, 6.3), tin=0.4, tout=0.8,
        tone={"hips": 0.12, "spine": 0.6, "neck": 0.85, "shoulders": 0.5, "legs": 0.1, "loc": 0.25},
        head_gain=1.1, right=("add", 0.45), right_fingers=0.3)))

    def ws_pivot(t):
        # the free (left) heel comes down while the weight moves onto it (mocap hips +x at 4.5-7.5 s)
        return {L: -5.0 * window(t, 1.2, 3.4, 0.7, 0.9)}
    add("Var_WeightShift", lambda: lib.mocap(Spec(
        name="Var_WeightShift", src="Weight Shift", seg=(3.6, 8.6), tin=0.5, tout=0.8,
        tone={"hips": 0.6, "spine": 0.7, "neck": 0.6, "shoulders": 0.6, "legs": 0.6, "loc": 0.55},
        right=("add", 0.5), pivot=ws_pivot,
        notes="weight onto the free leg and back; feet stay planted (free heel lowers/lifts)")))

    add("Var_Stretch", lambda: lib.mocap(Spec(
        name="Var_Stretch", src="Arm Stretching", seg=(0.15, 3.4), tin=0.45, tout=0.8, speed=0.85,
        tone={"hips": 0.4, "spine": 0.55, "neck": 0.6, "shoulders": 0.6, "legs": 0.3, "loc": 0.35},
        right=("abs", 0.9), right_fingers=0.4,
        left=("abs", 0.9, [(0.55, 3.0, 0.45, 0.6)]),
        notes="right arm drawn across the chest by the left hand; left hand leaves the hip and returns")))

    add("Var_NeckStretch", lambda: lib.mocap(Spec(
        name="Var_NeckStretch", src="Neck Stretching", seg=(0.0, 3.2), tin=0.4, tout=0.7, head_gain=2.0,
        tone={"hips": 0.3, "spine": 0.5, "neck": 0.8, "shoulders": 0.6, "legs": 0.2, "loc": 0.3},
        right=("add", 0.5))))

    add("Var_Catwalk", lambda: lib.mocap(Spec(
        name="Var_Catwalk", src="Catwalk Idle 02", seg=(0.0, 6.3), tin=0.6, tout=0.9,
        tone={"hips": 0.6, "spine": 0.6, "neck": 0.6, "shoulders": 0.6, "legs": 0.5, "loc": 0.5},
        right=("add", 0.6), notes="subtle feminine idle variation")))

    # ------------------------------------------------ keyed variations
    add("Var_Hair", lambda: var_hair(lib))
    add("Var_GlanceSmile", lambda: var_glance(lib))

    # ------------------------------------------------ talk beats
    talk = [
        dict(src="talking_general", seg=(0.6, 2.9), right=("add", 0.8)),
        dict(src="talking_one_hand", seg=(0.1, 3.3), right=("add", 0.75)),
        dict(src="Happy Hand Gesture", seg=(0.05, 2.3), right=("add", 0.85)),
        dict(src="talking_two_hands", seg=(0.1, 2.7), right=("add", 0.8),
             left=("abs", 0.55, [(0.4, 1.7, 0.35, 0.5)])),
        dict(src="talking_funny", seg=(1.0, 4.0), right=("abs", 0.75)),
    ]
    for i, d in enumerate(talk):
        nm = f"Talk_{i + 1}"
        base_tone = {"hips": 0.5, "spine": 0.7, "neck": 0.8, "shoulders": 0.7, "legs": 0.4, "loc": 0.4}
        d.setdefault("hand_k", 0.6)
        d.setdefault("adduct", 5.0)
        add(nm, (lambda nm=nm, d=d: lib.mocap(Spec(name=nm, tin=0.35, tout=0.6, tone=base_tone,
                                                    right_pose=("explain", 0.25), **d))))

    # ------------------------------------------------ actions
    add("Wave", lambda: wave(lib))
    add("Point", lambda: point(lib))
    add("Nod", lambda: lib.mocap(Spec(
        name="Nod", src="Head Nod Yes", seg=(0.0, 2.6), tin=0.25, tout=0.5, head_gain=1.3,
        tone={"hips": 0.4, "spine": 0.6, "neck": 1.0, "shoulders": 0.6, "legs": 0.3, "loc": 0.3},
        right=("add", 0.5))))
    add("Shrug", lambda: lib.mocap(Spec(
        name="Shrug", src="Shrugging", seg=(0.0, 2.0), tin=0.3, tout=0.55,
        tone={"hips": 0.5, "spine": 0.8, "neck": 0.9, "shoulders": 1.0, "legs": 0.3, "loc": 0.4},
        right=("add", 0.8), right_fingers=0.15, right_pose=("explain", 0.7), hand_k=0.6,
        left=("abs", 0.55, [(0.3, 1.25, 0.25, 0.45)]))))
    add("React_Happy", lambda: lib.mocap(Spec(
        name="React_Happy", src="laughing", seg=(1.0, 3.6), tin=0.3, tout=0.7,
        tone={"hips": 0.35, "spine": 0.4, "neck": 0.35, "shoulders": 0.6, "legs": 0.3, "loc": 0.3},
        head_gain=0.6, right=("add", 0.5), right_fingers=0.4, tweak=happy_tweak)))
    add("React_Relief", lambda: lib.mocap(Spec(
        name="React_Relief", src="relieved Sigh", seg=(0.0, 3.0), tin=0.3, tout=0.6,
        tone={"hips": 0.7, "spine": 0.9, "neck": 0.9, "shoulders": 0.9, "legs": 0.4, "loc": 0.6},
        right=("add", 0.7))))
    return C


# ============================================================================ keyed clips
def arc_wrist(sh, w0, w1, u, lead=0.2):
    """Wrist path from w0 to w1 around the shoulder `sh`: the direction (shoulder swing) leads, the
    reach distance (elbow flexion) follows `lead` later -> arcs + overlap, and no near-straight-arm
    IK singularity (the elbow never has to flex fast to pull the wrist in)."""
    d0, d1 = w0 - sh, w1 - sh
    ud = min(1.0, max(0.0, u / (1.0 - lead))) if u <= 1.0 else u   # u > 1: overshoot = extra swing
    ur = min(1.0, max(0.0, (u - lead) / (1.0 - lead)))
    q = d0.normalized().rotation_difference(d1.normalized())
    direction = qpow(q, ud) @ d0.normalized()
    dist = d0.length + (d1.length - d0.length) * smootherstep(ur)
    return sh + direction * dist


def target_hand_local(S, p, side, wrist, pole, hand_q):
    """Hand LOCAL rotation of the arm solved at its target (wrist + world hand orientation). The move
    then slerps the hand local base -> this (anatomical wrist all the way, no world-space flips)."""
    pp = p.copy()
    S.arm_ik(pp, side, wrist, pole, hand_q)
    return pp.q(f"{M}{side}Hand")


def anticipate(S, p, side, a, back=5.0, flex=6.0):
    """FK anticipation (a in 0..1): the arm draws back a little and the elbow softens."""
    if a <= 0:
        return
    S.rot_world(p, f"{M}{side}Arm", [(X, back * a)])
    S.add(p, f"{M}{side}ForeArm", Quaternion(S.hinge[side], math.radians(flex * a)))


def var_hair(lib):
    """Right hand tucks the hair behind the right ear and returns (IK, head-relative targets)."""
    S = lib.S
    T = 4.2
    bm = S.bm
    head_rest = bm[M + "Head"]
    # contact points in the BASE head frame (world at base pose): temple in front of the ear, then
    # sliding back over the ear
    ear = Vector((-0.074, -0.035, 1.650))
    temple = Vector((-0.068, -0.070, 1.700))
    behind = Vector((-0.072, 0.005, 1.668))
    hinv = head_rest.inverted()
    P = {k: hinv @ v for k, v in (("ear", ear), ("temple", temple), ("behind", behind))}
    # weights: raise (0.35-1.3), slide (1.3-2.2), linger, return (2.6-3.8)
    reach = Track([(0.0, 0.0), (0.3, 0.0), (0.75, 0.25), (1.25, 1.0, "hold"), (2.35, 1.0, "hold"),
                   (3.0, 0.45), (3.75, 0.0), (T, 0.0)])
    slide = Track([(0.0, 0.0), (1.25, 0.0, "hold"), (1.8, 0.7), (2.2, 1.0, "hold"), (T, 1.0)])
    headk = Track([(0.0, 0.0), (0.5, 0.0), (1.2, 1.0), (2.4, 1.0), (3.4, 0.0), (T, 0.0)])
    shoulder = Track([(0.0, 0.0), (0.6, 0.0), (1.2, 1.0), (2.4, 0.8), (3.5, 0.0), (T, 0.0)])

    def fn(t, p):
        hk = headk(t)
        # head tilts into the hand, turns a touch away to expose the ear; chest opens
        S.rot_world(p, M + "Spine2", [(Y, -1.5 * hk), (X, -1.0 * hk)])
        S.rot_world(p, M + "Neck", [(Y, -2.0 * hk), (Z, 2.0 * hk)])
        S.rot_world(p, M + "Head", [(Y, -4.0 * hk), (Z, 4.0 * hk), (X, -2.0 * hk)])
        S.rot_world(p, M + "RightShoulder", [(Y, -3.0 * shoulder(t)), (X, -1.5 * shoulder(t))])
        r = reach(t)
        if r > 1e-4:
            hm = S.world(p, M + "Head")
            sl = slide(t)
            c = (hm @ P["temple"]).lerp(hm @ P["behind"], sl)
            # hand frame: fingers up and back along the head, palm (volar +Z) towards the head
            hq = hm.to_quaternion() @ head_rest.to_quaternion().inverted()
            fy = hq @ Vector((0.0, 0.25 + 0.55 * sl, 1.0)).normalized()
            fz = hq @ Vector((1.0, 0.0, -0.1)).normalized()
            q_touch = hand_frame(fy, fz)
            wrist_touch = c - 0.105 * fy.normalized() - 0.025 * (fz - fz.dot(fy.normalized()) * fy.normalized()).normalized()
            # blend from the relaxed wrist along an arc (out to the side and up)
            w0 = S.world(p, M + "RightHand").translation
            u = smootherstep(r)
            sh = S.world(p, M + "RightArm").translation
            # waypoints (shoulder-relative): hanging -> in front of the chest (forearm rising) -> touch
            via = sh + Vector((-0.06, -0.24, -0.06))
            path = Track([(0.0, w0 - sh), (0.45, via - sh), (1.0, wrist_touch - sh)])
            wrist = sh + path(r)
            pole = sh + Vector((-0.45, -0.25, -0.20))
            q0 = S.world(p, M + "RightHand").to_quaternion()
            L1 = target_hand_local(S, p, R, wrist_touch, pole, q_touch)
            S.arm_ik(p, R, wrist, pole, None, pole_w=u, w=smootherstep(min(1.0, r / 0.25)))
            p.rot[M + "RightHand"] = S.base.q(M + "RightHand").slerp(L1, u)
            S.hand_pose(p, "relaxed", R, 1.0)
            # fingers straighten a little to comb the hair
            for f in ("Index", "Middle", "Ring", "Pinky"):
                for i in (1, 2, 3):
                    S.add(p, f"{M}RightHand{f}{i}", Quaternion(X, math.radians(-8 * u)))
        S.plant_feet(p)
        S.hip_hand_ik(p)
    return lib.keyed("Var_Hair", T, fn, notes="right hand tucks hair behind the ear (IK, head-relative)")


def var_glance(lib):
    """Coy glance: a look aside (anticipation), then back to camera with tilt + shoulder lift."""
    S = lib.S
    T = 3.4
    yaw = Track([(0.0, 0.0), (0.35, 0.0), (0.9, 7.0), (1.3, 6.0), (1.75, -3.5), (2.6, -2.5), (3.2, 0.0), (T, 0.0)])
    tilt = Track([(0.0, 0.0), (0.9, 0.5), (1.7, 6.0), (2.6, 5.0), (3.25, 0.0), (T, 0.0)])
    pitch = Track([(0.0, 0.0), (0.9, 2.0), (1.6, -2.5), (2.5, -2.0), (3.2, 0.0), (T, 0.0)])
    sh = Track([(0.0, 0.0), (1.2, 0.0), (1.75, 1.0), (2.2, 0.85), (3.0, 0.0), (T, 0.0)])

    def fn(t, p):
        y, r, x, s = yaw(t), tilt(t), pitch(t), sh(t)
        # overlap: chest leads a little, neck/head follow
        S.rot_world(p, M + "Spine2", [(Z, 0.25 * yaw(t - 0.1))])
        S.rot_world(p, M + "Neck", [(Z, 0.35 * y), (Y, 0.3 * r), (X, 0.4 * x)])
        S.rot_world(p, M + "Head", [(Z, 0.5 * y), (Y, 0.7 * r), (X, 0.6 * x)])
        # right shoulder lifts (coy), left a touch
        S.rot_world(p, M + "RightShoulder", [(Y, 5.0 * s), (X, -1.5 * s)])
        S.rot_world(p, M + "LeftShoulder", [(Y, 1.2 * s)])
        S.rot_world(p, M + "RightArm", [(Y, -2.0 * s)])
        idle_layer(lib, p, t, T, amp=0.5)
        S.plant_feet(p)
        S.hip_hand_ik(p)
    return lib.keyed("Var_GlanceSmile", T, fn, notes="head look aside then coy glance back, shoulder lift")


def wave(lib):
    """Friendly one-hand wave with the free right hand: anticipation, raise beside the face, 3 waves
    (forearm + wrist, fingers trail), settle, return."""
    S = lib.S
    T = 3.6
    base_wrist = S.relax["wrist"]
    sh0 = S.relax["shoulder"]
    # wrist target relative to the right shoulder joint (world axes at base)
    up = Vector((-0.20, -0.14, 0.12))     # out, forward, above the shoulder: hand beside the face
    reach = Track([(0.0, 0.0), (0.3, 0.0, "hold"), (0.98, 1.035), (1.15, 1.0, "hold"),
                   (2.4, 1.0, "hold"), (3.3, 0.0), (T, 0.0)])
    ant = Track([(0.0, 0.0), (0.1, 0.0), (0.3, 1.0), (0.6, 0.0), (T, 0.0)])
    waveamp = Track([(0.0, 0.0), (0.95, 0.0), (1.2, 1.0), (2.05, 1.0), (2.35, 0.0), (T, 0.0)])
    headk = Track([(0.0, 0.0), (0.4, 0.0), (1.0, 1.0), (2.45, 1.0), (3.3, 0.0), (T, 0.0)])

    def fn(t, p):
        hk = headk(t)
        S.rot_world(p, M + "Spine2", [(Z, 2.0 * hk), (Y, -1.5 * hk)])
        S.rot_world(p, M + "Head", [(Y, -4.0 * hk), (Z, -2.0 * hk)])
        S.rot_world(p, M + "RightShoulder", [(Y, -2.5 * hk)])
        r = reach(t)
        sh = S.world(p, M + "RightArm").translation
        w0 = S.world(p, M + "RightHand").translation
        up_w = sh + up
        u = min(1.0, max(0.0, r))
        anticipate(S, p, R, ant(t))
        w0 = S.world(p, M + "RightHand").translation
        wrist = arc_wrist(sh, w0, up_w, r)
        # waving: side to side about the forearm (wrist lateral + forearm rotation)
        ph = 2 * math.pi * (t - 1.15) / 0.42
        a = waveamp(t)
        wrist = wrist + Vector((-0.035 * math.sin(ph) * a, 0.0, 0.0))
        fy = Vector((0.15, 0.05, 1.0)).normalized()          # fingers up
        fz = Vector((0.0, 1.0, 0.0))                          # palm (volar) faces forward (-Y is fwd:
        fz = Vector((0.1, -1.0, 0.0)) * -1                    # volar normal points to +Y? no: to camera)
        fz = Vector((0.1, -1.0, 0.1))                         # palm towards the camera (-Y)
        q_up = hand_frame(fy, fz)
        wq = Quaternion(Vector((0, -1, 0)), math.radians(14 * math.sin(ph - 0.6) * a))  # wrist trails
        q0 = S.world(p, M + "RightHand").to_quaternion()
        pole = sh + Vector((-0.45, -0.05, -0.40))
        if r > 0:
            L1 = target_hand_local(S, p, R, up_w, sh + Vector((-0.45, -0.05, -0.40)), q_up)
            S.arm_ik(p, R, wrist, pole, None, pole_w=u, w=smootherstep(min(1.0, r / 0.25)))
            p.rot[M + "RightHand"] = S.base.q(M + "RightHand").slerp(L1, smootherstep(u))
            S.rot_world(p, M + "RightHand", [(Vector((0, -1, 0)), 14 * math.sin(ph - 0.6) * a)])
        S.hand_pose(p, "wave", R, smootherstep(u))
        S.plant_feet(p)
        S.hip_hand_ik(p)
    return lib.keyed("Wave", T, fn, notes="hand-keyed (Mixamo 'Waving' is a two-arm overhead wave)")


def point(lib):
    """Elegant point with the right index, forward-right at chest/shoulder height; anticipation,
    slight overshoot, hold, retract. Head and chest follow the hand."""
    S = lib.S
    T = 3.0
    reach = Track([(0.0, 0.0), (0.32, 0.0, "hold"), (0.9, 1.05), (1.05, 1.0, "hold"),
                   (1.85, 1.0, "hold"), (2.6, 0.0), (T, 0.0)])
    ant = Track([(0.0, 0.0), (0.1, 0.0), (0.33, 1.0), (0.6, 0.0), (T, 0.0)])
    headk = Track([(0.0, 0.0), (0.45, 0.0), (0.95, 1.0), (1.9, 1.0), (2.65, 0.0), (T, 0.0)])

    def fn(t, p):
        hk = headk(t)
        S.rot_world(p, M + "Spine1", [(Z, -2.0 * hk)])
        S.rot_world(p, M + "Spine2", [(Z, -3.0 * hk), (X, 1.0 * hk)])
        S.rot_world(p, M + "Head", [(Z, -6.0 * hk), (Y, -2.0 * hk)])
        r = reach(t)
        sh = S.world(p, M + "RightArm").translation
        w0 = S.world(p, M + "RightHand").translation
        tgt = sh + Vector((-0.20, -0.36, -0.10))
        u = min(1.0, max(0.0, r))
        anticipate(S, p, R, ant(t), back=4.0, flex=10.0)
        w0 = S.world(p, M + "RightHand").translation
        wrist = arc_wrist(sh, w0, tgt, r, lead=0.1)
        fy = (tgt - sh).normalized() + Vector((0, 0, 0.1))
        fz = Vector((0.3, 0.0, -1.0))   # palm down/in
        q_pt = hand_frame(fy, fz)
        q0 = S.world(p, M + "RightHand").to_quaternion()
        pole = sh + Vector((-0.3, 0.3, -0.4))
        if r > 0:
            L1 = aligned_hand_local(S, p, R, tgt, pole, Vector((0.35, 0.0, -1.0)), lift=8.0)
            S.arm_ik(p, R, wrist, pole, None, pole_w=u, w=smootherstep(min(1.0, r / 0.25)))
            p.rot[M + "RightHand"] = S.base.q(M + "RightHand").slerp(L1, smootherstep(u))
        S.hand_pose(p, "point", R, smootherstep(min(1.0, max(0.0, (r - 0.2) / 0.8))))
        S.plant_feet(p)
        S.hip_hand_ik(p)
    return lib.keyed("Point", T, fn, notes="hand-keyed (Mixamo 'Pointing Gesture' is a crouching two-hand move)")


def happy_tweak(lib, t, w, p):
    """Laugh: the head lifts a little (joy, not a bow); shoulders bounce with the laugh."""
    S = lib.S
    S.rot_world(p, M + "Head", [(X, -5.0 * w)])
    b = 0.5 + 0.5 * math.sin(2 * math.pi * t * 4.0)
    S.rot_world(p, M + "LeftShoulder", [(Y, -1.2 * b * w)])
    S.rot_world(p, M + "RightShoulder", [(Y, 1.2 * b * w)])


def aligned_hand_local(S, p, side, wrist, pole, palm, lift=0.0):
    """Hand local for an arm solved at `wrist`: fingers continue the forearm (lifted `lift` deg
    towards the back of the hand), palm towards `palm` (world)."""
    pp = p.copy()
    S.arm_ik(pp, side, wrist, pole, None)
    m = S.model.fk(pp, [f"{M}{side}Hand"])
    fa = (m[f"{M}{side}Hand"].translation - m[f"{M}{side}ForeArm"].translation).normalized()
    q = hand_frame(fa, palm)
    if lift:
        q = q @ Quaternion(X, math.radians(-lift))
    S.model.set_world(pp, f"{M}{side}Hand", q)
    return pp.q(f"{M}{side}Hand")


def think(lib):
    """Thinking hold loop: right knuckles under the chin (IK, head-relative so the hand rides the
    head's micro motion), left hand on the hip, slow head drift + a finger tap. 6 s loop."""
    S = lib.S
    T = 6.0
    head_rest = S.bm[M + "Head"]
    chin = head_rest.inverted() @ Vector((-0.012, -0.100, 1.530))

    def fn(t, p):
        u = 2 * math.pi * t / T
        S.rot_world(p, M + "Spine1", [(X, 1.5)])
        S.rot_world(p, M + "Spine2", [(X, 2.0), (Z, -2.0 + 0.8 * math.sin(u + 2.0))])
        S.rot_world(p, M + "Neck", [(X, 3.0)])
        S.rot_world(p, M + "Head", [(Y, -4.0 + 1.5 * math.sin(u)), (X, 3.0 + 1.0 * math.sin(2 * u + 0.5)),
                                    (Z, -3.0 + 2.5 * math.sin(u + 1.0))])
        S.rot_world(p, M + "RightShoulder", [(Y, 2.0)])
        hm = S.world(p, M + "Head")
        c = hm @ chin
        wrist = c + Vector((-0.020, -0.035, -0.095))
        sh = S.world(p, M + "RightArm").translation
        pole = sh + Vector((0.05, -0.35, -0.55))
        fy = Vector((0.25, -0.05, 1.0))
        fz = Vector((0.45, 1.0, 0.1))          # palm towards her neck
        S.arm_ik(p, R, wrist, pole, hand_frame(fy, fz))
        S.hand_pose(p, "thinking_chin", R, 1.0)
        tap = bump(t, 2.0, 0.15, 0.3, T) + bump(t, 2.55, 0.15, 0.3, T) + bump(t, 4.8, 0.2, 0.4, T)
        S.add(p, M + "RightHandIndex1", Quaternion(X, math.radians(-12 * tap)))
        S.add(p, M + "RightHandIndex2", Quaternion(X, math.radians(-6 * tap)))
        S.plant_feet(p)
        S.hip_hand_ik(p)
    return lib.keyed("Think", T, fn, loop=True, source="hand-keyed (IK to the chin)",
                     notes="loopable hold; runtime fades in from Idle")


# ============================================================================ bake
def reduce_keys(vals, err, tol):
    """Greedy key reduction: indices to keep so that linear interpolation between kept keys stays
    within `tol` of every dropped sample (err(a, b, u, v) = error of sample v against a->b at u)."""
    n = len(vals)
    keep = [0]
    i = 0
    while i < n - 1:
        j = i + 1
        while j + 1 < n:
            ok = True
            for k in range(i + 1, j + 1):
                if err(vals[i], vals[j + 1], (k - i) / (j + 1 - i), vals[k]) > tol:
                    ok = False
                    break
            if not ok:
                break
            j += 1
        keep.append(j)
        i = j
    return keep


def _qerr(a, b, u, v):
    return rigmath.qangle(a.slerp(b, u), v)


def _verr(a, b, u, v):
    return (a.lerp(b, u) - v).length


def bake(rig_ob, made, fps=FPS, tol_deg=0.02, tol_m=0.00005):
    """One action per clip, keys on frames 0..N (LINEAR), every mixamorig bone + Hips location, one
    muted NLA track per clip (strip at frame 0). Hemisphere-consistent quaternions. Keys are reduced
    per bone where LINEAR (slerp) interpolation reproduces the dropped 30 fps samples within tol_deg
    (loc: tol_m); first/last keys always kept, so start/end/loop seams are exact."""
    import bpy
    sc = bpy.context.scene
    sc.render.fps = fps
    sc.render.fps_base = 1.0
    ob = rig_ob
    ob.animation_data_create()
    names = [pb.name for pb in ob.pose.bones if pb.name.startswith(M)]
    for pb in ob.pose.bones:
        pb.rotation_mode = "QUATERNION"
    acts = []
    for clip in made:
        old = bpy.data.actions.get(clip.name)
        if old is not None:
            bpy.data.actions.remove(old)
        act = bpy.data.actions.new(clip.name)
        act.use_fake_user = False
        n = len(clip.frames)
        for b in names:
            qs = []
            prev = None
            for p in clip.frames:
                q = p.q(b).normalized()
                if prev is not None and q.dot(prev) < 0:
                    q = -q
                qs.append(q)
                prev = q
            act.groups.new(b)
            path = f'pose.bones["{b}"].rotation_quaternion'
            keep = reduce_keys(qs, _qerr, tol_deg) if tol_deg > 0 else list(range(n))
            for c in range(4):
                fc = act.fcurves.new(path, index=c, action_group=b)
                fc.keyframe_points.add(len(keep))
                co = []
                for i in keep:
                    co += [float(i), qs[i][c]]
                fc.keyframe_points.foreach_set("co", co)
                fc.keyframe_points.foreach_set("interpolation", [1] * len(keep))   # LINEAR
                fc.update()
        path = f'pose.bones["{HIPS}"].location'
        locs = [p.loc.copy() for p in clip.frames]
        keep = reduce_keys(locs, _verr, tol_m) if tol_m > 0 else list(range(n))
        for c in range(3):
            fc = act.fcurves.new(path, index=c, action_group=HIPS)
            fc.keyframe_points.add(len(keep))
            co = []
            for i in keep:
                co += [float(i), locs[i][c]]
            fc.keyframe_points.foreach_set("co", co)
            fc.keyframe_points.foreach_set("interpolation", [1] * len(keep))
            fc.update()
        tr = ob.animation_data.nla_tracks.new()
        tr.name = clip.name
        st = tr.strips.new(clip.name, 0, act)
        st.name = clip.name
        st.action_frame_start, st.action_frame_end = 0, n - 1
        tr.mute = True
        acts.append(act)
    ob.animation_data.action = None
    return acts
