"""Build / QA Masha's clips without running the whole v2 build (owner: Agent 4).

    blender -b <masha_v2_hands.blend> -P tools/masha_v2/anim/build_anim.py -- --out <dir>
            [--base <base_pose.json>] [--clips Wave,Point] [--qa 1] [--render 1] [--pen 1] [--glb 1]

  --qa 1      numbers per clip (qa.numeric) -> <out>/qa/anim_qa.json + printed table
  --pen 1     BVH penetration on the evaluated mesh at 8 sampled frames per clip
  --render 1  Workbench strips (8 frames; front + 3/4 right rows) -> <out>/qa/<clip>.png + contact sheets
  --glb 1     full export stage (export.build) into <out> (TEST GLB: masha.glb + masha_lite.glb), then a
              clean re-import check of the clips (names, durations, fps, keyed nodes, size)
The Mixamo FBX files are read in place (MASHA_MIXAMO_DIR); nothing is written next to them or in the repo.
"""
import json
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
V2 = os.path.dirname(HERE)
for p in (HERE, V2):
    if p not in sys.path:
        sys.path.insert(0, p)

import clips  # noqa: E402
import qa  # noqa: E402
import rigmath  # noqa: E402

DEFAULT_BASE = os.environ.get("MASHA_BASE_POSE",
                              r"C:/Users/jefer/Documents/Masha_Mixamo/trabajo/base_pose.json")


def args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = {"out": os.path.join(V2, "out_anim"), "base": DEFAULT_BASE, "clips": "", "qa": "1",
           "render": "0", "pen": "0", "glb": "0", "views": "front,34r"}
    for i in range(0, len(argv) - 1, 2):
        out[argv[i].lstrip("-")] = argv[i + 1]
    return out


def load_json(p):
    with open(p) as fh:
        return json.load(fh)


def main():
    a = args()
    out = os.path.abspath(a["out"])
    qdir = os.path.join(out, "qa")
    os.makedirs(qdir, exist_ok=True)
    rig = bpy.data.objects["Masha_Rig"]
    body = bpy.data.objects["Masha_Body"]
    base = load_json(a["base"])
    hp = load_json(os.path.join(V2, "hand_poses.json"))
    lib = clips.Library(rig, base, hp, body=body)
    names = [n for n in a["clips"].split(",") if n] or None
    made = lib.build(names)

    report = {}
    if a["qa"] == "1":
        pen = qa.Penetration(body) if a["pen"] == "1" else None
        for c in made:
            r = qa.numeric(lib.model, c.qa_dict(), lib.base)
            r["seconds"] = round(c.seconds, 3)
            r["loop"] = c.loop
            r["source"] = c.source
            if pen is not None:
                idx = sorted({round(k * (len(c.frames) - 1) / 7) for k in range(8)} |
                             {round(k * (len(c.frames) - 1) / 23) for k in range(24)})
                worst = {}
                for i in idx:
                    qa.show_pose(rig, c.frames[i])
                    m = pen.measure(rig)
                    for k, v in m.items():
                        if k not in worst or v < worst[k][0]:
                            worst[k] = (round(v, 1), round(i / clips.FPS, 2))
                r["pen_mm"] = worst
            report[c.name] = r
            print(f"QA {c.name}: " + json.dumps(r))
        with open(os.path.join(qdir, "anim_qa.json"), "w") as fh:
            json.dump(report, fh, indent=1)

    if a["render"] == "1":
        views = tuple(a["views"].split(","))
        pngs = []
        for c in made:
            n = len(c.frames)
            idx = [round(k * (n - 1) / 7) for k in range(8)]
            p = qa.render_strip(rig, c.frames, idx, os.path.join(qdir, f"{c.name}.png"), views=views)
            pngs.append(p)
            print("QA render", p)
        # contact sheets of 4 clips each
        for k in range(0, len(pngs), 4):
            qa.contact_sheet(pngs[k:k + 4], os.path.join(qdir, f"sheet_{k // 4 + 1:02d}.png"))
        rigmath.apply_to_bpy(rig, lib.base)

    if a["glb"] == "1":
        import export
        ctx = {"out_dir": out, "rig": rig, "body": body, "hand_poses": hp["poses"], "anim_clips": made,
               "base_pose": base}
        # authored joint positions (armature space) at sample frames, for the round-trip check
        joints = [f"mixamorig:{b}" for b in ("Hips", "Head", "LeftHand", "RightHand", "LeftFoot", "RightFoot",
                                              "RightHandIndex3", "LeftToeBase")]
        ref = {}
        for c in made:
            n = len(c.frames)
            ref[c.name] = {i: {j: tuple(lib.model.fk(c.frames[i])[j].translation) for j in joints}
                           for i in sorted({0, n // 3, n // 2, (2 * n) // 3, n - 1})}
        export.build(ctx)
        check_glb(os.path.join(out, "masha.glb"), qdir, ref)


def check_glb(path, qdir, ref=None):
    """Raw glTF read (clip names, durations, channels, keyed nodes, size) + clean-scene re-import."""
    import glb_tools
    rep, anim_bytes = glb_tools.animation_report(path)
    res = {"size_mb": round(os.path.getsize(path) / 1e6, 3), "anim_bytes_mb": round(anim_bytes / 1e6, 3),
           "clips": rep}
    for k, v in rep.items():
        print(f"GLBCHECK {k}: t0={v['t0']} dur={v['dur']} channels={v['channels']} nodes={v['nodes']} "
              f"masha={v['masha_nodes']} interp={v['interp']}")
    print(f"GLBCHECK size {res['size_mb']} MB, animation data {res['anim_bytes_mb']} MB")
    with open(os.path.join(qdir, "glb_check.json"), "w") as fh:
        json.dump(res, fh, indent=1)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.fps, sc.render.fps_base = 30, 1.0
    bpy.ops.import_scene.gltf(filepath=path)
    acts = {a.name: a for a in bpy.data.actions}
    print("GLBCHECK reimport ok:", len(acts), "actions")
    if not ref:
        return
    from mathutils import Vector
    arm = next(o for o in bpy.data.objects if o.type == "ARMATURE")
    arm.animation_data_create()
    for tr in list(arm.animation_data.nla_tracks):
        arm.animation_data.nla_tracks.remove(tr)
    worst = {}
    for clip, frames in ref.items():
        act = next((a for n, a in acts.items() if n.startswith(clip + "_") or n == clip), None)
        if act is None:
            print("GLBCHECK missing action for", clip)
            continue
        arm.animation_data.action = act
        err = 0.0
        for f, js in frames.items():
            sc.frame_set(f)
            bpy.context.view_layer.update()
            for j, p in js.items():
                w = arm.matrix_world @ arm.pose.bones[j].head
                err = max(err, (w - Vector(p)).length)
        worst[clip] = round(err * 1000, 3)
    res["roundtrip_joint_err_mm"] = worst
    print("GLBCHECK round-trip max joint error (mm):", worst)
    with open(os.path.join(qdir, "glb_check.json"), "w") as fh:
        json.dump(res, fh, indent=1)


if __name__ == "__main__":
    main()
