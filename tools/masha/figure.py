"""
Figura de Masha: cuerpo femenino, pelo, pestañas y cejas, y huesos de
movimiento secundario (pecho y glúteos).

Lo usa build_masha.py junto con anatomy.py (cara y manos).

Cuerpo
  1. FORMA: loft anatómico con silueta de reloj de arena (cintura/cadera ≈ 0,70,
     se mide sobre la malla y se imprime), pecho en forma de gota (polo
     inferior lleno, pendiente superior suave, leve asimetría natural, surco
     entre ambos y pliegue inframamario), glúteos redondos y levantados con
     separación, caderas y muslos suaves. Todo se funde con un remallado vóxel
     y un alisado que conserva el volumen: es la "escultura" de referencia.
  2. TOPOLOGÍA: retopología automática en quads (QuadriFlow) y, encima, parches
     radiales de anillos concéntricos en cada pecho y cada glúteo (el flujo que
     se usa para que esas zonas se deformen bien al moverse).
  3. DETALLE: la jaula con Multires (2 niveles) proyectada sobre la escultura
     (reshape), y horneado de NORMAL + AO sobre la versión del GLB.
  4. MOVIMIENTO SECUNDARIO: huesos breast.L/R y glute.L/R con pesos suaves
     (y grupos "jiggle_*" para Soft Body en Blender). En la app los mueve un
     muelle amortiguado (HoloRig).

Pelo
  Media melena por capas inspirada en Cortana: una "carcasa" suave de quads
  con el volumen del peinado (raya a un lado, flequillo barrido, más corta en
  la nuca, puntas hacia dentro) y encima tarjetas de pelo (hair cards) con
  una textura de mechones con alfa, que siguen el mismo flujo y rompen la
  silueta en las puntas.
"""

import math
import random

import bmesh
import bpy
import numpy as np
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree

import anatomy as A
from anatomy import HEAD_C, HEAD_R, lerp, smoothstep

SEED = 2217

# ──────────────────────────────────────────────────────────────────────────
# Texturas
# ──────────────────────────────────────────────────────────────────────────


def strand_texture(size, name, dark=(0.03, 0.07, 0.30), count=90, seed=5):
    """
    Mechones con alfa: finos, curvados, que se afinan hacia la punta (v = 1).
    RGB oscuro azulado (el tono lo da el material) y alfa por mechón.
    """
    rng = np.random.default_rng(seed)
    h = w = size
    alpha = np.zeros((h, w), np.float32)
    yy = np.linspace(0, 1, h)[:, None]
    for _ in range(count):
        x0 = rng.uniform(0, 1)
        bend = rng.uniform(-0.04, 0.04)
        width = rng.uniform(0.004, 0.010)
        length = rng.uniform(0.75, 1.0)
        a = rng.uniform(0.55, 1.0)
        xs = (x0 + bend * yy ** 2) % 1.0
        xx = np.linspace(0, 1, w)[None, :]
        d = np.minimum(np.abs(xx - xs), 1 - np.abs(xx - xs))
        taper = np.clip(1 - yy / length, 0, 1) ** 0.6
        alpha = np.maximum(alpha, a * np.exp(-(d / (width * (0.35 + 0.65 * taper))) ** 2) * (yy < length) * np.clip(taper * 3, 0, 1))
    rgba = np.zeros((h, w, 4), np.float32)
    for c in range(3):
        rgba[..., c] = dark[c] + 0.5 * alpha * (0.4 + 0.6 * (1 - yy))
    rgba[..., 3] = np.clip(alpha, 0, 1)
    im = bpy.data.images.new(name, w, h, alpha=True)
    im.pixels.foreach_set(rgba.ravel())
    return im


def hair_texture(size, name, code_img=None, seed=9):
    """
    Emisión del pelo: mechones finos a lo largo de v (la forma manda) y, por
    debajo, un 25 % del código holográfico (el "data stream" queda secundario).
    """
    rng = np.random.default_rng(seed)
    h = w = size
    img = np.zeros((h, w), np.float32)
    xx = np.linspace(0, 1, w)[None, :]
    yy = np.linspace(0, 1, h)[:, None]
    for _ in range(160):
        x0 = rng.uniform(0, 1)
        bend = rng.uniform(-0.05, 0.05)
        width = rng.uniform(0.0015, 0.004)
        xs = (x0 + bend * yy) % 1.0
        d = np.minimum(np.abs(xx - xs), 1 - np.abs(xx - xs))
        img = np.maximum(img, rng.uniform(0.35, 1.0) * np.exp(-(d / width) ** 2))
    img *= 0.55 + 0.45 * np.sin(yy * math.pi * 6 + xx * 11) ** 2
    if code_img is not None:
        code = np.array(code_img.pixels[:]).reshape(code_img.size[1], code_img.size[0], 4)[..., 0]
        if code.shape[0] != h:
            idx = (np.arange(h) * code.shape[0] // h)
            code = code[idx][:, idx]
        img = np.maximum(img, 0.25 * code)
    rgba = np.dstack([img, img, img, np.ones_like(img)]).astype(np.float32)
    im = bpy.data.images.new(name, w, h, alpha=False)
    im.pixels.foreach_set(rgba.ravel())
    return im


def eye_texture(size, name):
    """
    Iris humano y holográfico: pupila oscura, iris con fibras radiales y un
    collarete brillante, anillo limbal más oscuro (da mirada), esclerótica
    tenue (no blanca: una esclerótica blanca alrededor del iris es la mirada
    de susto) y un brillo de luz arriba a la izquierda.
    """
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    c = (size - 1) / 2.0
    r = np.hypot(xx - c, yy - c) / c
    ang = np.arctan2(yy - c, xx - c)
    rng = np.random.default_rng(3)
    fib = np.zeros_like(r)
    for k in (23, 37, 61, 89):
        fib += np.cos(ang * k + rng.uniform(0, 6.28)) * (1.0 / len((23, 37, 61, 89)))
    fib = 0.75 + 0.25 * fib
    iris_r, pupil_r = 0.50, 0.17
    t = np.clip((r - pupil_r) / (iris_r - pupil_r), 0, 1)
    iris = (0.55 + 0.35 * np.exp(-((t - 0.25) / 0.12) ** 2)) * fib * (1 - 0.45 * t ** 3)
    v = np.where(r < pupil_r, 0.03, iris)
    limbal = np.exp(-((r - iris_r) / 0.035) ** 2)
    v = np.where(r < iris_r + 0.03, v * (1 - 0.6 * limbal), v)
    v = np.where(r >= iris_r + 0.03, 0.09 * np.clip(1.3 - r, 0, 1), v)
    glint = np.exp(-((xx - c * 0.78) ** 2 + (yy - c * 1.25) ** 2) / (2 * (size * 0.045) ** 2))
    glint2 = 0.35 * np.exp(-((xx - c * 1.22) ** 2 + (yy - c * 0.8) ** 2) / (2 * (size * 0.025) ** 2))
    v = np.clip(v + glint + glint2, 0, 1).astype(np.float32)
    rgba = np.dstack([v, v, v, np.ones_like(v)])
    im = bpy.data.images.new(name, size, size, alpha=False)
    im.pixels.foreach_set(rgba.ravel())
    return im


def pack_image(img, path, fmt):
    """Guarda la imagen en `fmt` (JPEG/PNG) y la empaqueta: el exportador la conserva así."""
    import os
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.file_format = fmt
    img.filepath_raw = path
    img.save()
    img.pack()


# ──────────────────────────────────────────────────────────────────────────
# Cuerpo: forma
# ──────────────────────────────────────────────────────────────────────────

SHOULDER = 0.158
HIP_X = 0.090

# (z, semiancho x, semifondo y, desplazamiento y) — reloj de arena.
TORSO = [
    (0.800, 0.030, 0.030, 0.004),
    (0.830, 0.090, 0.070, 0.004),
    (0.875, 0.150, 0.102, 0.010),
    (0.925, 0.176, 0.114, 0.014),     # cadera
    (0.985, 0.163, 0.104, 0.010),
    (1.045, 0.137, 0.092, 0.004),
    (1.098, 0.128, 0.088, -0.001),    # cintura
    (1.150, 0.132, 0.089, -0.005),
    (1.200, 0.134, 0.090, -0.009),    # bajo el pecho
    (1.255, 0.140, 0.093, -0.011),
    (1.310, 0.145, 0.090, -0.008),
    (1.355, 0.146, 0.084, -0.002),
    (1.388, 0.128, 0.076, 0.004),
    (1.412, 0.096, 0.062, 0.006),
    (1.435, 0.060, 0.049, 0.008),
    (1.470, 0.044, 0.042, 0.006),
    (1.520, 0.041, 0.041, 0.002),
    (1.580, 0.038, 0.038, -0.002),
    (1.610, 0.028, 0.028, -0.004),
]

# Pecho y glúteos: centros (lado izquierdo, x > 0) y medidas.
BREAST_C = Vector((0.066, -0.062, 1.252))
BREAST_R = (0.060, 0.050, 0.058)          # ancho, proyección, alto  (≈ copa C)
GLUTE_C = Vector((0.068, 0.068, 0.912))
GLUTE_R = (0.082, 0.070, 0.086)


def arm_points(side):
    s = side
    shoulder = Vector((s * SHOULDER, 0.005, 1.392))
    drop = math.radians(48)
    d = Vector((s * math.cos(drop), 0.0, -math.sin(drop)))
    elbow = shoulder + d * 0.272
    d2 = (d + Vector((0, -0.08, 0))).normalized()
    wrist = elbow + d2 * 0.242
    return shoulder, elbow, wrist, d2


def _deformed_sphere(name, center, frame, fn, seg=40, rings=28):
    """Esfera unidad deformada por fn(n) -> Vector local, y orientada con `frame` (3×3)."""
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=seg, v_segments=rings, radius=1.0)
    for v in bm.verts:
        v.co = center + frame @ fn(v.co.normalized())
    return A.mesh_object(name, bm)


def breast(side, scale=1.0):
    """
    Pecho en gota: el polo inferior es más lleno y redondo, la pendiente
    superior es suave (casi recta, se funde con el pecho), el ápice mira un
    poco hacia fuera y hacia abajo.
    """
    rx, rp, rz = (r * scale for r in BREAST_R)

    def fn(n):
        x, y, z = n.x, n.y, n.z             # y = hacia delante (proyección)
        fwd = max(y, 0.0)
        # Por detrás se hunde en el torso.
        py = y * (rp if y > 0 else rp * 0.35)
        # Pendiente superior: menos proyección arriba; polo inferior lleno.
        py *= 1.0 - 0.38 * smoothstep(0.05, 0.95, z) * fwd
        pz = z * (rz * (0.92 if z > 0 else 1.06))
        pz -= 0.006 * fwd * fwd                          # el ápice cae un poco
        px = x * rx * (1.0 + 0.05 * max(-z, 0.0))
        return Vector((px, py, pz))

    # Marco: x lateral, y hacia delante (algo hacia fuera y abajo), z arriba.
    fwd = Vector((side * 0.22, -1.0, -0.10)).normalized()
    up = Vector((0, 0, 1))
    lat = up.cross(fwd).normalized() * (-side)
    up = fwd.cross(lat).normalized() * (-side)
    frame = Matrix((lat, fwd, up)).transposed()
    c = Vector((side * BREAST_C.x, BREAST_C.y, BREAST_C.z))
    return _deformed_sphere("breast", c, frame, fn)


def glute(side):
    """Glúteo redondo y levantado: más lleno arriba y con el pliegue inferior marcado."""
    rx, rp, rz = GLUTE_R

    def fn(n):
        x, y, z = n.x, n.y, n.z
        back = max(y, 0.0)
        py = y * (rp if y > 0 else rp * 0.5)
        py *= 1.0 + 0.10 * smoothstep(-0.2, 0.6, z) * back      # lleno arriba (levantado)
        pz = z * rz
        if z < 0:
            pz *= 1.0 - 0.22 * back * smoothstep(0.2, 1.0, -z)  # pliegue glúteo
        return Vector((x * rx, py, pz))

    frame = Matrix(((1, 0, 0), (0, 1, 0), (0, 0, 1)))
    c = Vector((side * GLUTE_C.x, GLUTE_C.y, GLUTE_C.z))
    return _deformed_sphere("glute", c, frame, fn, seg=36, rings=24)


def build_body_shape():
    """Todas las piezas del cuerpo (sin manos ni cabeza), listas para fundirse."""
    parts = [A.mesh_object("torso", _loft_bm([((0.0, y, z), rx, ry) for z, rx, ry, y in TORSO], 40))]
    for side in (1, -1):
        s = side
        # Caderas y trocánter: la curva que baja de la cintura al muslo.
        parts.append(_ellipsoid((s * 0.112, 0.006, 0.915), (0.062, 0.084, 0.095)))
        # Hombro redondeado (deltoides pequeño), sin escalón con el trapecio.
        parts.append(A.mesh_object("trap", _loft_bm([((s * 0.035, 0.012, 1.44), 0.034, 0.030), ((s * 0.11, 0.008, 1.405), 0.038, 0.033), ((s * SHOULDER, 0.005, 1.390), 0.042, 0.038)], 16)))
        parts.append(_ellipsoid((s * (SHOULDER + 0.004), 0.004, 1.366), (0.040, 0.040, 0.042)))
        # Brazo (hasta la muñeca: la mano es otra pieza).
        shoulder, elbow, wrist, _ = arm_points(side)
        parts.append(A.mesh_object("arm", _loft_bm([
            (shoulder - (elbow - shoulder).normalized() * 0.02, 0.045, 0.044),
            (shoulder + (elbow - shoulder) * 0.15, 0.043, 0.041),
            (shoulder + (elbow - shoulder) * 0.5, 0.037, 0.036),
            (elbow, 0.031, 0.030),
            (elbow + (wrist - elbow) * 0.3, 0.033, 0.029),
            (elbow + (wrist - elbow) * 0.7, 0.026, 0.022),
            (wrist, 0.023, 0.016),
        ], 24)))
        # Pierna: muslo lleno arriba, rodilla fina, pantorrilla marcada.
        hip = Vector((s * HIP_X, 0.006, 0.905))
        knee = Vector((s * 0.094, -0.006, 0.505))
        ankle = Vector((s * 0.098, 0.010, 0.085))
        parts.append(A.mesh_object("leg", _loft_bm([
            (hip + Vector((-s * 0.02, 0, 0.10)), 0.070, 0.070),
            (hip + Vector((0, 0, 0.03)), 0.098, 0.094),
            (hip, 0.098, 0.093),
            (hip.lerp(knee, 0.25), 0.086, 0.081),
            (hip.lerp(knee, 0.6), 0.066, 0.063),
            (hip.lerp(knee, 0.85), 0.052, 0.052),
            (knee, 0.046, 0.048),
            (knee.lerp(ankle, 0.22), 0.047, 0.052),
            (knee.lerp(ankle, 0.45), 0.041, 0.043),
            (knee.lerp(ankle, 0.8), 0.027, 0.028),
            (ankle, 0.023, 0.026),
        ], 24)))
        parts.append(A.mesh_object("foot", _loft_bm([
            (ankle + Vector((0, 0.010, 0.012)), 0.022, 0.024),     # dentro del tobillo: una sola pieza
            (ankle + Vector((0, 0.024, -0.035)), 0.026, 0.029),
            (ankle + Vector((0, 0.000, -0.050)), 0.031, 0.031),
            (ankle + Vector((0, -0.075, -0.060)), 0.036, 0.020),
            (ankle + Vector((0, -0.140, -0.071)), 0.034, 0.012),
            (ankle + Vector((0, -0.168, -0.074)), 0.025, 0.008),
        ], 20, twist_ref=(1, 0, 0))))
    return parts


def _ellipsoid(center, radii, seg=28, rings=20):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=seg, v_segments=rings, radius=1.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * radii[0], v.co.y * radii[1], v.co.z * radii[2])) + Vector(center)
    return A.mesh_object("ellipsoid", bm)


def _loft_bm(stations, ring, twist_ref=None):
    bm = bmesh.new()
    rings = []
    pts = [Vector(s[0]) for s in stations]
    for i, (c, rx, ry) in enumerate(stations):
        c = Vector(c)
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, len(pts) - 1)]
        t = (b - a).normalized() if (b - a).length > 1e-6 else Vector((0, 0, 1))
        if twist_ref is not None:
            u = (Vector(twist_ref) - t * Vector(twist_ref).dot(t)).normalized()
        else:
            ref = Vector((0, 0, 1)) if abs(t.z) < 0.9 else Vector((0, -1, 0))
            u = ref.cross(t).normalized()
        v = t.cross(u).normalized()
        rings.append([bm.verts.new(c + u * (math.cos(2 * math.pi * k / ring) * rx) + v * (math.sin(2 * math.pi * k / ring) * ry)) for k in range(ring)])
    for r0, r1 in zip(rings, rings[1:]):
        for k in range(ring):
            bm.faces.new((r0[k], r0[(k + 1) % ring], r1[(k + 1) % ring], r1[k]))
    for verts, flip in ((rings[0], True), (rings[-1], False)):
        center = bm.verts.new(sum((v.co for v in verts), Vector()) / len(verts))
        for k in range(ring):
            bm.faces.new((center, verts[(k + 1) % ring], verts[k]) if flip else (center, verts[k], verts[(k + 1) % ring]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return bm


# Pecho: ápice a la altura anatómica (≈ 0,72 × estatura), separación de
# ápices ≈ 18 cm, base ancha que se funde arriba con el pectoral.
BREAST_APEX = (0.090, 1.212)
GLUTE_APEX = (0.074, 0.905)


def _dome(dx, dz, rw, rz_up, rz_down, p_up, p_down):
    """Perfil de "gota": pendiente superior larga y suave, polo inferior redondo con pliegue definido."""
    rz = rz_up if dz > 0 else rz_down
    r2 = (dx / rw) ** 2 + (dz / rz) ** 2
    if r2 >= 1.0:
        return 0.0
    return (1.0 - r2) ** (p_up if dz > 0 else p_down)


def sculpt_breasts_glutes(obj):
    """
    Pecho y glúteos esculpidos como campos de desplazamiento sobre el torso ya
    fundido (así se funden con él sin costuras, como al esculpirlos a mano):

    pecho  proyección ≈ 5 cm (copa C), pendiente superior que nace en el
           pectoral, polo inferior lleno, pliegue inframamario, escote suave y
           una asimetría natural (el izquierdo un 4 % mayor).
    glúteos redondos y levantados (volumen arriba), surco interglúteo y pliegue
           glúteo marcado debajo.
    """
    me = obj.data
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.normal_update()
    for v in bm.verts:
        x, y, z = v.co
        n = v.normal
        off = Vector()
        if y < 0.02:
            for side in (1, -1):
                ax, az = side * BREAST_APEX[0], BREAST_APEX[1]
                # El centro de la base está algo por encima del ápice (caída natural).
                dx = x - ax
                dz = z - (az + 0.012)
                h = _dome(dx, dz, 0.080, 0.095, 0.058, 1.9, 1.15)
                if h > 0:
                    scale = 1.04 if side > 0 else 1.0
                    d = (n + Vector((side * 0.15, -0.35, -0.08))).normalized()
                    off += d * (0.052 * scale * h)
                # Pliegue inframamario: surco fino bajo el polo inferior.
                rr = math.hypot(dx / 0.080, dz / 0.058) if dz < 0 else 9.0
                off -= n * (0.0025 * math.exp(-(((rr - 1.0) / 0.10) ** 2)) * smoothstep(0.9, 0.2, abs(dx) / 0.08))
        if y > -0.02:
            for side in (1, -1):
                gx, gz = side * GLUTE_APEX[0], GLUTE_APEX[1]
                dx = x - gx
                dz = z - gz
                h = _dome(dx, dz, 0.092, 0.080, 0.062, 1.6, 1.0)
                if h > 0:
                    d = (n + Vector((side * 0.10, 0.25, 0.10))).normalized()
                    off += d * (0.036 * h)
                # Pliegue glúteo: bajo la curva inferior.
                rr = math.hypot(dx / 0.092, dz / 0.062) if dz < 0 else 9.0
                off -= n * (0.004 * math.exp(-(((rr - 1.0) / 0.12) ** 2)) * smoothstep(1.0, 0.3, abs(dx) / 0.092))
        if off.length_squared > 0:
            v.co += off
    bm.to_mesh(me)
    bm.free()
    me.update()


def sculpt_body(obj):
    """
    Retoques de escultura sobre el cuerpo fundido: separación de los glúteos
    (surco interglúteo), surco de la columna, clavículas suaves, línea alba y
    un ombligo discreto. Desplaza según la normal.
    """
    me = obj.data
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.normal_update()
    for v in bm.verts:
        x, y, z = v.co
        off = 0.0
        # Surco interglúteo: detrás, en el centro, entre 0,84 y 1,0 m.
        if y > 0.02:
            off -= 0.020 * math.exp(-((x / 0.011) ** 2)) * smoothstep(0.80, 0.86, z) * smoothstep(1.00, 0.94, z)
            # Columna: surco suave en la espalda.
            off -= 0.0025 * math.exp(-((x / 0.012) ** 2)) * smoothstep(1.0, 1.1, z) * smoothstep(1.42, 1.35, z)
            # Hoyuelos de Venus.
            for s in (1, -1):
                off -= 0.0018 * math.exp(-(((x - s * 0.032) / 0.012) ** 2 + ((z - 1.005) / 0.012) ** 2))
        else:
            # Clavículas y hueco supraesternal.
            for s in (1, -1):
                t = max(0.0, min(1.0, (abs(x) - 0.015) / 0.11)) if x * s > 0 else None
                if t is not None:
                    zc = 1.418 - 0.012 * t
                    off += 0.0016 * math.exp(-(((z - zc) / 0.006) ** 2)) * smoothstep(0.0, 0.1, t) * (1 - t)
            off -= 0.0022 * math.exp(-((x / 0.012) ** 2 + ((z - 1.425) / 0.010) ** 2))
            # Línea alba y ombligo.
            off -= 0.0008 * math.exp(-((x / 0.008) ** 2)) * smoothstep(0.98, 1.05, z) * smoothstep(1.2, 1.12, z)
            off -= 0.0035 * math.exp(-((x / 0.006) ** 2 + ((z - 1.02) / 0.007) ** 2))
        if off:
            v.co += v.normal * off
    bm.to_mesh(me)
    bm.free()
    me.update()


def waist_hip_ratio(obj):
    """Perímetro mínimo de la cintura / máximo de la cadera, cortando la malla en horizontal."""
    def girth(z):
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        geom = bm.verts[:] + bm.edges[:] + bm.faces[:]
        res = bmesh.ops.bisect_plane(bm, geom=geom, plane_co=(0, 0, z), plane_no=(0, 0, 1))
        cut = [e for e in res["geom_cut"] if isinstance(e, bmesh.types.BMEdge)]
        # Solo el contorno del tronco (sin brazos): |x| < 0.25.
        length = sum(e.calc_length() for e in cut if all(abs(v.co.x) < 0.25 for v in e.verts))
        bm.free()
        return length
    waist = min(girth(z) for z in np.linspace(1.06, 1.14, 9))
    hips = max(girth(z) for z in np.linspace(0.86, 0.96, 11))
    return waist, hips, waist / hips


# ──────────────────────────────────────────────────────────────────────────
# Cuerpo: topología, alta/baja
# ──────────────────────────────────────────────────────────────────────────

def _bvh(obj):
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    tree = BVHTree.FromBMesh(bm)
    bm.free()
    return tree


def surface_apex(tree, origin, direction):
    """Punto de la superficie más saliente en una dirección (rayo desde fuera)."""
    hit, normal, _, _ = tree.ray_cast(Vector(origin), Vector(direction).normalized())
    return hit, normal


def radial_patch(bm, tree, apex, normal, radius, radii):
    """
    Parche de anillos concéntricos alrededor de `apex`: se borran las caras
    dentro de `radius`, el agujero se rellena con insets y cada anillo se
    coloca en su círculo (en el plano tangente) y se proyecta a la superficie.
    Es el flujo radial que piden el pecho y los glúteos para deformarse bien.
    """
    faces = [f for f in bm.faces if (f.calc_center_median() - apex).length < radius]
    if len(faces) < 6:
        return
    rverts = {v for f in faces for v in f.verts}
    bmesh.ops.delete(bm, geom=faces, context="FACES")
    hole = [e for e in bm.edges if e.is_boundary and e.verts[0] in rverts and e.verts[1] in rverts]
    fill = bmesh.ops.holes_fill(bm, edges=hole, sides=0)
    if not fill["faces"]:
        return
    face = fill["faces"][0]
    rings = [list(face.verts)]
    for _ in radii:
        bmesh.ops.inset_individual(bm, faces=[face], thickness=0.002, depth=0.0, use_even_offset=True)
        rings.append(list(face.verts))
    n = normal.normalized()
    ux = (Vector((0, 0, 1)).cross(n)).normalized()
    uy = n.cross(ux).normalized()

    def local(p):
        d = p - apex
        return d.dot(ux), d.dot(uy)

    pts = [local(v.co) for v in rings[0]]
    true = [math.atan2(b, a) for a, b in pts]
    prev = [math.hypot(a, b) for a, b in pts]
    seg = [math.hypot(pts[(i + 1) % len(pts)][0] - pts[i][0], pts[(i + 1) % len(pts)][1] - pts[i][1]) for i in range(len(pts))]
    total = sum(seg)
    turn = sum(math.remainder(true[(i + 1) % len(true)] - true[i], 2 * math.pi) for i in range(len(true)))
    sign = 1.0 if turn >= 0 else -1.0
    arc, acc = [], 0.0
    for i in range(len(pts)):
        arc.append(sign * 2 * math.pi * acc / total)
        acc += seg[i]
    off = math.atan2(sum(math.sin(t - a) for t, a in zip(true, arc)), sum(math.cos(t - a) for t, a in zip(true, arc)))
    arc = [a + off for a in arc]
    for k, rk in enumerate(radii, start=1):
        w = min(1.0, k / 2.0)
        cur = []
        for i, v in enumerate(rings[k]):
            th = true[i] + w * math.remainder(arc[i] - true[i], 2 * math.pi)
            r = min(rk, prev[i] * 0.84)
            cur.append(r)
            p = apex + (ux * math.cos(th) + uy * math.sin(th)) * r
            loc, _, _, _ = tree.find_nearest(p)
            v.co = loc if loc is not None else p
        prev = cur
    c = bmesh.ops.poke(bm, faces=[face])
    for v in c["verts"]:
        v.co = apex


def snap_to(obj, tree):
    """Proyecta cada vértice sobre la escultura (retopología)."""
    for v in obj.data.vertices:
        loc, _, _, _ = tree.find_nearest(v.co)
        if loc is not None:
            v.co = loc
    obj.data.update()


def build_body(q):
    """
    Devuelve (alta, baja, ápices). Alta: jaula + Multires reproyectado sobre la
    escultura. Baja: jaula en quads con los parches radiales, sobre la
    escultura. Ápices: dónde colocar los huesos de movimiento secundario.
    """
    parts = build_body_shape()
    body = parts[0]
    A.activate(body)
    for o in parts[1:]:
        o.select_set(True)
    bpy.ops.object.join()
    body = bpy.context.view_layer.objects.active
    body.name = "Masha_Body_sculpt"

    # 1. Escultura. Primero una fusión gruesa y muy alisada (las uniones de
    #    las piezas se funden en curvas, sin escalones), luego la fina.
    for voxel, lam, its in ((0.007, 1.0, 24), (q["voxel_high"], 0.8, 10)):
        m = body.modifiers.new("remesh", "REMESH")
        m.mode = "VOXEL"
        m.voxel_size = voxel
        sm = body.modifiers.new("smooth", "LAPLACIANSMOOTH")
        sm.lambda_factor = lam
        sm.iterations = its
        sm.use_volume_preserve = True
        A.apply_modifiers(body)
    sculpt_breasts_glutes(body)
    sculpt_body(body)
    A.smooth_shading(body)
    w, h, ratio = waist_hip_ratio(body)
    print(f"[masha] cintura {w * 100:.1f} cm, cadera {h * 100:.1f} cm, cintura/cadera {ratio:.3f}")
    tree = _bvh(body)

    # 2. Retopología en quads. El remallado vóxel de Blender da una malla toda
    #    de quads, uniforme; con el tamaño de vóxel calculado para el número de
    #    caras buscado y relajada, es la jaula. (QuadriFlow sería la otra vía,
    #    pero en modo de fondo `blender -b` se lanza como tarea y no llega a
    #    ejecutarse.)
    area = sum(p.area for p in body.data.polygons)
    cage = A.duplicate(body, "Masha_Body_cage")
    m = cage.modifiers.new("remesh", "REMESH")
    m.mode = "VOXEL"
    m.voxel_size = math.sqrt(area / q["body_quads"])
    sm = cage.modifiers.new("relax", "LAPLACIANSMOOTH")
    sm.lambda_factor = 0.5
    sm.iterations = 4
    sm.use_volume_preserve = True
    A.apply_modifiers(cage)
    print(f"[masha] jaula del cuerpo: {len(cage.data.polygons)} quads")

    # Parches radiales en pecho y glúteos.
    apices = {}
    bm = bmesh.new()
    bm.from_mesh(cage.data)
    for side in (1, -1):
        s = side
        hit, nrm = surface_apex(tree, (s * BREAST_APEX[0], -0.4, BREAST_APEX[1]), (0, 1, 0))
        if hit is not None:
            apices[f"breast.{'L' if s > 0 else 'R'}"] = (hit, nrm)
            radial_patch(bm, tree, hit, nrm, 0.060, [0.049, 0.038, 0.028, 0.018, 0.009])
        hit, nrm = surface_apex(tree, (s * GLUTE_APEX[0], 0.4, GLUTE_APEX[1]), (0, -1, 0))
        if hit is not None:
            apices[f"glute.{'L' if s > 0 else 'R'}"] = (hit, nrm)
            radial_patch(bm, tree, hit, nrm, 0.068, [0.056, 0.044, 0.032, 0.020, 0.010])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(cage.data)
    bm.free()
    snap_to(cage, tree)
    A.smooth_shading(cage)

    # 3. Alta: Multires (2 niveles) reproyectado sobre la escultura.
    def reproject(mold):
        snap_to(mold, tree)

    high = A.multires_high(cage, 2, reproject, "Masha_Body_high")
    low = A.duplicate(cage, "Masha_Body")
    bpy.data.objects.remove(cage, do_unlink=True)
    bpy.data.objects.remove(body, do_unlink=True)
    A.smooth_shading(low)
    return high, low, apices


# ──────────────────────────────────────────────────────────────────────────
# Movimiento secundario
# ──────────────────────────────────────────────────────────────────────────

SOFT_BONES = {
    # hueso: (padre, radio de influencia, profundidad del hueso bajo la piel)
    "breast.L": ("chest", 0.078, 0.050),
    "breast.R": ("chest", 0.078, 0.050),
    "glute.L": ("hips", 0.090, 0.060),
    "glute.R": ("hips", 0.090, 0.060),
}


def add_soft_bones(rig, apices):
    """Huesos de pecho y glúteos: nacen dentro del cuerpo y apuntan al ápice."""
    A.activate(rig)
    bpy.ops.object.mode_set(mode="EDIT")
    eb = rig.data.edit_bones
    for name, (parent, _, depth) in SOFT_BONES.items():
        if name not in apices:
            continue
        hit, nrm = apices[name]
        b = eb.new(name)
        b.head = hit - nrm.normalized() * depth
        b.tail = hit
        b.parent = eb[parent]
        b.use_deform = True
    bpy.ops.object.mode_set(mode="OBJECT")


def soft_weights(body, apices):
    """
    Pesos suaves de los huesos de movimiento secundario (caída suave desde el
    ápice, sin bordes duros) y grupos "jiggle_breast"/"jiggle_glute" con los
    mismos pesos, listos como grupo objetivo (goal) de Soft Body en Blender.
    """
    me = body.data
    groups = {g.name: g for g in body.vertex_groups}
    jig = {k: body.vertex_groups.get(k) or body.vertex_groups.new(name=k) for k in ("jiggle_breast", "jiggle_glute")}
    for name, (_, radius, _) in SOFT_BONES.items():
        if name not in apices:
            continue
        hit, nrm = apices[name]
        grp = groups.get(name) or body.vertex_groups.new(name=name)
        groups[name] = grp
        nrm = nrm.normalized()
        for v in me.vertices:
            d = (v.co - hit)
            # Solo el lado bueno (delante para el pecho, detrás para los glúteos).
            if d.dot(nrm) < -radius * 0.6:
                continue
            w = smoothstep(radius, radius * 0.25, d.length)
            if w <= 0.001:
                continue
            w *= 0.85
            # Lo que no es de este hueso se reparte en el resto, normalizado.
            for g in v.groups:
                if g.group != grp.index and body.vertex_groups[g.group].name not in jig:
                    g.weight *= (1.0 - w)
            grp.add([v.index], w, "REPLACE")
            jig["jiggle_breast" if name.startswith("breast") else "jiggle_glute"].add([v.index], w, "REPLACE")


# ──────────────────────────────────────────────────────────────────────────
# Pelo
# ──────────────────────────────────────────────────────────────────────────

PART = 0.30          # raya al lado (acimut, a la izquierda de ella)


def _scalp(theta, elev, lift):
    n = A.uv_dir(theta, elev)
    p = A.head_volume(n) + HEAD_C
    return p + (p - HEAD_C).normalized() * lift


def hairline(theta):
    """
    Elevación del nacimiento del pelo alrededor de la cabeza: alta en el centro
    de la frente, baja en curva por las sienes, sobre las orejas en los lados
    y más abajo en la nuca.
    """
    front = math.cos(theta)
    back = max(-front, 0.0)
    if front > 0:
        return lerp(-0.02, 0.50, smoothstep(0.35, 0.95, front))
    return lerp(-0.02, -0.33, back)


def _resample(pts, samples):
    L = [0.0]
    for a, b in zip(pts, pts[1:]):
        L.append(L[-1] + (b - a).length)
    out = []
    for k in range(samples):
        target = L[-1] * k / (samples - 1)
        j = max(1, next((i for i, l in enumerate(L) if l >= target), len(L) - 1))
        t = (target - L[j - 1]) / max(L[j] - L[j - 1], 1e-9)
        out.append(pts[j - 1].lerp(pts[j], t))
    return out


def _hair_path(theta, samples, lift_scale=1.0, extra_len=0.0):
    """
    Un mechón de la melena desde la coronilla. Delante termina en el
    nacimiento del pelo (el volumen se afina hasta 0: no hay borde de casco);
    en los lados y la nuca deja la cabeza y cae pegado a la cara hasta la
    mandíbula, con las puntas metidas hacia dentro (bob). En los lados de la
    cara el pelo se retira un poco hacia atrás: enmarca, no tapa.
    """
    front = math.cos(theta)
    back = max(-front, 0.0)
    hl = hairline(theta)
    face_side = smoothstep(0.25, 0.75, front)          # 1 = delante (frente)
    pts = []
    # Sobre el cráneo: de la coronilla hasta donde el pelo deja la cabeza.
    leave = lerp(hl, lerp(0.05, -0.22, back), 1.0 - face_side)
    for i in range(26):
        t = i / 25
        e = lerp(1.47, leave, t ** 0.95)
        vol = 0.012 + 0.008 * math.sin(math.pi * min(1.0, t * 1.15)) + 0.004 * back
        # Delante, el volumen se afina hasta el nacimiento del pelo.
        vol *= lerp(1.0, smoothstep(hl, hl + 0.22, e) * 0.85 + 0.15, face_side)
        pts.append(_scalp(theta, e, vol * lift_scale))
    if face_side < 0.98:
        start = pts[-1]
        out = Vector((start.x - HEAD_C.x, start.y - HEAD_C.y, 0)).normalized()
        z_end = HEAD_C.z + HEAD_R.z * lerp(-0.86, -0.60, back) - extra_len
        drop = start.z - z_end
        hang = 1.0 - face_side
        for i in range(1, 12):
            t = i / 11
            flare = 0.005 * math.sin(math.pi * min(1.0, t * 1.25)) - 0.008 * smoothstep(0.72, 1.0, t)
            p = start + out * flare + Vector((0, 0, -drop * t * hang))
            p.y += 0.008 * max(front, 0.0) * t                # se retira hacia atrás junto a la cara
            pts.append(p)
    return _resample(pts, samples)


def _fringe_path(k, n, samples, rnd):
    """
    Flequillo barrido: nace junto a la raya y cruza la frente en diagonal
    hacia la sien contraria, terminando por encima de la ceja.
    """
    t0 = k / max(n - 1, 1)
    th_start = PART - 0.05 - 0.35 * t0 + rnd.uniform(-0.04, 0.04)
    th_end = lerp(-0.25, -0.95, t0) + rnd.uniform(-0.05, 0.05)
    e_start = 1.05 - 0.25 * t0
    e_end = lerp(0.42, 0.30, t0) + rnd.uniform(-0.02, 0.02)
    pts = []
    for i in range(20):
        t = i / 19
        th = lerp(th_start, th_end, smoothstep(0.0, 1.0, t))
        e = lerp(e_start, e_end, t ** 1.2)
        lift = 0.010 + 0.006 * math.sin(math.pi * t) - 0.004 * t
        pts.append(_scalp(th, e, lift))
    return _resample(pts, samples)


def build_hair(q, tex_dir, code_img):
    """
    Carcasa de volumen (quads, suave) + tarjetas de pelo con alfa. Devuelve un
    objeto con dos materiales: 0 = carcasa, 1 = tarjetas.
    """
    rnd = random.Random(SEED)
    n_theta, n_s = q["hair_theta"], q["hair_rows"]
    bm = bmesh.new()
    uv = bm.loops.layers.uv.new("UVMap")
    grid = []
    for i in range(n_theta):
        th = PART + 2 * math.pi * i / n_theta
        grid.append([bm.verts.new(p) for p in _hair_path(th, n_s)])
    crown = bm.verts.new(_scalp(PART, 1.5707, 0.016))
    for i in range(n_theta):
        a, b = grid[i], grid[(i + 1) % n_theta]
        u0, u1 = i / n_theta * 8, (i + 1) / n_theta * 8
        f = bm.faces.new((crown, b[0], a[0]))
        for l, t in zip(f.loops, ((u0, 0), (u1, 0), (u0, 0))):
            l[uv].uv = t
        for j in range(n_s - 1):
            f = bm.faces.new((a[j], b[j], b[j + 1], a[j + 1]))
            for l, t in zip(f.loops, ((u0, j / (n_s - 1)), (u1, j / (n_s - 1)), (u1, (j + 1) / (n_s - 1)), (u0, (j + 1) / (n_s - 1)))):
                l[uv].uv = t
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    shell = A.mesh_object("Masha_Hair", bm)
    sub = shell.modifiers.new("sub", "SUBSURF")
    sub.levels = 1
    sub.uv_smooth = "PRESERVE_BOUNDARIES"
    A.apply_modifiers(shell)
    A.smooth_shading(shell)

    # Tarjetas: siguen el mismo flujo, algo por fuera, con largos variados que
    # rompen la silueta en las puntas; más el flequillo barrido.
    bm = bmesh.new()
    uv = bm.loops.layers.uv.new("UVMap")

    def card(path, width):
        verts = []
        for j, p in enumerate(path):
            nxt = path[min(j + 1, len(path) - 1)]
            prv = path[max(j - 1, 0)]
            tangent = (nxt - prv).normalized()
            radial = (p - HEAD_C).normalized()
            side = tangent.cross(radial).normalized()
            wj = width * (1.0 - 0.6 * (j / (len(path) - 1)) ** 1.5)
            verts.append((bm.verts.new(p - side * wj / 2), bm.verts.new(p + side * wj / 2)))
        u0 = rnd.uniform(0, 0.75)
        for j in range(len(verts) - 1):
            (a0, b0), (a1, b1) = verts[j], verts[j + 1]
            f = bm.faces.new((a0, b0, b1, a1))
            v0, v1 = j / (len(verts) - 1), (j + 1) / (len(verts) - 1)
            for l, t in zip(f.loops, ((u0, v0), (u0 + 0.25, v0), (u0 + 0.25, v1), (u0, v1))):
                l[uv].uv = t

    for k in range(q["hair_cards"]):
        th = PART + rnd.uniform(0, 2 * math.pi)
        if math.cos(th) > 0.55:
            continue                                   # delante va el flequillo
        card(_hair_path(th, 10, lift_scale=rnd.uniform(1.08, 1.45), extra_len=rnd.uniform(-0.004, 0.012)), rnd.uniform(0.010, 0.017))
    n_fringe = max(8, q["hair_cards"] // 7)
    for k in range(n_fringe):
        card(_fringe_path(k, n_fringe, 12, rnd), rnd.uniform(0.014, 0.022))
    cards = A.mesh_object("hair_cards", bm)
    A.smooth_shading(cards)

    # Materiales.
    htex = hair_texture(q["tex"], "masha_hair", code_img)
    pack_image(htex, f"{tex_dir}/masha_hair.jpg", "JPEG")
    stex = strand_texture(512, "masha_strands")
    pack_image(stex, f"{tex_dir}/masha_strands.png", "PNG")
    shell.data.materials.append(hair_shell_material(htex))
    cards.data.materials.append(alpha_card_material("Holo_HairCards", stex, (0.35, 0.5, 1.0), 1.4))
    A.activate(shell)
    cards.select_set(True)
    bpy.ops.object.join()
    hair = bpy.context.view_layer.objects.active
    hair.name = "Masha_Hair"
    return hair


def hair_shell_material(tex):
    mat = bpy.data.materials.new("Holo_Hair")
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (0.16, 0.18, 0.75, 1)
    bsdf.inputs["Alpha"].default_value = 0.92
    bsdf.inputs["Roughness"].default_value = 0.35
    bsdf.inputs["Emission Strength"].default_value = 2.2
    img = nt.nodes.new("ShaderNodeTexImage")
    img.image = tex
    mapping = nt.nodes.new("ShaderNodeMapping")
    mapping.inputs["Location"].default_value = (0.0, 0.001, 0)
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mix = nt.nodes.new("ShaderNodeMixRGB")
    mix.blend_type = "MULTIPLY"
    mix.inputs["Fac"].default_value = 1.0
    mix.inputs["Color2"].default_value = (0.5, 0.55, 1.0, 1)
    nt.links.new(tc.outputs["UV"], mapping.inputs["Vector"])
    nt.links.new(mapping.outputs["Vector"], img.inputs["Vector"])
    nt.links.new(img.outputs["Color"], mix.inputs["Color1"])
    nt.links.new(mix.outputs["Color"], bsdf.inputs["Emission Color"])
    mat.blend_method = "BLEND"
    mat.use_backface_culling = False
    return mat


def alpha_card_material(name, tex, emit, strength):
    """Material de tarjetas (pelo, pestañas, cejas): color y alfa de la textura, algo de emisión."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    img = nt.nodes.new("ShaderNodeTexImage")
    img.image = tex
    nt.links.new(img.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(img.outputs["Alpha"], bsdf.inputs["Alpha"])
    bsdf.inputs["Emission Color"].default_value = (*emit, 1)
    bsdf.inputs["Emission Strength"].default_value = strength
    bsdf.inputs["Roughness"].default_value = 0.4
    mat.blend_method = "BLEND"
    mat.use_backface_culling = False
    return mat


# ──────────────────────────────────────────────────────────────────────────
# Pestañas y cejas
# ──────────────────────────────────────────────────────────────────────────

def _eye_point(side, eu, ev, scale=1.0):
    """(u, v) de un punto del contorno del ojo en coordenadas del ojo."""
    au = A.EYE_U + eu * A.EYE_OPEN[0] * scale
    v = A.EYE_V + A.EYE_TILT * A.EYE_OPEN[1] * eu + ev * A.EYE_OPEN[1] * scale
    return side * au, v


def build_face_hair(tex):
    """
    Pestañas (superiores largas y curvadas, inferiores cortas) y cejas finas
    con arco, como tarjetas con la textura de mechones. Cada vértice lleva en
    la capa "Param" el (u, v) de su raíz: así el parpadeo y las cejas se mueven
    con los morphs de la cara.
    """
    bm = bmesh.new()
    lp = bm.loops.layers.uv.new("Param")
    lm = bm.loops.layers.uv.new("UVMap")

    def strip(rows):
        """rows = [(param_raíz, punto_raíz, punto_punta, t)]"""
        pairs = [(bm.verts.new(r), bm.verts.new(tp), pr, t) for pr, r, tp, t in rows]
        for (a0, b0, p0, t0), (a1, b1, p1, t1) in zip(pairs, pairs[1:]):
            f = bm.faces.new((a0, a1, b1, b0))
            for l, (pp, m) in zip(f.loops, ((p0, (t0, 0)), (p1, (t1, 0)), (p1, (t1, 1)), (p0, (t0, 1)))):
                l[lp].uv = pp
                l[lm].uv = m

    for side in (1, -1):
        # Pestañas superiores: más largas hacia el ángulo externo, curvadas
        # hacia delante y arriba.
        rows = []
        for i in range(19):
            t = i / 18
            th = lerp(math.radians(8), math.radians(172), t)
            eu, ev = math.cos(th), math.sin(th)
            u, v = _eye_point(side, eu, ev, 1.02)
            root = A.face_point(u, v) + Vector((0, -0.0003, 0))
            length = 0.0062 * (0.45 + 0.55 * math.sin(th) ** 0.6) * (1.15 if eu > 0 else 0.85)
            d = Vector((side * 0.25 * eu, -0.85, 0.55)).normalized()
            tip = root + d * length + Vector((0, 0, 0.0012))
            rows.append(((u, v), root, tip, t * 4))
        strip(rows)
        # Inferiores: cortas.
        rows = []
        for i in range(13):
            t = i / 12
            th = lerp(math.radians(196), math.radians(344), t)
            eu, ev = math.cos(th), math.sin(th)
            u, v = _eye_point(side, eu, ev, 1.02)
            root = A.face_point(u, v) + Vector((0, -0.0002, 0))
            d = Vector((side * 0.2 * eu, -0.8, -0.55)).normalized()
            rows.append(((u, v), root, root + d * 0.0022 * (0.5 + 0.5 * abs(math.sin(th))), t * 3))
        strip(rows)
        # Ceja: arco fino, más grueso en la cabeza, con la cola hacia fuera.
        rows = []
        for i in range(17):
            t = i / 16
            au = lerp(0.19, 0.66, t)
            v = 0.262 + 0.034 * math.sin(math.pi * min(1.0, t / 0.65) * 0.5) - 0.022 * smoothstep(0.65, 1.0, t)
            half = lerp(0.016, 0.005, t ** 1.1)
            u = side * au
            lo = A.face_point(u, v - half)
            hi = A.face_point(u, v + half)
            lift = (lo - HEAD_C).normalized() * 0.0006
            rows.append(((u, v), lo + lift, hi + lift, t * 3))
        strip(rows)
    obj = A.mesh_object("face_hair", bm)
    A.smooth_shading(obj)
    obj.data.materials.append(alpha_card_material("Holo_Lashes", tex, (0.25, 0.45, 1.0), 0.6))
    return obj
