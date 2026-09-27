"""
Anatomía detallada de Masha: cara y manos con topología de verdad, detalle
esculpido con Multires y horneado de normales + oclusión (AO).

Lo usa build_masha.py. Flujo por pieza (cabeza, manos, cuerpo):

    JAULA (cage) de quads con buen flujo de aristas
      ├─ BAJA  = jaula + Subdivision Surface aplicado + Weighted Normals   → va al GLB
      └─ ALTA  = jaula + Multires (3 niveles) + "escultura" (Multires Reshape)
                 └─ se hornean NORMAL (espacio tangente) y AO sobre la BAJA

La escultura es procedural: el pincel de Sculpt Mode no se puede usar en
modo de fondo (`blender -b`, sin ventana), así que el detalle fino se
calcula aquí y se lleva a los niveles de Multires con `multires_reshape`,
que es la vía de Blender para escribir en ellos desde un script. Si se
quiere retocar a mano: abrir el .blend, esculpir sobre el objeto *_high
(Multires) y volver a hornear con bake_maps().

Cara
  Esfera de quads (cubo subdividido proyectado, ángulos iguales). En los
  ojos, la nariz y la boca se abre un agujero y se rellena con anillos
  concéntricos (inset repetido), que luego se recolocan sobre elipses:
    ojo   → reborde orbitario, pliegue del párpado, párpado, borde libre
            y la abertura (con cuenca hacia dentro, donde va el globo ocular)
    boca  → surco del labio, borde del bermellón, cuerpo del labio,
            línea de cierre y la abertura (con saco bucal: se abre de verdad)
    nariz → anillos hasta la punta
  La superficie es una función analítica de (u, v) —acimut, elevación—:
  cada vértice guarda su (u, v) en una capa UV "Param" y, tras subdividir,
  se vuelve a colocar exactamente sobre la forma (sin encoger rasgos).

Manos
  Palma como caja de quads (8×3×2), dedos extruidos región a región con
  sección de 8 vértices, 3 bucles por falange más el de la articulación,
  nudillos con volumen, uña con inset y pliegue, pulgar desde la eminencia
  tenar. Detalle (arrugas de nudillos, pliegues palmares y de las falanges,
  tendones del dorso) en la versión alta.
"""

import math

import bmesh
import bpy
from mathutils import Matrix, Vector

# ──────────────────────────────────────────────────────────────────────────
# Utilidades
# ──────────────────────────────────────────────────────────────────────────


def lerp(a, b, t):
    return a + (b - a) * t


def smoothstep(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def g2(x, y, cx, cy, sx, sy):
    """Gaussiana 2D (1 en el centro)."""
    return math.exp(-(((x - cx) / sx) ** 2) - (((y - cy) / sy) ** 2))


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


def duplicate(obj, name):
    dup = obj.copy()
    dup.data = obj.data.copy()
    dup.name = name
    dup.data.name = name
    dup.modifiers.clear()
    return link(dup)


def smooth_shading(obj):
    for p in obj.data.polygons:
        p.use_smooth = True


# ──────────────────────────────────────────────────────────────────────────
# Cabeza: volumen y rasgos
# ──────────────────────────────────────────────────────────────────────────

HEAD_C = Vector((0.0, -0.006, 1.638))
HEAD_R = Vector((0.079, 0.097, 0.114))     # semiejes: ancho, fondo, alto

# Rasgos en coordenadas angulares: u = acimut (0 = delante, + = izquierda de
# ella), v = elevación. Radianes.
# Proporciones de una cara femenina joven: ojos almendrados grandes y algo
# rasgados hacia arriba, boca más estrecha y llena, tercio inferior corto.
EYE_U, EYE_V = 0.405, 0.105
EYE_OPEN = (0.188, 0.044)                  # semiejes de la abertura: el párpado apoya sobre el iris
EYE_TILT = 0.22                            # el ángulo externo más alto (mirada felina, no de susto)
MOUTH_V = -0.50
MOUTH_W = 0.32                             # media anchura hasta la comisura
NOSE_C = (0.0, -0.165)

MOUTH_Z = math.sin(MOUTH_V)                # la línea de la boca en z normalizada (morphs)


def uv_dir(u, v):
    return Vector((math.sin(u) * math.cos(v), -math.cos(u) * math.cos(v), math.sin(v)))


def dir_uv(n):
    n = n.normalized()
    return math.atan2(n.x, -n.y), math.asin(max(-1.0, min(1.0, n.z)))


def head_volume(n):
    """
    Volumen de la cabeza sin rasgos, relativo a HEAD_C: cráneo lleno, cara
    más plana que una esfera y mandíbula que se estrecha con ángulo marcado.
    `n` es una dirección unitaria (cara hacia -y).
    """
    x, y, z = n.x, n.y, n.z
    front = max(0.0, -y)
    if y < 0:
        y *= lerp(1.0, 0.88, smoothstep(-0.6, 0.2, z))
    else:
        y *= 1.06 if z > -0.3 else 1.0
    if z < 0:
        k = min(1.0, (-z) ** 1.4)
        x *= lerp(1.0, lerp(0.60, 0.80, max(y, 0.0)), k)      # mandíbula en V suave
        y = y * lerp(1.0, 0.9, -z) - 0.05 * smoothstep(0.6, 1.0, -z) * front
    # Sienes algo hundidas (la cabeza no es un huevo).
    x *= 1.0 - 0.05 * g2(abs(math.atan2(n.x, -n.y)), n.z, 1.2, 0.25, 0.35, 0.25)
    return Vector((x * HEAD_R.x, y * HEAD_R.y, z * HEAD_R.z))


def eye_local(au, v):
    """Coordenadas del ojo normalizadas a su abertura (la abertura es r = 1)."""
    eu = (au - EYE_U) / EYE_OPEN[0]
    ev = (v - EYE_V - EYE_TILT * EYE_OPEN[1] * eu) / EYE_OPEN[1]
    return eu, ev


def nose_profile(v):
    """Cuánto sale la nariz (m) y su media anchura (rad) a la altura v."""
    # Nariz pequeña y recta, con la punta algo respingona.
    pts = [(0.10, 0.000, 0.045), (0.04, 0.0022, 0.040), (-0.05, 0.0068, 0.040),
           (-0.13, 0.0118, 0.046), (-0.190, 0.0172, 0.058), (-0.232, 0.0128, 0.062),
           (-0.268, 0.0050, 0.058), (-0.30, 0.000, 0.05)]
    if v >= pts[0][0] or v <= pts[-1][0]:
        return 0.0, 0.05
    for (v0, o0, w0), (v1, o1, w1) in zip(pts, pts[1:]):
        if v1 <= v <= v0:
            t = (v0 - v) / (v0 - v1)
            t = t * t * (3 - 2 * t)
            return lerp(o0, o1, t), lerp(w0, w1, t)
    return 0.0, 0.05


def face_relief(u, v, detail):
    """
    Relieve de la cara en metros (+ = hacia delante) en (u, v). La versión
    `detail` añade lo que solo va al mapa de normales: arrugas finas de los
    labios, pliegues nasolabiales, pliegue del párpado marcado, poros, cejas.
    """
    au = abs(u)
    r = 0.0
    # Frente lisa y redondeada; arco superciliar apenas marcado (uno fuerte
    # masculiniza y hunde la mirada).
    r += 0.0030 * g2(u, v, 0.0, 0.50, 0.60, 0.25)
    r += 0.0020 * g2(au, v, 0.37, 0.285, 0.26, 0.05)
    r -= 0.0006 * g2(u, v, 0.0, 0.24, 0.07, 0.05)          # entrecejo, casi liso

    # Ojo: cuenca poco honda, párpados suaves, pliegue superior y piel llena
    # bajo la ceja. Nada de ojeras (la AO bajo el ojo es lo que asusta).
    eu, ev = eye_local(au, v)
    re = math.hypot(eu, ev)
    upper = smoothstep(-0.1, 0.5, ev / max(re, 1e-4))
    r -= 0.0070 * math.exp(-((re / 2.4) ** 2))
    r += 0.0040 * math.exp(-(((re - 1.14) / 0.30) ** 2)) * lerp(0.7, 1.0, upper)
    r -= 0.0012 * math.exp(-(((re - 2.0) / 0.22) ** 2)) * upper       # pliegue palpebral
    r += 0.0013 * math.exp(-(((re - 2.7) / 0.40) ** 2)) * upper       # piel llena bajo la ceja

    # Mejillas: llenas y redondas ("manzanas"), pómulo alto pero suave.
    r += 0.0040 * g2(au, v, 0.56, -0.12, 0.17, 0.12)
    r += 0.0052 * g2(au, v, 0.42, -0.21, 0.16, 0.13)
    r += 0.0014 * g2(au, v, 0.38, -0.05, 0.12, 0.05)

    # Nariz: perfil, aletas pequeñas y orificios apenas insinuados.
    out, width = nose_profile(v)
    r += out * math.exp(-((u / width) ** 2))
    r += 0.0062 * g2(au, v, 0.085, -0.245, 0.038, 0.034)
    ra = math.hypot((au - 0.085) / 0.038, (v + 0.245) / 0.034)
    r -= 0.0007 * math.exp(-(((ra - 1.35) / 0.35) ** 2)) * smoothstep(-0.33, -0.2, v)
    r -= 0.0016 * g2(au, v, 0.040, -0.272, 0.030, 0.020)
    if detail:
        r -= 0.0007 * math.exp(-(((ra - 1.35) / 0.2) ** 2)) * smoothstep(-0.33, -0.2, v)
        r -= 0.0018 * g2(au, v, 0.040, -0.275, 0.018, 0.011)

    # Filtrum corto y marcado.
    r -= 0.0010 * g2(u, v, 0.0, -0.38, 0.026, 0.045)
    r += 0.0007 * g2(au, v, 0.042, -0.38, 0.014, 0.045)

    # Labios llenos: arco de Cupido definido, tubérculo, inferior carnoso y
    # comisuras un poco hacia arriba (gesto amable en reposo).
    mu = u / MOUTH_W
    mv = v - MOUTH_V
    mask = max(0.0, 1.0 - mu * mu) ** 0.7
    full = max(0.0, 1.0 - mu * mu) ** 1.2
    r += 0.0088 * full * math.exp(-(((mv - 0.026) / 0.022) ** 2))     # superior
    r += 0.0016 * g2(au, v, 0.055, MOUTH_V + 0.050, 0.035, 0.012)      # picos del arco de Cupido
    r -= 0.0008 * g2(u, v, 0.0, MOUTH_V + 0.052, 0.018, 0.010)         # su valle
    r += 0.0016 * g2(u, v, 0.0, MOUTH_V + 0.018, 0.06, 0.016)          # tubérculo
    r += 0.0110 * (full ** 0.8) * math.exp(-(((mv + 0.034) / 0.030) ** 2))  # inferior
    r += 0.0012 * g2(au, v, 0.11, MOUTH_V - 0.036, 0.08, 0.022)
    r -= 0.0012 * mask * math.exp(-((mv / 0.006) ** 2))                 # línea de cierre
    r -= 0.0024 * g2(au, v, MOUTH_W, MOUTH_V + 0.008, 0.032, 0.028)    # comisuras (algo altas)
    r -= 0.0009 * g2(u, v, 0.0, MOUTH_V - 0.140, 0.24, 0.045)          # surco mentolabial suave
    # Mentón pequeño y redondeado.
    r += 0.0080 * math.exp(-((u / 0.22) ** 4)) * math.exp(-(((v + 0.84) / 0.14) ** 2))

    if detail:
        # Borde del bermellón, fino y nítido.
        r += 0.0006 * mask * math.exp(-(((mv - 0.058) / 0.005) ** 2))
        r += 0.0005 * mask * math.exp(-(((mv + 0.075) / 0.006) ** 2))
        # Arrugas verticales finas de los labios, solo en el bermellón.
        lips = mask * smoothstep(0.058, 0.045, mv) * smoothstep(-0.072, -0.058, mv) * (1 - math.exp(-((mv / 0.006) ** 2)))
        r -= 0.00003 * lips * (0.5 + 0.5 * math.sin(u * 260.0))
        # Pliegue nasolabial (de la aleta a la comisura).
        ax, ay, bx, by = 0.13, -0.27, 0.40, -0.56
        t = max(0.0, min(1.0, ((au - ax) * (bx - ax) + (v - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2)))
        d = math.hypot(au - (ax + t * (bx - ax)), v - (ay + t * (by - ay)))
        r -= 0.0005 * math.exp(-((d / 0.015) ** 2)) * math.sin(t * math.pi)
        # Pliegue del párpado, más marcado, y línea de pestañas.
        r -= 0.0008 * math.exp(-(((re - 2.05) / 0.07) ** 2)) * upper
        r += 0.0005 * math.exp(-(((re - 1.03) / 0.05) ** 2))
        # Cejas: trazos finos a lo largo del arco.
        brow = g2(au, v, 0.40, 0.285, 0.22, 0.028)
        r += 0.00012 * brow * (0.5 + 0.5 * math.sin(au * 520.0 + v * 140.0))
        # Poros: ruido muy fino (suma de senos, determinista).
        r += 0.00002 * (math.sin(u * 910.0) * math.sin(v * 870.0 + 1.3) + math.sin(u * 1430.0 + 2.1) * math.sin(v * 1510.0))
    return r


def face_point(u, v, detail=False):
    """Punto de la superficie de la cabeza (absoluto) en (u, v)."""
    n = uv_dir(u, v)
    p = head_volume(n)
    front = max(0.0, -n.y)
    k = smoothstep(0.0, 0.35, front)
    if k > 0:
        p.y -= face_relief(u, v, detail) * k
    return p + HEAD_C


# ──────────────────────────────────────────────────────────────────────────
# Cabeza: topología
# ──────────────────────────────────────────────────────────────────────────

# (centro, elipse de selección, anillos (ru, rv) de fuera a dentro)
EYE_RINGS = [(0.285, 0.150), (0.255, 0.122), (0.228, 0.096), (0.209, 0.074), (0.197, 0.056), (0.188, 0.044)]
MOUTH_RINGS = [(0.40, 0.130), (0.37, 0.106), (0.352, 0.083), (0.340, 0.060), (0.332, 0.040), (0.326, 0.020), (0.32, 0.0035)]
NOSE_RINGS = [(0.098, 0.128), (0.078, 0.100), (0.058, 0.070), (0.036, 0.042)]


def quad_sphere(res):
    """Esfera de quads de ángulos iguales (sin polos): buena base para esculpir."""
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=2.0)
    bmesh.ops.subdivide_edges(bm, edges=bm.edges[:], cuts=res - 1, use_grid_fill=True)
    for v in bm.verts:
        c = v.co
        v.co = Vector((math.tan(c.x * math.pi / 4), math.tan(c.y * math.pi / 4), math.tan(c.z * math.pi / 4))).normalized()
    return bm


def _in_ellipse(u, v, cu, cv, ru, rv):
    return ((u - cu) / ru) ** 2 + ((v - cv) / rv) ** 2 <= 1.0


def _cut_feature(bm, param, center, sel, rings, taken, asym=(1.0, 1.0)):
    """
    Abre un agujero en la región elíptica `sel` alrededor de `center`, lo
    rellena con anillos concéntricos (inset repetido) y coloca cada anillo
    sobre su elipse. Devuelve (anillos de vértices, cara interior).
    `asym` = escala vertical de la mitad (superior, inferior) de los anillos.
    """
    cu, cv = center
    faces = []
    for f in bm.faces:
        u, v = dir_uv(f.calc_center_median())
        if _in_ellipse(u, v, cu, cv, *sel) and not any(vv in taken for vv in f.verts):
            faces.append(f)
    region_verts = {vv for f in faces for vv in f.verts}
    bmesh.ops.delete(bm, geom=faces, context="FACES")
    hole = [e for e in bm.edges if e.is_boundary and e.verts[0] in region_verts and e.verts[1] in region_verts]
    fill = bmesh.ops.holes_fill(bm, edges=hole, sides=0)
    face = fill["faces"][0]
    ring_list = [list(face.verts)]
    for _ in rings:
        bmesh.ops.inset_individual(bm, faces=[face], thickness=0.004, depth=0.0, use_even_offset=True)
        ring_list.append(list(face.verts))
    # El borde exterior se queda donde está; los anillos, sobre sus elipses.
    # Cada vértice de un anillo toma el ángulo de SU vértice del borde (inset
    # conserva el orden 1:1): las aristas radiales quedan rectas y ningún
    # anillo gira respecto al anterior (si gira, los quads se retuercen y al
    # subdividir se pliegan).
    # El borde es una escalera (sale de la rejilla): varios vértices seguidos
    # comparten casi el mismo ángulo. Por eso los ángulos se reparten por
    # longitud de arco a lo largo del borde (siempre crecientes y sin
    # amontonarse), orientados como el ángulo real de cada vértice.
    pts = [param.get(vv) or dir_uv(vv.co) for vv in ring_list[0]]
    true = [math.atan2((pv - cv) / sel[1], (pu - cu) / sel[0]) for pu, pv in pts]
    seg = [math.hypot((pts[(i + 1) % len(pts)][0] - pts[i][0]) / sel[0], (pts[(i + 1) % len(pts)][1] - pts[i][1]) / sel[1]) for i in range(len(pts))]
    total = sum(seg)
    turn = sum(math.remainder(true[(i + 1) % len(true)] - true[i], 2 * math.pi) for i in range(len(true)))
    sign = 1.0 if turn >= 0 else -1.0
    acc = 0.0
    arc = []
    for i in range(len(pts)):
        arc.append(sign * 2 * math.pi * acc / total)
        acc += seg[i]
    # Desfase que mejor alinea el reparto con los ángulos reales.
    off = math.atan2(sum(math.sin(t - a) for t, a in zip(true, arc)), sum(math.cos(t - a) for t, a in zip(true, arc)))
    arc = [a + off for a in arc]
    # Transición gradual del borde (irregular) a las elipses: los primeros
    # anillos siguen la dirección y el radio de su vértice del borde y cada
    # anillo queda siempre por dentro del anterior (así ninguna cara se da la
    # vuelta en las esquinas donde se tocan ojo, nariz y rejilla).
    prev = [math.hypot((pu - cu) / sel[0], (pv - cv) / sel[1]) for pu, pv in pts]
    for k, (ru, rv) in enumerate(rings, start=1):
        assert len(ring_list[k]) == len(pts)
        w = min(1.0, k / 3.0)
        cur = []
        for i, vv in enumerate(ring_list[k]):
            th = true[i] + w * math.remainder(arc[i] - true[i], 2 * math.pi)
            s_ = math.sin(th)
            rvk = rv * (asym[0] if s_ > 0 else asym[1])
            ex, ey = math.cos(th) * ru / sel[0], s_ * rvk / sel[1]
            re = math.hypot(ex, ey)
            r = min(re, prev[i] * 0.86)
            cur.append(r)
            param[vv] = (cu + ex / re * r * sel[0], cv + ey / re * r * sel[1])
        prev = cur
    for vv in ring_list[0]:
        param.setdefault(vv, dir_uv(vv.co))
    taken.update(region_verts)
    for r in ring_list:
        taken.update(r)
    return ring_list, face


def build_head_cage(res):
    """
    Jaula de la cabeza. Devuelve el objeto con:
      - capa UV "Param" = (u, v) de cada vértice de superficie
      - grupo "interior" = cuencas de los ojos y saco bucal (no se recolocan)
      - datos de anillos para colocar los ojos
    """
    bm = quad_sphere(res)
    # La capa de pesos se crea ANTES de nada: añadir capas después invalida
    # las referencias a vértices que se guarden por el camino.
    deform = bm.verts.layers.deform.verify()
    param = {}
    taken = set()
    # Primero la nariz, luego los ojos y la boca: cada región evita las anteriores.
    _, nose_face = _cut_feature(bm, param, NOSE_C, (0.125, 0.165), NOSE_RINGS, taken)
    eye_open = {}
    for side in (1, -1):
        rings, face = _cut_feature(bm, param, (side * EYE_U, EYE_V), (0.32, 0.185), EYE_RINGS, taken, asym=(1.0, 0.82))
        eye_open[side] = (rings[-1], face)
    mouth_rings, mouth_face = _cut_feature(bm, param, (0.0, MOUTH_V), (0.46, 0.165), MOUTH_RINGS, taken, asym=(0.9, 1.1))

    # Punta de la nariz: la cara interior se "pincha" hacia un vértice central.
    poked = bmesh.ops.poke(bm, faces=[nose_face])
    for vv in poked["verts"]:
        param[vv] = (NOSE_C[0], -0.205)

    # Todos los demás vértices: su (u, v) por dirección.
    for vv in bm.verts:
        if vv not in param:
            param[vv] = dir_uv(vv.co)
    # Colocar la superficie.
    for vv in bm.verts:
        vv.co = face_point(*param[vv])

    interior = set()
    # Ojos: la abertura se abre y su borde se extruye hacia dentro (cuenca).
    eye_centers = {}
    for side, (ring, face) in eye_open.items():
        center = sum((vv.co for vv in ring), Vector()) / len(ring)
        eye_centers[side] = (center, ring)
        bmesh.ops.delete(bm, geom=[face], context="FACES_ONLY")
        loop = ring
        for depth, shrink in ((0.0050, 0.80), (0.0110, 0.50)):
            edges = [e for e in bm.edges if e.is_boundary and e.verts[0] in loop and e.verts[1] in loop]
            ext = bmesh.ops.extrude_edge_only(bm, edges=edges)
            new = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)]
            origin = {}
            for nv in new:
                for e in nv.link_edges:
                    o = e.other_vert(nv)
                    if o in loop:
                        origin[nv] = o
            for nv in new:
                o = origin[nv].co
                nv.co = center + (o - center) * shrink + Vector((0, depth, 0))
                param[nv] = param[origin[nv]]
                nv[deform][0] = 1.0
            interior.update(new)
            loop = new
    # Boca: la línea de cierre se abre en un saco bucal cerrado.
    bmesh.ops.delete(bm, geom=[mouth_face], context="FACES_ONLY")
    loop = mouth_rings[-1]
    mcenter = sum((vv.co for vv in loop), Vector()) / len(loop)
    for depth, sx, sz in ((0.007, 0.90, 1.0), (0.020, 0.60, 3.0)):
        edges = [e for e in bm.edges if e.is_boundary and e.verts[0] in loop and e.verts[1] in loop]
        ext = bmesh.ops.extrude_edge_only(bm, edges=edges)
        new = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)]
        for nv in new:
            o = next(e.other_vert(nv) for e in nv.link_edges if e.other_vert(nv) in loop)
            d = o.co - mcenter
            nv.co = mcenter + Vector((d.x * sx, depth, d.z * sz))
            param[nv] = param[o]
            nv[deform][0] = 1.0
        interior.update(new)
        loop = new
    edges = [e for e in bm.edges if e.is_boundary and e.verts[0] in loop and e.verts[1] in loop]
    bmesh.ops.holes_fill(bm, edges=edges, sides=0)
    big = [f for f in bm.faces if len(f.verts) > 4 and all(vv in interior for vv in f.verts)]
    for f in big:
        for vv in bmesh.ops.poke(bm, faces=[f])["verts"]:
            vv[deform][0] = 1.0

    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    uv_layer = bm.loops.layers.uv.new("Param")
    for f in bm.faces:
        us = [param.get(l.vert, dir_uv(l.vert.co - HEAD_C)) for l in f.loops]
        # Costura de atrás (u = ±π): que una cara no salte de un lado al otro.
        if max(x for x, _ in us) - min(x for x, _ in us) > math.pi:
            us = [(x + 2 * math.pi if x < 0 else x, y) for x, y in us]
        for l, (x, y) in zip(f.loops, us):
            l[uv_layer].uv = (x, y)
    obj = mesh_object("Masha_Head_cage", bm)
    obj.vertex_groups.new(name="interior")
    smooth_shading(obj)
    return obj


def refit_surface(obj, detail):
    """
    Recoloca cada vértice de superficie sobre la forma analítica usando su
    (u, v) de la capa "Param" (interpolada al subdividir). Los del interior
    (cuencas, boca) se quedan donde los dejó la subdivisión.
    """
    me = obj.data
    uv = me.uv_layers["Param"].data
    gi = obj.vertex_groups["interior"].index
    target = {}
    for poly in me.polygons:
        for li in poly.loop_indices:
            vi = me.loops[li].vertex_index
            if vi not in target:
                target[vi] = tuple(uv[li].uv)
    for v in me.vertices:
        w = next((g.weight for g in v.groups if g.group == gi), 0.0)
        if w > 0.001:
            continue
        u, vv = target[v.index]
        if u > math.pi:
            u -= 2 * math.pi
        v.co = face_point(u, vv, detail)
    me.update()


def subdivide_applied(obj, levels, name):
    """Copia de `obj` con Subdivision Surface aplicado (UV interpoladas en lineal)."""
    dup = duplicate(obj, name)
    m = dup.modifiers.new("subsurf", "SUBSURF")
    m.levels = levels
    m.render_levels = levels
    m.uv_smooth = "NONE"
    m.boundary_smooth = "ALL"
    apply_modifiers(dup)
    smooth_shading(dup)
    return dup


def weighted_normals(obj):
    """Weighted Normals: sombreado limpio en superficies grandes y bordes definidos."""
    m = obj.modifiers.new("wn", "WEIGHTED_NORMAL")
    m.weight = 50
    m.keep_sharp = True
    m.mode = "FACE_AREA_WITH_ANGLE"
    apply_modifiers(obj)


def multires_high(cage, levels, detail_fn, name):
    """
    Versión alta con Multires: la jaula con `levels` niveles de Multires y el
    detalle de `detail_fn(obj)` escrito en el nivel superior con Reshape.
    """
    high = duplicate(cage, name)
    mr = high.modifiers.new("Multires", "MULTIRES")
    activate(high)
    for _ in range(levels):
        bpy.ops.object.multires_subdivide(modifier="Multires", mode="CATMULL_CLARK")
    # El "molde": misma topología que el nivel superior, con el detalle.
    mold = subdivide_applied(cage, levels, name + "_mold")
    detail_fn(mold)
    mold.select_set(True)
    high.select_set(True)
    bpy.context.view_layer.objects.active = high
    bpy.ops.object.multires_reshape(modifier="Multires")
    bpy.data.objects.remove(mold, do_unlink=True)
    mr.levels = levels
    mr.sculpt_levels = levels
    mr.render_levels = levels
    smooth_shading(high)
    return high


# ──────────────────────────────────────────────────────────────────────────
# Manos
# ──────────────────────────────────────────────────────────────────────────

# Proporciones de una mano adulta pequeña (dedo corazón ≈ 0,85 × palma).
PALM_LEN = 0.098
PALM_W = (0.058, 0.080)       # ancho en la muñeca y en los nudillos
PALM_T = (0.030, 0.026)       # grosor

# nombre, falanges (proximal, media, distal), ancho, grosor, abertura (rad), flexión (grados)
FINGERS = [
    ("pinky", (0.030, 0.018, 0.016), 0.0148, 0.0134, -0.13, (14, 20, 12)),
    ("ring", (0.038, 0.024, 0.018), 0.0166, 0.0150, -0.05, (12, 18, 11)),
    ("middle", (0.041, 0.026, 0.019), 0.0176, 0.0158, 0.01, (11, 16, 10)),
    ("index", (0.037, 0.022, 0.018), 0.0170, 0.0154, 0.07, (9, 14, 9)),
]
THUMB = ("thumb", (0.038, 0.029, 0.024), 0.0205, 0.0180, (10, 14))


def _palm_point(i, j, k, nx, ny, nz):
    ty = j / ny
    xn = i / nx * 2 - 1
    zn = k / nz * 2 - 1
    w = lerp(PALM_W[0], PALM_W[1], ty ** 0.7)
    t = lerp(PALM_T[0], PALM_T[1], ty)
    # Los nudillos forman un arco (el corazón, el más largo).
    y = ty * PALM_LEN * (1.0 - 0.07 * ty * (xn - 0.1) ** 2)
    # Sección redondeada (superelipse): los bordes de la palma son más finos
    # que el centro y no hay esquinas en ángulo recto.
    x = xn * w / 2 * (1.0 - 0.10 * zn * zn)
    z = zn * t / 2 * (1.0 - 0.42 * xn * xn)
    if zn > 0:
        z += 0.0030 * (1 - xn * xn)                 # dorso abombado
        if j == ny:
            # Cabezas de los metacarpianos: los nudillos del dorso.
            z += 0.0040 * (0.5 + 0.5 * math.cos(4 * math.pi * xn - math.pi)) ** 2 * (1 - 0.3 * xn * xn)   # máximo en el centro de cada dedo
    else:
        z += 0.0030 * (1 - xn * xn) * (1 - abs(2 * ty - 1) * 0.5)   # palma algo ahuecada
    if xn > 0.5 and ty < 0.75 and zn < 0:
        z -= 0.0045 * (xn - 0.5) / 0.5 * (1 - ty)   # eminencia tenar
    if xn < -0.5 and zn < 0:
        z -= 0.0025 * (-xn - 0.5) / 0.5             # eminencia hipotenar
    return Vector((x, y, z))


def _box_shell(nx, ny, nz, pos):
    """Caja de quads (solo superficie) con nx×ny×nz divisiones y posición pos(i,j,k)."""
    bm = bmesh.new()
    verts = {}

    def V(i, j, k):
        key = (i, j, k)
        if key not in verts:
            verts[key] = bm.verts.new(pos(i, j, k))
        return verts[key]

    faces = {}
    for j in range(ny):
        for k in range(nz):
            faces[("x0", j, k)] = bm.faces.new((V(0, j, k), V(0, j, k + 1), V(0, j + 1, k + 1), V(0, j + 1, k)))
            faces[("x1", j, k)] = bm.faces.new((V(nx, j, k), V(nx, j + 1, k), V(nx, j + 1, k + 1), V(nx, j, k + 1)))
    for i in range(nx):
        for k in range(nz):
            faces[("y0", i, k)] = bm.faces.new((V(i, 0, k), V(i + 1, 0, k), V(i + 1, 0, k + 1), V(i, 0, k + 1)))
            faces[("y1", i, k)] = bm.faces.new((V(i, ny, k), V(i, ny, k + 1), V(i + 1, ny, k + 1), V(i + 1, ny, k)))
    for i in range(nx):
        for j in range(ny):
            faces[("z0", i, j)] = bm.faces.new((V(i, j, 0), V(i, j + 1, 0), V(i + 1, j + 1, 0), V(i + 1, j, 0)))
            faces[("z1", i, j)] = bm.faces.new((V(i, j, nz), V(i + 1, j, nz), V(i + 1, j + 1, nz), V(i, j + 1, nz)))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return bm, faces


def _square_to_circle(a, b):
    """Lleva la rejilla de la sección (−1..1)² a un círculo: dedos redondos, no cuadrados."""
    return a * math.sqrt(max(0.0, 1 - b * b / 2)), b * math.sqrt(max(0.0, 1 - a * a / 2))


def _extrude_digit(bm, region, sec_x, sec_z, base, direction, xaxis, zaxis, lengths, width, thick, flex, loops, params, fid, base_scale=1.0):
    """
    Extruye un dedo desde `region` (caras de la palma) siguiendo su esqueleto.
    Cada falange lleva `loops` bucles; en cada articulación, volumen de nudillo.
    Devuelve los puntos de las articulaciones [base, j1, j2, punta] y las caras
    dorsales de la última falange (para la uña).
    """
    # Sección normalizada de cada vértice de la región (rejilla −1..1).
    center = sum((v.co for f in region for v in f.verts), Vector()) / sum(len(f.verts) for f in region)
    rv = {v for f in region for v in f.verts}
    sec = {}
    for v in rv:
        d = v.co - center
        a = max(-1.0, min(1.0, d.dot(sec_x) / (width / 2)))
        b = max(-1.0, min(1.0, d.dot(sec_z) / (thick / 2)))
        sec[v] = (round(a), round(b))
    total = sum(lengths)
    joints = [base.copy()]
    faces = region
    d = direction.normalized()
    x = xaxis.normalized()
    z = zaxis.normalized()
    s = 0.0
    p = base.copy()
    nail_faces = []

    def ring_pos(a, b, c, ww, tt, bump):
        ca, cb = _square_to_circle(a, b)
        off = x * (ca * ww / 2) + z * (cb * tt / 2)
        if b > 0.3:
            off += z * bump
        return c + off

    segs = []
    for pi, L in enumerate(lengths):
        # Flexión en la articulación (hacia la palma = −z local).
        ang = -math.radians(flex[pi]) if pi < len(flex) else 0.0
        if ang:
            rot = Matrix.Rotation(ang, 3, x)
            d = (rot @ d).normalized()
            z = (rot @ z).normalized()
        for li in range(1, loops + 1):
            segs.append((pi, li, L / loops, d.copy(), z.copy()))
    for idx, (pi, li, step, dd, zz) in enumerate(segs):
        by_pos = {tuple(round(c, 7) for c in o.co): o for o in sec}
        ext = bmesh.ops.extrude_face_region(bm, geom=faces)
        bmesh.ops.delete(bm, geom=faces, context="FACES")
        new_verts = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMVert)]
        nv_set = set(new_verts)
        # La tapa nueva: caras hechas solo de vértices nuevos (no las paredes).
        new_faces = [g for g in ext["geom"] if isinstance(g, bmesh.types.BMFace) and all(v in nv_set for v in g.verts)]
        # Cada vértice nuevo nace en la posición del viejo: así se emparejan
        # todos, también el central de la tapa (que no tiene arista al viejo).
        old_to_new = {}
        for nv in new_verts:
            o = by_pos.get(tuple(round(c, 7) for c in nv.co))
            if o is not None:
                old_to_new[o] = nv
        z = zz
        p = p + dd * step
        s += step
        tf = s / total
        ww = width * (1 - 0.20 * tf)
        tt = thick * (1 - 0.16 * tf)
        if base_scale != 1.0:
            # El primer tramo arranca ancho y se afina hasta la primera articulación.
            k = lerp(base_scale, 1.0, smoothstep(0.0, lengths[0], s))
            ww *= k
            tt *= lerp(1.0, k, 0.6)
        joint = li == loops
        if joint and pi < len(lengths) - 1:
            ww *= 1.025
            bump = 0.0014 if pi == 0 else 0.0010     # nudillo (solo por el dorso)
        else:
            bump = 0.0
        last = idx == len(segs) - 1
        nsec = {}
        for o, nv in old_to_new.items():
            a, b = sec[o]
            if last:
                # Punta: la última sección se estrecha (sin colapsar: la
                # subdivisión la redondea) y la yema cae un poco hacia la palma.
                ca, cb = _square_to_circle(a, b)
                nv.co = p + x * (ca * ww * 0.31) + z * (cb * tt * 0.27 - tt * 0.05) + dd * 0.0022
            else:
                nv.co = ring_pos(a, b, p, ww, tt, bump)
            nsec[nv] = (a, b)
        for nv, (a, b) in nsec.items():
            params[nv] = (fid, s, a, b)
        sec = nsec
        faces = new_faces
        if joint:
            joints.append(p.copy())
    # Uña: las caras dorsales del tramo de la falange distal previo a la punta
    # (en la punta las caras convergen y el inset las arrugaría).
    tip_s = s
    nail_faces = [f for f in bm.faces
                  if all(vv in params and params[vv][0] == fid for vv in f.verts)
                  and all(params[vv][3] > 0.5 for vv in f.verts)
                  and min(params[vv][1] for vv in f.verts) >= tip_s - 2 * lengths[-1] / loops - 1e-6
                  and max(params[vv][1] for vv in f.verts) <= tip_s - lengths[-1] / loops + 1e-6]
    joints[-1] = p + d * 0.004
    # La articulación de la base va dentro de la palma (como la real): así la
    # piel del nudillo se dobla con el dedo.
    joints[0] = base - direction.normalized() * 0.011
    return joints, nail_faces


def build_hand_cage(side, wrist, arm_dir, detail_uv=True):
    """
    Mano en espacio local (x = lado del índice/pulgar, y = hacia los dedos,
    z = dorso) y luego llevada a la muñeca. Devuelve (objeto, articulaciones).
    """
    nx, ny, nz = 8, 3, 2
    bm, faces = _box_shell(nx, ny, nz, lambda i, j, k: _palm_point(i, j, k, nx, ny, nz))
    params = {}
    # Parámetros de la palma: (fid 0, y, x normalizada, lado).
    for v in bm.verts:
        w = lerp(PALM_W[0], PALM_W[1], max(0.0, min(1.0, v.co.y / PALM_LEN)))
        params[v] = (0, v.co.y, max(-1.0, min(1.0, v.co.x / (w / 2))), 1.0 if v.co.z > 0 else -1.0)

    joints = {}
    nails = []
    X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))
    for f_i, (name, lengths, width, thick, spread, flex) in enumerate(FINGERS):
        region = [faces[("y1", 2 * f_i + a, k)] for a in (0, 1) for k in (0, 1)]
        # Hueco entre dedos: la base de cada uno se estrecha un poco (membrana).
        bmesh.ops.inset_region(bm, faces=region, thickness=0.0021, depth=0.0, use_even_offset=True)
        base = sum((v.co for f in region for v in f.verts), Vector()) / 16
        rot = Matrix.Rotation(-spread, 3, Z)
        j, nf = _extrude_digit(bm, region, X, Z, base, rot @ Y, rot @ X, Z, lengths, width, thick, flex, 3, params, f_i + 1)
        joints[name] = j
        nails += nf

    # Pulgar: desde el lado del índice, junto a la muñeca (eminencia tenar).
    name, lengths, width, thick, flex = THUMB
    region = [faces[("x1", j_, k)] for j_ in (0, 1) for k in (0, 1)]
    bmesh.ops.inset_region(bm, faces=region, thickness=0.0015, depth=0.0, use_even_offset=True)
    base = sum((v.co for f in region for v in f.verts), Vector()) / 16
    tdir = Vector((0.72, 0.58, -0.38)).normalized()
    tx = (Y - tdir * Y.dot(tdir)).normalized()
    tz = tdir.cross(tx).normalized()
    if tz.z < 0:
        tz = -tz
    j, nf = _extrude_digit(bm, region, Y, Z, base, tdir, tx, tz, lengths, width, thick, flex, 3, params, 5, base_scale=1.75)
    joints[name] = j
    nails += nf

    # Muñeca: se mete en el antebrazo (sin costura visible).
    wregion = [faces[("y0", i, k)] for i in range(nx) for k in range(nz)]
    ext = bmesh.ops.extrude_face_region(bm, geom=wregion)
    bmesh.ops.delete(bm, geom=wregion, context="FACES")
    for g in ext["geom"]:
        if isinstance(g, bmesh.types.BMVert):
            a = max(-1.0, min(1.0, g.co.x / (PALM_W[0] / 2)))
            b = max(-1.0, min(1.0, g.co.z / (PALM_T[0] / 2)))
            ca, cb = _square_to_circle(a, b)
            g.co = Vector((ca * 0.023, -0.026, cb * 0.0135))
            params[g] = (0, -0.026, a, 1.0 if b > 0 else -1.0)

    # Uñas: inset con un leve relieve y pliegue en su contorno.
    nails = [f for f in nails if f.is_valid]
    if nails:
        ins = bmesh.ops.inset_individual(bm, faces=nails, thickness=0.0009, depth=0.00035, use_even_offset=True)
        crease = bm.edges.layers.float.get("crease_edge") or bm.edges.layers.float.new("crease_edge")
        for f in nails:
            for e in f.edges:
                e[crease] = 0.8
        for f in ins["faces"]:
            for vv in f.verts:
                params.setdefault(vv, (6, 0.0, 0.0, 1.0))
    loose = [v for v in bm.verts if not v.link_faces]
    bmesh.ops.delete(bm, geom=loose, context="VERTS")

    # A la muñeca, en espacio de mundo. Ejes: y = antebrazo, z = dorso hacia
    # fuera y arriba, x = lado del pulgar (hacia delante en las dos manos).
    yw = arm_dir.normalized()
    zw = (arm_dir.cross(Vector((0, 1, 0))) * side).normalized()
    xw = (yw.cross(zw) * side).normalized()
    for v in bm.verts:
        c = v.co
        v.co = wrist + xw * c.x + yw * c.y + zw * c.z
    if side < 0:
        # Base espejo: sin esto las normales quedarían hacia dentro.
        bmesh.ops.reverse_faces(bm, faces=bm.faces)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)

    # Capas UV de parámetros (para el detalle en la versión alta).
    la = bm.loops.layers.uv.new("HandA")
    lb = bm.loops.layers.uv.new("HandB")
    for f in bm.faces:
        for l in f.loops:
            fid, s, a, b = params.get(l.vert, (0, 0.0, 0.0, 1.0))
            l[la].uv = (s, fid)
            l[lb].uv = (a, b)
    obj = mesh_object(f"Masha_Hand_{'L' if side > 0 else 'R'}_cage", bm)
    smooth_shading(obj)
    world = {k: [wrist + xw * p.x + yw * p.y + zw * p.z for p in pts] for k, pts in joints.items()}
    world["wrist"] = wrist
    world["dir"] = yw
    world["axes"] = (xw, yw, zw)
    return obj, world


def hand_detail(obj):
    """
    Esculpido fino de la mano (va al mapa de normales): arrugas del dorso de
    los nudillos, pliegues de flexión de los dedos, líneas de la palma,
    tendones del dorso y almohadillas de las yemas.
    """
    me = obj.data
    bm = bmesh.new()
    bm.from_mesh(me)
    bm.normal_update()
    la = bm.loops.layers.uv["HandA"]
    lb = bm.loops.layers.uv["HandB"]
    info = {}
    for f in bm.faces:
        for l in f.loops:
            if l.vert not in info:
                info[l.vert] = (l[la].uv[0], l[la].uv[1], l[lb].uv[0], l[lb].uv[1])
    joints_s = {}
    for fi, (_, lengths, *_r) in enumerate(FINGERS, start=1):
        joints_s[fi] = [sum(lengths[:k]) for k in (1, 2)]
    joints_s[5] = [THUMB[1][0], THUMB[1][0] + THUMB[1][1]]
    for v, (s, fid, a, b) in info.items():
        fi = int(round(fid))
        off = 0.0
        if fi in joints_s and abs(fid - fi) < 0.2:
            for sj in joints_s[fi]:
                ds = s - sj
                if b > 0.2:
                    # Dorso del nudillo: 3-4 arrugas finas.
                    off -= 0.00022 * math.exp(-((ds / 0.004) ** 2)) * (0.5 + 0.5 * math.cos(2 * math.pi * ds / 0.0017))
                else:
                    # Palma del dedo: un pliegue marcado en cada articulación.
                    off -= 0.00055 * math.exp(-((ds / 0.0009) ** 2))
            if b < -0.2:
                tip = sum(FINGERS[fi - 1][1]) if fi <= 4 else sum(THUMB[1])
                off += 0.0004 * math.exp(-(((s - (tip - 0.007)) / 0.005) ** 2))   # yema
        elif fi == 0:
            y = s
            if b < 0:
                # Líneas de la palma: del corazón, de la cabeza y de la vida.
                for yc, slope, width in ((0.070, 0.10, 0.0009), (0.052, 0.14, 0.0009)):
                    off -= 0.0006 * math.exp(-(((y - (yc + slope * a * 0.04)) / width) ** 2)) * smoothstep(-1.0, -0.4, -abs(a))
                life = math.hypot((a - 0.55) * 0.03, y - 0.030)
                off -= 0.0006 * math.exp(-(((life - 0.024) / 0.0009) ** 2)) * smoothstep(0.08, 0.02, y) * (a > -0.2)
            else:
                # Tendones extensores, del carpo a cada nudillo.
                for col in (-0.75, -0.25, 0.25, 0.75):
                    off += 0.00035 * math.exp(-(((a - col) / 0.09) ** 2)) * smoothstep(0.015, 0.035, y) * smoothstep(0.09, 0.07, y)
        if off:
            v.co += v.normal * off
    bm.to_mesh(me)
    bm.free()
    me.update()


# ──────────────────────────────────────────────────────────────────────────
# Horneado
# ──────────────────────────────────────────────────────────────────────────

def _gltf_output_group():
    """Grupo "glTF Material Output": el exportador lee de aquí la oclusión (AO)."""
    ng = bpy.data.node_groups.get("glTF Material Output")
    if ng is None:
        ng = bpy.data.node_groups.new("glTF Material Output", "ShaderNodeTree")
        ng.interface.new_socket(name="Occlusion", in_out="INPUT", socket_type="NodeSocketFloat")
    return ng


def setup_bake_engine(scene):
    scene.render.engine = "CYCLES"
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        for backend in ("OPTIX", "CUDA", "HIP", "METAL", "ONEAPI"):
            try:
                prefs.compute_device_type = backend
            except TypeError:
                continue
            prefs.get_devices()
            if any(d.type == backend for d in prefs.devices):
                for d in prefs.devices:
                    d.use = True
                scene.cycles.device = "GPU"
                break
    except Exception:
        pass
    if scene.world is None:
        scene.world = bpy.data.worlds.new("bake")
    scene.world.light_settings.distance = 0.015


def bake_maps(low, high, size, name, cage=0.004, ray=0.02, out_dir=None, interior_group=None):
    """
    Hornea NORMAL (espacio tangente, convención OpenGL como pide glTF) y AO
    de `high` sobre `low`, en una UV propia ("BakeUV"), y los conecta al
    material de `low`: mapa de normales al BSDF, AO a la oclusión de glTF y,
    multiplicado, al color base (así los pliegues se leen aunque el
    holograma sea sobre todo emisivo).
    """
    scene = bpy.context.scene
    setup_bake_engine(scene)
    me = low.data
    uv = me.uv_layers.get("BakeUV") or me.uv_layers.new(name="BakeUV")
    me.uv_layers.active = uv
    activate(low)
    # Piel e interior (cuencas, saco bucal) se despliegan por separado: si no,
    # Smart UV Project los junta en la misma isla (miran hacia el mismo lado)
    # y el interior, oscuro, acaba pintado sobre los labios y los párpados.
    inner = set()
    if interior_group and interior_group in low.vertex_groups:
        gi = low.vertex_groups[interior_group].index
        iv = {v.index for v in me.vertices if any(g.group == gi and g.weight > 0.001 for g in v.groups)}
        inner = {p.index for p in me.polygons if all(vi in iv for vi in p.vertices)}
    groups = [[p.index for p in me.polygons if p.index not in inner]]
    if inner:
        groups.append(sorted(inner))
    for faces in groups:
        bpy.ops.object.mode_set(mode="OBJECT")
        fs = set(faces)
        for p in me.polygons:
            p.select = p.index in fs
        bpy.ops.object.mode_set(mode="EDIT")
        bpy.ops.uv.smart_project(angle_limit=math.radians(62), island_margin=0.006, area_weight=0.0, correct_aspect=True, scale_to_bounds=False)
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.select_all(action="SELECT")
    bpy.ops.uv.pack_islands(rotate=True, margin=0.004)
    bpy.ops.object.mode_set(mode="OBJECT")

    normal = bpy.data.images.new(f"{name}_normal", size, size, alpha=False)
    normal.colorspace_settings.name = "Non-Color"
    ao = bpy.data.images.new(f"{name}_ao", size, size, alpha=False)

    mat = low.active_material
    nt = mat.node_tree
    uvn = nt.nodes.new("ShaderNodeUVMap")
    uvn.uv_map = "BakeUV"
    n_img = nt.nodes.new("ShaderNodeTexImage")
    n_img.image = normal
    a_img = nt.nodes.new("ShaderNodeTexImage")
    a_img.image = ao
    nt.links.new(uvn.outputs["UV"], n_img.inputs["Vector"])
    nt.links.new(uvn.outputs["UV"], a_img.inputs["Vector"])

    bake = scene.render.bake
    bake.use_selected_to_active = True
    bake.cage_extrusion = cage
    bake.max_ray_distance = ray
    bake.margin = 6
    bake.normal_space = "TANGENT"
    bpy.ops.object.select_all(action="DESELECT")
    high.select_set(True)
    low.select_set(True)
    bpy.context.view_layer.objects.active = low

    scene.cycles.samples = 8
    nt.nodes.active = n_img
    bpy.ops.object.bake(type="NORMAL")
    # AO: los rayos solo deben chocar con la versión alta. La baja coincide
    # con ella (la taparía entera) y el resto de la escena no es de esta pieza.
    hidden = []
    for o in bpy.context.scene.objects:
        if o not in (low, high) and not o.hide_render:
            o.hide_render = True
            hidden.append(o)
    vis = {k: getattr(low, k) for k in ("visible_diffuse", "visible_glossy", "visible_transmission", "visible_shadow", "visible_volume_scatter")}
    for k in vis:
        setattr(low, k, False)
    scene.cycles.samples = 64
    nt.nodes.active = a_img
    bpy.ops.object.bake(type="AO")
    for k, val in vis.items():
        setattr(low, k, val)
    for o in hidden:
        o.hide_render = False

    # Conexiones finales.
    bsdf = next(n for n in nt.nodes if n.type == "BSDF_PRINCIPLED")
    nmap = nt.nodes.new("ShaderNodeNormalMap")
    nmap.uv_map = "BakeUV"
    nmap.inputs["Strength"].default_value = 1.0
    nt.links.new(n_img.outputs["Color"], nmap.inputs["Color"])
    nt.links.new(nmap.outputs["Normal"], bsdf.inputs["Normal"])
    nt.links.new(a_img.outputs["Color"], bsdf.inputs["Base Color"])
    grp = nt.nodes.new("ShaderNodeGroup")
    grp.node_tree = _gltf_output_group()
    sep = nt.nodes.new("ShaderNodeSeparateColor")
    nt.links.new(a_img.outputs["Color"], sep.inputs["Color"])
    nt.links.new(sep.outputs["Red"], grp.inputs["Occlusion"])

    # La AO se suaviza (60 %): marca los pliegues sin ojeras ni suciedad.
    import numpy as np
    px = np.empty(len(ao.pixels), np.float32)
    ao.pixels.foreach_get(px)
    px = px.reshape(-1, 4)
    px[:, :3] = 1.0 - 0.6 * (1.0 - px[:, :3])
    ao.pixels.foreach_set(px.ravel())
    # Se guardan en JPEG y se empaquetan así: el GLB los lleva en JPEG
    # (4× menos que PNG) sin tocar las texturas con alfa, que van en PNG.
    import os
    folder = out_dir or bpy.app.tempdir
    os.makedirs(folder, exist_ok=True)
    for img in (normal, ao):
        img.filepath_raw = os.path.join(folder, f"{img.name}.jpg")
        img.file_format = "JPEG"
        img.save()
        img.pack()
    return normal, ao
