"""Convierte el SVG del emblema (subconjunto: circle, ellipse, path, g con clip o
escala, degradados en userSpaceOnUse) a un VectorDrawable de Android."""
import re
import sys
import xml.etree.ElementTree as ET

NS = '{http://www.w3.org/2000/svg}'
src, dst = sys.argv[1], sys.argv[2]
root = ET.parse(src).getroot()
grads = {}
clips = {}


def color(c, a=1.0):
    c = c.strip().lstrip('#')
    if len(c) == 3:
        c = ''.join(ch * 2 for ch in c)
    return '#%02X%s' % (round(a * 255), c.upper())


for el in root.iter():
    tag = el.tag.replace(NS, '')
    if tag in ('linearGradient', 'radialGradient'):
        stops = [(float(s.get('offset')), color(s.get('stop-color'), float(s.get('stop-opacity', '1')))) for s in el.findall(NS + 'stop')]
        grads[el.get('id')] = (tag, dict(el.attrib), stops)
    if tag == 'clipPath':
        c = el.find(NS + 'circle')
        clips[el.get('id')] = circle_d = None
        clips[el.get('id')] = (float(c.get('cx')), float(c.get('cy')), float(c.get('r')))


def circle(cx, cy, r):
    return f'M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z'


def ellipse(cx, cy, rx, ry):
    return f'M{cx - rx},{cy} a{rx},{ry} 0 1,0 {2 * rx},0 a{rx},{ry} 0 1,0 {-2 * rx},0 Z'


def gradient(ref, ind):
    gid = re.match(r'url\(#(.+)\)', ref).group(1)
    tag, a, stops = grads[gid]
    items = '\n'.join(f'{ind}    <item android:offset="{o:g}" android:color="{c}" />' for o, c in stops)
    if tag == 'linearGradient':
        head = f'<gradient android:type="linear" android:startX="{a["x1"]}" android:startY="{a["y1"]}" android:endX="{a["x2"]}" android:endY="{a["y2"]}">'
    else:
        head = f'<gradient android:type="radial" android:centerX="{a["cx"]}" android:centerY="{a["cy"]}" android:gradientRadius="{a["r"]}">'
    return f'{ind}{head}\n{items}\n{ind}</gradient>'


out = []


def emit_path(el, d, ind):
    fill = el.get('fill', 'none' if el.get('stroke') else 'black')
    stroke = el.get('stroke')
    op = float(el.get('opacity', '1'))
    attrs = [f'android:pathData="{" ".join(d.split())}"']
    kids = []
    if fill != 'none':
        fa = float(el.get('fill-opacity', '1')) * op
        if fill.startswith('url'):
            kids.append(f'{ind}    <aapt:attr name="android:fillColor">\n{gradient(fill, ind + "        ")}\n{ind}    </aapt:attr>')
            if fa != 1:
                attrs.append(f'android:fillAlpha="{fa:g}"')
        else:
            attrs.append(f'android:fillColor="{color(fill, fa)}"')
    if stroke and stroke != 'none':
        sa = float(el.get('stroke-opacity', '1')) * op
        attrs.append(f'android:strokeWidth="{el.get("stroke-width", "1")}"')
        if stroke.startswith('url'):
            kids.append(f'{ind}    <aapt:attr name="android:strokeColor">\n{gradient(stroke, ind + "        ")}\n{ind}    </aapt:attr>')
            if sa != 1:
                attrs.append(f'android:strokeAlpha="{sa:g}"')
        else:
            attrs.append(f'android:strokeColor="{color(stroke, sa)}"')
        if el.get('stroke-linecap'):
            attrs.append(f'android:strokeLineCap="{el.get("stroke-linecap")}"')
        if el.get('stroke-linejoin'):
            attrs.append(f'android:strokeLineJoin="{el.get("stroke-linejoin")}"')
    a = ('\n' + ind + '    ').join(attrs)
    if kids:
        out.append(f'{ind}<path\n{ind}    {a}>\n' + '\n'.join(kids) + f'\n{ind}</path>')
    else:
        out.append(f'{ind}<path\n{ind}    {a} />')


def walk(parent, ind, stroke_inherit=None):
    for el in parent:
        tag = el.tag.replace(NS, '')
        if tag in ('defs',):
            continue
        if tag == 'g':
            if not list(el):
                continue
            attrs = []
            clip = el.get('clip-path')
            tr = el.get('transform')
            if tr:
                m = re.match(r'translate\(([\d.]+) ([\d.]+)\) scale\(([\d.]+)\)', tr)
                attrs += [f'android:pivotX="{m.group(1)}"', f'android:pivotY="{m.group(2)}"', f'android:scaleX="{m.group(3)}"', f'android:scaleY="{m.group(3)}"']
            out.append(f'{ind}<group' + (' ' + ' '.join(attrs) if attrs else '') + '>')
            if clip:
                cid = re.match(r'url\(#(.+)\)', clip).group(1)
                if cid in clips:
                    out.append(f'{ind}    <clip-path android:pathData="{circle(*clips[cid])}" />')
            walk(el, ind + '    ')
            out.append(f'{ind}</group>')
        elif tag == 'circle':
            emit_path(el, circle(float(el.get('cx')), float(el.get('cy')), float(el.get('r'))), ind)
        elif tag == 'ellipse':
            emit_path(el, ellipse(float(el.get('cx')), float(el.get('cy')), float(el.get('rx')), float(el.get('ry'))), ind)
        elif tag == 'path':
            d = el.get('d')
            if d.strip():
                emit_path(el, d, ind)


walk(root, '    ')
xml = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  Emblema de Masha: medallón con canto champán (el de la intro), su perfil de
  holograma con la coleta alta en media luna siguiendo el borde, un destello
  por ojo y el anillo del holotanque del que nace. Vectorial: nítido a
  cualquier tamaño, del botón de 24 dp al widget. Fuente de diseño en
  tools/masha_icon/masha_emblem.svg.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
''' + '\n'.join(out) + '\n</vector>\n'
open(dst, 'w', encoding='utf-8').write(xml)
print(len(out), 'piezas')
