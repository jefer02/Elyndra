# Review-sheet presets. rt() mirrors the runtime rules of face_contract.json "lipsync" (keep in sync).
import os
import rlib

VIS = ["viseme_aa", "viseme_E", "viseme_I", "viseme_O", "viseme_U", "viseme_PP", "viseme_FF", "viseme_TH",
       "viseme_DD", "viseme_kk", "viseme_CH", "viseme_SS", "viseme_nn", "viseme_RR"]
UNITS = ["jawOpen", "mouthClose", "mouthFunnel", "mouthPucker", "mouthRollLower", "mouthRollUpper", "mouthUpperUp",
         "mouthLowerDown", "mouthShrugLower", "mouthShrugUpper", "mouthStretch", "mouthDimple", "mouthPress",
         "mouthLeft", "mouthRight", "tongueOut", "mouthSmileLeft"]
RESTUNDO = {"mouthPucker": 1.0, "jawOpen": 0.6}
REST_FIX = 0.15 / 0.69



SEAL = {"viseme_PP": 1.0, "viseme_FF": 0.6}


def rt(keys):
    """What the runtime writes: rest-closure correction + lip seal for closed visemes."""
    k = dict(keys)
    U = sum(w for n, w in k.items() if n.startswith("viseme_")) + sum(RESTUNDO.get(n, 0) * w for n, w in k.items())
    add = 0.0
    if U > 1:
        add += REST_FIX * (U - 1)
    j = k.get("jawOpen", 0)
    add += j * sum(k.get(s, 0) * f for s, f in SEAL.items())
    if add:
        k["mouthClose"] = k.get("mouthClose", 0) + add
    return k


def lab(keys):
    return " ".join(n.replace("viseme_", "v").replace("mouth", "m") + f"{w:.2f}" for n, w in keys.items()) or "rest"


SEQ = {  # word -> list of (label, keys) key poses (runtime-like weights, jaw from the research table x JA 0.5)
    "mama": [("m", {"viseme_PP": 0.9, "jawOpen": 0.05}), ("a", {"viseme_aa": 0.7, "jawOpen": 0.45}),
             ("m", {"viseme_PP": 0.85, "viseme_aa": 0.1, "jawOpen": 0.12}), ("a", {"viseme_aa": 0.7, "jawOpen": 0.5})],
    "puedo": [("p", {"viseme_PP": 0.9, "viseme_U": 0.1, "jawOpen": 0.03}), ("ue", {"viseme_U": 0.45, "viseme_E": 0.45, "jawOpen": 0.2}),
              ("d", {"viseme_DD": 0.55, "viseme_E": 0.25, "jawOpen": 0.15}), ("o", {"viseme_O": 0.75, "jawOpen": 0.3})],
    "fuego": [("f", {"viseme_FF": 0.85, "viseme_U": 0.15, "jawOpen": 0.05}), ("ue", {"viseme_U": 0.4, "viseme_E": 0.5, "jawOpen": 0.25}),
              ("g", {"viseme_kk": 0.6, "viseme_E": 0.2, "jawOpen": 0.15}), ("o", {"viseme_O": 0.75, "jawOpen": 0.3})],
    "the": [("th", {"viseme_TH": 0.85, "jawOpen": 0.12}), ("e", {"viseme_E": 0.6, "viseme_TH": 0.15, "jawOpen": 0.25}),
            ("rest", {})],
    "cosa": [("k", {"viseme_kk": 0.6, "viseme_O": 0.3, "jawOpen": 0.15}), ("o", {"viseme_O": 0.75, "jawOpen": 0.3}),
             ("s", {"viseme_SS": 0.75, "viseme_O": 0.1, "jawOpen": 0.07}), ("a", {"viseme_aa": 0.7, "jawOpen": 0.45})],
    "hola": [("o", {"viseme_O": 0.75, "jawOpen": 0.3}), ("l", {"viseme_nn": 0.6, "viseme_O": 0.2, "jawOpen": 0.2}),
             ("a", {"viseme_aa": 0.7, "jawOpen": 0.45}), ("rest", {})],
    "perro": [("p", {"viseme_PP": 0.9, "jawOpen": 0.03}), ("e", {"viseme_E": 0.65, "jawOpen": 0.28}),
              ("rr", {"viseme_RR": 0.65, "viseme_E": 0.15, "jawOpen": 0.15}), ("o", {"viseme_O": 0.75, "jawOpen": 0.3})],
}


def run(name, head, out):
    tiles = os.path.join(out, "tiles_" + name)
    if name == "vis":
        specs = [("rest", {})] + [(n, {n: 1.0}) for n in VIS]
        rlib.render_grid(head, specs, ["front", "threeq", "side", "cut"], os.path.join(out, "sheet_visemes_alone.png"), tiles)
    elif name == "visjaw":
        specs = [(lab(rt({n: 1.0, "jawOpen": 0.35})), rt({n: 1.0, "jawOpen": 0.35})) for n in VIS]
        rlib.render_grid(head, specs, ["front", "threeq", "side", "cut"], os.path.join(out, "sheet_visemes_jaw035.png"), tiles)
    elif name == "units":
        specs = [(n, {n: 1.0}) for n in UNITS] + [("jawOpen0.35+mouthClose0.35", {"jawOpen": 0.35, "mouthClose": 0.35}),
                                                  ("tongueOut+jawOpen0.4", {"tongueOut": 1.0, "jawOpen": 0.4})]
        rlib.render_grid(head, specs, ["front", "threeq", "side", "cut"], os.path.join(out, "sheet_units.png"), tiles)
    elif name == "jaw":
        specs = [(lab(rt(k)), rt(k)) for k in ({"jawOpen": j} for j in (0.15, 0.35, 0.6, 1.0))]
        specs += [(lab(rt(k)), rt(k)) for k in ({"viseme_TH": 1.0, "jawOpen": j} for j in (0.0, 0.15, 0.6))]
        specs += [(lab(rt(k)), rt(k)) for k in ({"viseme_DD": 1.0, "jawOpen": j} for j in (0.15, 0.6))]
        specs += [(lab(rt(k)), rt(k)) for k in ({"viseme_PP": 1.0, "jawOpen": j} for j in (0.15, 0.35))]
        rlib.render_grid(head, specs, ["front", "threeq", "side", "cut"], os.path.join(out, "sheet_jaw_tongue.png"), tiles)
    elif name == "seq":
        specs = []
        for word, poses in SEQ.items():
            for i, (ph, k) in enumerate(poses):
                specs.append((f"{word} {i + 1}/{len(poses)} /{ph}/", rt(k)))
        for v in ("front", "threeq"):
            rlib.render_grid(head, specs, [v], os.path.join(out, f"sheet_sequences_{v}.png"), tiles + "_" + v, cols=4, rows_per_page=8)
    elif name == "range":
        specs = []
        for n in VIS:
            for j in (0.15, 0.6):
                k = rt({n: 0.8, "jawOpen": j})
                specs.append((lab(k), k))
        rlib.render_grid(head, specs, ["front", "threeq"], os.path.join(out, "sheet_visemes_jaw015_060.png"), tiles, cols=4, rows_per_page=7)
    elif name == "focus":
        specs = []
        for n in ("viseme_FF", "viseme_TH", "viseme_DD", "viseme_O", "viseme_aa"):
            specs += [(n, {n: 1.0}), (lab(rt({n: 1.0, "jawOpen": 0.35})), rt({n: 1.0, "jawOpen": 0.35}))]
        rlib.render_grid(head, specs, ["front", "threeq", "side", "cut"], os.path.join(out, "focus.png"), tiles)
    elif name == "quick":
        specs = [("rest", {})] + [(n, {n: 1.0}) for n in VIS]
        rlib.render_grid(head, specs, ["front"], os.path.join(out, "quick_front.png"), tiles, cols=5, res=(320, 260))
        rlib.render_grid(head, specs, ["side"], os.path.join(out, "quick_side.png"), tiles + "_s", cols=5, res=(320, 260))
