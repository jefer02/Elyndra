#!/usr/bin/env python3
"""
Sintetizador de los sonidos de interfaz de Elyndra.

Todos los sonidos salen de aquí, de cero: osciladores, envolventes,
parciales, ruido filtrado y una reverberación pequeña. No se usa ni se
imita ningún sonido de otro producto; cada paquete tiene un carácter propio
(Console: cristal y campanas limpias · Soft: madera redonda, tipo marimba ·
Retro: ondas de pulso de banda limitada, arpegios de 8 bits).

Salida: WAV mono de 16 bits a 22 050 Hz en app/src/main/res/raw/
(ui_<paquete>_<evento>.wav). Cada sonido empieza y acaba en cero (sin
clics), se le quita la continua y se iguala su sonoridad (RMS) por evento
en todos los paquetes, con un techo de pico de -1 dBFS.

Uso:  python tools/gen_ui_sounds.py
Solo necesita la biblioteca estándar de Python 3.
"""

import math
import os
import random
import struct
import wave

SR = 22050
NYQ = SR / 2
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")

EVENTS = ["navigate", "select", "back", "open", "close", "toggle_on", "toggle_off", "error", "launch"]


# ─── piezas básicas ──────────────────────────────────────────

def silence(sec):
    return [0.0] * int(sec * SR)


def mix_into(buf, sig, at_sec=0.0, gain=1.0):
    start = int(at_sec * SR)
    need = start + len(sig)
    if need > len(buf):
        buf.extend([0.0] * (need - len(buf)))
    for i, v in enumerate(sig):
        buf[start + i] += v * gain
    return buf


def env(n, attack, tau, hold=0.0):
    """Ataque suave (1 - e^-t/a) y caída exponencial (e^-t/tau) tras [hold]."""
    out = []
    for i in range(n):
        t = i / SR
        a = 1.0 - math.exp(-t / attack) if attack > 0 else 1.0
        d = 1.0 if t < hold else math.exp(-(t - hold) / tau)
        out.append(a * d)
    return out


def partial_tone(freq, dur, partials, attack=0.002, tau=0.05, glide_to=None, glide_time=None, vibrato=0.0):
    """Suma de parciales (razón, amplitud, factor de caída) con glissando opcional."""
    n = int(dur * SR)
    out = [0.0] * n
    for ratio, amp, decay_mult in partials:
        e = env(n, attack, tau * decay_mult)
        phase = 0.0
        for i in range(n):
            t = i / SR
            f = freq
            if glide_to is not None:
                g = min(1.0, t / (glide_time or dur))
                g = 1 - (1 - g) ** 2  # llega rápido y se asienta
                f = freq + (glide_to - freq) * g
            if vibrato:
                f *= 1.0 + vibrato * math.sin(2 * math.pi * 5.5 * t)
            fr = f * ratio
            if fr >= NYQ * 0.95:
                break
            phase += 2 * math.pi * fr / SR
            out[i] += amp * e[i] * math.sin(phase)
    return out


def pulse(freq, dur, duty=0.5, attack=0.001, tau=0.05, hold=0.0, vibrato=0.0):
    """Onda de pulso de banda limitada (serie de Fourier hasta Nyquist)."""
    n = int(dur * SR)
    e = env(n, attack, tau, hold)
    kmax = max(1, int(NYQ * 0.9 / freq))
    weights = [(k, math.sin(math.pi * k * duty) / k) for k in range(1, kmax + 1)]
    weights = [(k, w) for k, w in weights if abs(w) > 1e-4]
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SR
        f = freq * (1.0 + vibrato * math.sin(2 * math.pi * 6.0 * t))
        phase += 2 * math.pi * f / SR
        s = 0.0
        for k, w in weights:
            if f * k < NYQ * 0.92:
                s += w * math.cos(k * phase)
        out.append(s * e[i] * 0.6)
    return out


def noise(dur, seed):
    rnd = random.Random(seed)
    return [rnd.uniform(-1.0, 1.0) for _ in range(int(dur * SR))]


def one_pole_lp(sig, cutoff):
    a = math.exp(-2 * math.pi * cutoff / SR)
    y = 0.0
    out = []
    for x in sig:
        y = (1 - a) * x + a * y
        out.append(y)
    return out


def one_pole_hp(sig, cutoff):
    lp = one_pole_lp(sig, cutoff)
    return [x - l for x, l in zip(sig, lp)]


def swept_band(sig, f0, f1):
    """Paso banda que barre de f0 a f1 (dos polos por lado)."""
    n = len(sig)
    out = []
    lp1 = lp2 = hp1 = hp2 = 0.0
    prev = 0.0
    for i, x in enumerate(sig):
        f = f0 * (f1 / f0) ** (i / max(1, n - 1))
        a_hi = math.exp(-2 * math.pi * min(f * 1.6, NYQ * 0.9) / SR)
        a_lo = math.exp(-2 * math.pi * (f / 1.6) / SR)
        lp1 = (1 - a_hi) * x + a_hi * lp1
        lp2 = (1 - a_hi) * lp1 + a_hi * lp2
        hp1 = a_lo * (hp1 + lp2 - prev)
        prev = lp2
        hp2 = hp1
        out.append(hp2)
    return out


def shape(sig, e):
    return [x * g for x, g in zip(sig, e)]


def reverb(sig, tail=0.5, wet=0.22):
    """Tres peines en paralelo y un paso todo: una sala pequeña y oscura."""
    out = sig + [0.0] * int(tail * SR)
    dry = list(out)
    wet_sum = [0.0] * len(out)
    for delay_ms, fb in ((29.7, 0.72), (37.1, 0.70), (41.1, 0.68)):
        d = int(delay_ms / 1000 * SR)
        buf = [0.0] * len(out)
        lp = 0.0
        for i in range(len(out)):
            fbv = buf[i - d] if i >= d else 0.0
            lp = 0.6 * fbv + 0.4 * lp  # amortigua los agudos del eco
            buf[i] = dry[i] + fb * lp
            wet_sum[i] += buf[i] / 3.0
    d = int(0.005 * SR)
    ap = [0.0] * len(out)
    for i in range(len(out)):
        xd = wet_sum[i - d] if i >= d else 0.0
        yd = ap[i - d] if i >= d else 0.0
        ap[i] = -0.5 * wet_sum[i] + xd + 0.5 * yd
    return [x + wet * w for x, w in zip(dry, ap)]


def finish(sig, peak_db, lowpass=None):
    if lowpass:
        sig = one_pole_lp(sig, lowpass)
    # Sin continua.
    sig = one_pole_hp(sig, 25)
    # Fundidos de 3 ms: empieza y acaba en cero, sin clics.
    f = int(0.003 * SR)
    n = len(sig)
    for i in range(min(f, n)):
        g = i / f
        sig[i] *= g
        sig[n - 1 - i] *= g
    # Recorta el silencio final (umbral -70 dB) y deja 5 ms.
    thr = 10 ** (-70 / 20) * max(1e-9, max(abs(x) for x in sig))
    last = n - 1
    while last > 0 and abs(sig[last]) < thr:
        last -= 1
    sig = sig[: min(n, last + int(0.005 * SR))]
    for i in range(min(f, len(sig))):
        sig[len(sig) - 1 - i] *= i / f
    peak = max(abs(x) for x in sig) or 1.0
    target = 10 ** (peak_db / 20)
    return [x / peak * target for x in sig]


def write(name, sig):
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, x)) * 32767)) for x in sig))
    return os.path.getsize(path)


BELL = [(1.0, 1.0, 1.0), (2.76, 0.18, 0.5), (5.40, 0.06, 0.3)]
GLASS = [(1.0, 1.0, 1.0), (2.0, 0.15, 0.6), (3.01, 0.05, 0.4)]
WOOD = [(1.0, 1.0, 1.0), (4.0, 0.22, 0.25), (9.2, 0.05, 0.12)]


# ─── Console: cristal y campanas limpias ─────────────────────

def console():
    s = {}
    click = shape(one_pole_hp(noise(0.006, 1), 3000), env(int(0.006 * SR), 0.0003, 0.0015))
    tick = partial_tone(2350, 0.045, GLASS, attack=0.0008, tau=0.009)
    s["navigate"] = finish(mix_into(tick, click, 0, 0.08), -9)

    sel = silence(0.2)
    mix_into(sel, partial_tone(1320, 0.16, BELL, tau=0.05))
    mix_into(sel, partial_tone(1980, 0.15, BELL, tau=0.05), 0.045, 0.8)
    s["select"] = finish(sel, -6, lowpass=7000)

    back = silence(0.17)
    mix_into(back, partial_tone(1760, 0.14, BELL, tau=0.04), 0, 0.8)
    mix_into(back, partial_tone(1175, 0.14, BELL, tau=0.045), 0.04)
    s["back"] = finish(back, -7, lowpass=6000)

    n = 0.26
    air = shape(swept_band(noise(n, 2), 600, 3000), [math.sin(math.pi * i / (n * SR)) ** 2 for i in range(int(n * SR))])
    opn = mix_into(list(air), partial_tone(880, n, GLASS, attack=0.02, tau=0.09, glide_to=1320, glide_time=0.12), 0, 0.9)
    s["open"] = finish(opn, -7, lowpass=6500)

    n = 0.22
    air = shape(swept_band(noise(n, 3), 2600, 500), [math.sin(math.pi * i / (n * SR)) ** 2 for i in range(int(n * SR))])
    cls = mix_into(list(air), partial_tone(1320, n, GLASS, attack=0.004, tau=0.07, glide_to=880, glide_time=0.1), 0, 0.9)
    s["close"] = finish(cls, -8, lowpass=6000)

    on = silence(0.1)
    mix_into(on, click, 0, 0.35)
    mix_into(on, partial_tone(1568, 0.09, GLASS, tau=0.03, glide_to=2093, glide_time=0.025), 0.004)
    s["toggle_on"] = finish(on, -8)
    off = silence(0.1)
    mix_into(off, click, 0, 0.35)
    mix_into(off, partial_tone(1568, 0.09, GLASS, tau=0.03, glide_to=1175, glide_time=0.025), 0.004)
    s["toggle_off"] = finish(off, -9)

    err = silence(0.26)
    low = [(1.0, 1.0, 1.0), (2.0, 0.3, 0.7), (3.0, 0.15, 0.5)]
    for at in (0.0, 0.11):
        mix_into(err, partial_tone(330, 0.12, low, attack=0.004, tau=0.06), at, 0.6)
        mix_into(err, partial_tone(334, 0.12, low, attack=0.004, tau=0.06), at, 0.5)
    s["error"] = finish(err, -6, lowpass=2500)

    ln = silence(1.1)
    for i, f in enumerate((659, 988, 1319, 1976)):
        mix_into(ln, partial_tone(f, 0.6, BELL, attack=0.002, tau=0.18), i * 0.07, 0.55 - i * 0.06)
    for f in (330, 494, 659):
        mix_into(ln, partial_tone(f, 1.0, [(1.0, 1.0, 1.0), (2.0, 0.2, 0.8)], attack=0.12, tau=0.45), 0.05, 0.22)
    shimmer = shape(swept_band(noise(0.7, 4), 2000, 6000), env(int(0.7 * SR), 0.2, 0.2))
    mix_into(ln, shimmer, 0.1, 0.12)
    s["launch"] = finish(reverb(ln, 0.4, 0.25), -3, lowpass=8000)
    return s


# ─── Soft: madera redonda ────────────────────────────────────

def soft():
    s = {}
    s["navigate"] = finish(partial_tone(1046, 0.035, WOOD, attack=0.001, tau=0.008), -11, lowpass=4000)

    sel = silence(0.22)
    mix_into(sel, partial_tone(784, 0.18, WOOD, tau=0.07))
    mix_into(sel, partial_tone(1046, 0.18, WOOD, tau=0.07), 0.06, 0.85)
    s["select"] = finish(sel, -8, lowpass=4500)

    back = silence(0.22)
    mix_into(back, partial_tone(1046, 0.18, WOOD, tau=0.06), 0, 0.8)
    mix_into(back, partial_tone(784, 0.18, WOOD, tau=0.07), 0.06)
    s["back"] = finish(back, -9, lowpass=4000)

    n = 0.26
    breath = shape(one_pole_lp(noise(n, 5), 1400), [math.sin(math.pi * i / (n * SR)) for i in range(int(n * SR))])
    s["open"] = finish(mix_into(partial_tone(523, n, [(1.0, 1.0, 1.0), (2.0, 0.1, 0.6)], attack=0.03, tau=0.1, glide_to=784, glide_time=0.15, vibrato=0.004), breath, 0, 0.15), -9, lowpass=3500)
    breath = shape(one_pole_lp(noise(n, 6), 1200), [math.sin(math.pi * i / (n * SR)) for i in range(int(n * SR))])
    s["close"] = finish(mix_into(partial_tone(784, n, [(1.0, 1.0, 1.0), (2.0, 0.1, 0.6)], attack=0.01, tau=0.09, glide_to=523, glide_time=0.14), breath, 0, 0.15), -10, lowpass=3200)

    on = silence(0.14)
    mix_into(on, partial_tone(880, 0.1, WOOD, tau=0.03))
    mix_into(on, partial_tone(1320, 0.1, WOOD, tau=0.035), 0.04)
    s["toggle_on"] = finish(on, -10, lowpass=4000)
    off = silence(0.14)
    mix_into(off, partial_tone(1320, 0.1, WOOD, tau=0.03))
    mix_into(off, partial_tone(880, 0.1, WOOD, tau=0.035), 0.04)
    s["toggle_off"] = finish(off, -11, lowpass=3800)

    err = silence(0.3)
    for at in (0.0, 0.13):
        mix_into(err, partial_tone(262, 0.14, WOOD, attack=0.002, tau=0.05), at)
        mix_into(err, partial_tone(277, 0.14, WOOD, attack=0.002, tau=0.05), at, 0.7)
    s["error"] = finish(err, -8, lowpass=2200)

    ln = silence(1.0)
    for i, f in enumerate((523, 659, 784, 1046)):
        mix_into(ln, partial_tone(f, 0.55, WOOD, attack=0.002, tau=0.16), i * 0.085, 0.6)
    for f in (262, 392, 523):
        mix_into(ln, partial_tone(f, 0.9, [(1.0, 1.0, 1.0)], attack=0.15, tau=0.4), 0.08, 0.2)
    s["launch"] = finish(reverb(ln, 0.35, 0.2), -4, lowpass=4500)
    return s


# ─── Retro: pulsos de banda limitada ─────────────────────────

def retro():
    s = {}
    s["navigate"] = finish(pulse(1760, 0.03, 0.25, tau=0.01), -12, lowpass=6000)

    def steps(freqs, step, duty=0.5, tau=0.05, tail=0.05):
        out = silence(step * len(freqs) + tail)
        for i, f in enumerate(freqs):
            mix_into(out, pulse(f, step + tail, duty, tau=tau), i * step)
        return out

    s["select"] = finish(steps([1047, 1568], 0.035, 0.5, tau=0.04), -10, lowpass=6000)
    s["back"] = finish(steps([1568, 1047], 0.035, 0.5, tau=0.04), -11, lowpass=5500)
    s["open"] = finish(steps([523, 659, 784, 1047], 0.025, 0.25, tau=0.03), -10, lowpass=6000)
    s["close"] = finish(steps([1047, 784, 659, 523], 0.025, 0.25, tau=0.03), -11, lowpass=5500)
    s["toggle_on"] = finish(steps([1319, 1760], 0.03, 0.125, tau=0.03), -12, lowpass=6000)
    s["toggle_off"] = finish(steps([1760, 1319], 0.03, 0.125, tau=0.03), -12, lowpass=5500)

    err = silence(0.26)
    for at in (0.0, 0.12):
        mix_into(err, pulse(196, 0.1, 0.5, tau=0.08, hold=0.05), at)
    s["error"] = finish(err, -9, lowpass=2400)

    ln = silence(1.0)
    arp = [523, 659, 784, 1047, 1319, 1568, 2093]
    for i, f in enumerate(arp):
        mix_into(ln, pulse(f, 0.08, 0.25, tau=0.05), i * 0.045, 0.6)
    tail_at = len(arp) * 0.045
    for f in (523, 659, 784):
        mix_into(ln, pulse(f, 0.6, 0.5, attack=0.004, tau=0.22, vibrato=0.006), tail_at, 0.28)
    s["launch"] = finish(ln, -5, lowpass=5000)
    return s


# Sonoridad (RMS, dBFS) de cada evento, igual en los tres paquetes: cambiar
# de paquete no sube ni baja el volumen. Techo de pico: -1 dBFS.
LEVELS = {
    "navigate": -25.0, "select": -20.0, "back": -21.0, "open": -20.0, "close": -21.0,
    "toggle_on": -22.0, "toggle_off": -23.0, "error": -18.0, "launch": -18.0,
}
CEILING_DB = -1.0


def level(sig, rms_db):
    rms = math.sqrt(sum(x * x for x in sig) / len(sig)) or 1e-9
    g = 10 ** (rms_db / 20) / rms
    peak = max(abs(x) for x in sig) * g
    ceiling = 10 ** (CEILING_DB / 20)
    if peak > ceiling:
        g *= ceiling / peak
    return [x * g for x in sig]


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for pack, build in (("console", console), ("soft", soft), ("retro", retro)):
        sounds = build()
        assert set(sounds) == set(EVENTS), pack
        for event in EVENTS:
            sounds[event] = level(sounds[event], LEVELS[event])
            size = write(f"ui_{pack}_{event}", sounds[event])
            total += size
            print(f"{pack:8s} {event:11s} {len(sounds[event]) / SR * 1000:6.0f} ms  {size / 1024:5.1f} KB")
    print(f"total {total / 1024:.0f} KB")


if __name__ == "__main__":
    main()
