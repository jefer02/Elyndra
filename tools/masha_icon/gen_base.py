"""Genera variantes del emblema de Masha (orbe de cristal + monograma)."""

DEFS = '''
  <defs>
    <radialGradient id="sphere" cx="42" cy="34" r="78" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#7A86FF"/>
      <stop offset="0.3" stop-color="#4045D6"/>
      <stop offset="0.68" stop-color="#1B1863"/>
      <stop offset="1" stop-color="#07061C"/>
    </radialGradient>
    <radialGradient id="cyan" cx="28" cy="82" r="42" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#5DE4F4" stop-opacity="0.75"/>
      <stop offset="1" stop-color="#5DE4F4" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="violet" cx="88" cy="36" r="40" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#D46BFF" stop-opacity="0.6"/>
      <stop offset="1" stop-color="#D46BFF" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="warm" cx="74" cy="94" r="30" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#F6D59A" stop-opacity="0.45"/>
      <stop offset="1" stop-color="#F6D59A" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="spec" x1="0" y1="8" x2="0" y2="52" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="rim" x1="14" y1="10" x2="94" y2="100" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.85"/>
      <stop offset="0.4" stop-color="#C9CFFF" stop-opacity="0.25"/>
      <stop offset="0.75" stop-color="#5DE4F4" stop-opacity="0.15"/>
      <stop offset="1" stop-color="#F6D59A" stop-opacity="0.7"/>
    </linearGradient>
    <linearGradient id="mark" x1="0" y1="30" x2="0" y2="80" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF"/>
      <stop offset="0.55" stop-color="#FFF1D2"/>
      <stop offset="1" stop-color="#9FEFFA"/>
    </linearGradient>
    <clipPath id="disc"><circle cx="54" cy="54" r="50"/></clipPath>
  </defs>'''

ORB_BACK = '''
  <circle cx="54" cy="54" r="50" fill="url(#sphere)"/>
  <g clip-path="url(#disc)">
    <circle cx="28" cy="82" r="42" fill="url(#cyan)"/>
    <circle cx="88" cy="36" r="40" fill="url(#violet)"/>
    <circle cx="74" cy="94" r="30" fill="url(#warm)"/>
  </g>'''

ORB_FRONT = '''
  <path d="M22 40 C26 22 40 10 54 9 C72 8 86 20 88 32 C76 22 64 19 52 20 C38 21 28 28 22 40 Z" fill="url(#spec)"/>
  <circle cx="54" cy="54" r="49.4" fill="none" stroke="url(#rim)" stroke-width="1.2"/>'''

# A: monograma M de cinta continua.
MARK_A = '''
  <path d="M34 74 C36 56 37 44 41 38 C44 34 48 37 50 42 L54 54 L58 42 C60 37 64 34 67 38 C71 44 72 56 74 74"
        fill="none" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M34 74 C36 56 37 44 41 38 C44 34 48 37 50 42 L54 54 L58 42 C60 37 64 34 67 38 C71 44 72 56 74 74"
        fill="none" stroke="url(#mark)" stroke-width="6.5" stroke-linecap="round" stroke-linejoin="round"/>'''

# B: M de voz — cinco barras de onda con la silueta de una M.
def bars():
    xs = [30, 42, 54, 66, 78]
    hs = [18, 42, 24, 42, 18]
    out = []
    for x, h in zip(xs, hs):
        y0, y1 = 54 - h / 2, 54 + h / 2
        out.append(f'<path d="M{x} {y0} L{x} {y1}" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="12" stroke-linecap="round"/>')
    for x, h in zip(xs, hs):
        y0, y1 = 54 - h / 2, 54 + h / 2
        out.append(f'<path d="M{x} {y0} L{x} {y1}" stroke="url(#mark)" stroke-width="7" stroke-linecap="round"/>')
    return '\n  '.join(out)

SPARK = '''
  <path d="M80 22 C80.5 26 81.5 27 85.5 27.5 C81.5 28 80.5 29 80 33 C79.5 29 78.5 28 74.5 27.5 C78.5 27 79.5 26 80 22 Z" fill="#FFFFFF"/>'''


def svg(mark, spark=True):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">{DEFS}{ORB_BACK}{mark}{SPARK if spark else ""}{ORB_FRONT}\n</svg>\n'


open('optA.svg', 'w').write(svg(MARK_A))
open('optB.svg', 'w').write(svg(bars()))
html = '''<html><body style="margin:0;background:#F3F4F8;padding:16px;font-family:sans-serif">
<div style="display:flex;gap:28px;align-items:center">
 <img src="optA.svg" width="220"><img src="optA.svg" width="58"><img src="optA.svg" width="40">
 <img src="optB.svg" width="220"><img src="optB.svg" width="58"><img src="optB.svg" width="40">
</div>
<div style="display:flex;gap:28px;align-items:center;background:#0D0F16;padding:16px;margin-top:12px">
 <img src="optA.svg" width="120"><img src="optA.svg" width="40"><img src="optB.svg" width="120"><img src="optB.svg" width="40">
</div></body></html>'''
open('opts.html', 'w').write(html)
