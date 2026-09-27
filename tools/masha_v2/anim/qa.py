"""Clip QA for Masha (owner: Agent 4): numeric checks + Workbench frame strips / contact sheets.

Used by build_anim.py (`--qa 1`). Numbers per clip:
  end_err_deg     max angle (any mixamorig bone) between first/last frame and the base pose (+ Hips loc mm)
  seam_deg        loops: angle jump last->first compared with the neighbouring frame steps
  foot_drift_mm   max displacement of each planted ankle / toe from its base position
  foot_min_mm     lowest ankle/toe joint height change vs base (sinking < 0)
  hip_hand_mm     hip-hand wrist slide relative to the pelvis while it is meant to rest there
  max_vel_dps     peak joint angular velocity (deg/s) and the bone, max accel spike ratio (pops)
  pen_mm          worst penetration (signed distance < 0) of arms/hands vs torso and legs vs legs,
                  on the evaluated mesh at sampled frames (BVH), with the runtime twist rule applied
"""
import math
import os

import bpy
import numpy as np
from mathutils import Quaternion, Vector

import rigmath
from rigmath import M, Y

HERE = os.path.dirname(os.path.abspath(__file__))


# ============================================================================ twist preview
def apply_twist(rig_ob):
    """Runtime twist rule (hand_poses.json twist_rule, as base_pose.Solver.apply_twist): preview only."""
    for side, sfx in (("Left", "L"), ("Right", "R")):
        fa = rig_ob.data.bones[f"{M}{side}ForeArm"]
        hb = rig_ob.pose.bones[f"{M}{side}Hand"]
        r0 = (fa.matrix_local.to_3x3().inverted() @ hb.bone.matrix_local.to_3x3()).to_quaternion()
        qf = r0 @ hb.rotation_quaternion @ r0.inverted()
        if qf.w < 0:          # same as SpringBones.kt twistAngle(): hemisphere first
            qf = -qf
        a = 2 * math.atan2(qf.y, qf.w)
        for base, fac in (("forearm_twist_mid", 1 / 3), ("forearm_twist", 2 / 3)):
            tb = rig_ob.pose.bones.get(f"masha:{base}.{sfx}")
            if tb:
                tb.rotation_mode = "QUATERNION"
                tb.rotation_quaternion = Quaternion(Y, fac * a)
        ub = rig_ob.pose.bones.get(f"masha:upperarm_twist.{sfx}")
        if ub:
            q = rig_ob.pose.bones[f"{M}{side}Arm"].rotation_quaternion.copy()
            if q.w < 0:
                q = -q
            ub.rotation_mode = "QUATERNION"
            ub.rotation_quaternion = Quaternion(Y, -0.5 * 2 * math.atan2(q.y, q.w))


def show_pose(rig_ob, pose):
    rigmath.apply_to_bpy(rig_ob, pose)
    apply_twist(rig_ob)
    bpy.context.view_layer.update()


# ============================================================================ numbers
def numeric(model, clip, base):
    """clip: dict(name, frames=[Pose], loop, plant, hip_hand=[weights]) -> metrics dict."""
    fr = clip["frames"]
    n = len(fr)
    names = model.mixamo
    res = {}
    # endpoints
    def perr(p):
        a = max(rigmath.qangle(p.q(b), base.q(b)) for b in names)
        return a, (p.loc - base.loc).length * 1000
    if clip.get("loop"):
        a0, l0 = perr(fr[0]) if clip.get("loop_from_base") else (0.0, 0.0)
        res["end_err_deg"] = round(a0, 4)
        seam = max(rigmath.qangle(fr[-1].q(b), fr[0].q(b)) for b in names)
        # "seamless": the wrap step (last -> first frame is one frame step: last == first means the
        # clip is sampled 0..T with T == 0; compare the step first->second vs last-1 -> last)
        step_a = max(rigmath.qangle(fr[1].q(b), fr[0].q(b)) for b in names)
        step_b = max(rigmath.qangle(fr[-1].q(b), fr[-2].q(b)) for b in names)
        res["seam_deg"] = round(seam, 4)
        res["seam_steps_deg"] = (round(step_b, 3), round(step_a, 3))
    else:
        a0, l0 = perr(fr[0])
        a1, l1 = perr(fr[-1])
        res["end_err_deg"] = round(max(a0, a1), 4)
        res["end_err_loc_mm"] = round(max(l0, l1), 3)
    # feet
    bm = model.fk(base)
    drift = {}
    low = 0.0
    feet = [M + s + b for s in ("Left", "Right") for b in ("Foot", "ToeBase")]
    toe_tips = {}
    mats_all = [model.fk(p) for p in fr]
    for f, mats in enumerate(mats_all):
        planted = clip.get("plant_w", [1.0] * n)[f] > 0.999
        for b in feet:
            d = (mats[b].translation - bm[b].translation)
            if planted:
                drift[b] = max(drift.get(b, 0.0), d.length * 1000)
            low = min(low, d.z * 1000)
    res["foot_drift_mm"] = round(max(drift.values()) if drift else 0.0, 3)
    res["foot_dz_min_mm"] = round(low, 3)
    # hip hand slide relative to the pelvis
    hw = clip.get("hip_w")
    if hw is not None:
        hb = M + "Hips"
        wb = M + "LeftHand"
        ref = bm[hb].inverted() @ bm[wb].translation
        slide = 0.0
        for f, mats in enumerate(mats_all):
            if hw[f] > 0.999:
                slide = max(slide, ((mats[hb].inverted() @ mats[wb].translation) - ref).length * 1000)
        res["hip_hand_slide_mm"] = round(slide, 3)
    # velocities (local rotation per bone), accel spikes
    vmax, vb = 0.0, ""
    spike, sb = 0.0, ""
    fps = 30.0
    for b in names:
        vs = [rigmath.qangle(fr[i + 1].q(b), fr[i].q(b)) * fps for i in range(n - 1)]
        if clip.get("loop"):
            vs.append(rigmath.qangle(fr[0].q(b), fr[-1].q(b)) * fps)
        m = max(vs) if vs else 0.0
        if m > vmax:
            vmax, vb = m, b
        # pop detector: a step much larger than both neighbours (second difference on angle steps)
        for i in range(1, len(vs) - 1):
            s = vs[i] - 0.5 * (vs[i - 1] + vs[i + 1])
            if s > spike:
                spike, sb = s, b
    res["max_vel_dps"] = (round(vmax, 1), vb.replace(M, ""))
    res["pop_dps"] = (round(spike, 1), sb.replace(M, ""))
    return res


class Penetration:
    """Worst signed distance of arms/hands vs torso(+thigh) and legs vs legs on the evaluated mesh."""

    def __init__(self, body):
        import base_pose
        self.bp = base_pose
        self.reg = base_pose.Regions(body)
        R = self.reg
        self.torso = R.region("torso")
        self.sets = {}
        for s in ("Left", "Right"):
            self.sets[s] = {"hand": R.region("hand", s), "forearm": R.region("forearm", s),
                            "uparm": R.region("uparm", s), "thigh": R.region("thigh", s),
                            "shin": R.region("shin", s), "foot": R.region("foot", s)}

    def measure(self, rig_ob):
        co = self.reg.coords()
        P = self.reg.polys
        sq = self.bp.signed_query
        out = {}
        for s in ("Left", "Right"):
            S = self.sets[s]
            body = self.torso | S["thigh"]
            d1, _, _ = sq(co, P, body, S["hand"])
            d2, _, _ = sq(co, P, body, S["forearm"])
            sh = rig_ob.pose.bones[f"{M}{s}Arm"].head
            q = {k for k in S["uparm"] if (co[k] - sh).length > 0.12}
            d3, _, _ = sq(co, P, self.torso, q)
            out[f"{s}_hand"] = d1 * 1000
            out[f"{s}_forearm"] = d2 * 1000
            out[f"{s}_uparm"] = d3 * 1000
        # hand vs face/head is not on Masha_Body; legs vs legs below the upper thigh
        def leg(s):
            S = self.sets[s]
            return {k for k in S["thigh"] | S["shin"] | S["foot"] if co[k].z < 0.70}
        lL, lR = leg("Left"), leg("Right")
        d1, _, _ = sq(co, P, lR, lL)
        d2, _, _ = sq(co, P, lL, lR)
        out["legs"] = min(d1, d2) * 1000
        # sole height (lowest foot vertex)
        feet = self.sets["Left"]["foot"] | self.sets["Right"]["foot"]
        out["sole_min"] = min(co[i].z for i in feet) * 1000
        return out


# ============================================================================ renders
def setup_render(res=(260, 400)):
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "OBJECT"
    sh.show_cavity = True
    sh.cavity_type = "BOTH"
    sh.show_shadows = False
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = "PNG"
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
        me = bpy.data.meshes.new("qa_ground")
        me.from_pydata([(-1.5, -1.5, 0), (1.5, -1.5, 0), (1.5, 1.5, 0), (-1.5, 1.5, 0)], [], [(0, 1, 2, 3)])
        g = bpy.data.objects.new("qa_ground", me)
        sc.collection.objects.link(g)
        g.color = (0.22, 0.24, 0.27, 1)
    cam = bpy.data.objects.get("qa_cam") or bpy.data.objects.new("qa_cam", bpy.data.cameras.new("qa_cam"))
    if cam.name not in sc.collection.objects:
        sc.collection.objects.link(cam)
    sc.camera = cam
    return cam


VIEWS = {
    "front": ((0, -4.4, 1.0), (0, 0, 0.9), 62),
    "34": ((2.6, -3.4, 1.15), (0, 0, 0.9), 62),       # her left front (hip-hand side)
    "34r": ((-2.6, -3.4, 1.15), (0, 0, 0.9), 62),     # her right front (gesture side)
    "side": ((4.4, 0.0, 1.0), (0, 0, 0.9), 62),
    "close": ((-1.0, -1.5, 1.55), (-0.05, 0, 1.35), 60),     # upper body, her right front
}


def _look(cam, view):
    loc, tgt, lens = VIEWS[view]
    cam.location = Vector(loc)
    cam.rotation_euler = (Vector(tgt) - cam.location).to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = lens
    cam.data.clip_start = 0.01


def _render_px(path):
    sc = bpy.context.scene
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    img = bpy.data.images.load(path, check_existing=False)
    w, h = img.size
    a = np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4)
    bpy.data.images.remove(img)
    return a


def _save_px(a, path):
    h, w = a.shape[:2]
    img = bpy.data.images.new(os.path.basename(path), w, h, alpha=True)
    img.pixels.foreach_set(a.ravel())
    img.filepath_raw = path
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)


def _label(a, text_rows):
    """Tiny frame index marks: a bar at the top whose length encodes the frame position."""
    return a


def render_strip(rig_ob, frames, idx, out_png, views=("front", "34r"), tmp=None):
    """Renders the poses frames[i] for i in idx, one row per view, tiles side by side -> out_png."""
    cam = setup_render()
    tmp = tmp or os.path.join(os.path.dirname(out_png), "_tile.png")
    rows = []
    for v in views:
        _look(cam, v)
        tiles = []
        for i in idx:
            show_pose(rig_ob, frames[i])
            px = _render_px(tmp)
            # progress bar (time position) at the bottom of each tile
            h, w = px.shape[:2]
            frac = i / max(1, len(frames) - 1)
            px[0:5, :, :3] = 0.15
            px[0:5, : max(1, int(w * frac)), :3] = (0.95, 0.6, 0.2)
            px[:, -2:, :3] = 0.0
            tiles.append(px)
        rows.append(np.concatenate(tiles, axis=1))
    sheet = np.concatenate(rows[::-1], axis=0)   # image rows are bottom-up
    _save_px(sheet, out_png)
    if os.path.isfile(tmp):
        os.remove(tmp)
    return out_png


def contact_sheet(pngs, out_png, cols=1):
    ims = []
    for p in pngs:
        img = bpy.data.images.load(p, check_existing=False)
        w, h = img.size
        ims.append(np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4))
        bpy.data.images.remove(img)
    W = max(i.shape[1] for i in ims)
    ims = [np.pad(i, ((0, 0), (0, W - i.shape[1]), (0, 0))) for i in ims]
    sep = np.zeros((6, W, 4), np.float32)
    sep[..., 3] = 1
    parts = []
    for i in ims[::-1]:
        parts += [i, sep]
    _save_px(np.concatenate(parts, axis=0), out_png)
    return out_png
