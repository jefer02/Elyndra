"""Pure-math pose model of Masha_Rig (owner: Agent 4): FK, analytic two-bone IK, world<->local rotations.

No depsgraph: a pose is a `Pose` (local pose-bone quaternions = pb.rotation_quaternion, plus the Hips
pb.location); `RigModel.fk(pose)` returns armature-space 4x4 matrices exactly as Blender's pb.matrix
(pose = parent_pose @ (parent_rest^-1 @ rest) @ Translation(loc) @ Rotation(q)). Armature space = world
(Masha_Rig has an identity object transform): Z up, she faces -Y, her left is +X.
"""
import math

from mathutils import Matrix, Quaternion, Vector

M = "mixamorig:"
X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))
HIPS = M + "Hips"


def smoothstep(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def smootherstep(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * x * (x * (6 * x - 15) + 10)


def qpow(q, k):
    """Scale a rotation's angle by k (shortest arc)."""
    if q.w < 0:
        q = -q
    if abs(k - 1.0) < 1e-9:
        return q.copy()
    if 0.0 <= k <= 1.0:
        return Quaternion().slerp(q, k)
    axis, ang = q.to_axis_angle()
    return Quaternion(axis, ang * k)


def qangle(a, b):
    """Angle (deg) between two rotations."""
    d = a.normalized().rotation_difference(b.normalized())
    v = math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
    return math.degrees(2 * math.atan2(v, abs(d.w)))


def charq(rots):
    """[(axis, deg)] applied in order -> armature-space quaternion."""
    q = Quaternion()
    for axis, deg in rots:
        if abs(deg) > 1e-9:
            q = Quaternion(axis, math.radians(deg)) @ q
    return q


class Pose:
    __slots__ = ("rot", "loc")

    def __init__(self, rot=None, loc=None):
        self.rot = rot or {}
        self.loc = loc.copy() if loc is not None else Vector()

    def copy(self):
        return Pose({k: v.copy() for k, v in self.rot.items()}, self.loc)

    def q(self, name):
        return self.rot.get(name, Quaternion())


class RigModel:
    def __init__(self, rig_ob, only_prefix=None):
        bones = rig_ob.data.bones
        order = []
        seen = set()

        def visit(b):
            if b.name in seen:
                return
            if b.parent is not None:
                visit(b.parent)
            seen.add(b.name)
            order.append(b.name)

        for b in bones:
            visit(b)
        self.order = order
        self.parent = {b.name: (b.parent.name if b.parent else None) for b in bones}
        self.rest = {b.name: b.matrix_local.copy() for b in bones}
        self.restq = {n: m.to_quaternion() for n, m in self.rest.items()}
        self.rel = {}
        for b in bones:
            p = b.parent
            self.rel[b.name] = (p.matrix_local.inverted() @ b.matrix_local) if p else b.matrix_local.copy()
        self.relq = {n: m.to_quaternion() for n, m in self.rel.items()}
        self.length = {b.name: b.length for b in bones}
        self.mixamo = [n for n in order if n.startswith(M)]
        self.children = {n: [] for n in order}
        for n, p in self.parent.items():
            if p:
                self.children[p].append(n)

    # ---------------------------------------------------------------- FK
    def fk(self, pose, names=None):
        """Armature-space matrices of all bones (or of `names` + their ancestors)."""
        out = {}
        need = None
        if names is not None:
            need = set()
            for n in names:
                while n and n not in need:
                    need.add(n)
                    n = self.parent[n]
        for n in self.order:
            if need is not None and n not in need:
                continue
            q = pose.rot.get(n)
            basis = q.to_matrix().to_4x4() if q is not None else Matrix.Identity(4)
            if n == HIPS:
                basis = Matrix.Translation(pose.loc) @ basis
            p = self.parent[n]
            out[n] = (out[p] @ self.rel[n] @ basis) if p else (self.rel[n] @ basis)
        return out

    def world_q(self, pose, name, mats=None):
        mats = mats or self.fk(pose, [name])
        return mats[name].to_quaternion()

    def local_from_world(self, pose, name, Wq, mats=None):
        """Local pose quaternion that gives bone `name` the armature-space rotation Wq (parents as posed)."""
        p = self.parent[name]
        if p is None:
            return (self.relq[name].inverted() @ Wq).normalized()
        mats = mats or self.fk(pose, [p])
        Wp = mats[p].to_quaternion()
        return ((Wp @ self.relq[name]).inverted() @ Wq).normalized()

    def head(self, mats, name):
        return mats[name].translation.copy()

    def tail(self, mats, name):
        return mats[name] @ Vector((0, self.length[name], 0))

    # ---------------------------------------------------------------- IK
    def two_bone(self, pose, upper, lower, target, pole, end=None, soft=0.9995):
        """Analytic two-bone IK: moves the tip of `lower` (head of `end`) to `target`; the mid joint goes
        into the plane (root, target, pole point). Minimal-swing rotations keep each bone's twist.
        Returns the residual distance (m)."""
        mats = self.fk(pose, [lower])
        A = mats[upper].translation
        B = mats[lower].translation
        C = self.tail(mats, lower)
        l1 = (B - A).length
        l2 = (C - B).length
        d = target - A
        dist = d.length
        dist = min(max(dist, abs(l1 - l2) + 1e-4), (l1 + l2) * soft)
        dn = d.normalized()
        pv = pole - A
        n = pv - pv.dot(dn) * dn
        if n.length < 1e-6:
            n = (B - A) - (B - A).dot(dn) * dn
        n.normalize()
        ca = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)
        ca = min(1.0, max(-1.0, ca))
        sa = math.sqrt(1 - ca * ca)
        B2 = A + l1 * (ca * dn + sa * n)
        C2 = A + dn * dist
        # upper
        Wu = mats[upper].to_quaternion()
        q1 = (B - A).rotation_difference(B2 - A)
        pose.rot[upper] = self.local_from_world(pose, upper, q1 @ Wu, mats)
        mats = self.fk(pose, [lower])
        Wl = mats[lower].to_quaternion()
        cur = self.tail(mats, lower) - mats[lower].translation
        q2 = cur.rotation_difference(C2 - B2)
        pose.rot[lower] = self.local_from_world(pose, lower, q2 @ Wl, mats)
        mats = self.fk(pose, [lower])
        return (self.tail(mats, lower) - target).length

    def hinge_ik(self, pose, upper, lower, target, pole, hinge, th_ref=1.0, soft=0.9995):
        """Anatomical two-bone IK: `lower` rotates ONLY about its local `hinge` axis (elbow), `upper`
        is aimed at the elbow and rolled so the bend plane contains the pole point. Unique solution:
        no path dependence, no twist flips. Returns the residual (m)."""
        mats = self.fk(pose, [lower])
        A = mats[upper].translation
        B = mats[lower].translation
        C = self.tail(mats, lower)
        l1 = (B - A).length
        l2 = (C - B).length
        d = target - A
        dist = min(max(d.length, abs(l1 - l2) + 1e-4), (l1 + l2) * soft)
        dn = d.normalized()
        pv = pole - A
        n = pv - pv.dot(dn) * dn
        if n.length < 1e-6:
            n = (B - A) - (B - A).dot(dn) * dn
        n.normalize()
        ca = min(1.0, max(-1.0, (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)))
        E = A + l1 * (ca * dn + math.sqrt(1 - ca * ca) * n)
        C2 = A + dn * dist
        e_dir = (E - A).normalized()
        f_dir = (C2 - E).normalized()
        want = e_dir.angle(f_dir)
        relq = self.relq[lower]
        h = hinge.normalized()

        def fdir(th):
            return relq @ (Quaternion(h, th) @ Y)

        def ang(th):
            return Y.angle(fdir(th))
        grid = [-1.6 + 0.02 * k for k in range(161)]
        th0 = min(grid, key=ang)
        # flexion branch = the side of th0 that contains a known flexed angle (the base pose's)
        sg = 1.0 if th_ref >= th0 else -1.0
        lo, hi = 0.0, 2.6
        if want <= ang(th0):
            th = th0
        else:
            for _ in range(40):
                mid = 0.5 * (lo + hi)
                if ang(th0 + sg * mid) < want:
                    lo = mid
                else:
                    hi = mid
            th = th0 + sg * 0.5 * (lo + hi)
        f_loc = fdir(th)
        # upper-arm world rotation: local (Y, bend normal) -> world (e_dir, bend normal)
        nl = Y.cross(f_loc)
        nw = e_dir.cross(f_dir)
        if nl.length < 1e-6 or nw.length < 1e-6:
            nl = relq @ h
            nw = dn.cross(n)
        nl.normalize()
        nw.normalize()

        def basis(a, z):
            x = a.normalized()
            z = (z - z.dot(x) * x).normalized()
            y = z.cross(x)
            return Matrix((x, y, z)).transposed()
        Wu = (basis(e_dir, nw) @ basis(Y, nl).transposed()).to_quaternion()
        pose.rot[upper] = self.local_from_world(pose, upper, Wu, mats)
        pose.rot[lower] = Quaternion(h, th)
        mats = self.fk(pose, [lower])
        return (self.tail(mats, lower) - target).length

    def set_world(self, pose, name, Wq):
        pose.rot[name] = self.local_from_world(pose, name, Wq)


def pose_from_bpy(rig_ob, prefix=M):
    rot = {}
    for pb in rig_ob.pose.bones:
        if pb.name.startswith(prefix):
            rot[pb.name] = pb.rotation_quaternion.copy()
    return Pose(rot, rig_ob.pose.bones[HIPS].location.copy())


def pose_from_base(data):
    rot = {n: Quaternion(v["rotation_quaternion"]).normalized() for n, v in data["bones"].items()}
    loc = Vector(data["bones"][HIPS]["location"])
    return Pose(rot, loc)


def apply_to_bpy(rig_ob, pose):
    for pb in rig_ob.pose.bones:
        pb.rotation_mode = "QUATERNION"
        if pb.name in pose.rot:
            pb.rotation_quaternion = pose.rot[pb.name]
        elif pb.name.startswith(M):
            pb.rotation_quaternion = Quaternion()
    rig_ob.pose.bones[HIPS].location = pose.loc
