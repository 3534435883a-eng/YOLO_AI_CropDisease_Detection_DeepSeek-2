# Commercial Tomato Greenhouse

This directory contains the editable Blender source, Cycles review renders, and the
runtime GLB assets used by the digital-twin page.

## Blender source

- `commercial-tomato-greenhouse.blend` is the packed final scene.
- Units are meters. The 26 m x 13 m footprint is a design assumption for the
  simulation, not a surveyed construction drawing.
- `Physical 45mm review camera` is the exterior camera.
- `Physical 35mm interior review camera` is the aisle camera with depth of field.
- The scene uses Cycles, 64 samples, denoising, and a 7680 x 4320 output profile.
- `COL_Dew_Humidity_Overlay` contains the water droplets. Set its
  `hide_render` flag to switch between humid/dew and dry-leaf states.

## Materials and detail

The scene includes compound tomato leaves with SSS and procedural vein noise,
occasional aging edges, matte micro-bumped tomatoes with sparse spots, a dusty
and lightly scratched polyethylene film using Fresnel response, wet soil, varied
gravel, sparse aisle weeds, and IoT temperature/humidity sensors.

## Review renders

The `review/` folder contains 1920 x 1080 comparisons, including humid and dry
aisle views. The `renders/` folder contains the approved 8K exterior and interior
Cycles renders when the render job has completed.

## Web runtime

`../../YOLO_AI_CropDisease_Detection_Vue/public/models/greenhouse/` contains the
optimized structure and equipment GLBs. The Vue digital-twin page keeps the
procedural, data-driven tomato canopy so growth, fruit count, disease overlays,
and sensor state remain interactive at runtime.
