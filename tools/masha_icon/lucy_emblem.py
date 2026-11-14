"""Emblema de Lucy: genera los conceptos (SVG de diseño y VectorDrawable) y la
hoja de comparación.

Toda la geometría sale de aquí, en la rejilla de 108 de los iconos de Android,
así que el emblema se puede rehacer y retocar sin editor. Cada concepto tiene
dos versiones: a color (cristal y holograma, con la paleta Plasma) y plana de
un color (icono pequeño de notificación y contextos tintados).

Uso:
    python lucy_emblem.py sheet              # out/: SVG de los tres + sheet.html
    python lucy_emblem.py compare            # out/compare.html: antes y después del concepto elegido
    python lucy_emblem.py small              # out/small.html: glifo pequeño frente al grande
    python lucy_emblem.py drawable           # escribe masha.xml, masha_small.xml, lucy_mono.xml e ic_stat_lucy.xml

Las hojas se pasan a PNG con Edge sin ventana:
    msedge --headless=new --force-device-scale-factor=1 --window-size=1800,1420 \
        --virtual-time-budget=3000 --screenshot=out/sheet.png out/sheet.html
out/ no se versiona.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
RES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res', 'drawable')

# Paleta Plasma (docs/UI_DESIGN.md §2): primario, secundario, destello; y la tinta del cristal.
PLASMA, CYAN, GLINT = '#7C5CFF', '#2BD9FF', '#FFF1C9'
ROSE = '#C77DFF'
INK0, INK1 = '#0B0A1F', '#1C1747'


# ── geometría ────────────────────────────────────────────────

def f(v):
    return f'{v:.2f}'.rstrip('0').rstrip('.')


def poly(pts):
    return 'M' + ' L'.join(f'{f(x)} {f(y)}' for x, y in pts) + ' Z'


def rounded(pts, r):
    """Polígono con las esquinas redondeadas (curva cuadrática por vértice)."""
    n = len(pts)
    d = []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        a = _toward(p1, p0, r)
        b = _toward(p1, p2, r)
        d.append(('M' if i == 0 else 'L') + f'{f(a[0])} {f(a[1])}')
        d.append(f'Q{f(p1[0])} {f(p1[1])} {f(b[0])} {f(b[1])}')
    return ' '.join(d) + ' Z'


def _toward(p, q, dist):
    dx, dy = q[0] - p[0], q[1] - p[1]
    n = math.hypot(dx, dy)
    k = min(dist, n / 2) / n
    return (p[0] + dx * k, p[1] + dy * k)


def circle(cx, cy, r):
    return f'M{f(cx - r)} {f(cy)} a{f(r)} {f(r)} 0 1 0 {f(2 * r)} 0 a{f(r)} {f(r)} 0 1 0 {f(-2 * r)} 0 Z'


def hexagon(cx, cy, r, rot=0.0):
    return [(cx + r * math.cos(math.radians(rot + 60 * i)), cy + r * math.sin(math.radians(rot + 60 * i))) for i in range(6)]


def cubic(p0, p1, p2, p3, t):
    u = 1 - t
    return (
        u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
        u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1],
    )


def ribbon(segments, width, steps=28):
    """Trazo caligráfico: una línea central (cúbicas encadenadas) con grosor variable width(s), s en 0…1."""
    centre = []
    for k, seg in enumerate(segments):
        for i in range(steps + (1 if k == len(segments) - 1 else 0)):
            centre.append(cubic(*seg, i / steps))
    total = len(centre) - 1
    left, right = [], []
    for i, p in enumerate(centre):
        q0 = centre[max(i - 1, 0)]
        q1 = centre[min(i + 1, total)]
        dx, dy = q1[0] - q0[0], q1[1] - q0[1]
        n = math.hypot(dx, dy) or 1
        nx, ny = -dy / n, dx / n
        w = width(i / total) / 2
        left.append((p[0] + nx * w, p[1] + ny * w))
        right.append((p[0] - nx * w, p[1] - ny * w))
    return poly(left + right[::-1]), centre


def arc(cx, cy, r, a0, a1):
    """Arco abierto (para trazos) de a0 a a1 grados, sentido horario en pantalla."""
    x0, y0 = cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0))
    x1, y1 = cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1))
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f'M{f(x0)} {f(y0)} A{f(r)} {f(r)} 0 {large} 1 {f(x1)} {f(y1)}'


# ── piezas: un concepto es una lista de capas ────────────────
# Capa: dict(d, fill, stroke, width, alpha, evenodd, cap). fill/stroke = color "#RRGGBB"
# o el id de un degradado de GRADS.

def lin(x1, y1, x2, y2, *stops):
    return ('linear', (x1, y1, x2, y2), stops)


def rad(cx, cy, r, *stops):
    return ('radial', (cx, cy, r), stops)


GRADS = {
    # Cristal del tanque: tinta honda con un velo de plasma arriba.
    'glass': rad(54, 30, 70, (0, '#2A2170', 1), (0.55, INK1, 1), (1, INK0, 1)),
    # Canto holográfico: plasma → cian → destello.
    'rim': lin(14, 12, 94, 98, (0, '#B9A6FF', 1), (0.45, PLASMA, 1), (0.8, CYAN, 1), (1, GLINT, 1)),
    # La marca a color: de destello a cian por el plasma.
    'mark': lin(0, 22, 0, 86, (0, '#FFFFFF', 1), (0.5, '#D9CCFF', 1), (1, '#8FEFFF', 1)),
    # Marca sobre cualquier fondo (sin cristal detrás): plasma ↘ cian.
    'markInk': lin(30, 20, 84, 88, (0, ROSE, 1), (0.5, PLASMA, 1), (1, CYAN, 1)),
    # Haz del proyector: cian que se apaga hacia arriba.
    'beam': lin(0, 76, 0, 22, (0, CYAN, 0.55), (1, CYAN, 0)),
    'moon': lin(18, 90, 90, 18, (0, '#2A1F7A', 1), (0.55, PLASMA, 1), (1, CYAN, 1)),
    'glint': rad(80, 26, 10, (0, GLINT, 1), (1, GLINT, 0)),
}


def concept_holotank(mono):
    """1 · Holotank L: el L macizo y achaflanado; su pie es la placa emisora del tanque."""
    hexa = hexagon(54, 54, 47, rot=30)  # punta arriba: se lee como prisma, no como moneda
    inner = hexagon(54, 54, 41.5, rot=30)
    # El L: fuste con chaflán arriba a la izquierda y pie que se ensancha en placa.
    l_shape = [(37, 31), (42, 26), (51, 26), (51, 64), (73, 64), (78, 69), (78, 75), (37, 75)]
    # Líneas de escaneo que cruzan el fuste: el L se está materializando por arriba.
    cuts = [poly([(36, y), (52, y), (52, y + h), (36, y + h)]) for y, h in ((35, 2.6), (42.5, 2.0), (49, 1.4))]
    node = poly([(66, 32), (70, 36), (66, 40), (62, 36)])
    if mono:
        return [
            dict(d=rounded(hexa, 6) + ' ' + rounded(inner, 5), fill='#FFFFFF', evenodd=True),
            dict(d=poly(l_shape) + ' ' + ' '.join(cuts), fill='#FFFFFF', evenodd=True),
            dict(d=node, fill='#FFFFFF'),
        ]
    return [
        dict(d=rounded(hexa, 6), fill='glass'),
        dict(d=poly([(55, 64), (73, 64), (86, 24), (52, 24)]), fill='beam'),
        dict(d=rounded(hexa, 6), stroke='rim', width=2.6),
        dict(d=rounded(inner, 5), stroke='#FFFFFF', width=0.6, alpha=0.22),
        dict(d=poly(l_shape) + ' ' + ' '.join(cuts), fill='mark', evenodd=True),
        dict(d=poly([(37, 75), (78, 75), (78, 72.6), (37, 72.6)]), fill=CYAN, alpha=0.85),
        dict(d=node, fill=GLINT),
    ]


def prism(r, base=0.66):
    """Hexágono con la punta arriba y la de abajo cortada en una base plana (el pie del tanque).

    Orden: abajo-derecha, base (der., izq.), abajo-izquierda, arriba-izquierda, punta, arriba-derecha.
    [base] es dónde se corta la arista de abajo (0 = vértice, 1 = vértice vecino).
    """
    lr, bottom, ll, ul, top, ur = hexagon(54, 54, r, rot=30)
    cut = lambda a: (bottom[0] + (a[0] - bottom[0]) * (1 - base), bottom[1] + (a[1] - bottom[1]) * (1 - base))
    return [lr, cut(lr), cut(ll), ll, ul, top, ur]


def rounded_open(pts, r, edge, t0, t1):
    """El contorno de [pts] (esquinas redondeadas) abierto en la arista [edge] entre t0 y t1: un trazo con hueco."""
    n = len(pts)
    a, b = pts[edge], pts[(edge + 1) % n]
    lerp = lambda t: (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    start, end = lerp(t1), lerp(t0)
    d = [f'M{f(start[0])} {f(start[1])}']
    for k in range(1, n + 1):
        i = (edge + k) % n
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        u, v = _toward(p1, p0, r), _toward(p1, p2, r)
        d.append(f'L{f(u[0])} {f(u[1])} Q{f(p1[0])} {f(p1[1])} {f(v[0])} {f(v[1])}')
    d.append(f'L{f(end[0])} {f(end[1])}')
    return ' '.join(d)


def upright_bands(x0, x1, solid_to, bands, chamfer):
    """El fuste del L, macizo hasta [solid_to] y por encima en franjas cada vez más finas y separadas.

    [bands]: (hueco, franja) de abajo arriba. [chamfer]: (y donde empieza el chaflán, cuánto corta),
    que recorta el canto izquierdo de las franjas que llegan arriba. Devuelve [(path, i)], i = 0 abajo.
    """
    cy, cut = chamfer
    left = lambda y: x0 + max(0.0, min(cut, cy - y))
    out, y = [], solid_to
    for i, (gap, band) in enumerate(bands):
        bottom = y - gap
        top = bottom - band
        out.append((poly([(left(bottom), bottom), (x1, bottom), (x1, top), (left(top), top)]), i))
        y = top
    return out, y


def concept_holotank_v2(mono, small=False):
    """1 · Holotank L (v2): se materializa por arriba en líneas de escaneo y el tanque se abre junto al nodo.

    [small]: el glifo para 32 dp o menos (icono de notificación, versión plana y el emblema pequeño):
    tres franjas opacas en vez de seis translúcidas, sin el filo interior, para que la punta del L
    siga nítida a 24 dp.
    """
    outer = prism(47)
    inner = prism(41.5)
    gap = (5, 0.28, 0.68) if small else (5, 0.30, 0.66)  # arista punta → arriba-derecha: el hueco del anillo de foco
    # Fuste macizo y, encima, franjas que adelgazan y se separan: aparece, no es una torre.
    if small:
        solid = 47
        bands, _ = upright_bands(37, 51, solid, [(1.3, 4.2), (1.9, 2.8), (2.5, 1.8)], (31, 5))
    else:
        solid = 49
        bands, _ = upright_bands(37, 51, solid, [(0.7, 4.6), (0.9, 3.4), (1.1, 2.5), (1.35, 1.8), (1.6, 1.25), (1.85, 0.85)], (31, 5))
    base = [(37, solid), (51, solid), (51, 64), (73, 64), (78, 69), (78, 75), (37, 75)]
    # El cristal también se abre: una muesca en V en el hueco del canto, y en ella el nodo (ella), que asoma.
    a, b = outer[5], outer[6]
    on_edge = lambda t: (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    mid = on_edge(0.5)
    nx, ny = (54 - mid[0]), (54 - mid[1])
    k = math.hypot(nx, ny)
    inward = lambda d: (mid[0] + nx / k * d, mid[1] + ny / k * d)
    notch = inward(9)
    glass = rounded_open(outer, 6, *gap) + f' L{f(notch[0])} {f(notch[1])} Z'
    node_c = inward(4.5)
    node = poly([(node_c[0], node_c[1] - 4.2), (node_c[0] + 4.2, node_c[1]), (node_c[0], node_c[1] + 4.2), (node_c[0] - 4.2, node_c[1])])
    # El haz del emisor se estrecha hacia ella y se apaga al llegar.
    beam = poly([(55, 64), (73, 64), (node_c[0] + 3, node_c[1] + 3), (node_c[0] - 3, node_c[1] + 3)])
    GRADS['beamToNode'] = lin(0, 70, 0, node_c[1] + 3, (0, CYAN, 0.5), (0.7, CYAN, 0.16), (1, CYAN, 0))
    # Los dos extremos del canto en el hueco acaban en un remate de luz: se lee como abertura, no como rotura.
    ends = [on_edge(gap[1]), on_edge(gap[2])]
    for i, (x, y) in enumerate(ends):
        GRADS[f'capGlow{i}'] = rad(round(x, 2), round(y, 2), 4.6, (0, GLINT, 0.75), (1, GLINT, 0))
    if mono:
        return [
            dict(d=rounded_open(outer, 6, *gap), stroke='#FFFFFF', width=4.4, cap='round'),
            dict(d=poly(base) + ' ' + ' '.join(p for p, _ in bands), fill='#FFFFFF'),
            dict(d=node, fill='#FFFFFF'),
        ]
    fade = [1.0] * 3 if small else [1.0, 0.82, 0.66, 0.52, 0.4, 0.3]
    caps = []
    for i, (x, y) in enumerate(ends):
        caps.append(dict(d=circle(x, y, 4.6), fill=f'capGlow{i}'))
        caps.append(dict(d=circle(x, y, 1.75), fill=GLINT))
    hairline = [] if small else [dict(d=rounded_open(inner, 5, *gap), stroke='#FFFFFF', width=0.6, alpha=0.22, cap='round')]
    return [
        dict(d=glass, fill='glass'),
        dict(d=beam, fill='beamToNode'),
        dict(d=rounded_open(outer, 6, *gap), stroke='rim', width=3.2 if small else 2.6, cap='round'),
        *hairline,
        *caps,
        dict(d=poly(base), fill='mark'),
        *[dict(d=p, fill='mark', alpha=None if small else fade[i]) for p, i in bands],
        dict(d=poly([(37, 75), (78, 75), (78, 72.6), (37, 72.6)]), fill=CYAN, alpha=0.85),
        dict(d=node, fill=GLINT),
    ]


def concept_eclipse(mono):
    """2 · Eclipse L: media luna gótica abierta arriba a la derecha (el hueco del anillo de foco) con el L dentro."""
    moon = circle(54, 54, 44) + ' ' + circle(66, 43, 37)
    # Contraste de trazo: fuste fino, pie grueso con el remate cortado en diagonal.
    l_shape = [(39, 30), (46, 30), (46, 62), (74, 62), (66, 74), (39, 74)]
    if mono:
        return [
            dict(d=moon, fill='#FFFFFF', evenodd=True),
            dict(d=poly(l_shape), fill='#FFFFFF'),
            dict(d=circle(84, 27, 4), fill='#FFFFFF'),
        ]
    return [
        dict(d=moon, fill='moon', evenodd=True),
        dict(d=circle(54, 54, 43.4), stroke='#FFFFFF', width=0.6, alpha=0.18),
        dict(d=poly(l_shape), fill='markInk'),
        dict(d=poly([(39, 30), (46, 30), (46, 32.4), (39, 32.4)]), fill=GLINT, alpha=0.9),
        dict(d=circle(84, 27, 10), fill='glint'),
        dict(d=circle(84, 27, 4), fill=GLINT),
    ]


def concept_ribbon(mono):
    """3 · Ribbon L: un solo trazo que baja y se recoge en la coleta que forma el pie; un nodo de luz en la punta."""
    segs = [
        ((42, 18), (41, 36), (40, 56), (40, 70)),
        ((40, 70), (40, 79), (44, 82), (53, 82)),
        ((53, 82), (66, 82), (80, 78), (90, 60)),
    ]

    def width(s):
        # Grueso en el fuste, se afina en la curva y casi desaparece en la punta (pincel).
        if s < 0.12:
            return 4 + s / 0.12 * 7
        if s < 0.45:
            return 11
        return max(1.6, 11 - (s - 0.45) / 0.55 * 9.6)

    body, centre = ribbon(segs, width)
    tip = centre[-1]
    orbit = arc(54, 54, 45, 200, 470)  # 270°, abierto abajo a la derecha: por ahí sale la coleta
    if mono:
        return [
            dict(d=orbit, stroke='#FFFFFF', width=3.2, cap='round'),
            dict(d=body, fill='#FFFFFF'),
            dict(d=circle(tip[0], tip[1] - 1, 5), fill='#FFFFFF'),
        ]
    return [
        dict(d=orbit, stroke='rim', width=2.4, cap='round', alpha=0.9),
        dict(d=body, fill='markInk'),
        dict(d=circle(tip[0], tip[1] - 1, 10), fill='glint2'),
        dict(d=circle(tip[0], tip[1] - 1, 4.4), fill=GLINT),
    ]


GRADS['glint2'] = rad(90, 59, 10, (0, GLINT, 0.9), (1, GLINT, 0))

CONCEPTS = [
    ('1 · Holotank L', concept_holotank),
    ('2 · Eclipse L', concept_eclipse),
    ('3 · Ribbon L', concept_ribbon),
]


# ── salida: SVG ──────────────────────────────────────────────

def _svg_paint(v, uid):
    return f'url(#{uid}{v})' if v in GRADS else v


def svg(layers, uid, size=108):
    used = {l.get(k) for l in layers for k in ('fill', 'stroke')} & set(GRADS)
    defs = []
    for gid in sorted(used):
        kind, g, stops = GRADS[gid]
        st = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
        if kind == 'linear':
            defs.append(f'<linearGradient id="{uid}{gid}" gradientUnits="userSpaceOnUse" x1="{g[0]}" y1="{g[1]}" x2="{g[2]}" y2="{g[3]}">{st}</linearGradient>')
        else:
            defs.append(f'<radialGradient id="{uid}{gid}" gradientUnits="userSpaceOnUse" cx="{g[0]}" cy="{g[1]}" r="{g[2]}">{st}</radialGradient>')
    body = []
    for l in layers:
        a = [f'd="{l["d"]}"']
        a.append(f'fill="{_svg_paint(l["fill"], uid)}"' if l.get('fill') else 'fill="none"')
        if l.get('stroke'):
            a += [f'stroke="{_svg_paint(l["stroke"], uid)}"', f'stroke-width="{l["width"]}"', 'stroke-linejoin="round"']
            if l.get('cap'):
                a.append(f'stroke-linecap="{l["cap"]}"')
        if l.get('evenodd'):
            a.append('fill-rule="evenodd"')
        if l.get('alpha') is not None:
            a.append(f'opacity="{l["alpha"]}"')
        body.append(f'<path {" ".join(a)}/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">'
            f'<defs>{"".join(defs)}</defs>{"".join(body)}</svg>')


# ── salida: VectorDrawable ───────────────────────────────────

def _argb(c, a=1.0):
    return '#%02X%s' % (round(a * 255), c.lstrip('#').upper())


def _vd_gradient(gid, ind):
    kind, g, stops = GRADS[gid]
    items = '\n'.join(f'{ind}    <item android:offset="{o}" android:color="{_argb(c, a)}" />' for o, c, a in stops)
    if kind == 'linear':
        head = f'<gradient android:type="linear" android:startX="{g[0]}" android:startY="{g[1]}" android:endX="{g[2]}" android:endY="{g[3]}">'
    else:
        head = f'<gradient android:type="radial" android:centerX="{g[0]}" android:centerY="{g[1]}" android:gradientRadius="{g[2]}">'
    return f'{ind}{head}\n{items}\n{ind}</gradient>'


def vector(layers, header, size_dp=108, tint=None):
    out = []
    for l in layers:
        attrs = [f'android:pathData="{l["d"]}"']
        kids = []
        alpha = l.get('alpha')
        if l.get('fill'):
            if l['fill'] in GRADS:
                kids.append(f'        <aapt:attr name="android:fillColor">\n{_vd_gradient(l["fill"], "            ")}\n        </aapt:attr>')
            else:
                attrs.append(f'android:fillColor="{_argb(l["fill"])}"')
            if alpha is not None:
                attrs.append(f'android:fillAlpha="{alpha}"')
        if l.get('stroke'):
            attrs.append(f'android:strokeWidth="{l["width"]}"')
            attrs.append('android:strokeLineJoin="round"')
            if l.get('cap'):
                attrs.append(f'android:strokeLineCap="{l["cap"]}"')
            if l['stroke'] in GRADS:
                kids.append(f'        <aapt:attr name="android:strokeColor">\n{_vd_gradient(l["stroke"], "            ")}\n        </aapt:attr>')
            else:
                attrs.append(f'android:strokeColor="{_argb(l["stroke"])}"')
            if alpha is not None:
                attrs.append(f'android:strokeAlpha="{alpha}"')
        if l.get('evenodd'):
            attrs.append('android:fillType="evenOdd"')
        a = '\n        '.join(attrs)
        out.append(f'    <path\n        {a}>\n' + '\n'.join(kids) + '\n    </path>' if kids else f'    <path\n        {a} />')
    tint_attr = f'\n    android:tint="{tint}"' if tint else ''
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!--\n{header}\n-->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{size_dp}dp"\n'
        f'    android:height="{size_dp}dp"{tint_attr}\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        + '\n'.join(out) + '\n</vector>\n'
    )


# ── hoja de comparación ──────────────────────────────────────

DARK, LIGHT = '#0B0A1A', '#EEF0F6'  # tinta de Elyndra y perla del tema Frost


def sheet(concepts=None, filename='sheet.html', svgs=True):
    os.makedirs(OUT, exist_ok=True)
    cols = []
    for i, (name, make) in enumerate(concepts or CONCEPTS, start=1):
        colour, mono = make(False), make(True)
        if svgs:
            open(os.path.join(OUT, f'concept{i}.svg'), 'w', encoding='utf-8').write(svg(colour, f'c{i}', 512))
            open(os.path.join(OUT, f'concept{i}_mono.svg'), 'w', encoding='utf-8').write(svg(mono, f'm{i}', 512))
        big = lambda bg, uid: f'<div class="tile" style="background:{bg}">{svg(colour, uid, 512)}</div>'
        small = []
        for bg, fg, label in ((DARK, None, 'color · oscuro'), (LIGHT, None, 'color · claro'), (DARK, '#FFFFFF', 'mono · oscuro'), (LIGHT, '#3B2FA0', 'mono · claro')):
            layers = colour if fg is None else [dict(l, fill=fg if l.get('fill') else None, stroke=fg if l.get('stroke') else None, alpha=None) for l in mono]
            uid = f's{i}{len(small)}'
            s24 = svg(layers, uid, 24)
            s24x = svg(layers, uid + 'x', 24)
            small.append(
                f'<div class="mini" style="background:{bg}"><div class="px">{s24}</div>'
                f'<canvas class="zoom" data-svg="{_b64(s24x)}"></canvas><span style="color:{"#aaa" if bg == DARK else "#555"}">{label}</span></div>'
            )
        cols.append(
            f'<section><h2>{name}</h2>{big(DARK, f"d{i}")}{big(LIGHT, f"l{i}")}'
            f'<div class="row">{"".join(small)}</div></section>'
        )
    html = f'''<!doctype html><html><head><meta charset="utf-8"><title>Lucy · emblema</title><style>
body{{margin:0;padding:28px;background:#15141f;font-family:Segoe UI,sans-serif;color:#eee;display:flex;gap:28px}}
section{{width:560px}} h2{{font-weight:600;font-size:22px;margin:0 0 14px}}
.tile{{width:560px;height:560px;display:flex;align-items:center;justify-content:center;border-radius:14px;margin-bottom:14px}}
.row{{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}}
.mini{{border-radius:10px;padding:10px 6px;display:flex;flex-direction:column;align-items:center;gap:6px;font-size:11px}}
.px{{width:24px;height:24px}} .px svg{{display:block}}
.zoom{{width:96px;height:96px;image-rendering:pixelated}}
</style></head><body>{"".join(cols)}<script>
document.querySelectorAll('canvas.zoom').forEach(c=>{{c.width=24;c.height=24;const i=new Image();
i.onload=()=>{{c.getContext('2d').drawImage(i,0,0,24,24)}};i.src='data:image/svg+xml;base64,'+c.dataset.svg;}});
</script></body></html>'''
    path = os.path.join(OUT, filename)
    open(path, 'w', encoding='utf-8').write(html)
    print(path)


def _b64(s):
    import base64
    return base64.b64encode(s.encode('utf-8')).decode('ascii')


HEADER = """  Emblema de Lucy (Holotank L): un L macizo cuyo pie es la placa emisora del
  holotanque; la punta del fuste se materializa en líneas de escaneo. El prisma
  de cristal tiene la base plana y se abre arriba a la derecha (el hueco del
  anillo de foco), donde asoma ella: el rombo de luz al que sube el haz.
  {variant}
  Generado por tools/masha_icon/lucy_emblem.py: no se edita a mano."""


def drawables():
    """Escribe las tres versiones del emblema en res/drawable."""
    files = {
        'masha.xml': (concept_holotank_v2(False), 108, None,
                      'A color, para 40 dp o más (botón de la biblioteca, núcleo del holotanque).'),
        'masha_small.xml': (concept_holotank_v2(False, small=True), 108, None,
                            'A color, glifo simplificado (tres franjas opacas) para 32 dp o menos.'),
        'lucy_mono.xml': (concept_holotank_v2(True, small=True), 24, None,
                          'Plana, de un color (blanco): para tintar con el acento o el tema.'),
        'ic_stat_lucy.xml': (concept_holotank_v2(True, small=True), 24, '#FFFFFFFF',
                             'Icono pequeño de las notificaciones de Lucy: blanco plano, el sistema lo tiñe.'),
    }
    for name, (layers, size, tint, variant) in files.items():
        path = os.path.normpath(os.path.join(RES, name))
        open(path, 'w', encoding='utf-8', newline='\n').write(vector(layers, HEADER.format(variant=variant), size, tint))
        print(path)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'sheet'
    if cmd == 'sheet':
        sheet()
    elif cmd == 'compare':
        sheet([('Antes · Holotank L', concept_holotank), ('Después · Holotank L v2', concept_holotank_v2)], 'compare.html', svgs=False)
    elif cmd == 'drawable':
        drawables()
    elif cmd == 'small':
        sheet([('Holotank L v2 · pequeño', lambda mono: concept_holotank_v2(mono, small=True)),
               ('Holotank L v2 · grande', concept_holotank_v2)], 'small.html', svgs=False)
