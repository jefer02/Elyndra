"""Masha -> FBX to upload to Mixamo (so the clips come back on her exact skeleton and proportions).

  blender -b <masha_v2_hands.blend or masha_v2_export.blend> -P tools/masha_v2/anim/mixamo_upload.py -- <out.fbx>

What goes in the FBX:
  - Masha_Rig with only the standard "mixamorig:*" bones (Mixamo recognises the names and skips its
    auto-rigger). The extra "masha:*" bones (twist, breast, glute, hair, eye) are removed and their
    skin weights added to their parent bone, so the mesh still deforms correctly in Mixamo's preview.
  - Masha_Body (without MPFB helper geometry) and Masha_Head, no shape keys, no hair/eyes/cards.
  - Rest pose unchanged (A-pose); units metres (the FBX exporter writes centimetres, what Mixamo expects).
Nothing here touches the app model: the file is only a carrier for the skeleton.
"""
import os
import sys

import bpy


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    out = os.path.abspath(argv[0] if argv else "masha_mixamo.fbx")
    O = bpy.data.objects
    rig = O["Masha_Rig"]
    keep_meshes = {"Masha_Body", "Masha_Head"}

    for o in list(O):
        if o.type == "MESH" and o.name not in keep_meshes:
            bpy.data.objects.remove(o, do_unlink=True)
        elif o.type not in ("MESH", "ARMATURE"):
            bpy.data.objects.remove(o, do_unlink=True)

    bones = {b.name: b for b in rig.data.bones}
    extra = [n for n in bones if not n.startswith("mixamorig:")]

    def std_parent(name):
        b = bones[name].parent
        while b is not None and not b.name.startswith("mixamorig:"):
            b = b.parent
        return b.name if b else None

    target = {n: std_parent(n) for n in extra}

    for name in keep_meshes:
        me_ob = O[name]
        me_ob.shape_key_clear()
        for m in list(me_ob.modifiers):
            if m.type == "MASK":
                me_ob.modifiers.remove(m)
        # helper geometry off (the MPFB "body" group marks the visible skin)
        g = me_ob.vertex_groups.get("body")
        if g is not None:
            import bmesh
            gi = g.index
            keep = {v.index for v in me_ob.data.vertices if any(e.group == gi and e.weight > 0 for e in v.groups)}
            bm = bmesh.new()
            bm.from_mesh(me_ob.data)
            bm.verts.ensure_lookup_table()
            bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.index not in keep], context="VERTS")
            bm.to_mesh(me_ob.data)
            bm.free()
        vg = me_ob.vertex_groups
        for src, dst in target.items():
            s = vg.get(src)
            if s is None or dst is None:
                continue
            d = vg.get(dst) or vg.new(name=dst)
            for v in me_ob.data.vertices:
                for e in v.groups:
                    if e.group == s.index and e.weight > 0:
                        d.add([v.index], e.weight, "ADD")
            vg.remove(s)
        for grp in list(vg):
            if grp.name not in bones or grp.name in target:
                vg.remove(grp)
        print("MIXAMO mesh", name, "verts", len(me_ob.data.vertices))

    bpy.context.view_layer.objects.active = rig
    for o in O:
        o.select_set(False)
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    eb = rig.data.edit_bones
    for n in extra:
        if n in eb:
            eb.remove(eb[n])
    bpy.ops.object.mode_set(mode="OBJECT")
    if rig.animation_data:
        rig.animation_data_clear()
    for pb in rig.pose.bones:
        pb.matrix_basis.identity()
    print("MIXAMO bones", len(rig.data.bones), "removed", len(extra))

    for o in O:
        o.select_set(True)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    bpy.ops.export_scene.fbx(
        filepath=out, use_selection=True, object_types={"ARMATURE", "MESH"},
        apply_unit_scale=True, apply_scale_options="FBX_SCALE_NONE", axis_forward="-Z", axis_up="Y",
        add_leaf_bones=False, primary_bone_axis="Y", secondary_bone_axis="X", armature_nodetype="NULL",
        use_armature_deform_only=True, bake_anim=False, use_mesh_modifiers=False, mesh_smooth_type="FACE",
        path_mode="STRIP", embed_textures=False,
    )
    print(f"MIXAMO wrote {out} {os.path.getsize(out) / 1e6:.2f} MB")


main()
