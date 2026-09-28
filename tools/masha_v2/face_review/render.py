"""Mouth/lip-sync review sheets (Blender 4.2, no MPFB needed).

    blender -b -P tools/masha_v2/face_review/render.py -- <out_dir> vis,visjaw,units,jaw,seq,range <masha.glb>
    blender -b <iter.blend with Masha_Head> -P tools/masha_v2/face_review/render.py -- <out_dir> vis

Sets (rsets.py): vis = each viseme alone; visjaw = each viseme + jawOpen 0.35; range = each viseme 0.8 + jawOpen
0.15/0.6; units = every mouth unit; jaw = jaw/tongue/seal checks; seq = word key poses (mamá, puedo, fuego, the,
cosa, hola, perro). Views: front, 3/4, side, sagittal cutaway. Combos are written with the runtime rules applied
(rsets.rt: rest-closure + seal). glbdiff.py: semantic GLB diff (python glbdiff.py old.glb new.glb)."""
import sys, os
sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bpy, rlib, rsets
argv = sys.argv[sys.argv.index("--") + 1:]
out = os.path.abspath(argv[0]); os.makedirs(out, exist_ok=True)
if len(argv) > 2 and argv[2].endswith(".glb"):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=argv[2])
    for o in bpy.data.objects:
        if o.animation_data: o.animation_data.action = None
        if o.type == "ARMATURE":
            for pb in o.pose.bones: pb.matrix_basis.identity()
    bpy.context.view_layer.update()
head = bpy.data.objects["Masha_Head"]
rlib.setup(head)
for s in argv[1].split(","):
    rsets.run(s, head, out)
