"""Masha v2 idle BASE POSE: fashion-model contrapposto, authored with IK and baked to FK (owner: Agent 3).

    blender -b <masha_v2_hands.blend> -P tools/masha_v2/anim/base_pose.py -- --out <dir> [--qa 1]
            [--hand L|R] [--poses <hand_poses.json>]

Writes <dir>/base_pose.json:
    {"bones": {bone: {"rotation_quaternion": [w,x,y,z], "location": [x,y,z]}},   # every mixamorig:* bone
     "meta":  {standing leg, hand-on-hip side, measured angles, foot heights, penetrations, ...}}
rotation_quaternion / location are Blender pose-bone LOCAL values (relative to the rest pose, i.e. what
pb.rotation_quaternion / pb.location hold). Only mixamorig:Hips has a non-zero location. The masha:*
bones (twist, breast, glute, hair, eye) are never posed: the runtime drives them. (QA below applies the
runtime twist rule from hand_poses.json only to preview the deformation; it is not written to the JSON.)

Reuse from clip authoring (Agent 4):
    sys.path.insert(0, ".../tools/masha_v2/anim"); import base_pose
    data = base_pose.load("<dir>/base_pose.json")
    base_pose.apply_base_pose(rig_ob, data)            # sets rotation_quaternion + location of every bone
    q = base_pose.base_quat(data, "mixamorig:Spine")   # to layer clip offsets on top: local = base @ delta

How the pose is built (solve()):
  1. Torso FK in character axes (Rrest^-1 R Rrest, as tools/masha_v2/export.py): pelvis roll/shift/turn,
     spine counter-roll + twist, neck/head tilt and turn.
  2. Leg IK (Blender IK constraint, chain 2, pole target in front of the knee) to ankle targets on the
     ground. The standing leg's hip height is solved so that the IK distance gives exactly
     STAND_KNEE_FLEX; the free leg's ankle target sits in front of / across the standing foot.
  3. Arm IK: hip hand -> target empty parented to mixamorig:Hips + elbow pole out/back; relaxed arm ->
     target placed so the elbow flexes RELAX_ELBOW_FLEX, pole behind the elbow.
  4. Bake: visual (constraint) transforms -> local FK quaternions; constraints and empties removed.
  5. FK details on top: foot/toe orientations (flat standing foot, ball of the free foot on the ground),
     hand orientations (built from world directions), finger poses (hand_poses.json).
  6. Feedback loops on the DEFORMED mesh: ankle heights until each sole's lowest vertex is at
     FOOT_CLEARANCE, and the hip-hand target along the palm normal until the palm rests on the hip
     with HIP_HAND_CONTACT penetration (BVHTree signed distances).
Character axes (armature = world: Z up, she faces -Y, her left is +X):
  about +X: bend forward / plantar-flex foot; about +Z: turn towards her left; about +Y: roll, + lowers
  her left side.
"""
import json
import math
import os
import sys

import bpy
from mathutils import Euler, Matrix, Quaternion, Vector
from mathutils.bvhtree import BVHTree

M = "mixamorig:"
X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))
HERE = os.path.dirname(os.path.abspath(__file__))

# ============================================================================ TUNABLES
# Sides. s = +1 her left (+X), -1 her right. The whole pose is written for STANDING = "Right"; the
# hand-on-hip side can be flipped with --hand (the left hand, on the free-leg side, reads best: see meta).
STANDING = "Right"          # weight-bearing leg
HIP_HAND = "Left"           # hand resting on the hip
SIDE_CHOICE = ("Left hand on the hip (free-leg side, as in the reference). Compared with --hand R: the "
               "left version balances the silhouette (pushed-out right hip on one side, elbow triangle on "
               "the other) and keeps the wrist natural (extension ~45 deg, no deviation, twist ~-29 deg); "
               "the right version stacks hip + elbow on one side and needs ~65 deg wrist extension.")

# --- pelvis (mixamorig:Hips), degrees / metres, world axes; signs are for STANDING = Right
PELVIS_ROLL = 4.0           # about +Y: free (left) hip drops, standing hip rises and is pushed out
PELVIS_SHIFT = 0.050        # lateral shift of the pelvis over the standing foot (m)
PELVIS_FWD_SHIFT = 0.0      # forward (-Y) shift of the pelvis (m)
PELVIS_TURN = -4.0          # about +Z: - turns the pelvis to her right, free-leg hip comes forward
PELVIS_TILT = 2.0           # about +X: slight anterior tilt

# --- spine counter-roll / twist / arch (per bone: Spine, Spine1, Spine2), degrees
SPINE_ROLL = (-3.0, -3.0, -2.5)     # counter-roll: shoulders tilt opposite to the pelvis
SPINE_TURN = (2.5, 3.0, 3.0)        # upper body twists back towards her left (left shoulder back)
SPINE_FWD = (-1.0, -1.5, -1.0)      # chest lifted (slight back arch)

# --- shoulders (clavicles) roll about +Y, degrees (+ lowers her left side)
SHOULDER_ROLL = {"Left": -2.0, "Right": 3.0}   # hip-hand shoulder slightly up, relaxed side slightly down

# --- neck / head (character axes, degrees)
NECK_TURN, HEAD_TURN = -2.5, -3.5   # counter the spine twist: face turned back towards the camera
NECK_ROLL, HEAD_ROLL = 3.5, 4.5     # tilt towards her left (continues the S curve)
NECK_FWD, HEAD_FWD = 1.5, -0.5       # keep the chin level (spine arch lifts it)

# --- legs
FOOT_CLEARANCE = 0.0015     # target height of each planted sole's lowest vertex above z=0 (m)
STAND_ANKLE_XY = (-0.070, 0.005)   # standing (right) ankle target, x/y in world (m)
STAND_KNEE_FLEX = 4.5       # anatomical knee angle of the standing leg (deg); hip height is solved for it
STAND_TOE_OUT = 10.0        # standing foot yaw, toes outwards (deg)
STAND_KNEE_POLE = (-0.03, -0.6, 0.0)   # pole offset from the knee (world), slightly outwards/forwards
FREE_ANKLE_XY = (-0.030, -0.150)    # free (left) ankle target: in front of and towards the standing foot
FREE_TOE_OUT = 22.0         # free foot yaw, toes outwards (deg)
FREE_PLANTAR = 5.0          # free foot plantar-flexion (heel lifts; ball stays on the ground), deg
FREE_TOE_BEND = 1.0         # fraction of FREE_PLANTAR given back to the toes (keeps the toes flat)
FREE_KNEE_POLE = (0.02, -0.6, 0.0)     # pole offset from the knee: forwards, following the toes out

# --- hip hand (arm IK). Positions are in REST coordinates of the Hips bone (as if the pelvis were at
# rest); the IK target empty is parented to mixamorig:Hips so it follows the pelvis.
HIP_PALM_YZ = (-0.045, 1.070)          # palm centre on the skin: y (front-back) and z of the side of
                                        # the waist/iliac crest; found by a ray cast from outside
HIP_FINGER_DIR = (0.00, -0.80, -0.60)   # first guess of the finger direction (then: from the forearm)
HIP_WRIST_DEV = 0.0                     # extra ulnar(+)/radial(-) deviation about the palm normal (deg)
HIP_FRAME_ITERS = 4                     # hand frame <-> forearm direction fixed-point iterations
HIP_SKIN_RADIUS = 0.035                 # skin normals averaged over this radius around the palm point (m)
HIP_PALM_OFFSET = (0.050, 0.020)        # palm centre from the wrist: along +Y (m), along +Z (palm pad)
HIP_ELBOW_POLE = (0.70, 0.30, -0.05)    # pole offset from the shoulder joint: out and slightly back
HIP_HAND_CONTACT = -0.0025              # wanted min signed distance hand->hip (negative = soft contact)
HIP_FINGER_FLEX = 0.45                  # finger curl as a fraction of hand pose "relaxed"
HIP_FINGER_SPREAD = 0.0                 # spread fraction of "relaxed" (fingers together)

# --- relaxed arm (arm IK). Direction from the shoulder joint to the wrist, world.
RELAX_DIR = (-0.15, -0.06, -1.0)        # for the right arm (x mirrored for the left)
RELAX_ELBOW_FLEX = 12.0                 # anatomical elbow flexion (deg)
RELAX_ELBOW_POLE = (0.0, 0.6, 0.0)      # pole offset from the elbow: behind
RELAX_WRIST_FLEX = 8.0                  # hand +X flexion on top of the aligned hand (deg)
RELAX_PALM_IN = (1.0, 0.35, 0.0)        # palm faces the thigh (right hand; x mirrored for the left)
RELAX_FINGERS = 1.0                     # fraction of hand pose "relaxed"

# --- solver loops
SOLVE_ITERS = 6                         # outer feedback loop (feet heights + hand contact)

# ============================================================================ helpers
def side_sign(side):
    return 1.0 if side == "Left" else -1.0


def other(side):
    return "Right" if side == "Left" else "Left"


def update():
    bpy.context.view_layer.update()


def rest_q(rig, name):
    return rig.data.bones[name].matrix_local.to_quaternion()


def char_rot(rig, name, rots):
    """rots: [(axis Vector, deg)] applied in order, character axes -> local pose quaternion."""
    q = Quaternion()
    for axis, deg in rots:
        if abs(deg) > 1e-9:
            q = Quaternion(axis, math.radians(deg)) @ q
    rr = rest_q(rig, name)
    pb = rig.pose.bones[name]
    pb.rotation_quaternion = rr.inverted() @ q @ rr


def set_world_rot(rig, name, R3):
    """Local rotation so the bone's armature-space orientation becomes R3 (parents must be updated)."""
    pb = rig.pose.bones[name]
    par = pb.parent
    restrel = par.bone.matrix_local.to_3x3().inverted() @ pb.bone.matrix_local.to_3x3()
    local = (par.matrix.to_3x3() @ restrel).inverted() @ R3
    pb.rotation_quaternion = local.to_quaternion().normalized()


def frame(ydir, zdir):
    """Rotation matrix whose +Y = ydir and +Z = zdir (orthogonalised)."""
    y = ydir.normalized()
    z = (zdir - zdir.dot(y) * y).normalized()
    x = y.cross(z)
    return Matrix((x, y, z)).transposed()


def mirror(v, s):
    return Vector((v[0] * s, v[1], v[2]))


def bone_vec(rig, name):
    pb = rig.pose.bones[name]
    return pb.tail - pb.head


def angle_between(a, b):
    return math.degrees(a.angle(b))


def reset(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.location = (0, 0, 0)
        pb.scale = (1, 1, 1)
        for c in list(pb.constraints):
            pb.constraints.remove(c)


def load_hand_poses(path=None):
    path = path or os.path.join(HERE, "..", "hand_poses.json")
    with open(path) as fh:
        return json.load(fh)


def set_fingers(rig, pose, side, flex=1.0, spread=1.0):
    """Finger bones of a hand_poses.json pose (the Hand bone itself is skipped): X (curl) and Y scaled
    by `flex`, Z (spread / deviation) by `spread`."""
    for bone, e in pose.items():
        if f"{M}{side}Hand" not in bone or bone == f"{M}{side}Hand":
            continue
        ex, ey, ez = (math.radians(v) for v in e)
        rig.pose.bones[bone].rotation_quaternion = Euler((ex * flex, ey * flex, ez * spread), "XYZ").to_quaternion()


# ----------------------------------------------------------------------------- IK helpers
def make_empty(name, loc, parent_rig=None, parent_bone=None):
    e = bpy.data.objects.get(name) or bpy.data.objects.new(name, None)
    if e.name not in bpy.context.scene.collection.objects:
        bpy.context.scene.collection.objects.link(e)
    if parent_rig is not None:
        e.parent = parent_rig
        e.parent_type = "BONE"
        e.parent_bone = parent_bone
        e.matrix_parent_inverse = Matrix.Identity(4)
    update()
    e.matrix_world = Matrix.Translation(loc)
    update()
    return e


def add_ik(rig, lower, target, pole):
    pb = rig.pose.bones[lower]
    c = pb.constraints.new("IK")
    c.target = target
    c.pole_target = pole
    c.chain_count = 2
    c.use_tail = True
    c.use_stretch = False
    c.iterations = 1000
    return c


def fit_pole_angle(rig, c, upper, lower, pole):
    """Pick the IK pole_angle that puts the mid joint exactly in the root-target-pole plane."""
    best = None
    for guess in (0.0, 90.0, -90.0, 180.0):
        c.pole_angle = math.radians(guess)
        update()
        for _ in range(3):
            root = rig.pose.bones[upper].head
            mid = rig.pose.bones[lower].head
            tip = rig.pose.bones[lower].tail
            axis = (tip - root).normalized()
            a = mid - root
            p = pole.matrix_world.translation - root
            a -= a.dot(axis) * axis
            p -= p.dot(axis) * axis
            phi = math.atan2(axis.dot(a.cross(p)), a.dot(p))
            c.pole_angle += phi
            update()
        root = rig.pose.bones[upper].head
        mid = rig.pose.bones[lower].head
        tip = rig.pose.bones[lower].tail
        axis = (tip - root).normalized()
        a = mid - root
        p = pole.matrix_world.translation - root
        a -= a.dot(axis) * axis
        p -= p.dot(axis) * axis
        err = abs(math.atan2(axis.dot(a.cross(p)), a.dot(p)))
        if best is None or err < best[0]:
            best = (err, c.pole_angle)
    c.pole_angle = best[1]
    update()
    return math.degrees(best[0])


def bake_chains(rig, bones):
    """Visual (constraint) transforms -> local FK rotations; constraints removed."""
    update()
    mats = {}
    for name in bones:  # parent before child
        pb = rig.pose.bones[name]
        mats[name] = rig.convert_space(pose_bone=pb, matrix=pb.matrix, from_space="POSE", to_space="LOCAL")
    for name in bones:
        pb = rig.pose.bones[name]
        for c in list(pb.constraints):
            pb.constraints.remove(c)
        pb.rotation_quaternion = mats[name].to_quaternion().normalized()
        pb.location = (0, 0, 0)
    update()


def two_bone_reach(l1, l2, flex_deg):
    """Root-to-tip distance of a two-bone chain whose anatomical flexion is flex_deg."""
    return math.sqrt(l1 * l1 + l2 * l2 + 2 * l1 * l2 * math.cos(math.radians(flex_deg)))


# ----------------------------------------------------------------------------- mesh measurement
class Regions:
    """Vertex regions of the evaluated Masha_Body (MASK applied) by dominant deform bone."""

    GROUPS = {
        "torso": ("Hips", "Spine", "Spine1", "Spine2", "breast", "glute"),
    }

    def __init__(self, body):
        self.body = body
        me = self._mesh()
        names = {g.index: g.name for g in body.vertex_groups}
        self.dom = []
        for v in me.vertices:
            best, bw = None, 0.0
            for g in v.groups:
                n = names.get(g.group, "")
                if (n.startswith(M) or n.startswith("masha:")) and g.weight > bw:
                    best, bw = n, g.weight
            self.dom.append(best or "")
        self.polys = [tuple(p.vertices) for p in me.polygons]
        self.body.evaluated_get(bpy.context.evaluated_depsgraph_get()).to_mesh_clear()

    def _mesh(self):
        dg = bpy.context.evaluated_depsgraph_get()
        return self.body.evaluated_get(dg).to_mesh()

    def coords(self):
        me = self._mesh()
        co = [v.co.copy() for v in me.vertices]
        self.body.evaluated_get(bpy.context.evaluated_depsgraph_get()).to_mesh_clear()
        return co

    def region(self, name, side=None):
        def match(d):
            if name == "torso":
                return d in (M + "Hips", M + "Spine", M + "Spine1", M + "Spine2") or \
                    d.startswith("masha:breast") or d.startswith("masha:glute")
            sd = side or ""
            sfx = ".L" if side == "Left" else ".R"
            if name == "thigh":
                return d == f"{M}{sd}UpLeg"
            if name == "shin":
                return d == f"{M}{sd}Leg"
            if name == "foot":
                return d in (f"{M}{sd}Foot", f"{M}{sd}ToeBase")
            if name == "toe":
                return d == f"{M}{sd}ToeBase"
            if name == "uparm":
                return d in (f"{M}{sd}Arm", f"masha:upperarm_twist{sfx}")
            if name == "forearm":
                return d == f"{M}{sd}ForeArm" or d.startswith("masha:forearm_twist") and d.endswith(sfx)
            if name == "hand":
                return d.startswith(f"{M}{sd}Hand")
            return False
        return {i for i, d in enumerate(self.dom) if match(d)}


def signed_query(co, polys, face_set_verts, query_verts):
    """BVH over polygons whose verts are all in face_set_verts; signed distance of each query vert.
    Returns (min signed distance, vertex index of it, list of (i, d))."""
    fs = [p for p in polys if all(i in face_set_verts for i in p)]
    if not fs or not query_verts:
        return 1e9, -1, []
    bvh = BVHTree.FromPolygons(co, fs, all_triangles=False, epsilon=0.0)
    out = []
    for i in query_verts:
        hit = bvh.find_nearest(co[i])
        if hit[0] is None:
            continue
        loc, nrm, _, dist = hit
        sgn = 1.0 if (co[i] - loc).dot(nrm) >= 0 else -1.0
        out.append((i, sgn * dist))
    if not out:
        return 1e9, -1, []
    i, d = min(out, key=lambda t: t[1])
    return d, i, out


# ----------------------------------------------------------------------------- the pose
class Solver:
    def __init__(self, rig, body, hand_poses, hip_hand=HIP_HAND, standing=STANDING):
        self.rig = rig
        self.body = body
        self.hp = hand_poses["poses"]
        self.twist = hand_poses.get("twist_rule", {})
        self.hip = hip_hand
        self.relax = other(hip_hand)
        self.stand = standing
        self.free = other(standing)
        self.ss = side_sign(standing)   # -1 for Right standing
        self.reg = Regions(body)
        self.corr = {"stand_z": 0.0, "free_z": 0.0, "hand": Vector((0, 0, 0))}
        self.info = {}

    # ---- lengths
    def length(self, name):
        b = self.rig.data.bones[name]
        return (b.tail_local - b.head_local).length

    def hips_to_world(self, p_rest):
        """Point given in rest coordinates -> world, carried by the posed Hips bone (= parenting)."""
        hb = self.rig.pose.bones[M + "Hips"]
        return (hb.matrix @ hb.bone.matrix_local.inverted()) @ Vector(p_rest)

    def hips_dir_to_world(self, d):
        hb = self.rig.pose.bones[M + "Hips"]
        return (hb.matrix.to_3x3() @ hb.bone.matrix_local.to_3x3().inverted()) @ Vector(d)

    # ---- 1. torso
    def torso(self, hips_dz):
        rig, ss = self.rig, self.ss
        # For STANDING=Right (ss=-1): roll +PELVIS_ROLL lowers the free left hip.
        f = -ss
        char_rot(rig, M + "Hips", [(X, PELVIS_TILT), (Y, f * PELVIS_ROLL), (Z, f * PELVIS_TURN)])
        hb = rig.pose.bones[M + "Hips"]
        d = Vector((ss * PELVIS_SHIFT, -PELVIS_FWD_SHIFT, hips_dz))
        hb.location = hb.bone.matrix_local.to_3x3().inverted() @ d
        for name, r, t, fw in zip(("Spine", "Spine1", "Spine2"), SPINE_ROLL, SPINE_TURN, SPINE_FWD):
            char_rot(rig, M + name, [(X, fw), (Y, f * r), (Z, f * t)])
        for side in ("Left", "Right"):
            roll = SHOULDER_ROLL["Left" if side == self.hip else "Right"]
            # roll values are written for a left hip hand; mirror sign for the right side
            char_rot(rig, f"{M}{side}Shoulder", [(Y, roll * side_sign(self.hip))])
        char_rot(rig, M + "Neck", [(X, NECK_FWD), (Y, f * NECK_ROLL), (Z, f * NECK_TURN)])
        char_rot(rig, M + "Head", [(X, HEAD_FWD), (Y, f * HEAD_ROLL), (Z, f * HEAD_TURN)])
        update()

    # ---- 2. legs
    def leg_targets(self):
        rig = self.rig
        st, fr, ss = self.stand, self.free, self.ss
        l1, l2 = self.length(f"{M}{st}UpLeg"), self.length(f"{M}{st}Leg")
        rest_ankle_z = rig.data.bones[f"{M}{st}Leg"].tail_local.z
        # STAND_ANKLE_XY is written for the right leg (x<0): mirror when standing on the left
        sx = STAND_ANKLE_XY[0] if st == "Right" else -STAND_ANKLE_XY[0]
        a_st = Vector((sx, STAND_ANKLE_XY[1], rest_ankle_z + self.corr["stand_z"]))
        # hip height so the standing leg has exactly STAND_KNEE_FLEX
        reach = two_bone_reach(l1, l2, STAND_KNEE_FLEX)
        dz = 0.0
        for _ in range(6):
            self.torso(dz)
            hip = rig.pose.bones[f"{M}{st}UpLeg"].head
            dxy = (hip.xy - a_st.xy).length
            want = a_st.z + math.sqrt(max(reach * reach - dxy * dxy, 1e-6))
            dz += want - hip.z
        self.torso(dz)
        self.info["hips_dz"] = dz
        fz = rig.data.bones[f"{M}{fr}Leg"].tail_local.z
        fx = FREE_ANKLE_XY[0] if fr == "Left" else -FREE_ANKLE_XY[0]
        a_fr = Vector((fx, FREE_ANKLE_XY[1], fz + self.corr["free_z"]))
        return a_st, a_fr

    def legs_ik(self, a_st, a_fr):
        rig = self.rig
        made = []
        errs = {}
        for side, tgt, pole_off in ((self.stand, a_st, STAND_KNEE_POLE), (self.free, a_fr, FREE_KNEE_POLE)):
            s = side_sign(side)
            knee = rig.pose.bones[f"{M}{side}Leg"].head
            t = make_empty(f"ik_{side}_foot", tgt)
            # pole offsets are written for STANDING = Right: mirror x when standing on the left
            m = 1.0 if self.stand == "Right" else -1.0
            po = Vector((pole_off[0] * m, pole_off[1], pole_off[2]))
            p = make_empty(f"ik_{side}_knee", knee + po)
            c = add_ik(rig, f"{M}{side}Leg", t, p)
            made += [t, p]
            errs[side] = (c, p)
        update()
        for side, (c, p) in errs.items():
            self.info[f"pole_err_{side}_leg"] = fit_pole_angle(rig, c, f"{M}{side}UpLeg", f"{M}{side}Leg", p)
        return made

    # ---- skin under the hip hand (posed torso + same-side thigh)
    def skin_point(self, yz, side):
        """Ray from outside (her `side`, pelvis frame) towards the body at height/depth yz (rest-hips
        coordinates): first skin hit + the skin normal averaged over HIP_SKIN_RADIUS."""
        s = side_sign(side)
        co = self.reg.coords()
        verts = self.reg.region("torso") | self.reg.region("thigh", side)
        fs = [f for f in self.reg.polys if all(i in verts for i in f)]
        bvh = BVHTree.FromPolygons(co, fs, all_triangles=False, epsilon=0.0)
        origin = self.hips_to_world(Vector((0.5 * s, yz[0], yz[1])))
        d = self.hips_dir_to_world(Vector((-s, 0, 0))).normalized()
        loc, nrm, _, _ = bvh.ray_cast(origin, d)
        if loc is None:
            raise RuntimeError("hip-hand ray missed the body; check HIP_PALM_YZ")
        hits = bvh.find_nearest_range(loc, HIP_SKIN_RADIUS)
        n = Vector()
        for hl, hn, _, hd in hits:
            n += hn * (1.0 - hd / HIP_SKIN_RADIUS)
        n = n.normalized() if n.length > 1e-9 else nrm
        return loc, n

    # ---- 3. arms
    def arms_ik(self):
        rig = self.rig
        made = []
        # hip hand. The palm lies flat on the hip: hand +Z = -(skin normal at HIP_PALM_YZ); hand +Y
        # = forearm direction projected on the skin's tangent plane (no radial/ulnar deviation beyond
        # HIP_WRIST_DEV), so the wrist extension is exactly the angle at which the forearm meets the hip.
        h, s = self.hip, side_sign(self.hip)
        p_s, n_out = self.skin_point(HIP_PALM_YZ, h)
        z = -n_out
        fdir = self.hips_dir_to_world(mirror(HIP_FINGER_DIR, s)).normalized()   # first guess
        t = p = c = None
        for k in range(HIP_FRAME_ITERS):
            y = fdir - fdir.dot(z) * z
            y = Quaternion(z, math.radians(HIP_WRIST_DEV * s)) @ y.normalized()
            R = frame(y, z)
            wrist = p_s + self.corr["hand"] - HIP_PALM_OFFSET[0] * R.col[1] - HIP_PALM_OFFSET[1] * R.col[2]
            if t is None:
                # target empty parented to Hips (placed at the world wrist position)
                t = make_empty(f"ik_{h}_hand", wrist, rig, M + "Hips")
                sh = rig.pose.bones[f"{M}{h}Arm"].head
                p = make_empty(f"ik_{h}_elbow", sh + mirror(HIP_ELBOW_POLE, s))
                c = add_ik(rig, f"{M}{h}ForeArm", t, p)
                update()
                self.info["pole_err_hip_arm"] = fit_pole_angle(rig, c, f"{M}{h}Arm", f"{M}{h}ForeArm", p)
            else:
                t.matrix_world = Matrix.Translation(wrist)
                update()
            fdir = bone_vec(rig, f"{M}{h}ForeArm").normalized()
        self.hip_R = R
        self.hip_skin = (p_s, n_out)
        self.hip_wrist_target = wrist.copy()
        made += [t, p]
        # relaxed arm
        r, s = self.relax, side_sign(self.relax)
        l1, l2 = self.length(f"{M}{r}Arm"), self.length(f"{M}{r}ForeArm")
        sh = rig.pose.bones[f"{M}{r}Arm"].head
        d = Vector((RELAX_DIR[0] * -s, RELAX_DIR[1], RELAX_DIR[2])).normalized()
        wrist = sh + d * two_bone_reach(l1, l2, RELAX_ELBOW_FLEX)
        t = make_empty(f"ik_{r}_hand", wrist)
        p = make_empty(f"ik_{r}_elbow", sh + d * l1 + Vector(RELAX_ELBOW_POLE))
        c = add_ik(rig, f"{M}{r}ForeArm", t, p)
        update()
        self.info["pole_err_relax_arm"] = fit_pole_angle(rig, c, f"{M}{r}Arm", f"{M}{r}ForeArm", p)
        made += [t, p]
        return made

    # ---- 5. FK details
    def feet(self):
        rig = self.rig
        for side in (self.stand, self.free):
            s = side_sign(side)
            free = side == self.free
            yaw = (FREE_TOE_OUT if free else STAND_TOE_OUT) * s
            pitch = FREE_PLANTAR if free else 0.0
            Rz = Matrix.Rotation(math.radians(yaw), 3, Z)
            Rx = Matrix.Rotation(math.radians(pitch), 3, X)
            foot = f"{M}{side}Foot"
            set_world_rot(rig, foot, Rz @ Rx @ rig.data.bones[foot].matrix_local.to_3x3())
            update()
            toe = f"{M}{side}ToeBase"
            Rt = Matrix.Rotation(math.radians(pitch * (1 - FREE_TOE_BEND)), 3, X)
            set_world_rot(rig, toe, Rz @ Rt @ rig.data.bones[toe].matrix_local.to_3x3())
            update()

    def hands(self):
        rig = self.rig
        # hip hand: world orientation solved in arms_ik (palm on the skin, fingers continue the forearm)
        set_world_rot(rig, f"{M}{self.hip}Hand", self.hip_R)
        set_fingers(rig, self.hp["relaxed"], self.hip, HIP_FINGER_FLEX, HIP_FINGER_SPREAD)
        # relaxed hand: aligned with the forearm, palm towards the thigh, then a soft wrist flexion
        r, s = self.relax, side_sign(self.relax)
        fa = bone_vec(rig, f"{M}{r}ForeArm").normalized()
        pin = Vector((RELAX_PALM_IN[0] * -s, RELAX_PALM_IN[1], RELAX_PALM_IN[2]))
        R = frame(fa, pin) @ Matrix.Rotation(math.radians(RELAX_WRIST_FLEX), 3, X)
        set_world_rot(rig, f"{M}{r}Hand", R)
        set_fingers(rig, self.hp["relaxed"], r, RELAX_FINGERS, RELAX_FINGERS)
        update()

    # ---- twist preview (runtime rule, QA only)
    def apply_twist(self, on=True):
        rig = self.rig
        for side, sfx in (("Left", "L"), ("Right", "R")):
            fa = rig.data.bones[f"{M}{side}ForeArm"]
            hb = rig.pose.bones[f"{M}{side}Hand"]
            r0 = (fa.matrix_local.to_3x3().inverted() @ hb.bone.matrix_local.to_3x3()).to_quaternion()
            qf = r0 @ hb.rotation_quaternion @ r0.inverted()
            a = 2 * math.atan2(qf.y, qf.w)
            for base, fac in (("forearm_twist_mid", 1 / 3), ("forearm_twist", 2 / 3)):
                tb = rig.pose.bones.get(f"masha:{base}.{sfx}")
                if tb:
                    tb.rotation_mode = "QUATERNION"
                    tb.rotation_quaternion = Quaternion(Y, fac * a if on else 0.0)
            ub = rig.pose.bones.get(f"masha:upperarm_twist.{sfx}")
            if ub:
                q = rig.pose.bones[f"{M}{side}Arm"].rotation_quaternion
                ub.rotation_mode = "QUATERNION"
                ub.rotation_quaternion = Quaternion(Y, -0.5 * 2 * math.atan2(q.y, q.w) if on else 0.0)
            self.info[f"hand_twist_{side}_deg"] = round(math.degrees(a), 1)
        update()

    # ---- one full solve
    def solve_once(self):
        rig = self.rig
        reset(rig)
        a_st, a_fr = self.leg_targets()
        empties = self.legs_ik(a_st, a_fr)
        empties += self.arms_ik()
        chain = [f"{M}{s}{b}" for s in ("Left", "Right") for b in ("UpLeg", "Leg", "Arm", "ForeArm")]
        chain.sort(key=lambda n: ("Leg" in n and "Up" not in n, "ForeArm" in n))  # parents first
        self.ik_err = {
            "stand_ankle_mm": (rig.pose.bones[f"{M}{self.stand}Leg"].tail - a_st).length * 1000,
            "free_ankle_mm": (rig.pose.bones[f"{M}{self.free}Leg"].tail - a_fr).length * 1000,
            "hip_wrist_mm": (rig.pose.bones[f"{M}{self.hip}ForeArm"].tail - self.hip_wrist_target).length * 1000,
        }
        bake_chains(rig, chain)
        for e in empties:
            bpy.data.objects.remove(e, do_unlink=True)
        update()
        self.feet()
        self.hands()
        self.apply_twist(True)

    # ---- measurements on the deformed mesh
    def measure(self):
        co = self.reg.coords()
        R = self.reg
        res = {}
        for side in (self.stand, self.free):
            foot = R.region("foot", side)
            ank = self.rig.pose.bones[f"{M}{side}Foot"].head
            fwd = (self.rig.pose.bones[f"{M}{side}ToeBase"].head - ank)
            fwd.z = 0
            fwd.normalize()
            heel = [i for i in foot if (co[i] - ank).dot(fwd) < -0.01]
            ball = [i for i in foot if (co[i] - ank).dot(fwd) > 0.06]
            res[side] = {
                "sole_min_mm": min(co[i].z for i in foot) * 1000,
                "heel_min_mm": min(co[i].z for i in heel) * 1000,
                "ball_min_mm": min(co[i].z for i in ball) * 1000,
            }
        self.feet_m = res
        return co

    def penetrations(self, co):
        R = self.reg
        P = R.polys
        h, r = self.hip, self.relax
        torso = R.region("torso")
        out = {}
        # hip hand & forearm vs torso + same-side thigh
        body_h = torso | R.region("thigh", h)
        d, i, _ = signed_query(co, P, body_h, R.region("hand", h))
        out["hip_hand_vs_hip"] = {"min_signed_mm": d * 1000, "vert": i}
        d, i, _ = signed_query(co, P, body_h, R.region("forearm", h))
        out["hip_forearm_vs_torso"] = {"min_signed_mm": d * 1000, "vert": i}
        # relaxed hand & forearm vs torso + thigh (want a small gap, no contact)
        body_r = torso | R.region("thigh", r)
        d, i, _ = signed_query(co, P, body_r, R.region("hand", r))
        out["relax_hand_vs_body"] = {"min_signed_mm": d * 1000, "vert": i}
        d, i, _ = signed_query(co, P, body_r, R.region("forearm", r))
        out["relax_forearm_vs_body"] = {"min_signed_mm": d * 1000, "vert": i}
        # upper arms vs torso, away from the shoulder joint (the armpit fold is skin-to-skin anyway)
        for side in ("Left", "Right"):
            sh = self.rig.pose.bones[f"{M}{side}Arm"].head
            q = {k for k in R.region("uparm", side) if (co[k] - sh).length > 0.12}
            d, i, _ = signed_query(co, P, torso, q)
            out[f"uparm_{side}_vs_torso"] = {"min_signed_mm": d * 1000, "vert": i}
        # leg vs leg below the upper thigh (inner thighs touch naturally at the top)
        def leg(side):
            return {k for k in R.region("thigh", side) | R.region("shin", side) | R.region("foot", side)
                    if co[k].z < 0.70}
        lL, lR = leg("Left"), leg("Right")
        d1, i1, _ = signed_query(co, P, lR, lL)
        d2, i2, _ = signed_query(co, P, lL, lR)
        out["leg_vs_leg"] = {"min_signed_mm": min(d1, d2) * 1000, "vert": i1 if d1 < d2 else i2}
        # info only: inner upper thighs (skin-to-skin contact is anatomical there, also at rest)
        uL = {k for k in R.region("thigh", "Left") if co[k].z >= 0.70}
        uR = {k for k in R.region("thigh", "Right") if co[k].z >= 0.70}
        d, i, _ = signed_query(co, P, uR, uL)
        out["upper_thighs_info"] = {"min_signed_mm": d * 1000, "vert": i}
        self.pen = out
        return out

    # ---- full solve with feedback
    def solve(self):
        for it in range(SOLVE_ITERS):
            self.solve_once()
            co = self.measure()
            pen = self.penetrations(co)
            fs = self.feet_m[self.stand]["sole_min_mm"] / 1000
            ff = self.feet_m[self.free]["ball_min_mm"] / 1000
            dh = pen["hip_hand_vs_hip"]["min_signed_mm"] / 1000
            print(f"BASEPOSE iter {it}: stand sole {fs*1000:.1f} mm, free ball {ff*1000:.1f} mm, "
                  f"heel {self.feet_m[self.free]['heel_min_mm']:.1f} mm, hip hand {dh*1000:.1f} mm, "
                  f"ik {self.ik_err}")
            done = True
            if abs(fs - FOOT_CLEARANCE) > 0.0005:
                self.corr["stand_z"] -= fs - FOOT_CLEARANCE
                done = False
            if abs(ff - FOOT_CLEARANCE) > 0.0005:
                self.corr["free_z"] -= ff - FOOT_CLEARANCE
                done = False
            if abs(dh - HIP_HAND_CONTACT) > 0.001:
                # move the wrist along the palm normal (into the body if there is a gap)
                self.corr["hand"] += self.hip_R.col[2] * (dh - HIP_HAND_CONTACT)
                done = False
            if done:
                break
        self.report()

    def report(self):
        rig = self.rig
        pb = rig.pose.bones

        def flex(a, b):
            return angle_between(bone_vec(rig, a), bone_vec(rig, b))

        hips = pb[M + "Hips"]
        lh, rh = pb[M + "LeftUpLeg"].head, pb[M + "RightUpLeg"].head
        ls, rs = pb[M + "LeftArm"].head, pb[M + "RightArm"].head
        head = pb[M + "Head"].matrix.to_3x3() @ rig.data.bones[M + "Head"].matrix_local.to_3x3().inverted()
        fwd = head @ Vector((0, -1, 0))
        hx = head @ X
        for side in ("Left", "Right"):
            # wrist swing in the hand frame: forearm direction seen from the hand (twist: hand_twist_*)
            hm = pb[f"{M}{side}Hand"].matrix.to_3x3()
            f = hm.transposed() @ bone_vec(rig, f"{M}{side}ForeArm").normalized()
            self.info[f"wrist_{side}_flex_deg"] = math.degrees(math.atan2(-f.z, f.y))
            self.info[f"wrist_{side}_dev_deg"] = math.degrees(math.atan2(f.x, f.y))
        self.info.update({
            "pelvis_line_deg": math.degrees(math.atan2(lh.z - rh.z, lh.x - rh.x)),
            "shoulder_line_deg": math.degrees(math.atan2(ls.z - rs.z, ls.x - rs.x)),
            "hips_offset_m": list(round(v, 4) for v in (hips.head - rig.data.bones[M + "Hips"].head_local)),
            "stand_knee_deg": flex(f"{M}{self.stand}UpLeg", f"{M}{self.stand}Leg"),
            "free_knee_deg": flex(f"{M}{self.free}UpLeg", f"{M}{self.free}Leg"),
            "hip_elbow_deg": flex(f"{M}{self.hip}Arm", f"{M}{self.hip}ForeArm"),
            "relax_elbow_deg": flex(f"{M}{self.relax}Arm", f"{M}{self.relax}ForeArm"),
            "head_yaw_deg": math.degrees(math.atan2(fwd.x, -fwd.y)),
            "head_pitch_deg": math.degrees(math.asin(max(-1, min(1, fwd.z)))),
            "head_roll_deg": math.degrees(math.atan2(hx.z, hx.x)),
            "chest_yaw_deg": self.yaw(M + "Spine2"),
            "pelvis_yaw_deg": self.yaw(M + "Hips"),
            "head_yaw_rel_chest_deg": math.degrees(math.atan2(fwd.x, -fwd.y)) - self.yaw(M + "Spine2"),
            "free_ankle_ahead_m": (pb[f"{M}{self.stand}Foot"].head - pb[f"{M}{self.free}Foot"].head).y,
            "free_ankle_cross_m": pb[f"{M}{self.free}Foot"].head.x - pb[f"{M}{self.stand}Foot"].head.x,
            "wrist_local_euler_deg": [round(math.degrees(a), 1) for a in
                                      pb[f"{M}{self.hip}Hand"].rotation_quaternion.to_euler("XYZ")],
            "relax_wrist_local_euler_deg": [round(math.degrees(a), 1) for a in
                                            pb[f"{M}{self.relax}Hand"].rotation_quaternion.to_euler("XYZ")],
        })
        for k, v in self.info.items():
            print(f"BASEPOSE {k}: {v if not isinstance(v, float) else round(v, 2)}")
        for k, v in self.feet_m.items():
            print(f"BASEPOSE foot {k}: " + ", ".join(f"{a}={b:.1f}" for a, b in v.items()))
        for k, v in self.pen.items():
            print(f"BASEPOSE pen {k}: {v['min_signed_mm']:.1f} mm")

    def yaw(self, name):
        """World yaw (deg, + = towards her left) of a bone's rest-forward (-Y) direction."""
        pb = self.rig.pose.bones[name]
        m = pb.matrix.to_3x3() @ pb.bone.matrix_local.to_3x3().inverted()
        f = m @ Vector((0, -1, 0))
        return math.degrees(math.atan2(f.x, -f.y))

    # ---- export
    def data(self):
        rig = self.rig
        bones = {}
        for pb in rig.pose.bones:
            if not pb.name.startswith(M):
                continue
            q = pb.rotation_quaternion.normalized()
            loc = pb.location if pb.name == M + "Hips" else Vector()
            bones[pb.name] = {"rotation_quaternion": [round(v, 6) for v in q],
                              "location": [round(v, 6) for v in loc]}
        meta = {
            "standing_leg": self.stand, "free_leg": self.free, "hand_on_hip": self.hip,
            "relaxed_arm": self.relax, "side_choice": SIDE_CHOICE,
            "space": "Blender pose-bone local (relative to rest), quaternion w,x,y,z; Hips location in "
                     "its bone-local axes. masha:* bones are not posed.",
            "foot_heights_mm": {k: {a: round(b, 2) for a, b in v.items()} for k, v in self.feet_m.items()},
            "penetration_mm": {k: round(v["min_signed_mm"], 2) for k, v in self.pen.items()},
            "measured": {k: (round(v, 3) if isinstance(v, float) else v) for k, v in self.info.items()},
            "ik_residual_mm": {k: round(v, 3) for k, v in self.ik_err.items()},
        }
        return {"bones": bones, "meta": meta}


# ============================================================================ reuse API
def load(path):
    with open(path) as fh:
        return json.load(fh)


def base_quat(data, bone):
    b = data["bones"].get(bone)
    return Quaternion(b["rotation_quaternion"]) if b else Quaternion()


def base_loc(data, bone):
    b = data["bones"].get(bone)
    return Vector(b["location"]) if b else Vector()


def apply_base_pose(rig, data, bones=None):
    """Set every mixamorig:* pose bone of `rig` to the base pose (rotation_quaternion + location)."""
    for name, v in data["bones"].items():
        if bones is not None and name not in bones:
            continue
        pb = rig.pose.bones.get(name)
        if pb is None:
            continue
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = Quaternion(v["rotation_quaternion"])
        pb.location = Vector(v["location"])


# ============================================================================ QA renders
def qa_renders(out_dir, solver, tag=""):
    sc = bpy.context.scene
    os.makedirs(out_dir, exist_ok=True)
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "OBJECT"
    sh.show_cavity = True
    sh.cavity_type = "BOTH"
    sh.show_shadows = False
    sc.render.resolution_x, sc.render.resolution_y = 640, 960
    if sc.world is None:
        sc.world = bpy.data.worlds.new("qa")
    sc.world.color = (0.08, 0.08, 0.09)
    for o in bpy.data.objects:
        if o.type == "MESH":
            o.color = (0.58, 0.64, 0.8, 1)
            o.hide_render = False
    hair = bpy.data.objects.get("Masha_Hair")
    if hair:
        hair.color = (0.45, 0.5, 0.7, 1)
    if "qa_ground" not in bpy.data.objects:
        bpy.ops.mesh.primitive_plane_add(size=3.0, location=(0, 0, 0))
        g = bpy.context.active_object
        g.name = "qa_ground"
        g.color = (0.22, 0.24, 0.27, 1)
    cam = bpy.data.objects.get("qa_cam") or bpy.data.objects.new("qa_cam", bpy.data.cameras.new("qa_cam"))
    if cam.name not in sc.collection.objects:
        sc.collection.objects.link(cam)
    sc.camera = cam
    hand = solver.rig.pose.bones[f"{M}{solver.hip}Hand"].head.copy()
    s = side_sign(solver.hip)
    shots = [
        ("front", (0, -4.2, 1.0), (0, 0, 0.9), 70, (640, 960)),
        ("34_left", (2.9, -3.0, 1.15), (0, 0, 0.9), 70, (640, 960)),
        ("34_right", (-2.9, -3.0, 1.15), (0, 0, 0.9), 70, (640, 960)),
        ("profile_left", (4.2, 0, 1.0), (0, 0, 0.9), 70, (640, 960)),
        ("profile_right", (-4.2, 0, 1.0), (0, 0, 0.9), 70, (640, 960)),
        ("back", (0, 4.2, 1.0), (0, 0, 0.9), 70, (640, 960)),
        ("hand_hip", tuple(hand + Vector((0.75 * s, -0.75, 0.2))), tuple(hand + Vector((0.03 * s, 0.0, 0.03))), 50, (800, 800)),
        ("hand_hip_front", tuple(hand + Vector((0.0, -1.1, 0.1))), tuple(hand + Vector((0.0, 0.0, 0.03))), 50, (800, 800)),
        ("hand_hip_back", tuple(hand + Vector((0.6 * s, 0.8, 0.2))), tuple(hand + Vector((0.03 * s, 0.0, 0.03))), 50, (800, 800)),
        ("feet_front", (0.0, -1.3, 0.3), (0, -0.04, 0.06), 50, (900, 600)),
        ("feet_side", (1.3, -0.1, 0.12), (0, -0.04, 0.04), 50, (900, 600)),
        ("feet_side_r", (-1.3, -0.1, 0.12), (0, -0.04, 0.04), 50, (900, 600)),
    ]
    paths = []
    for name, loc, tgt, lens, res in shots:
        cam.location = Vector(loc)
        cam.rotation_euler = (Vector(tgt) - cam.location).to_track_quat("-Z", "Y").to_euler()
        cam.data.lens = lens
        cam.data.clip_start = 0.01
        sc.render.resolution_x, sc.render.resolution_y = res
        sc.render.filepath = os.path.join(out_dir, f"{tag}{name}.png")
        bpy.ops.render.render(write_still=True)
        paths.append(sc.render.filepath)
    return paths


# ============================================================================ main
def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = {"out": os.path.abspath("base_pose_out"), "qa": "0", "hand": HIP_HAND[0], "poses": ""}
    for i in range(0, len(argv) - 1, 2):
        out[argv[i].lstrip("-")] = argv[i + 1]
    return out


def main():
    args = parse_args()
    out = os.path.abspath(args["out"])
    os.makedirs(out, exist_ok=True)
    rig = bpy.data.objects["Masha_Rig"]
    body = bpy.data.objects["Masha_Body"]
    hand = "Left" if args["hand"].upper().startswith("L") else "Right"
    solver = Solver(rig, body, load_hand_poses(args["poses"] or None), hip_hand=hand)
    solver.solve()
    data = solver.data()
    name = "base_pose.json" if hand == HIP_HAND else f"base_pose_hand{hand[0]}.json"
    with open(os.path.join(out, name), "w") as fh:
        json.dump(data, fh, indent=1)
    print("BASEPOSE wrote", os.path.join(out, name))
    # round trip: the JSON alone must reproduce the solved pose (what Agent 4 / the runtime will get)
    ref = solver.reg.coords()
    reset(rig)
    apply_base_pose(rig, load(os.path.join(out, name)))
    update()
    solver.apply_twist(True)
    err = max((a - b).length for a, b in zip(ref, solver.reg.coords()))
    print(f"BASEPOSE round-trip max vertex error: {err * 1000:.4f} mm")
    if args["qa"] not in ("0", "", "false"):
        tag = "" if hand == HIP_HAND else f"hand{hand[0]}_"
        for p in qa_renders(os.path.join(out, "qa"), solver, tag):
            print("BASEPOSE render", p)


if __name__ == "__main__":
    main()
