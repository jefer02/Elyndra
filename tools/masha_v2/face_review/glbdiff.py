"""Semantic GLB diff: python glbdiff.py old.glb new.glb [--tol 0]
Compares everything by resolved *content* (accessor data decoded, nodes/skins/anims matched by name),
so re-indexing of accessors/bufferViews does not count as a difference.
Masha_Head morph targets are compared by target NAME (so inserted targets don't shift the comparison)."""
import json, struct, sys, hashlib
import numpy as np

CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}


def read(path):
    b = open(path, "rb").read()
    off, js, binc = 12, None, b""
    while off < len(b):
        n, t = struct.unpack_from("<II", b, off)
        c = b[off + 8: off + 8 + n]
        if t == 0x4E4F534A:
            js = json.loads(c)
        else:
            binc = c
        off += 8 + n
    return js, binc, len(b)


def acc(js, binc, i):
    a = js["accessors"][i]
    dt, nc, cnt = CT[a["componentType"]], NC[a["type"]], a["count"]
    out = np.zeros((cnt, nc), dtype=dt)
    if "bufferView" in a:
        bv = js["bufferViews"][a["bufferView"]]
        st = bv.get("byteStride", 0)
        base = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        es = np.dtype(dt).itemsize * nc
        if st and st != es:
            for k in range(cnt):
                out[k] = np.frombuffer(binc, dt, nc, base + k * st)
        else:
            out = np.frombuffer(binc, dt, cnt * nc, base).reshape(cnt, nc).copy()
    sp = a.get("sparse")
    if sp:
        ii = sp["indices"]; vv = sp["values"]
        bi = js["bufferViews"][ii["bufferView"]]; bvv = js["bufferViews"][vv["bufferView"]]
        idx = np.frombuffer(binc, CT[ii["componentType"]], sp["count"], bi.get("byteOffset", 0) + ii.get("byteOffset", 0))
        val = np.frombuffer(binc, dt, sp["count"] * nc, bvv.get("byteOffset", 0) + vv.get("byteOffset", 0)).reshape(-1, nc)
        out[idx.astype(np.int64)] = val
    return out


def h(x):
    return hashlib.md5(np.ascontiguousarray(x).tobytes()).hexdigest()[:10]


def summarize(path):
    js, binc, size = read(path)
    S = {"size": size, "nodes": {}, "meshes": {}, "skins": {}, "anims": {}, "materials": {}, "images": [], "morph": {}}
    nodes = js["nodes"]
    for n in nodes:
        d = {k: n.get(k) for k in ("translation", "rotation", "scale", "matrix")}
        d["children"] = sorted(nodes[c].get("name", "") for c in n.get("children", []))
        d["mesh"] = js["meshes"][n["mesh"]]["name"] if "mesh" in n else None
        d["skin"] = n.get("skin")
        S["nodes"][n.get("name")] = d
    for m in js["meshes"]:
        names = m.get("extras", {}).get("targetNames", [])
        prims = []
        for p in m["primitives"]:
            pd = {"material": js["materials"][p["material"]]["name"] if "material" in p else None,
                  "mode": p.get("mode", 4), "attrs": {k: h(acc(js, binc, v)) for k, v in p["attributes"].items()},
                  "indices": h(acc(js, binc, p["indices"])) if "indices" in p else None,
                  "ntargets": len(p.get("targets", []))}
            tg = {}
            for ti, t in enumerate(p.get("targets", [])):
                nm = names[ti] if ti < len(names) else str(ti)
                tg[nm] = {k: acc(js, binc, v) for k, v in t.items()}
            pd["targets"] = tg
            prims.append(pd)
        S["meshes"][m["name"]] = {"prims": prims, "targetNames": names, "weights": m.get("weights")}
    for s in js.get("skins", []):
        S["skins"][s.get("name", "skin")] = {"joints": [nodes[j].get("name") for j in s["joints"]],
                                             "ibm": h(acc(js, binc, s["inverseBindMatrices"])),
                                             "skeleton": s.get("skeleton")}
    for a in js.get("animations", []):
        ch = {}
        for c in a["channels"]:
            smp = a["samplers"][c["sampler"]]
            key = (nodes[c["target"]["node"]].get("name"), c["target"]["path"])
            ch[key] = (smp.get("interpolation"), h(acc(js, binc, smp["input"])), h(acc(js, binc, smp["output"])))
        S["anims"][a["name"]] = ch
    for m in js["materials"]:
        S["materials"][m["name"]] = json.dumps(m, sort_keys=True)
    S["images"] = [json.dumps(i, sort_keys=True) for i in js.get("images", [])]
    S["other"] = {k: js.get(k) for k in ("scene", "scenes", "extensionsUsed", "extensionsRequired", "samplers", "textures")}
    return S


def main():
    a, b = sys.argv[1], sys.argv[2]
    tol = float(sys.argv[sys.argv.index("--tol") + 1]) if "--tol" in sys.argv else 0.0
    A, B = summarize(a), summarize(b)
    bad = []
    print(f"size {A['size']} -> {B['size']} ({(B['size'] - A['size']) / 1e6:+.3f} MB)")
    for sec in ("nodes", "skins", "anims", "materials"):
        ka, kb = set(A[sec]), set(B[sec])
        if ka != kb:
            bad.append(f"{sec}: keys differ -{sorted(ka - kb)} +{sorted(kb - ka)}")
        diff = [k for k in ka & kb if A[sec][k] != B[sec][k]]
        print(f"{sec}: {len(ka)} vs {len(kb)}; differing: {len(diff)}")
        for k in diff[:10]:
            bad.append(f"{sec}[{k}] differs")
    if A["images"] != B["images"]:
        bad.append("images differ")
    if A["other"] != B["other"]:
        bad.append("scenes/samplers/textures/extensions differ")
    for mn in sorted(set(A["meshes"]) | set(B["meshes"])):
        ma, mb = A["meshes"].get(mn), B["meshes"].get(mn)
        if ma is None or mb is None:
            bad.append(f"mesh {mn} missing on one side")
            continue
        if len(ma["prims"]) != len(mb["prims"]):
            bad.append(f"mesh {mn} prim count differs")
            continue
        tn_a, tn_b = ma["targetNames"], mb["targetNames"]
        if tn_a or tn_b:
            print(f"mesh {mn}: targets {len(tn_a)} -> {len(tn_b)}; ntargets per prim {[p['ntargets'] for p in mb['prims']]}")
            print("   added:", [n for n in tn_b if n not in tn_a])
            print("   removed:", [n for n in tn_a if n not in tn_b])
        for pi, (pa, pb) in enumerate(zip(ma["prims"], mb["prims"])):
            for k in ("material", "mode", "attrs", "indices"):
                if pa[k] != pb[k]:
                    bad.append(f"mesh {mn} prim {pi} ({pa['material']}) {k} differs: {pa[k]} vs {pb[k]}")
            changed = []
            for tn in pa["targets"]:
                if tn not in pb["targets"]:
                    continue
                for attr, va in pa["targets"][tn].items():
                    vb = pb["targets"][tn].get(attr)
                    if vb is None or va.shape != vb.shape:
                        changed.append((tn, attr, "shape"))
                        continue
                    d = float(np.abs(va.astype(np.float64) - vb).max()) if va.size else 0.0
                    if d > tol:
                        changed.append((tn, attr, round(d * 1000, 3)))
            if changed:
                print(f"   {mn} prim {pi} ({pa['material']}) changed targets (name, attr, max diff mm/units*1e3):")
                for c in changed:
                    print("      ", c)
    print("\nNON-HEAD-MORPH DIFFERENCES:" if bad else "\nno differences outside morph targets")
    for x in bad:
        print("  ", x)


main()
