"""Rewrite a GLB so every sparse morph-target accessor becomes Filament-safe.

Blender's glTF exporter (export_try_sparse_sk=True) writes most shape-key deltas as
sparse accessors *without* a bufferView (implicit all-zero base). Filament 1.56's
gltfio does not apply those to the mesh positions on device: the weights change
lighting a bit but the face does not deform (found in lip-sync QA, 2026-09-27).

Modes:
  dense  – expand each sparse morph accessor into a plain dense accessor.
  shared – keep it sparse but give it a bufferView pointing to one shared block of
           zeros (glTF allows accessors to share a bufferView); much smaller.

Only morph-target accessors (mesh.primitives[].targets) are touched; everything else
(nodes, skin, animations, materials, textures, base geometry) is copied as is.

usage: python densify_morphs.py in.glb out.glb [shared|dense]   (default shared; export.py runs it)
"""
import json
import struct
import sys

COMP = {5126: ("f", 4), 5123: ("H", 2), 5125: ("I", 4), 5121: ("B", 1), 5120: ("b", 1), 5122: ("h", 2)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def read_glb(path):
    b = open(path, "rb").read()
    magic, ver, _ = struct.unpack("<III", b[:12])
    assert magic == 0x46546C67 and ver == 2
    jl = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + jl])
    off = 20 + jl
    binl = struct.unpack("<I", b[off:off + 4])[0]
    return j, bytearray(b[off + 8:off + 8 + binl])


def write_glb(path, j, bin_):
    while len(bin_) % 4:
        bin_ += b"\0"
    js = json.dumps(j, separators=(",", ":")).encode()
    while len(js) % 4:
        js += b" "
    total = 12 + 8 + len(js) + 8 + len(bin_)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(bin_), 0x004E4942) + bytes(bin_))


def view_bytes(j, bin_, vi):
    v = j["bufferViews"][vi]
    o = v.get("byteOffset", 0)
    return bin_[o:o + v["byteLength"]]


def append(j, bin_, data):
    while len(bin_) % 4:
        bin_ += b"\0"
    o = len(bin_)
    bin_ += data
    j["bufferViews"].append({"buffer": 0, "byteOffset": o, "byteLength": len(data)})
    return len(j["bufferViews"]) - 1


def dense_floats(j, bin_, acc):
    n = acc["count"] * NCOMP[acc["type"]]
    vals = [0.0] * n
    if "bufferView" in acc:
        raw = view_bytes(j, bin_, acc["bufferView"])
        o = acc.get("byteOffset", 0)
        vals = list(struct.unpack_from("<%df" % n, raw, o))
    sp = acc["sparse"]
    it, iv = sp["indices"], sp["values"]
    fmt, size = COMP[it["componentType"]]
    idx = struct.unpack_from("<%d%s" % (sp["count"], fmt), view_bytes(j, bin_, it["bufferView"]), it.get("byteOffset", 0))
    k = NCOMP[acc["type"]]
    vv = struct.unpack_from("<%df" % (sp["count"] * k), view_bytes(j, bin_, iv["bufferView"]), iv.get("byteOffset", 0))
    for s, i in enumerate(idx):
        vals[i * k:(i + 1) * k] = vv[s * k:(s + 1) * k]
    return vals


def main():
    rewrite(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "shared")


def rewrite(src, dst, mode="shared"):
    j, bin_ = read_glb(src)
    morph_accs = set()
    for m in j["meshes"]:
        for p in m["primitives"]:
            for t in p.get("targets", []):
                morph_accs.update(t.values())
    zero_view = {}
    changed = 0
    for ai in sorted(morph_accs):
        acc = j["accessors"][ai]
        if "sparse" not in acc:
            continue
        assert acc["componentType"] == 5126, "only float morph targets"
        if mode == "dense":
            vals = dense_floats(j, bin_, acc)
            acc["bufferView"] = append(j, bin_, struct.pack("<%df" % len(vals), *vals))
            acc.pop("byteOffset", None)
            del acc["sparse"]
        else:
            if "bufferView" in acc:
                continue
            nbytes = acc["count"] * NCOMP[acc["type"]] * 4
            if nbytes not in zero_view:
                zero_view[nbytes] = append(j, bin_, bytes(nbytes))
            acc["bufferView"] = zero_view[nbytes]
        changed += 1
    j["buffers"][0]["byteLength"] = len(bin_) + (-len(bin_)) % 4
    write_glb(dst, j, bin_)
    print(f"{changed} morph accessors rewritten ({mode}); bin {len(bin_) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
