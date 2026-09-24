"""Save the approved greenhouse concept as a packed 8K Cycles scene."""

import sys
from pathlib import Path

import bpy


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "assets" / "greenhouse"
FINAL_BLEND = ASSETS / "commercial-tomato-greenhouse.blend"
RENDERS = ASSETS / "renders"
RENDERS.mkdir(parents=True, exist_ok=True)

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 64
scene.cycles.use_denoising = True
scene.render.resolution_x = 7680
scene.render.resolution_y = 4320
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"

exterior = bpy.data.objects["Physical 45mm review camera"]
interior = bpy.data.objects["Physical 35mm interior review camera"]
dew = bpy.data.collections["COL_Dew_Humidity_Overlay"]
dew.hide_render = False
scene.camera = exterior
scene.render.filepath = str(RENDERS / "commercial-tomato-greenhouse-exterior-8k.png")
scene["render_profile"] = "Cycles 64 samples, denoised, 7680x4320"
scene["dew_state"] = "humid; toggle COL_Dew_Humidity_Overlay.hide_render for dry"
scene["model_dimensions_m"] = "26 x 13; design assumption, not surveyed dimensions"

for image in bpy.data.images:
    if image.source == "FILE" and image.filepath:
        image.pack()

bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(FINAL_BLEND))
print(f"FINAL_SCENE {FINAL_BLEND}")

if "--render" in sys.argv:
    bpy.ops.render.render(write_still=True)
    print(f"FINAL_RENDER {scene.render.filepath}")
    scene.camera = interior
    scene.view_settings.exposure = 0.42
    scene.render.filepath = str(RENDERS / "commercial-tomato-greenhouse-interior-8k.png")
    bpy.ops.render.render(write_still=True)
    print(f"FINAL_RENDER {scene.render.filepath}")
