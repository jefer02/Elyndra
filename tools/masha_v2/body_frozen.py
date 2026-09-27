"""Stage 1 - body (owner: Agent 3). v0 baseline written by the orchestrator.

Creates the MPFB human, bakes macros, adds the mixamo rig and the CC0 system assets, and
renames everything to the stable object names the rest of the pipeline relies on:
  Masha_Body, Masha_Rig, Masha_Eyes, Masha_Brows, Masha_Lashes, Masha_Hair, Masha_Teeth, Masha_Tongue
"""
import bpy

import common

MACRO = {"gender": 0.0, "age": 0.52, "muscle": 0.5, "weight": 0.45, "proportions": 1.0,
         "height": 0.55, "cupsize": 0.5, "firmness": 0.6,
         "race": {"asian": 0.33, "caucasian": 0.34, "african": 0.33}}

# (subdir, mhclo, MPFB asset_type, ctx key, object name)
ASSETS = [
    ("eyes", "low-poly.mhclo", "Eyes", "eyes", "Masha_Eyes"),
    ("eyebrows", "eyebrow001.mhclo", "Eyebrows", "brows", "Masha_Brows"),
    ("eyelashes", "eyelashes01.mhclo", "Eyelashes", "lashes", "Masha_Lashes"),
    ("teeth", "teeth_base.mhclo", "Teeth", "teeth", "Masha_Teeth"),
    ("tongue", "tongue01.mhclo", "Tongue", "tongue", "Masha_Tongue"),
    ("hair", "ponytail01.mhclo", "Hair", "hair", "Masha_Hair"),
]


def build(ctx):
    HumanService = common.mpfb("HumanService")
    TargetService = common.mpfb("TargetService")
    AssetService = common.mpfb("AssetService")

    body = HumanService.create_human(mask_helpers=True, detailed_helpers=True, extra_vertex_groups=True,
                                     feet_on_ground=True, scale=0.1, macro_detail_dict=MACRO)
    # Freeze the shape before rigging/fitting so macro keys never reach the GLB as morph targets.
    TargetService.bake_targets(body)
    rig = HumanService.add_builtin_rig(body, "mixamo")

    new = {}
    before = set(bpy.data.objects)
    for sub, fname, atype, key, obj_name in ASSETS:
        path = AssetService.find_asset_absolute_path(fname, asset_subdir=sub)
        if path is None:
            raise RuntimeError(f"missing MPFB asset {sub}/{fname}")
        HumanService.add_mhclo_asset(path, body, asset_type=atype, subdiv_levels=0, material_type="GAMEENGINE")
        added = [o for o in bpy.data.objects if o not in before and o.type == "MESH"]
        before = set(bpy.data.objects)
        obj = added[0]
        obj.name = obj_name
        obj.data.name = obj_name
        new[key] = obj

    body.name = body.data.name = "Masha_Body"
    rig.name = "Masha_Rig"
    rig.data.name = "Masha_Rig"
    ctx.update(body=body, rig=rig, **new)


def qa(ctx):
    common.qa_renders(ctx["qa_dir"], "body")
