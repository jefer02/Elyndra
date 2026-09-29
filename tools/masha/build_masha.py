"""
Masha — generador del personaje holográfico 3D (Blender 4.2+).

Construye de forma procedural, sin assets externos (la cara, las manos y
el horneado están en anatomy.py):
  - La cabeza: jaula de quads con bucles concéntricos en ojos, párpados,
    nariz y labios → Multires (3 niveles) con el esculpido fino → versión
    del GLB con Subdivision Surface + Weighted Normals y mapas NORMAL + AO
    horneados. Morph targets: visemas (AA, O, EE, FV, MBP), boca abierta
    (con saco bucal), parpadeo real de los párpados, sonrisa y cejas.
  - Las manos: palma de quads y dedos extruidos por falange (3 bucles por
    falange + articulación), nudillos, uñas; mismo flujo alta/baja/horneado.
  - El cuerpo: loft anatómico → remallado vóxel fino (alta) y diezmado
    (baja) → horneado.
  - El pelo: casquete + mechones con forma (corto en la nuca, flequillo
    barrido), con un hueso de flotación.
  - El esqueleto (columna, cuello, cabeza, brazos, 5 dedos por mano,
    piernas, pelo) y el pesado automático.
  - Los materiales holográficos: translucidez, emisión con textura de
    código generada aquí, desplazamiento del código (KHR_texture_transform,
    que la app anima) y un casco invertido que hace de halo de Fresnel.
  - Las animaciones: Idle, Talk, Listen, Think, Explain, Wave y el giro de
    los anillos del holotanque.
  - El holotanque (pedestal, anillos, haz de proyección).
  - El entorno HDR de la sala de control (Cycles, panorámica equirect).

Salida (por defecto en app/src/main/assets/masha/):
  masha.glb        calidad alta  (~57k triángulos, mapas 1024)
  masha_lite.glb   calidad media (~33k triángulos, mapas 512)
  tools/masha/textures/*.png   los mapas horneados (también van dentro del GLB)
  room.hdr         entorno/IBL de la sala de control (1024x512)

Uso:
  blender -b -P tools/masha/build_masha.py -- [--out DIR] [--only high|lite|env]
          [--no-env] [--preview PNG]

Convenciones: metros, Z arriba, el personaje mira a -Y (el exportador glTF
lo pasa a Y arriba / +Z delante, que es lo que espera Filament).
"""

import argparse
import math
import os
import random
import sys

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Quaternion, Vector

# Cara, manos y horneado (topología, Multires, mapas de normales + AO).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import anatomy  # noqa: E402
import figure  # noqa: E402
from anatomy import HEAD_C, HEAD_R, MOUTH_Z  # noqa: E402

# ──────────────────────────────────────────────────────────────────────────
# Parámetros
# ──────────────────────────────────────────────────────────────────────────

# Presupuesto de triángulos del GLB — alta: cabeza+ojos ~18,5k, manos ~9k,
# cuerpo ~14k, pelo ~8k, halo ~3,5k, holotanque ~3k ≈ 57k. Ligera ≈ 33k.
# (Los *_faces son caras del diezmado: ya en triángulos para el cuerpo; el
# pelo son quads, cada uno cuenta como dos.)
QUALITY = {
    "high": dict(voxel=0.0045, voxel_high=0.0020, body_quads=9000, rim_faces=3500,
                 hair_theta=48, hair_rows=16, hair_cards=110,
                 head_res=16, head_sub=1, hand_sub=1, multires=3, bake=1024, tex=1024),
    "lite": dict(voxel=0.0060, voxel_high=0.0026, body_quads=4500, rim_faces=1800,
                 hair_theta=36, hair_rows=12, hair_cards=60,
                 head_res=12, head_sub=1, hand_sub=0, multires=3, bake=512, tex=512),
}

FPS = 30
SEED = 1701

# Colores base (lineales). La app los tiñe en tiempo real según el ánimo.
SKIN_BASE = (0.07, 0.22, 0.85, 0.74)
SKIN_EMIT = (0.35, 0.75, 1.0)
# Cara y manos: algo más opacas y claras, para que el relieve horneado se lea.
FACE_BASE = (0.16, 0.38, 1.0, 0.95)
RIM_COLOR = (0.35, 0.85, 1.0, 0.38)
EYE_EMIT = (0.75, 0.95, 1.0)
TANK_EMIT = (0.2, 0.75, 1.0)


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "assets", "masha"))
    p = argparse.ArgumentParser()
    p.add_argument("--out", default=default_out)
    p.add_argument("--only", choices=["high", "lite", "env"], default=None)
    p.add_argument("--no-env", action="store_true")
    p.add_argument("--preview", default=None, help="PNG de vista previa (Cycles) de la calidad alta")
    p.add_argument("--textures", default=os.path.join(here, "textures"), help="dónde guardar los mapas horneados (PNG)")
    return p.parse_args(argv)


# ──────────────────────────────────────────────────────────────────────────
# Utilidades de escena y malla
# ──────────────────────────────────────────────────────────────────────────

def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    for block in (bpy.data.meshes, bpy.data.materials, bpy.data.images, bpy.data.actions, bpy.data.armatures, bpy.data.curves):
        for item in list(block):
            block.remove(item)
    scene = bpy.context.scene
    scene.render.fps = FPS
    scene.unit_settings.system = "METRIC"
    return scene


def link(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def activate(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def apply_modifiers(obj):
    activate(obj)
    for m in list(obj.modifiers):
        bpy.ops.object.modifier_apply(modifier=m.name)


def mesh_object(name, bm):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return link(bpy.data.objects.new(name, me))


def frame_from(tangent):
    """Base ortonormal (u, v, t) para una sección perpendicular a `tangent`."""
    t = tangent.normalized()
    ref = Vector((0, 0, 1)) if abs(t.z) < 0.9 else Vector((0, -1, 0))
    u = ref.cross(t).normalized()
    v = t.cross(u).normalized()
    return u, v, t


def loft(name, stations, ring=28, cap=True, twist_ref=None):
    """
    Tubo por secciones elípticas. `stations` = [(centro, rx, ry), ...]; rx va
    por el eje u de la sección (horizontal), ry por v. Con `twist_ref` se fija
    la orientación de u (p. ej. la palma de la mano).
    """
    bm = bmesh.new()
    rings = []
    pts = [Vector(s[0]) for s in stations]
    for i, (c, rx, ry) in enumerate(stations):
        c = Vector(c)
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, len(pts) - 1)]
        tangent = (b - a) if (b - a).length > 1e-6 else Vector((0, 0, 1))
        if twist_ref is not None:
            t = tangent.normalized()
            u = (Vector(twist_ref) - t * Vector(twist_ref).dot(t)).normalized()
            v = t.cross(u).normalized()
        else:
            u, v, _ = frame_from(tangent)
        verts = []
        for k in range(ring):
            ang = 2 * math.pi * k / ring
            verts.append(bm.verts.new(c + u * (math.cos(ang) * rx) + v * (math.sin(ang) * ry)))
        rings.append(verts)
    for r0, r1 in zip(rings, rings[1:]):
        for k in range(ring):
            bm.faces.new((r0[k], r0[(k + 1) % ring], r1[(k + 1) % ring], r1[k]))
    if cap:
        for verts, flip in ((rings[0], True), (rings[-1], False)):
            center = bm.verts.new(sum((v.co for v in verts), Vector()) / len(verts))
            for k in range(ring):
                tri = (center, verts[(k + 1) % ring], verts[k]) if flip else (center, verts[k], verts[(k + 1) % ring])
                bm.faces.new(tri)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return mesh_object(name, bm)


def ellipsoid(name, center, radii, seg=24, rings=16):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=seg, v_segments=rings, radius=1.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * radii[0], v.co.y * radii[1], v.co.z * radii[2])) + Vector(center)
    return mesh_object(name, bm)


def lerp(a, b, t):
    return a + (b - a) * t


def smoothstep(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def gauss(d2, s):
    return math.exp(-d2 / (2 * s * s))


# ──────────────────────────────────────────────────────────────────────────
# Anatomía (1,68 m). El personaje mira a -Y.
# ──────────────────────────────────────────────────────────────────────────

# Proporciones compartidas con figure.py (esqueleto y manos las usan).
SHOULDER = figure.SHOULDER
arm_points = figure.arm_points


def leg_joints(side):
    s = side
    hip = Vector((s * figure.HIP_X, 0.006, 0.905))
    knee = Vector((s * 0.094, -0.006, 0.505))
    ankle = Vector((s * 0.098, 0.010, 0.085))
    return dict(hip=hip, knee=knee, ankle=ankle, toe=ankle + Vector((0, -0.16, -0.07)))


def join(objs, name):
    activate(objs[0])
    for o in objs[1:]:
        o.select_set(True)
    bpy.ops.object.join()
    obj = bpy.context.view_layer.objects.active
    obj.name = name
    obj.data.name = name
    return obj


# ──────────────────────────────────────────────────────────────────────────
# Cara: ojos y morph targets (la geometría está en anatomy.py)
# ──────────────────────────────────────────────────────────────────────────

def head_params(head):
    """(u, v) de cada vértice (capa "Param") y el conjunto de vértices interiores."""
    me = head.data
    uv = me.uv_layers["Param"].data
    param = {}
    for poly in me.polygons:
        for li in poly.loop_indices:
            vi = me.loops[li].vertex_index
            if vi not in param:
                u, v = uv[li].uv
                param[vi] = (u - 2 * math.pi if u > math.pi else u, v)
    gi = head.vertex_groups["interior"].index
    interior = {v.index for v in me.vertices if any(g.group == gi and g.weight > 0.001 for g in v.groups)}
    return param, interior


def build_eyes(head, radius):
    """
    Globos oculares detrás de la abertura de los párpados: se centran en el
    borde libre (anillo r = 1) y asoman lo justo para que la córnea quede
    entre los párpados.
    """
    param, interior = head_params(head)
    me = head.data
    eyes = []
    for side in (1, -1):
        rim = []
        for i, (u, v) in param.items():
            if i in interior or u * side <= 0:
                continue
            eu, ev = anatomy.eye_local(abs(u), v)
            if abs(math.hypot(eu, ev) - 1.0) < 0.06:
                rim.append(me.vertices[i].co)
        c = sum(rim, Vector()) / len(rim)
        center = Vector((c.x, c.y + radius - 0.0013, c.z))
        eyes.append(ellipsoid("eye", center, (radius, radius, radius), seg=28, rings=18))
    return eyes


def add_face_morphs(head):
    """
    Morph targets de la cara, calculados sobre la posición de reposo de la
    malla nueva. La app los mezcla en vivo:
      MouthOpen, V_AA, V_O, V_EE, V_FV, V_MBP  visemas (la boca se abre de
                                               verdad: hay saco bucal)
      Smile, BrowUp, BrowFrown                 expresión
      Blink_L, Blink_R                         los párpados bajan sobre el globo
    """
    me = head.data
    param, interior = head_params(head)
    head.shape_key_add(name="Basis", from_mix=False)
    rest = [v.co.copy() for v in me.vertices]
    pivot = HEAD_C + Vector((0, 0.028, -0.030))    # bisagra de la mandíbula (delante de la oreja)

    def norm(co):
        d = co - HEAD_C
        return Vector((d.x / HEAD_R.x, d.y / HEAD_R.y, d.z / HEAD_R.z)).normalized()

    dirs = [norm(c) for c in rest]

    def key(name, fn):
        kb = head.shape_key_add(name=name, from_mix=False)
        for i in range(len(me.vertices)):
            kb.data[i].co = rest[i] + fn(i, dirs[i], rest[i])
        return kb

    def front(n):
        return max(0.0, -n.y)

    def jaw_w(n):
        # Corte nítido en la línea de la boca: el labio de abajo baja, el de arriba no.
        return smoothstep(MOUTH_Z + 0.004, MOUTH_Z - 0.05, n.z) * smoothstep(-0.2, 0.35, front(n) + 0.25)

    def mouth_w(n):
        return gauss(n.x * n.x, 0.26) * gauss((n.z - MOUTH_Z) ** 2, 0.09) * front(n)

    def jaw_open(i, n, co, angle=15.0):
        w = jaw_w(n)
        if w <= 0:
            return Vector()
        rot = Matrix.Rotation(math.radians(angle) * w, 3, "X")
        return (rot @ (co - pivot) + pivot) - co

    def lip_part(n, amount):
        up = gauss((n.z - (MOUTH_Z + 0.05)) ** 2, 0.03) * gauss(n.x * n.x, 0.22) * front(n)
        return Vector((0, 0, amount * up))

    key("MouthOpen", lambda i, n, co: jaw_open(i, n, co) + lip_part(n, 0.002))
    key("V_AA", lambda i, n, co: jaw_open(i, n, co, 13.0) + lip_part(n, 0.003) + Vector((n.x * 0.004 * mouth_w(n), 0, 0)))
    key("V_O", lambda i, n, co: jaw_open(i, n, co, 8.0) + Vector((-n.x * 0.020 * mouth_w(n), -0.006 * mouth_w(n), 0)))
    key("V_EE", lambda i, n, co: jaw_open(i, n, co, 4.0) + Vector((n.x * 0.016 * mouth_w(n), 0.002 * mouth_w(n), 0.0015 * mouth_w(n))))
    key("V_FV", lambda i, n, co: Vector((0, 0.002, 0.004)) * (gauss((n.z - (MOUTH_Z - 0.06)) ** 2, 0.03) * gauss(n.x * n.x, 0.22) * front(n)))
    key("V_MBP", lambda i, n, co: Vector((0, -0.0015, 0.0022)) * (gauss((n.z - (MOUTH_Z - 0.05)) ** 2, 0.04) * gauss(n.x * n.x, 0.22) * front(n))
        + Vector((0, -0.0015, -0.0012)) * (gauss((n.z - (MOUTH_Z + 0.05)) ** 2, 0.04) * gauss(n.x * n.x, 0.22) * front(n)))

    def smile(i, n, co):
        corner = sum(gauss((n.x - s * 0.31) ** 2 + (n.z - MOUTH_Z) ** 2, 0.1) for s in (1, -1))
        cheek = sum(gauss((n.x - s * 0.45) ** 2 + (n.z + 0.12) ** 2, 0.12) for s in (1, -1))
        f = front(n)
        return Vector((n.x * 0.006 * corner, 0.003 * corner, 0.006 * corner)) * f + Vector((0, -0.002, 0.003)) * cheek * f

    key("Smile", smile)
    key("BrowUp", lambda i, n, co: Vector((0, 0, 0.005)) * gauss((n.z - 0.3) ** 2, 0.08) * gauss(n.x * n.x, 0.5) * front(n))
    key("BrowFrown", lambda i, n, co: Vector((-n.x * 0.004, -0.001, -0.003)) * gauss((n.z - 0.28) ** 2, 0.07) * gauss(n.x * n.x, 0.22) * front(n))

    # Parpadeo: el párpado superior baja hasta casi tocar el inferior (que
    # sube un poco) y se adelanta para pasar por encima de la córnea.
    for side, name in ((1, "Blink_L"), (-1, "Blink_R")):
        def blink(i, n, co, side=side):
            if i in interior:
                return Vector()
            u, v = param[i]
            if u * side <= 0:
                return Vector()
            eu, ev = anatomy.eye_local(abs(u), v)
            re = math.hypot(eu, ev)
            if re > 2.4:
                return Vector()
            up = smoothstep(-0.35, 0.35, ev)
            w_up = smoothstep(2.4, 1.0, re)
            w_lo = smoothstep(1.9, 1.0, re)
            dv = up * (-0.80 - ev) * anatomy.EYE_OPEN[1] * w_up + (1 - up) * 0.14 * anatomy.EYE_OPEN[1] * w_lo
            dz = dv * HEAD_R.z * math.cos(v)
            bulge = 0.0036 * up * w_up * max(0.0, 1.0 - abs(eu) * 0.8)
            return Vector((0, -bulge, dz))

        key(name, blink)


# ──────────────────────────────────────────────────────────────────────────
# Pelo
# ──────────────────────────────────────────────────────────────────────────

# ──────────────────────────────────────────────────────────────────────────
# Texturas y materiales holográficos
# ──────────────────────────────────────────────────────────────────────────

def code_texture(size, name, seed, density=1.0):
    """
    Patrón de código a lo Cortana: trazas de circuito, glifos de bits,
    columnas de datos y una rejilla hexagonal tenue. Gris (se tiñe con el
    color de emisión) y que se repite sin costura en vertical.
    """
    rng = np.random.default_rng(seed)
    img = np.zeros((size, size), np.float32)
    s = size / 1024.0

    def hline(y, x0, x1, v, w=1):
        y0 = int(y) % size
        img[y0:y0 + w, max(0, int(x0)):min(size, int(x1))] = np.maximum(img[y0:y0 + w, max(0, int(x0)):min(size, int(x1))], v)

    def vline(x, y0, y1, v, w=1):
        x0 = int(x) % size
        for yy in range(int(y0), int(y1)):
            img[yy % size, x0:x0 + w] = np.maximum(img[yy % size, x0:x0 + w], v)

    # Rejilla hexagonal tenue.
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    k = 2 * np.pi / (38 * s)
    hexg = (np.cos(k * xx) + np.cos(k * (0.5 * xx + 0.866 * yy)) + np.cos(k * (-0.5 * xx + 0.866 * yy)))
    img += 0.06 * (hexg > 2.6)

    # Trazas de circuito (paseos Manhattan con nodos).
    for _ in range(int((140 * s + 40) * density)):
        x, y = rng.integers(0, size, 2)
        v = rng.uniform(0.45, 1.0)
        for _ in range(rng.integers(3, 9)):
            L = int(rng.integers(12, 90) * s) + 4
            if rng.random() < 0.5:
                x1 = x + L * rng.choice([-1, 1])
                hline(y, min(x, x1), max(x, x1), v, max(1, int(2 * s)))
                x = int(np.clip(x1, 0, size - 1))
            else:
                y1 = y + L * rng.choice([-1, 1])
                vline(x, min(y, y1), max(y, y1), v, max(1, int(2 * s)))
                y = int(y1) % size
        r = max(2, int(4 * s))
        img[max(0, y - r):y + r, max(0, x - r):x + r] = v

    # Glifos: bloques de bits 5x7.
    cell = max(3, int(5 * s))
    for _ in range(int((220 * s + 60) * density)):
        gx, gy = rng.integers(0, size - 8 * cell, 2)
        bits = rng.random((7, 5)) < 0.45
        v = rng.uniform(0.35, 0.9)
        for by in range(7):
            for bx in range(5):
                if bits[by, bx]:
                    img[gy + by * cell:gy + by * cell + cell - 1, gx + bx * cell:gx + bx * cell + cell - 1] = v

    # Columnas de datos (lo que "fluye" al desplazar la textura en vertical).
    for _ in range(int((40 * s + 10) * density)):
        x = rng.integers(0, size)
        for y in range(0, size, max(4, int(9 * s))):
            if rng.random() < 0.55:
                img[y:y + max(2, int(5 * s)), x:x + max(1, int(2 * s))] = rng.uniform(0.3, 0.8)

    img = np.clip(img, 0, 1)
    rgba = np.dstack([img, img, img, np.ones_like(img)]).astype(np.float32)
    im = bpy.data.images.new(name, size, size, alpha=True)
    im.pixels.foreach_set(rgba.ravel())
    im.file_format = "PNG"
    im.pack()
    return im


def holo_material(name, base, emit, emit_strength, tex=None, tex_scale=(1, 1), alpha=None, double=False):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = base
    bsdf.inputs["Roughness"].default_value = 0.32
    bsdf.inputs["Metallic"].default_value = 0.0
    bsdf.inputs["Emission Color"].default_value = (*emit, 1)
    bsdf.inputs["Emission Strength"].default_value = emit_strength
    a = base[3] if alpha is None else alpha
    bsdf.inputs["Alpha"].default_value = a
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    if tex is not None:
        uv = nt.nodes.new("ShaderNodeTexCoord")
        mapping = nt.nodes.new("ShaderNodeMapping")
        mapping.vector_type = "POINT"
        mapping.inputs["Scale"].default_value = (tex_scale[0], tex_scale[1], 1)
        # Un desplazamiento distinto de cero obliga al exportador a escribir
        # KHR_texture_transform: la app lo anima para que el código fluya.
        mapping.inputs["Location"].default_value = (0.0, 0.001, 0)
        img = nt.nodes.new("ShaderNodeTexImage")
        img.image = tex
        mix = nt.nodes.new("ShaderNodeMixRGB")
        mix.blend_type = "MULTIPLY"
        mix.inputs["Fac"].default_value = 1.0
        mix.inputs["Color2"].default_value = (*emit, 1)
        nt.links.new(uv.outputs["UV"], mapping.inputs["Vector"])
        nt.links.new(mapping.outputs["Vector"], img.inputs["Vector"])
        nt.links.new(img.outputs["Color"], mix.inputs["Color1"])
        nt.links.new(mix.outputs["Color"], bsdf.inputs["Emission Color"])
    if a < 0.999:
        # El exportador glTF lee el modo de mezcla de aquí (alphaMode BLEND).
        mat.blend_method = "BLEND"
        if hasattr(mat, "surface_render_method"):
            mat.surface_render_method = "BLENDED"
    mat.use_backface_culling = not double
    return mat


def cylindrical_uv(obj, v_scale=2.2, u_repeat=1.0):
    """
    UV cilíndrica alrededor del eje vertical: al desplazar V el código sube
    por todo el cuerpo a la vez, que es lo que se busca (no islas sueltas).
    """
    me = obj.data
    layer = me.uv_layers.get("UVMap") or me.uv_layers.new(name="UVMap")
    me.uv_layers.active = layer
    layer.active_render = True
    uv = layer.data
    for poly in me.polygons:
        us = []
        for li in poly.loop_indices:
            co = me.vertices[me.loops[li].vertex_index].co
            u = (math.atan2(co.x, -co.y) / (2 * math.pi) + 0.5) * u_repeat
            us.append((li, u, co.z * v_scale))
        # Costura: que un polígono no salte de 0 a 1.
        umax = max(u for _, u, _ in us)
        for li, u, v in us:
            if umax - u > 0.5 * u_repeat:
                u += u_repeat
            uv[li].uv = (u, v)


def fade_to_feet(obj, z0=0.08, z1=0.55, floor_alpha=0.35):
    """Alfa por vértice (COLOR_0) que se apaga hacia los pies: el holograma "nace" del pedestal."""
    me = obj.data
    attr = me.color_attributes.get("Col") or me.color_attributes.new("Col", "BYTE_COLOR", "CORNER")
    me.color_attributes.active_color = attr
    for li, loop in enumerate(me.loops):
        z = me.vertices[loop.vertex_index].co.z
        a = lerp(floor_alpha, 1.0, smoothstep(z0, z1, z))
        attr.data[li].color = (1.0, 1.0, 1.0, a)


def rim_shell(src, name, mat, offset=0.0035):
    """Casco invertido: la misma malla, inflada y con normales hacia dentro → halo en la silueta."""
    shell = src.copy()
    shell.data = src.data.copy()
    shell.name = name
    shell.data.name = name
    link(shell)
    if shell.data.shape_keys:
        activate(shell)
        bpy.ops.object.shape_key_remove(all=True)
    bm = bmesh.new()
    bm.from_mesh(shell.data)
    for v in bm.verts:
        v.co += v.normal * offset
    bmesh.ops.reverse_faces(bm, faces=bm.faces)
    bm.to_mesh(shell.data)
    bm.free()
    shell.data.materials.clear()
    shell.data.materials.append(mat)
    return shell


# ──────────────────────────────────────────────────────────────────────────
# Esqueleto
# ──────────────────────────────────────────────────────────────────────────

def build_armature(hands, legs):
    arm_data = bpy.data.armatures.new("MashaRig")
    rig = link(bpy.data.objects.new("MashaRig", arm_data))
    activate(rig)
    bpy.ops.object.mode_set(mode="EDIT")
    eb = arm_data.edit_bones

    def bone(name, head, tail, parent=None, connect=False, roll=0.0):
        b = eb.new(name)
        b.head = Vector(head)
        b.tail = Vector(tail)
        b.roll = roll
        if parent:
            b.parent = eb[parent]
            b.use_connect = connect
        return b

    bone("root", (0, 0, 0), (0, 0.15, 0))
    bone("hips", (0, 0.004, 0.93), (0, 0.004, 1.04), "root")
    bone("spine", (0, 0.004, 1.04), (0, -0.002, 1.17), "hips", True)
    bone("chest", (0, -0.002, 1.17), (0, 0.002, 1.40), "spine", True)
    bone("neck", (0, 0.004, 1.43), (0, 0.0, 1.555), "chest")
    bone("head", (0, 0.0, 1.555), (0, 0.0, 1.76), "neck", True)
    bone("hair_float", (0, 0.02, 1.70), (0, 0.06, 1.62), "head")
    for side, sx in ((1, "L"), (-1, "R")):
        shoulder, elbow, wrist, d = arm_points(side)
        bone(f"clavicle.{sx}", (side * 0.03, 0.0, 1.42), shoulder, "chest")
        bone(f"upper_arm.{sx}", shoulder, elbow, f"clavicle.{sx}", True)
        bone(f"forearm.{sx}", elbow, wrist, f"upper_arm.{sx}", True)
        j = hands[side]
        bone(f"hand.{sx}", wrist, wrist + j["dir"] * 0.07, f"forearm.{sx}", True)
        for f in ("thumb", "index", "middle", "ring", "pinky"):
            pts = j[f]
            parent = f"hand.{sx}"
            for i in range(3):
                name = f"{f}.{i + 1}.{sx}"
                bone(name, pts[i], pts[i + 1], parent, i > 0)
                parent = name
        L = legs[side]
        bone(f"thigh.{sx}", L["hip"], L["knee"], "hips")
        bone(f"shin.{sx}", L["knee"], L["ankle"], f"thigh.{sx}", True)
        bone(f"foot.{sx}", L["ankle"], L["toe"], f"shin.{sx}", True)
    bpy.ops.object.mode_set(mode="OBJECT")
    return rig


def skin(obj, rig):
    activate(obj)
    rig.select_set(True)
    bpy.context.view_layer.objects.active = rig
    try:
        bpy.ops.object.parent_set(type="ARMATURE_AUTO")
        ok = any(len(v.groups) for v in obj.data.vertices[:200])
    except RuntimeError:
        ok = False
    if not ok:
        print(f"[masha] pesado por calor falló en {obj.name}; uso envolventes")
        obj.parent = None
        obj.modifiers.clear()
        activate(obj)
        rig.select_set(True)
        bpy.context.view_layer.objects.active = rig
        bpy.ops.object.parent_set(type="ARMATURE_ENVELOPE")


ARM_BONES = ("clavicle", "upper_arm", "forearm", "hand", "thumb", "index", "middle", "ring", "pinky")


def clean_torso_weights(obj):
    """
    El pesado por calor deja que los huesos del brazo arrastren el costado del
    torso y la cadera (en pose A la mano queda cerca). Ahí solo manda la columna.
    """
    arm_groups = [g.index for g in obj.vertex_groups if g.name.startswith(ARM_BONES)]
    for v in obj.data.vertices:
        if abs(v.co.x) < 0.19 and v.co.z < 1.30:
            for gi in arm_groups:
                obj.vertex_groups[gi].remove([v.index])
    activate(obj)
    bpy.ops.object.vertex_group_limit_total(group_select_mode="ALL", limit=4)
    bpy.ops.object.vertex_group_normalize_all(group_select_mode="ALL", lock_active=False)


def skin_rigid(obj, rig, weights):
    """Pesos a mano: `weights(co) -> {hueso: peso}` (cabeza, pelo)."""
    for name in {n for n in ("head", "neck", "hair_float")}:
        if name not in obj.vertex_groups:
            obj.vertex_groups.new(name=name)
    for v in obj.data.vertices:
        for bname, w in weights(v.co).items():
            if w > 0:
                obj.vertex_groups[bname].add([v.index], w, "REPLACE")
    mod = obj.modifiers.new("Armature", "ARMATURE")
    mod.object = rig
    obj.parent = rig


# ──────────────────────────────────────────────────────────────────────────
# Animaciones
# ──────────────────────────────────────────────────────────────────────────

class Poser:
    """
    Rotaciones expresadas en ejes de la armadura (X = lado, Y = atrás,
    Z = arriba), convertidas a la base local de cada hueso: así una pose se
    escribe como "baja el brazo 30° alrededor de Y" sin pensar en rolls.
    """

    def __init__(self, rig):
        self.rig = rig
        self.rest = {b.name: b.matrix_local.to_3x3() for b in rig.data.bones}

    def q(self, bone, axis, deg):
        m = self.rest[bone]
        local_axis = (m.inverted() @ Vector(axis)).normalized()
        return Quaternion(local_axis, math.radians(deg))

    def pose(self, frame, rotations):
        """rotations = {hueso: [(eje, grados), ...]} — se componen en orden."""
        for pb in self.rig.pose.bones:
            q = Quaternion()
            for axis, deg in rotations.get(pb.name, []):
                q = self.q(pb.name, axis, deg) @ q
            pb.rotation_mode = "QUATERNION"
            pb.rotation_quaternion = q
            pb.keyframe_insert("rotation_quaternion", frame=frame)


X, Y, Z = (1, 0, 0), (0, 1, 0), (0, 0, 1)

# Ejes de cada mano (x = lado del pulgar, y = dedos, z = dorso), de anatomy.
HAND_AXES = {}


def curl(side, deg):
    """Flexión de un dedo hacia la palma, en el eje de su mano (las dos son espejo)."""
    return (tuple(HAND_AXES[side][0]), -side * deg)


def base_pose(t, amp=1.0):
    """Pose de reposo viva: brazos abajo, respiración, balanceo, pelo flotando. `t` en segundos."""
    br = math.sin(2 * math.pi * t / 3.0)                  # respiración, 3 s
    sway = math.sin(2 * math.pi * t / 6.0)
    look = math.sin(2 * math.pi * t / 6.0 + 0.7)
    tilt = math.sin(2 * math.pi * t / 2.0) * 0.4 + math.sin(2 * math.pi * t / 3.0) * 0.6
    r = {
        "hips": [(Y, 1.2 * sway * amp)],
        "spine": [(X, -1.2 * br * amp), (Y, -0.8 * sway * amp)],
        "chest": [(X, -1.4 * br * amp)],
        "neck": [(Z, 2.0 * look * amp)],
        "head": [(Z, 3.0 * look * amp), (X, 1.5 * tilt * amp), (Y, 1.2 * sway * amp)],
        "hair_float": [(X, 5 * math.sin(2 * math.pi * t / 3.0 + 1.1)), (Y, 3 * math.sin(2 * math.pi * t / 2.0))],
    }
    for side, sx in ((1, "L"), (-1, "R")):
        r[f"upper_arm.{sx}"] = [(Y, side * 34), (X, -4 + 1.5 * br * amp)]
        r[f"forearm.{sx}"] = [(X, -10 - 1.0 * br * amp)]
        r[f"hand.{sx}"] = [(Y, side * 4)]
        for f in ("index", "middle", "ring", "pinky"):
            for i in (1, 2, 3):
                r[f"{f}.{i}.{sx}"] = [curl(side, 6 + 4 * i)]
        r[f"thumb.2.{sx}"] = [curl(side, 8)]
        r[f"thigh.{sx}"] = [(Y, side * 0.8 * sway * amp)]
    return r


def merge(a, b):
    out = {k: list(v) for k, v in a.items()}
    for k, v in b.items():
        out.setdefault(k, []).extend(v)
    return out


def ease(t):
    return 0.5 - 0.5 * math.cos(math.pi * min(max(t, 0.0), 1.0))


def envelope(t, dur, rise=0.25, fall=0.3):
    """0 → 1 → 0 a lo largo de un gesto (entra y vuelve a la pose de reposo)."""
    return ease(t / (dur * rise)) * ease((dur - t) / (dur * fall))


def make_action(rig, poser, name, seconds, fn, step=3):
    rig.animation_data_create()
    act = bpy.data.actions.new(name)
    rig.animation_data.action = act
    frames = int(round(seconds * FPS))
    for f in list(range(0, frames, step)) + [frames]:
        poser.pose(f + 1, fn(f / FPS))
    # Lazo perfecto: el último fotograma es el primero.
    track = rig.animation_data.nla_tracks.new()
    track.name = name
    strip = track.strips.new(name, 1, act)
    strip.name = name
    track.mute = True
    rig.animation_data.action = None
    return act


def build_actions(rig):
    poser = Poser(rig)

    make_action(rig, poser, "Idle", 6.0, lambda t: base_pose(t))

    def talk(t):
        nod = math.sin(2 * math.pi * t / 1.0) * 0.6 + math.sin(2 * math.pi * t / 2.0) * 0.8
        g = math.sin(2 * math.pi * t / 2.0)
        extra = {
            "head": [(X, 2.2 * nod), (Z, 2.5 * math.sin(2 * math.pi * t / 4.0))],
            "upper_arm.R": [(X, -8 - 4 * g), (Y, 6)],
            "forearm.R": [(X, -22 - 10 * (0.5 + 0.5 * g))],
            "hand.R": [(Z, -10 * g)],
            "upper_arm.L": [(X, -3 - 2 * math.sin(2 * math.pi * t / 4.0))],
            "forearm.L": [(X, -8 - 4 * (0.5 + 0.5 * math.sin(2 * math.pi * t / 4.0)))],
        }
        return merge(base_pose(t, 1.2), extra)

    make_action(rig, poser, "Talk", 4.0, talk)

    def listen(t):
        return merge(base_pose(t, 0.7), {
            "head": [(Y, -6.0), (X, 3.0)],
            "neck": [(X, 3.0)],
            "chest": [(X, 2.0)],
        })

    make_action(rig, poser, "Listen", 4.0, listen)

    def think(t):
        e = envelope(t, 4.0, 0.2, 0.2)
        return merge(base_pose(t, 0.6), {
            "upper_arm.R": [(Y, 30 * e), (X, -48 * e)],
            "forearm.R": [(X, -105 * e)],
            "hand.R": [(X, 25 * e)],
            "upper_arm.L": [(X, -14 * e), (Y, -8 * e)],
            "forearm.L": [(X, -70 * e)],
            "head": [(Y, 7 * e), (X, -4 * e), (Z, -6 * e)],
        })

    make_action(rig, poser, "Think", 4.0, think)

    def explain(t):
        e = envelope(t, 3.2, 0.3, 0.3)
        open_ = ease((t - 0.8) / 0.6) * e
        return merge(base_pose(t, 0.8), {
            "upper_arm.R": [(Y, 22 * e), (X, -38 * e)],
            "forearm.R": [(X, -45 * e), (Y, 20 * e)],
            "hand.R": [(Y, 30 * open_)],
            "head": [(Z, 8 * e), (X, 3 * e)],
            **{f"{f}.{i}.R": [curl(-1, -9 * open_)] for f in ("index", "middle", "ring", "pinky") for i in (1, 2, 3)},
        })

    make_action(rig, poser, "Explain", 3.2, explain)

    def wave(t):
        e = envelope(t, 2.6, 0.25, 0.3)
        w = math.sin(2 * math.pi * t / 0.55) * e
        return merge(base_pose(t, 0.8), {
            "upper_arm.R": [(Y, 62 * e), (X, -18 * e)],
            "forearm.R": [(Y, -70 * e), (X, -20 * e)],
            "hand.R": [(Z, 18 * w)],
            "head": [(Z, 5 * e), (Y, 4 * e)],
        })

    make_action(rig, poser, "Wave", 2.6, wave)


def spin_action(obj, name, seconds, turns):
    obj.animation_data_create()
    obj.rotation_mode = "XYZ"
    act = bpy.data.actions.new(name)
    obj.animation_data.action = act
    frames = int(seconds * FPS)
    obj.rotation_euler = (0, 0, 0)
    obj.keyframe_insert("rotation_euler", frame=1)
    obj.rotation_euler = (0, 0, 2 * math.pi * turns)
    obj.keyframe_insert("rotation_euler", frame=frames + 1)
    for fc in act.fcurves:
        for kp in fc.keyframe_points:
            kp.interpolation = "LINEAR"
    track = obj.animation_data.nla_tracks.new()
    track.name = name
    track.strips.new(name, 1, act)
    track.mute = True
    obj.animation_data.action = None


# ──────────────────────────────────────────────────────────────────────────
# Holotanque
# ──────────────────────────────────────────────────────────────────────────

def build_holotank(tex):
    metal = bpy.data.materials.new("Tank_Metal")
    metal.use_nodes = True
    b = metal.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.02, 0.03, 0.05, 1)
    b.inputs["Metallic"].default_value = 0.9
    b.inputs["Roughness"].default_value = 0.35
    glow = holo_material("Tank_Glow", (0.05, 0.3, 0.6, 1.0), TANK_EMIT, 6.0)
    beam = holo_material("Tank_Beam", (0.2, 0.7, 1.0, 0.10), TANK_EMIT, 1.4, tex=tex, tex_scale=(3, 1), alpha=0.10, double=True)
    ring_mat = holo_material("Tank_Ring", (0.2, 0.6, 1.0, 0.55), TANK_EMIT, 4.0, tex=tex, tex_scale=(6, 0.5), alpha=0.55, double=True)

    objs = []
    bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=0.52, depth=0.07, location=(0, 0, -0.035))
    base = bpy.context.active_object
    base.name = "Tank_Base"
    bev = base.modifiers.new("bevel", "BEVEL")
    bev.width = 0.012
    bev.segments = 2
    apply_modifiers(base)
    base.data.materials.append(metal)
    objs.append(base)
    for r, w in ((0.47, 0.012), (0.36, 0.006), (0.22, 0.004)):
        bpy.ops.mesh.primitive_torus_add(major_radius=r, minor_radius=w, major_segments=72, minor_segments=6, location=(0, 0, 0.002))
        t = bpy.context.active_object
        t.name = "Tank_GlowRing"
        t.scale.z = 0.4
        t.data.materials.append(glow)
        objs.append(t)
    # Haz de proyección: cono abierto que sube desde el pedestal.
    bpy.ops.mesh.primitive_cone_add(vertices=64, radius1=0.40, radius2=0.30, depth=0.30, end_fill_type="NOTHING", location=(0, 0, 0.15))
    cone = bpy.context.active_object
    cone.name = "Tank_Beam"
    cylindrical_uv(cone, 1.5)
    cone.data.materials.append(beam)
    objs.append(cone)
    # Anillos flotantes que giran alrededor de ella.
    spinners = []
    for i, (z, r, turns) in enumerate(((0.55, 0.42, 1), (1.25, 0.36, -1))):
        bpy.ops.mesh.primitive_cylinder_add(vertices=96, radius=r, depth=0.018, end_fill_type="NOTHING", location=(0, 0, z))
        ring = bpy.context.active_object
        ring.name = f"Tank_Orbit{i}"
        ring.rotation_euler = (math.radians(4 + 3 * i), math.radians(-3 * i), 0)
        activate(ring)
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=False)
        cylindrical_uv(ring, 8)
        ring.data.materials.append(ring_mat)
        spin_action(ring, f"RingSpin{i}", 24.0, turns)
        spinners.append(ring)
    return objs, spinners


# ──────────────────────────────────────────────────────────────────────────
# Sala de control → HDR
# ──────────────────────────────────────────────────────────────────────────

def render_room_hdr(path):
    """
    Sala de control circular con consolas, pantallas de datos, pilares de luz y
    niebla volumétrica azul, renderizada en panorámica equirect (Cycles). Es el
    fondo y la luz ambiental (IBL) de la escena en la app.
    """
    scene = reset_scene()
    tex = code_texture(1024, "room_code", SEED + 7)
    scene.render.engine = "CYCLES"
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for backend in ("OPTIX", "CUDA", "HIP", "METAL", "ONEAPI"):
        try:
            prefs.compute_device_type = backend
            prefs.get_devices()
            if any(d.type == backend for d in prefs.devices):
                for d in prefs.devices:
                    d.use = True
                scene.cycles.device = "GPU"
                break
        except TypeError:
            continue
    scene.cycles.samples = 96
    scene.cycles.use_denoising = True
    scene.render.resolution_x = 1024
    scene.render.resolution_y = 512
    scene.world = bpy.data.worlds.new("void")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.003, 0.006, 0.014, 1)

    code = tex
    panel = holo_material("Room_Panel", (0.0, 0.0, 0.0, 1.0), (0.25, 0.7, 1.0), 9.0, tex=code, tex_scale=(2, 1))
    strip = holo_material("Room_Strip", (0.0, 0.0, 0.0, 1.0), (0.3, 0.75, 1.0), 14.0)
    violet = holo_material("Room_Violet", (0.0, 0.0, 0.0, 1.0), (0.55, 0.4, 1.0), 8.0)
    wall = bpy.data.materials.new("Room_Wall")
    wall.use_nodes = True
    wb = wall.node_tree.nodes["Principled BSDF"]
    wb.inputs["Base Color"].default_value = (0.03, 0.045, 0.07, 1)
    wb.inputs["Metallic"].default_value = 0.6
    wb.inputs["Roughness"].default_value = 0.4

    # Paredes, suelo y techo.
    bpy.ops.mesh.primitive_cylinder_add(vertices=48, radius=7.0, depth=6.0, location=(0, 0, 2.2))
    room = bpy.context.active_object
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.flip_normals()
    bpy.ops.object.mode_set(mode="OBJECT")
    room.data.materials.append(wall)
    rnd = random.Random(SEED + 3)
    # Pantallas de datos y consolas alrededor.
    for i in range(18):
        a = 2 * math.pi * i / 18 + rnd.uniform(-0.05, 0.05)
        r = 6.3
        z = rnd.choice([1.3, 1.6, 2.4, 3.1])
        w = rnd.uniform(0.8, 1.6)
        h = rnd.uniform(0.4, 0.9)
        bpy.ops.mesh.primitive_plane_add(size=1, location=(math.sin(a) * r, math.cos(a) * r, z))
        p = bpy.context.active_object
        p.scale = (w, h, 1)
        p.rotation_euler = (math.radians(90), 0, -a + math.pi)
        p.data.materials.append(panel if rnd.random() < 0.8 else violet)
        # Consola a los pies de algunas pantallas.
        if rnd.random() < 0.6:
            bpy.ops.mesh.primitive_cube_add(size=1, location=(math.sin(a) * (r - 0.6), math.cos(a) * (r - 0.6), 0.45))
            c = bpy.context.active_object
            c.scale = (w * 0.9, 0.5, 0.45)
            c.rotation_euler = (0, 0, -a)
            c.data.materials.append(wall)
            bpy.ops.mesh.primitive_plane_add(size=1, location=(math.sin(a) * (r - 0.85), math.cos(a) * (r - 0.85), 0.92))
            s = bpy.context.active_object
            s.scale = (w * 0.8, 0.05, 1)
            s.rotation_euler = (0, 0, -a)
            s.data.materials.append(strip)
    # Pilares de luz y líneas de suelo.
    for i in range(8):
        a = 2 * math.pi * (i + 0.5) / 8
        bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=0.05, depth=5.0, location=(math.sin(a) * 6.8, math.cos(a) * 6.8, 2.2))
        bpy.context.active_object.data.materials.append(strip if i % 2 else violet)
    for r in (2.2, 3.6, 5.2):
        bpy.ops.mesh.primitive_torus_add(major_radius=r, minor_radius=0.02, major_segments=96, minor_segments=6, location=(0, 0, -0.78))
        bpy.context.active_object.data.materials.append(strip)
    # Anillo cenital.
    bpy.ops.mesh.primitive_torus_add(major_radius=2.5, minor_radius=0.06, major_segments=96, minor_segments=8, location=(0, 0, 4.6))
    bpy.context.active_object.data.materials.append(violet)

    # Niebla volumétrica azul (muy suave).
    bpy.ops.mesh.primitive_cube_add(size=13.5, location=(0, 0, 2.2))
    fog = bpy.context.active_object
    fm = bpy.data.materials.new("Fog")
    fm.use_nodes = True
    nt = fm.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    vol = nt.nodes.new("ShaderNodeVolumePrincipled")
    vol.inputs["Color"].default_value = (0.35, 0.6, 1.0, 1)
    vol.inputs["Density"].default_value = 0.035
    o = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(vol.outputs["Volume"], o.inputs["Volume"])
    fog.data.materials.append(fm)

    # Cámara panorámica en el centro, a la altura de los ojos.
    cam_data = bpy.data.cameras.new("pano")
    cam_data.type = "PANO"
    if hasattr(cam_data, "panorama_type"):
        cam_data.panorama_type = "EQUIRECTANGULAR"
    else:
        cam_data.cycles.panorama_type = "EQUIRECTANGULAR"
    cam = link(bpy.data.objects.new("pano", cam_data))
    cam.location = (0, 0, 1.55)
    cam.rotation_euler = (math.radians(90), 0, 0)
    scene.camera = cam
    scene.render.image_settings.file_format = "HDR"
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print(f"[masha] entorno → {path}")


# ──────────────────────────────────────────────────────────────────────────
# Personaje completo
# ──────────────────────────────────────────────────────────────────────────

def build_character(qname, tex_dir=None):
    q = QUALITY[qname]
    reset_scene()
    tex_dir = tex_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "textures")
    suffix = "" if qname == "high" else "_lite"
    tex = code_texture(q["tex"], "masha_code", SEED)
    figure.pack_image(tex, os.path.join(tex_dir, f"masha_code{suffix}.jpg"), "JPEG")
    # En la cara el código es escaso: los rasgos mandan.
    face_code = code_texture(q["tex"], "masha_face_code", SEED + 1, density=0.28)
    figure.pack_image(face_code, os.path.join(tex_dir, f"masha_face_code{suffix}.jpg"), "JPEG")
    eye_tex = figure.eye_texture(128 if qname == "lite" else 256, "masha_eye")
    figure.pack_image(eye_tex, os.path.join(tex_dir, f"masha_eye{suffix}.jpg"), "JPEG")
    strands = figure.strand_texture(256, "masha_lash_strands", count=70, seed=11)
    figure.pack_image(strands, os.path.join(tex_dir, f"masha_lashes{suffix}.png"), "PNG")

    skin_mat = holo_material("Holo_Skin", SKIN_BASE, SKIN_EMIT, 3.2, tex=tex, tex_scale=(1, 1))
    face_mat = holo_material("Holo_Face", FACE_BASE, SKIN_EMIT, 1.2, tex=face_code, tex_scale=(1, 1))
    hand_mat = holo_material("Holo_Hands", FACE_BASE, SKIN_EMIT, 2.0, tex=tex, tex_scale=(1, 1))
    eye_mat = holo_material("Holo_Eyes", (0.03, 0.06, 0.14, 1.0), EYE_EMIT, 4.5, tex=eye_tex)
    rim_mat = holo_material("Holo_Rim", RIM_COLOR, (0.4, 0.85, 1.0), 5.0)

    # ── Cuerpo: escultura → quads + parches radiales → Multires → horneado ──
    body_high, body, apices = figure.build_body(q)
    cylindrical_uv(body, 2.4, 2.0)
    body.data.materials.append(skin_mat)
    anatomy.weighted_normals(body)
    anatomy.bake_maps(body, body_high, q["bake"], "masha_body" + suffix, cage=0.006, ray=0.02, out_dir=tex_dir)
    bpy.data.objects.remove(body_high, do_unlink=True)

    # ── Cabeza: jaula con bucles → Multires (alta) / Subdivisión (baja) ──
    cage = anatomy.build_head_cage(q["head_res"])
    head_high = anatomy.multires_high(cage, q["multires"], lambda o: anatomy.refit_surface(o, True), "Masha_Head_high")
    head = anatomy.subdivide_applied(cage, q["head_sub"], "Masha_Head")
    anatomy.refit_surface(head, False)
    cylindrical_uv(head, 2.4, 2.0)
    head.data.materials.append(face_mat)
    anatomy.weighted_normals(head)
    anatomy.bake_maps(head, head_high, q["bake"], "masha_face" + suffix, cage=0.005, ray=0.02, out_dir=tex_dir, interior_group="interior")
    bpy.data.objects.remove(head_high, do_unlink=True)
    bpy.data.objects.remove(cage, do_unlink=True)
    # Pestañas y cejas: se unen antes de los morphs (parpadean y se levantan con ellos).
    lashes = figure.build_face_hair(strands)
    lashes.data.materials[0].name = "Holo_Lashes"
    head = join([head, lashes], "Masha_Head")
    add_face_morphs(head)
    eyes = build_eyes(head, 0.0118)
    for e in eyes:
        e.data.materials.append(eye_mat)
    head.data.uv_layers.remove(head.data.uv_layers["Param"])
    n_head = len(head.data.vertices)
    head = join([head] + eyes, "Masha_Head")
    # Iris en proyección frontal (UVMap de los ojos).
    uv = head.data.uv_layers["UVMap"].data
    eye_idx = [v.index for v in head.data.vertices if v.index >= n_head]
    for side in (1, -1):
        idx = [i for i in eye_idx if head.data.vertices[i].co.x * side > 0]
        pts = [head.data.vertices[i].co for i in idx]
        cx = sum(p.x for p in pts) / len(pts)
        cz = sum(p.z for p in pts) / len(pts)
        w = max(abs(p.x - cx) for p in pts) * 2
        idx = set(idx)
        for poly in head.data.polygons:
            if poly.vertices[0] in idx:
                for li in poly.loop_indices:
                    co = head.data.vertices[head.data.loops[li].vertex_index].co
                    uv[li].uv = ((co.x - cx) / w + 0.5, (co.z - cz) / w + 0.5)

    # ── Manos ──
    hands = {}
    cages = []
    for side in (1, -1):
        _, _, wrist, d = arm_points(side)
        c, joints = anatomy.build_hand_cage(side, wrist, d)
        hands[side] = joints
        HAND_AXES[side] = joints["axes"]
        cages.append(c)
    hand_cage = join(cages, "Masha_Hands_cage")
    hands_high = anatomy.multires_high(hand_cage, q["multires"], anatomy.hand_detail, "Masha_Hands_high")
    if q["hand_sub"]:
        hands_low = anatomy.subdivide_applied(hand_cage, q["hand_sub"], "Masha_Hands")
    else:
        hands_low = anatomy.duplicate(hand_cage, "Masha_Hands")
    for name in ("HandA", "HandB"):
        hands_low.data.uv_layers.remove(hands_low.data.uv_layers[name])
    cylindrical_uv(hands_low, 2.4, 2.0)
    hands_low.data.materials.append(hand_mat)
    anatomy.weighted_normals(hands_low)
    anatomy.bake_maps(hands_low, hands_high, q["bake"], "masha_hands" + suffix, cage=0.004, ray=0.014, out_dir=tex_dir)
    bpy.data.objects.remove(hands_high, do_unlink=True)
    bpy.data.objects.remove(hand_cage, do_unlink=True)

    # ── Pelo: carcasa con volumen + tarjetas ──
    hair = figure.build_hair(q, tex_dir, tex)

    # ── Esqueleto, pesos y huesos de movimiento secundario ──
    legs = {side: leg_joints(side) for side in (1, -1)}
    rig = build_armature(hands, legs)
    figure.add_soft_bones(rig, apices)
    skin(body, rig)
    clean_torso_weights(body)
    figure.soft_weights(body, apices)
    activate(body)
    bpy.ops.object.vertex_group_limit_total(group_select_mode="BONE_DEFORM", limit=4)
    bpy.ops.object.vertex_group_normalize_all(group_select_mode="BONE_DEFORM", lock_active=False)
    fade_to_feet(body)
    skin(hands_low, rig)
    activate(hands_low)
    bpy.ops.object.vertex_group_limit_total(group_select_mode="ALL", limit=4)
    bpy.ops.object.vertex_group_normalize_all(group_select_mode="ALL", lock_active=False)

    # Halo de silueta: copia del cuerpo ya pesado (se deforma igual) y más ligera.
    body_rim = rim_shell(body, "Masha_BodyRim", rim_mat)
    body_rim.data.uv_layers.remove(body_rim.data.uv_layers["BakeUV"])
    dec = body_rim.modifiers.new("decimate", "DECIMATE")
    dec.ratio = min(1.0, q["rim_faces"] / max(len(body_rim.data.polygons), 1))
    activate(body_rim)
    bpy.ops.object.modifier_move_to_index(modifier="decimate", index=0)
    bpy.ops.object.modifier_apply(modifier="decimate")
    fade_to_feet(body_rim, floor_alpha=0.15)

    def head_weights(co):
        w = smoothstep(1.545, 1.585, co.z)
        return {"head": w, "neck": 1 - w}

    skin_rigid(head, rig, head_weights)

    def hair_weights(co):
        f = smoothstep(1.74, 1.56, co.z)
        return {"head": 1 - 0.8 * f, "hair_float": 0.8 * f}

    skin_rigid(hair, rig, hair_weights)

    build_actions(rig)
    tank, spinners = build_holotank(tex)
    return dict(body=body, head=head, hands=hands_low, hair=hair, rig=rig, tank=tank, spinners=spinners, tex=tex, hand_joints=hands)


def export_glb(path):
    bpy.ops.object.select_all(action="SELECT")
    kwargs = dict(
        filepath=path,
        export_format="GLB",
        use_selection=False,
        export_apply=False,
        export_yup=True,
        export_texcoords=True,
        export_normals=True,
        export_tangents=True,             # los mapas de normales se hornearon con MikkTSpace
        export_materials="EXPORT",
        export_image_format="AUTO",       # cada imagen en su formato: JPEG los mapas, PNG las de alfa
        export_skins=True,
        export_morph=True,
        export_morph_normal=True,
        export_morph_tangent=False,
        export_animations=True,
        export_nla_strips=True,
        export_force_sampling=True,
        export_def_bones=False,
        export_extras=True,
        export_vertex_color="ACTIVE",
        export_lights=False,
        export_cameras=False,
    )
    try:
        bpy.ops.export_scene.gltf(**kwargs, export_animation_mode="NLA_TRACKS")
    except TypeError:
        bpy.ops.export_scene.gltf(**kwargs)
    print(f"[masha] exportado → {path} ({os.path.getsize(path) / 1e6:.2f} MB)")


def _clay(obj):
    """
    Material "arcilla" para revisar la forma: gris mate, sin emisión, con el
    mapa de normales y la AO horneados (lo mismo que verá el móvil).
    """
    for i, mat in enumerate(obj.data.materials):
        if mat is None:
            continue
        clay = mat.copy()
        clay.name = mat.name + "_clay"
        nt = clay.node_tree
        bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
        bsdf.inputs["Emission Strength"].default_value = 0.0
        bsdf.inputs["Alpha"].default_value = 1.0
        bsdf.inputs["Roughness"].default_value = 0.55
        clay.blend_method = "OPAQUE"
        if bsdf.inputs["Base Color"].is_linked:
            # AO × gris medio: la forma se lee sin quemarse.
            src = bsdf.inputs["Base Color"].links[0].from_socket
            mul = nt.nodes.new("ShaderNodeMixRGB")
            mul.blend_type = "MULTIPLY"
            mul.inputs["Fac"].default_value = 1.0
            mul.inputs["Color2"].default_value = (0.42, 0.40, 0.38, 1)
            nt.links.new(src, mul.inputs["Color1"])
            nt.links.new(mul.outputs["Color"], bsdf.inputs["Base Color"])
        else:
            bsdf.inputs["Base Color"].default_value = (0.42, 0.40, 0.38, 1)
        obj.data.materials[i] = clay


def render_preview(path, ctx):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 64
    scene.cycles.use_denoising = True
    anatomy.setup_bake_engine(scene)
    scene.world = bpy.data.worlds.new("prev")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.005, 0.01, 0.025, 1)
    # Cycles no descarta caras traseras: el casco de silueta taparía el cuerpo.
    for o in bpy.data.objects:
        if o.name.endswith("Rim") or o.name.startswith("Tank"):
            o.hide_render = True
    rig = ctx["rig"]
    rig.animation_data.action = None
    for pb in rig.pose.bones:
        pb.rotation_quaternion = Quaternion()
    base, ext = os.path.splitext(path)

    def light(loc, target, energy, color, size):
        ld = bpy.data.lights.new("l", "AREA")
        ld.energy = energy
        ld.color = color
        ld.size = size
        lo = link(bpy.data.objects.new("l", ld))
        lo.location = loc
        lo.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        return lo

    def shot(tag, loc, target, lens, w, h):
        cam_data = bpy.data.cameras.new(tag)
        cam_data.lens = lens
        cam_data.clip_start = 0.01
        cam = link(bpy.data.objects.new(tag, cam_data))
        cam.location = loc
        cam.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        scene.camera = cam
        scene.render.resolution_x = w
        scene.render.resolution_y = h
        scene.render.image_settings.file_format = "PNG"
        scene.render.filepath = f"{base}_{tag}{ext}"
        bpy.ops.render.render(write_still=True)

    face = HEAD_C + Vector((0, -0.08, -0.01))
    hand = ctx["hand_joints"][1]
    palm = hand["wrist"] + hand["dir"] * 0.07
    out = hand["axes"][2]
    thumb_side = hand["axes"][0]

    # Holograma (como en la app).
    light((-1.5, 1.2, 2.2), (0, 0, 1.0), 400, (0.3, 0.6, 1.0), 1.5)
    light((1.8, -1.5, 1.8), (0, 0, 1.0), 150, (0.8, 0.8, 1.0), 1.5)
    shot("holo_full", (0.0, -3.2, 1.05), (0, 0, 0.95), 50, 720, 1080)
    shot("holo_face", face + Vector((0.10, -0.52, 0.02)), face, 85, 720, 720)

    # Arcilla: la forma sin el brillo del holograma.
    for o in bpy.data.objects:
        if o.type == "MESH" and o.name.startswith("Masha_"):
            _clay(o)
    for o in [o for o in bpy.data.objects if o.type == "LIGHT"]:
        bpy.data.objects.remove(o, do_unlink=True)
    light(face + Vector((-0.6, -0.9, 0.5)), face, 22, (1, 0.97, 0.94), 0.5)
    light(face + Vector((0.8, -0.3, 0.2)), face, 5, (0.8, 0.85, 1.0), 0.8)
    light(face + Vector((0.2, 0.8, 0.4)), face, 14, (0.6, 0.8, 1.0), 0.5)
    shot("clay_face", face + Vector((0.0, -0.55, 0.0)), face, 85, 720, 720)
    shot("clay_face34", face + Vector((0.36, -0.42, 0.03)), face, 85, 720, 720)
    shot("clay_profile", face + Vector((0.55, -0.02, 0.0)), face, 85, 720, 720)
    light(palm + out * 0.6 + thumb_side * 0.3 + Vector((0, 0, 0.4)), palm, 16, (1, 0.97, 0.94), 0.4)
    light(palm - out * 0.5 + Vector((0, -0.4, 0.2)), palm, 5, (0.8, 0.85, 1.0), 0.6)
    shot("clay_hand_back", palm + out * 0.40 + thumb_side * 0.08, palm, 70, 720, 720)
    shot("clay_hand_palm", palm - out * 0.40 + thumb_side * 0.10, palm, 70, 720, 720)
    # Cuerpo en arcilla: frente, perfil y espalda (silueta y curvas).
    for o in [o for o in bpy.data.objects if o.type == "LIGHT"]:
        bpy.data.objects.remove(o, do_unlink=True)
    mid = Vector((0, 0, 1.05))
    light(mid + Vector((-1.2, -2.0, 1.4)), mid, 110, (1, 0.97, 0.94), 1.2)
    light(mid + Vector((1.6, -0.8, 0.6)), mid, 30, (0.8, 0.85, 1.0), 1.5)
    light(mid + Vector((0.3, 2.0, 1.0)), mid, 70, (0.7, 0.8, 1.0), 1.5)
    shot("clay_body_front", mid + Vector((0, -3.3, 0.05)), mid, 55, 720, 1080)
    shot("clay_body_side", mid + Vector((3.3, -0.2, 0.05)), mid, 55, 720, 1080)
    shot("clay_body_back", mid + Vector((0.4, 3.3, 0.05)), mid, 55, 720, 1080)
    print(f"[masha] vista previa → {base}_*.png")


def main():
    args = parse_args()
    os.makedirs(args.out, exist_ok=True)
    qualities = [] if args.only == "env" else [args.only] if args.only else ["high", "lite"]
    for qname in qualities:
        ctx = build_character(qname, args.textures)
        name = "masha.glb" if qname == "high" else "masha_lite.glb"
        export_glb(os.path.join(args.out, name))
        if args.preview and qname == "high":
            render_preview(args.preview, ctx)
    if not args.no_env:
        render_room_hdr(os.path.join(args.out, "room.hdr"))


if __name__ == "__main__":
    main()
