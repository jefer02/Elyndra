#!/usr/bin/env python3
"""
Música de fondo de Elyndra: un ambiente de menú de consola, original.

Se sintetiza de cero (sin muestras de nada): cuatro acordes largos de pad
con voces desafinadas, un bajo suave, campanitas pentatónicas dispersas con
eco y un aire de ruido muy tenue. Todo se mezcla en un búfer **circular**:
lo que sobra por el final (colas, ecos) entra por el principio, así que el
bucle no tiene costura.

Salida: app/src/main/res/raw/ui_music_ambient.wav (mono, 16 bits, 22 050 Hz).
Uso:  python tools/gen_ui_music.py   (solo la biblioteca estándar)
"""

import math
import os
import random
import struct
import wave

SR = 22050
BAR = 8.0                      # segundos por acorde
CHORDS = [                     # Re mayor, tranquilo: Dmaj9 · Bm11 · Gmaj7(#11) · A6sus
    (146.83, [293.66, 369.99, 440.00, 659.25]),
    (123.47, [246.94, 293.66, 369.99, 440.00]),
    (98.00, [293.66, 392.00, 493.88, 554.37]),
    (110.00, [293.66, 329.63, 440.00, 493.88]),
]
LENGTH = BAR * len(CHORDS)     # 32 s
N = int(LENGTH * SR)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw", "ui_music_ambient.wav")

buf = [0.0] * N


def add(at, sig, gain=1.0):
    """Mezcla circular: lo que se sale por el final entra por el principio."""
    start = int(at * SR)
    for i, v in enumerate(sig):
        buf[(start + i) % N] += v * gain


def pad_note(freq, dur, amp):
    """Tres voces desafinadas (±4 cents) con dos parciales y ventana de coseno."""
    n = int(dur * SR)
    out = [0.0] * n
    for detune in (-0.0023, 0.0, 0.0023):
        f = freq * (1 + detune)
        ph = random.random() * 2 * math.pi
        step = 2 * math.pi * f / SR
        for i in range(n):
            ph += step
            out[i] += math.sin(ph) + 0.22 * math.sin(2 * ph) + 0.07 * math.sin(3 * ph)
    for i in range(n):
        w = 0.5 - 0.5 * math.cos(2 * math.pi * i / n)  # entra y sale sola
        out[i] *= w * amp / 3.0
    return out


def bell(freq, amp):
    dur = 2.6
    n = int(dur * SR)
    out = [0.0] * n
    for ratio, a, tau in ((1.0, 1.0, 1.1), (2.76, 0.16, 0.45), (5.4, 0.05, 0.2)):
        f = freq * ratio
        if f > SR * 0.45:
            continue
        step = 2 * math.pi * f / SR
        ph = 0.0
        for i in range(n):
            t = i / SR
            ph += step
            out[i] += a * (1 - math.exp(-t / 0.004)) * math.exp(-t / tau) * math.sin(ph)
    return [x * amp for x in out]


def main():
    random.seed(20260930)

    # Pads: cada acorde dura 1,6 compases y se solapa con el siguiente.
    for k, (root, notes) in enumerate(CHORDS):
        start = k * BAR - BAR * 0.3
        dur = BAR * 1.6
        for f in notes:
            add(start, pad_note(f, dur, 0.16))
        add(start, pad_note(root, dur, 0.22))          # bajo
        add(start, pad_note(root * 2, dur, 0.05))

    # Campanitas pentatónicas (Re mayor), dispersas y suaves.
    scale = [587.33, 659.25, 739.99, 880.00, 987.77, 1174.66]
    t = 0.7
    while t < LENGTH - 0.2:
        add(t, bell(random.choice(scale), 0.05 + random.random() * 0.03))
        t += random.choice((1.5, 2.0, 2.5, 3.0))

    # Aire: ruido paso bajo, muy tenue, con un vaivén lento.
    lp = 0.0
    air = []
    for i in range(N):
        lp += 0.03 * (random.uniform(-1, 1) - lp)
        air.append(lp * (0.5 + 0.5 * math.sin(2 * math.pi * i / N * 4)))
    add(0, air, 0.25)

    # Eco circular (375 ms, 35 %), dos vueltas para que la cola se asiente.
    d = int(0.375 * SR)
    echo = [0.0] * N
    low = 0.0
    for _ in range(2):
        for i in range(N):
            low += 0.35 * (echo[(i - d) % N] - low)
            echo[i] = buf[i] + 0.35 * low
    mixed = [b + 0.3 * (e - b) for b, e in zip(buf, echo)]

    # Trémolo lento (4 ciclos por bucle: sin costura) y nivel: RMS -24 dBFS, pico ≤ -3.
    mixed = [x * (0.85 + 0.15 * math.sin(2 * math.pi * i / N * 4)) for i, x in enumerate(mixed)]
    mean = sum(mixed) / N
    mixed = [x - mean for x in mixed]
    rms = math.sqrt(sum(x * x for x in mixed) / N)
    g = 10 ** (-24 / 20) / rms
    peak = max(abs(x) for x in mixed) * g
    if peak > 10 ** (-3 / 20):
        g *= 10 ** (-3 / 20) / peak
    with wave.open(OUT, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x * g)) * 32767)) for x in mixed))
    print(f"{OUT}: {LENGTH:.0f} s, {os.path.getsize(OUT) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
