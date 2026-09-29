"""
Masha — ambiente sonoro del núcleo holográfico (lazo perfecto).

Sala de control / holotanque / núcleo de IA: zumbido eléctrico de nave,
campo de energía, pads graves que respiran, clics y pitidos de proceso de
datos y una reverberación espacial. Suave y en segundo plano: está pensado
para sonar *debajo* de la voz de Masha.

Por qué no hay chasquido al dar la vuelta:
  - Todos los tonos tienen un número entero de ciclos en el lazo (f = k / T).
  - El ruido se sintetiza en el dominio de la frecuencia (fases aleatorias →
    IFFT), así que es periódico por construcción.
  - Los eventos (pitidos, clics) se escriben módulo N: lo que empieza al final
    termina al principio.
  - La reverb es una convolución circular (FFT de tamaño N): su cola se pliega
    sobre el inicio en vez de cortarse.

Salida: app/src/main/res/raw/masha_ambient.ogg (Ogg Vorbis, estéreo, 44,1 kHz).

Uso:
  python tools/masha/generate_ambient.py [--seconds 96] [--out RUTA] [--wav]
Requiere: numpy, soundfile (pip install numpy soundfile).
"""

import argparse
import os
import sys

import numpy as np
import soundfile as sf

SR = 44100


def args():
    here = os.path.dirname(os.path.abspath(__file__))
    default = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "res", "raw", "masha_ambient.ogg"))
    p = argparse.ArgumentParser()
    p.add_argument("--seconds", type=float, default=96.0)
    p.add_argument("--out", default=default)
    p.add_argument("--seed", type=int, default=1701)
    p.add_argument("--wav", action="store_true", help="además, un WAV de referencia al lado")
    return p.parse_args()


class Loop:
    def __init__(self, seconds, seed):
        self.N = int(round(seconds * SR))
        self.T = self.N / SR
        self.t = np.arange(self.N) / SR
        self.rng = np.random.default_rng(seed)

    def q(self, f):
        """Frecuencia cuantizada a un número entero de ciclos en el lazo."""
        return max(1, round(f * self.T)) / self.T

    def sine(self, f, phase=0.0):
        return np.sin(2 * np.pi * self.q(f) * self.t + phase)

    def lfo(self, cycles, phase=0.0, lo=0.0, hi=1.0):
        """LFO con un número entero de ciclos en el lazo, entre lo y hi."""
        v = 0.5 + 0.5 * np.sin(2 * np.pi * cycles * self.t / self.T + phase)
        return lo + (hi - lo) * v

    def noise(self, shape):
        """Ruido periódico con el espectro de amplitud `shape(freqs)`."""
        freqs = np.fft.rfftfreq(self.N, 1 / SR)
        mag = shape(freqs)
        ph = self.rng.uniform(0, 2 * np.pi, len(freqs))
        spec = mag * np.exp(1j * ph)
        spec[0] = 0
        x = np.fft.irfft(spec, self.N)
        return x / (np.std(x) + 1e-12)


def band(lo, hi, slope=1.0):
    """Forma espectral de banda suave con caída 1/f^slope (ruido rosado-ish)."""
    def f(freqs):
        fr = np.maximum(freqs, 1.0)
        m = 1.0 / fr ** (slope / 2)
        m *= 1 / (1 + (lo / fr) ** 4)
        m *= 1 / (1 + (fr / hi) ** 4)
        return m
    return f


def db(x):
    return 10 ** (x / 20)


def hum(L):
    """Zumbido de nave: 55 Hz con armónicos, una copia desafinada que late y el siseo eléctrico impar."""
    out = np.zeros(L.N)
    for k, a in ((1, 1.0), (2, 0.55), (3, 0.32), (4, 0.2), (6, 0.08)):
        out += a * L.sine(55 * k, phase=k * 0.7)
        out += 0.6 * a * L.sine(55 * k + 10 / L.T, phase=k * 1.9)   # batido de 9,6 s
    buzz = sum(0.15 / k * L.sine(60 * k, phase=k) for k in (3, 5, 7, 9))
    out += buzz * L.lfo(4, 0.3, 0.3, 1.0)
    return out * L.lfo(3, 1.2, 0.8, 1.0)


def energy_field(L):
    """Campo de energía: banda de ruido 220–900 Hz que respira despacio, decorrelada en estéreo."""
    l = L.noise(band(220, 900, 1.0))
    r = L.noise(band(220, 900, 1.0))
    env = L.lfo(6, 0.0, 0.45, 1.0) * L.lfo(2, 2.0, 0.7, 1.0)
    shimmer = L.noise(band(3000, 9000, 0.5)) * 0.08 * L.lfo(5, 1.0, 0.0, 1.0) ** 3
    return np.stack([l * env + shimmer, r * env + shimmer])


def rumble(L):
    """Retumbar subgrave (30–70 Hz): da tamaño a la sala sin oírse como nota."""
    return L.noise(band(28, 70, 0.0)) * L.lfo(2, 0.5, 0.6, 1.0)


def pads(L):
    """
    Pads graves: acorde de Re menor 9 (D2 A2 F3 C4 E4), cada voz con parciales
    tipo sierra filtrada y un par de copias desafinadas (chorus). Brillo y
    volumen evolucionan con LFOs lentos, desfasados entre voces.
    """
    notes = (73.42, 110.0, 174.61, 261.63, 329.63)
    gains = (1.0, 0.8, 0.6, 0.45, 0.32)
    left = np.zeros(L.N)
    right = np.zeros(L.N)
    for i, (f0, g) in enumerate(zip(notes, gains)):
        swell = L.lfo(1 + i % 3, i * 1.3, 0.25, 1.0)
        bright = L.lfo(2, i * 0.9 + 1.0, 0.0, 1.0)
        pan = 0.5 + 0.35 * np.sin(i * 2.1)
        voice_l = np.zeros(L.N)
        voice_r = np.zeros(L.N)
        for k in range(1, 7):
            amp = (1.0 / k ** 1.5) * (1.0 if k <= 2 else bright ** (k - 2))
            for det, side in ((-0.18, 0), (0.0, 1), (0.21, 2)):
                s = amp * L.sine(f0 * k + det, phase=L.rng.uniform(0, 2 * np.pi))
                if side != 1:
                    voice_l += s * (1.0 if side == 0 else 0.5)
                if side != 0:
                    voice_r += s * (1.0 if side == 2 else 0.5)
        left += g * swell * voice_l * (1 - pan)
        right += g * swell * voice_r * pan
    return np.stack([left, right])


def place(buf, start, sig, pan):
    """Suma `sig` en `buf` a partir de `start`, módulo N (los eventos dan la vuelta)."""
    N = buf.shape[1]
    idx = (start + np.arange(len(sig))) % N
    buf[0, idx] += sig * np.sqrt(1 - pan)
    buf[1, idx] += sig * np.sqrt(pan)


def data_events(L):
    """
    Proceso de datos: ráfagas de pitidos cortos (1,5–4 kHz), clics de relé,
    barridos de escáner y algún latido grave de confirmación. Escasos: se
    notan como vida de la sala, no como ritmo.
    """
    buf = np.zeros((2, L.N))
    rng = L.rng
    t = 0.0
    while t < L.T:
        kind = rng.choice(["burst", "click", "sweep", "pulse"], p=[0.45, 0.35, 0.12, 0.08])
        start = int(t * SR)
        pan = rng.uniform(0.1, 0.9)
        if kind == "burst":
            n = rng.integers(2, 6)
            base = rng.choice([1568.0, 1760.0, 2093.0, 2349.0, 2637.0, 3136.0])
            gap = rng.uniform(0.045, 0.09)
            for j in range(n):
                dur = rng.uniform(0.018, 0.05)
                tt = np.arange(int(dur * SR)) / SR
                f = base * rng.choice([1.0, 1.25, 1.5, 2.0])
                env = np.exp(-tt / (dur * 0.35)) * np.minimum(1, tt / 0.002)
                place(buf, start + int(j * gap * SR), 0.22 * env * np.sin(2 * np.pi * f * tt), pan)
        elif kind == "click":
            dur = rng.uniform(0.002, 0.006)
            n = int(dur * SR)
            tt = np.arange(n) / SR
            click = rng.standard_normal(n) * np.exp(-tt / (dur * 0.3))
            click = np.convolve(click, np.ones(3) / 3, mode="same")
            place(buf, start, 0.25 * click, pan)
        elif kind == "sweep":
            dur = rng.uniform(0.18, 0.35)
            tt = np.arange(int(dur * SR)) / SR
            f0, f1 = rng.uniform(700, 1100), rng.uniform(2000, 3200)
            phase = 2 * np.pi * (f0 * tt + (f1 - f0) * tt ** 2 / (2 * dur))
            env = np.sin(np.pi * tt / dur) ** 2
            place(buf, start, 0.08 * env * np.sin(phase), pan)
        else:
            dur = 0.35
            tt = np.arange(int(dur * SR)) / SR
            env = np.exp(-tt / 0.09) * np.minimum(1, tt / 0.004)
            place(buf, start, 0.28 * env * np.sin(2 * np.pi * 98.0 * tt), 0.5)
        t += rng.exponential(1.6) + 0.25
    return buf


def impulse_response(L, seconds=3.4):
    """IR estéreo sintética: primeras reflexiones + cola exponencial que se oscurece."""
    n = int(seconds * SR)
    tt = np.arange(n) / SR
    rng = L.rng
    ir = np.zeros((2, n))
    for ch in range(2):
        tail = rng.standard_normal(n) * np.exp(-6.9 * tt / seconds)
        # Cada vez más oscura: filtro de un polo cuyo corte baja con el tiempo.
        out = np.zeros(n)
        y = 0.0
        for i in range(n):
            a = 0.25 + 0.7 * (i / n)
            y = (1 - a) * tail[i] + a * y
            out[i] = y
        for _ in range(8):
            d = int(rng.uniform(0.008, 0.06) * SR)
            out[d] += rng.uniform(0.3, 0.7) * rng.choice([-1, 1])
        ir[ch] = out
    return ir / np.max(np.abs(ir))


def circular_reverb(x, ir):
    """Convolución circular (tamaño N): la cola de la reverb se pliega sobre el inicio."""
    N = x.shape[1]
    out = np.zeros_like(x)
    for ch in range(2):
        h = np.zeros(N)
        h[: ir.shape[1]] = ir[ch]
        out[ch] = np.fft.irfft(np.fft.rfft(x[ch]) * np.fft.rfft(h), N)
    return out


def soft_limit(x, ceiling_db=-6.0):
    c = db(ceiling_db)
    return c * np.tanh(x / c)


def main():
    # La consola de Windows no siempre es UTF-8.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    a = args()
    L = Loop(a.seconds, a.seed)
    print(f"[ambient] {L.T:.2f} s, {L.N} muestras")

    h = hum(L)
    field = energy_field(L)
    rum = rumble(L)
    pad = pads(L)
    events = data_events(L)

    dry = np.zeros((2, L.N))
    dry += db(-26) * h / np.max(np.abs(h))
    dry += db(-31) * field / np.max(np.abs(field))
    dry += db(-28) * rum / np.max(np.abs(rum))
    dry += db(-21) * pad / np.max(np.abs(pad))
    ev = db(-27) * events / np.max(np.abs(events))

    ir = impulse_response(L)
    send = circular_reverb(0.5 * pad * db(-21) / np.max(np.abs(pad)) + ev, ir)
    send *= db(-12) / (np.max(np.abs(send)) + 1e-12)
    mix = dry + 0.55 * ev + send

    # Nivel: RMS ~ −24 dBFS (fondo tranquilo) y picos suaves por debajo de −6 dBFS.
    rms = np.sqrt(np.mean(mix ** 2))
    mix *= db(-24) / rms
    mix = soft_limit(mix, -6.0)

    # Comprobación del lazo: el salto final→inicio debe parecerse a un paso normal.
    step = np.abs(mix[:, 0] - mix[:, -1]).max()
    typical = np.percentile(np.abs(np.diff(mix, axis=1)), 99.5)
    print(f"[ambient] salto en el lazo {step:.5f} (p99.5 de un paso normal {typical:.5f})")
    assert step <= typical * 1.5, "el lazo no es continuo"

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    data = mix.T.astype(np.float32)
    # Por bloques: con una sola escritura enorme el codificador Vorbis de
    # libsndfile desborda la pila en Windows.
    with sf.SoundFile(a.out, "w", SR, 2, format="OGG", subtype="VORBIS") as f:
        for i in range(0, len(data), 65536):
            f.write(data[i:i + 65536])
    print(f"[ambient] → {a.out} ({os.path.getsize(a.out) / 1e6:.2f} MB)")
    if a.wav:
        wav = os.path.splitext(a.out)[0] + ".wav"
        sf.write(wav, data, SR, subtype="PCM_16")
        print(f"[ambient] → {wav}")


if __name__ == "__main__":
    main()
