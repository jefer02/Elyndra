"""GLB post-processing for Masha's clips (owner: Agent 4). Pure Python (no bpy).

Blender's glTF exporter writes a channel for EVERY joint of an animated armature (translation, rotation
and scale), including the masha:* spring/twist/eye bones that the app drives itself. The runtime treats
any channel as "keyed" (MashaAnimatorFilament sentinel test), so those must go. prune_animation_channels()
removes the unwanted channels + samplers and repacks the binary chunk so their data really leaves the
file (unused accessors/bufferViews are dropped, the rest re-indexed, 4-byte aligned).

    import glb_tools
    stats = glb_tools.prune_animation_channels(path, keep=glb_tools.masha_keep)
"""
import json
import struct

GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


def read_glb(path):
    with open(path, "rb") as fh:
        data = fh.read()
    magic, version, length = struct.unpack_from("<III", data, 0)
    if magic != GLB_MAGIC:
        raise ValueError(f"{path}: not a GLB")
    off = 12
    js, binc = None, b""
    while off < length:
        clen, ctype = struct.unpack_from("<II", data, off)
        chunk = data[off + 8: off + 8 + clen]
        if ctype == CHUNK_JSON:
            js = json.loads(chunk.decode("utf-8"))
        elif ctype == CHUNK_BIN:
            binc = chunk
        off += 8 + clen
    return js, binc


def write_glb(path, js, binc):
    jb = json.dumps(js, separators=(",", ":")).encode("utf-8")
    jb += b" " * ((4 - len(jb) % 4) % 4)
    binc = binc + b"\0" * ((4 - len(binc) % 4) % 4)
    total = 12 + 8 + len(jb) + (8 + len(binc) if binc else 0)
    out = struct.pack("<III", GLB_MAGIC, 2, total) + struct.pack("<II", len(jb), CHUNK_JSON) + jb
    if binc:
        out += struct.pack("<II", len(binc), CHUNK_BIN) + binc
    with open(path, "wb") as fh:
        fh.write(out)
    return total


def masha_keep(node_name, path, anim_name):
    """Default policy: never key masha:* nodes; no scale; translation only on the Hips; everything else
    (mixamorig rotations, holotank objects) stays."""
    if node_name.startswith("masha:"):
        return False
    if node_name.startswith("mixamorig:"):
        if path == "scale":
            return False
        if path == "translation" and node_name != "mixamorig:Hips":
            return False
    return True


def _accessor_refs(js):
    """Every accessor index referenced outside animations + animation samplers."""
    refs = set()
    for m in js.get("meshes", []):
        for p in m.get("primitives", []):
            refs.update(p.get("attributes", {}).values())
            if "indices" in p:
                refs.add(p["indices"])
            for t in p.get("targets", []):
                refs.update(t.values())
    for s in js.get("skins", []):
        if "inverseBindMatrices" in s:
            refs.add(s["inverseBindMatrices"])
    for a in js.get("animations", []):
        for s in a["samplers"]:
            refs.add(s["input"])
            refs.add(s["output"])
    return refs


def prune_animation_channels(path, keep=masha_keep, out_path=None):
    js, binc = read_glb(path)
    nodes = js.get("nodes", [])
    stats = {"channels_before": 0, "channels_after": 0, "bytes_before": len(binc)}
    for a in js.get("animations", []):
        chans = a["channels"]
        stats["channels_before"] += len(chans)
        kept = [c for c in chans
                if keep(nodes[c["target"]["node"]].get("name", ""), c["target"]["path"], a.get("name", ""))]
        used = sorted({c["sampler"] for c in kept})
        remap = {old: new for new, old in enumerate(used)}
        a["samplers"] = [a["samplers"][i] for i in used]
        for c in kept:
            c["sampler"] = remap[c["sampler"]]
        a["channels"] = kept
        stats["channels_after"] += len(kept)
    js["animations"] = [a for a in js.get("animations", []) if a["channels"]]

    # --- drop unreferenced accessors, then unreferenced bufferViews; repack the BIN chunk
    acc = js.get("accessors", [])
    used_acc = sorted(_accessor_refs(js))
    amap = {old: new for new, old in enumerate(used_acc)}
    new_acc = [acc[i] for i in used_acc]
    view_refs = {a["bufferView"] for a in new_acc if "bufferView" in a}
    for a in new_acc:
        sp = a.get("sparse")
        if sp:
            view_refs.add(sp["indices"]["bufferView"])
            view_refs.add(sp["values"]["bufferView"])
    for im in js.get("images", []):
        if "bufferView" in im:
            view_refs.add(im["bufferView"])
    views = js.get("bufferViews", [])
    used_views = sorted(view_refs)
    vmap = {old: new for new, old in enumerate(used_views)}
    out = bytearray()
    new_views = []
    for i in used_views:
        v = dict(views[i])
        start = v.get("byteOffset", 0)
        chunk = binc[start: start + v["byteLength"]]
        out += b"\0" * ((4 - len(out) % 4) % 4)
        v["byteOffset"] = len(out)
        v["buffer"] = 0
        out += chunk
        new_views.append(v)
    for a in new_acc:
        if "bufferView" in a:
            a["bufferView"] = vmap[a["bufferView"]]
        sp = a.get("sparse")
        if sp:
            sp["indices"]["bufferView"] = vmap[sp["indices"]["bufferView"]]
            sp["values"]["bufferView"] = vmap[sp["values"]["bufferView"]]
    for im in js.get("images", []):
        if "bufferView" in im:
            im["bufferView"] = vmap[im["bufferView"]]

    def ra(i):
        return amap[i]
    for m in js.get("meshes", []):
        for p in m.get("primitives", []):
            p["attributes"] = {k: ra(v) for k, v in p.get("attributes", {}).items()}
            if "indices" in p:
                p["indices"] = ra(p["indices"])
            if "targets" in p:
                p["targets"] = [{k: ra(v) for k, v in t.items()} for t in p["targets"]]
    for s in js.get("skins", []):
        if "inverseBindMatrices" in s:
            s["inverseBindMatrices"] = ra(s["inverseBindMatrices"])
    for a in js.get("animations", []):
        for s in a["samplers"]:
            s["input"] = ra(s["input"])
            s["output"] = ra(s["output"])
    js["accessors"] = new_acc
    js["bufferViews"] = new_views
    if js.get("buffers"):
        js["buffers"] = [{"byteLength": len(out)}]
    stats["bytes_after"] = len(out)
    stats["file_bytes"] = write_glb(out_path or path, js, bytes(out))
    return stats


def animation_report(path):
    """{clip: {duration, channels by path, keyed node names, interpolations}} + animation byte count."""
    js, binc = read_glb(path)
    nodes, acc = js["nodes"], js["accessors"]
    size = {"VEC3": 12, "VEC4": 16, "SCALAR": 4}
    rep, total = {}, 0
    for a in js.get("animations", []):
        paths, names = {}, set()
        tmin, tmax = 1e9, 0.0
        for c in a["channels"]:
            paths[c["target"]["path"]] = paths.get(c["target"]["path"], 0) + 1
            names.add(nodes[c["target"]["node"]].get("name", "?"))
        for s in a["samplers"]:
            i, o = acc[s["input"]], acc[s["output"]]
            tmin, tmax = min(tmin, i["min"][0]), max(tmax, i["max"][0])
            total += i["count"] * 4 + o["count"] * size[o["type"]]
        rep[a["name"]] = {"t0": round(tmin, 4), "dur": round(tmax, 4), "channels": paths,
                          "nodes": len(names), "masha_nodes": sorted(n for n in names if n.startswith("masha:")),
                          "interp": sorted({s.get("interpolation", "LINEAR") for s in a["samplers"]})}
    return rep, total
