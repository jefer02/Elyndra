import math
TOP, BOT = 35.0, 74.0
L0, L1, V, R1, R0 = (33, BOT), (40.5, TOP), (54, 65), (67.5, TOP), (75, BOT)
THIN, THICK = 2.9, 8.2
def off(p, q, d):
    dx, dy = q[0]-p[0], q[1]-p[1]; n = math.hypot(dx, dy); nx, ny = -dy/n, dx/n
    return ((p[0]+nx*d, p[1]+ny*d), (q[0]+nx*d, q[1]+ny*d))
def inter(l1, l2):
    (x1,y1),(x2,y2) = l1; (x3,y3),(x4,y4) = l2
    den = (x1-x2)*(y3-y4)-(y1-y2)*(x3-x4)
    px = ((x1*y2-y1*x2)*(x3-x4)-(x1-x2)*(x3*y4-y3*x4))/den
    py = ((x1*y2-y1*x2)*(y3-y4)-(y1-y2)*(x3*y4-y3*x4))/den
    return (px, py)
def hline(y): return ((0,y),(100,y))
def edges(p,q,w):
    a, b = off(p,q,w/2), off(p,q,-w/2)
    return a, b
A = edges(L0,L1,THIN); B = edges(L1,V,THICK); C = edges(V,R1,THIN); D = edges(R1,R0,THICK)
def pick(es, y, side):  # side: 'left' → menor x a la altura y
    xs = [(inter(e, hline(y))[0], e) for e in es]
    xs.sort(); return xs[0][1] if side=='left' else xs[1][1]
Al, Ar = pick(A, 60, 'left'), pick(A, 60, 'right')
# B baja hacia la derecha: su canto "alto" es el de mayor x a una altura dada
Bt, Bb = pick(B, 50, 'right'), pick(B, 50, 'left')
Ct, Cb = pick(C, 50, 'left'), pick(C, 50, 'right')
Dl, Dr = pick(D, 60, 'left'), pick(D, 60, 'right')
pts = [
 inter(Al, hline(BOT)), inter(Al, hline(TOP)), inter(Bt, hline(TOP)),
 inter(Bt, Ct), inter(Ct, hline(TOP)), inter(Dr, hline(TOP)),
 inter(Dr, hline(BOT)), inter(Dl, hline(BOT)), inter(Dl, Cb),
 inter(Cb, Bb), inter(Bb, Ar), inter(Ar, hline(BOT)),
]
d = 'M' + ' L'.join(f'{x:.2f} {y:.2f}' for x,y in pts) + ' Z'
print(d)
open('glyph.txt','w').write(d)
