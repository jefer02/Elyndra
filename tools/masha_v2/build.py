"""Masha v2 build entry point.

  blender -b -P build.py -- --stages body,face,hands,shoulders,export --qa 1 --out <dir> --quality high [--bodymod body_frozen]

Stages run in order; each module exposes build(ctx). A stage can be skipped for iteration,
but later stages assume earlier ones ran. Saves <out>/masha_v2_<last stage>.blend.
"""
import importlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import bpy  # noqa: E402
import common  # noqa: E402

ORDER = ["body", "face", "hands", "shoulders", "export"]


def main():
    args = common.parse_args({"stages": "body,face,hands,shoulders,export", "qa": "1",
                              "out": os.path.join(HERE, "out"), "quality": "high",
                              "bodymod": "body"})
    stages = [s for s in ORDER if s in args["stages"].split(",")]
    ctx = {"out_dir": common.ensure_dir(args["out"]),
           "qa_dir": common.ensure_dir(os.path.join(args["out"], "qa")),
           "quality": args["quality"], "soft_bones": {}, "hand_poses": {}, "face_shapes": []}
    common.clear_scene()
    for name in stages:
        mod = importlib.import_module(args["bodymod"] if name == "body" else name)
        importlib.reload(mod)
        print(f"==== STAGE {name}")
        mod.build(ctx)
        common.report(ctx, name)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(ctx["out_dir"], f"masha_v2_{stages[-1]}.blend"))
    if args["qa"] == "1" and hasattr(importlib.import_module(stages[-1]), "qa"):
        importlib.import_module(stages[-1]).qa(ctx)


main()
