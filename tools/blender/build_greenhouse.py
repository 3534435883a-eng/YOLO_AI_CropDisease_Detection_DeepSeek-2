import math
from pathlib import Path

import bpy
from mathutils import Matrix, Vector


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "assets" / "greenhouse" / "greenhouse-v2.blend"
EXPORT = ROOT / "YOLO_AI_CropDisease_Detection_Vue" / "public" / "models" / "greenhouse"
LENGTH = 26.0
WIDTH = 13.0
EAVE = 4.0
ARCH_RISE = 1.7
BED_CENTERS = (-4.4, -2.25, 2.25, 4.4)
BAY_CENTERS = (-3.25, 3.25)


def position(x, height, across):
    return Vector((x, -across, height))


def color(hex_value):
    channels = [int(hex_value[index:index + 2], 16) / 255 for index in (0, 2, 4)]
    return tuple(((channel + 0.055) / 1.055) ** 2.4 if channel > 0.04045 else channel / 12.92 for channel in channels) + (1.0,)


def material(name, hex_value, metallic=0.0, roughness=0.5, emission=0.0):
    result = bpy.data.materials.new(name)
    result.diffuse_color = color(hex_value)
    result.use_nodes = True
    shader = result.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = color(hex_value)
    shader.inputs["Metallic"].default_value = metallic
    shader.inputs["Roughness"].default_value = roughness
    if emission:
        shader.inputs["Emission Color"].default_value = color(hex_value)
        shader.inputs["Emission Strength"].default_value = emission
    return result


def collection(name):
    result = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(result)
    return result


def place(object_ref, target_collection, parent=None):
    for old_collection in tuple(object_ref.users_collection):
        old_collection.objects.unlink(object_ref)
    target_collection.objects.link(object_ref)
    if parent is not None:
        world_matrix = object_ref.matrix_world.copy()
        object_ref.parent = parent
        object_ref.matrix_world = world_matrix
    return object_ref


def empty(name, center, target_collection, parent=None):
    result = bpy.data.objects.new(name, None)
    target_collection.objects.link(result)
    result.matrix_world = Matrix.Translation(position(*center))
    if parent is not None:
        world_matrix = result.matrix_world.copy()
        result.parent = parent
        result.matrix_world = world_matrix
    return result


def box(name, center, size, surface, target_collection, parent=None, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=position(*center))
    result = bpy.context.object
    result.name = name
    result.dimensions = (size[0], size[2], size[1])
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    result.data.materials.append(surface)
    if bevel:
        modifier = result.modifiers.new("Machined edges", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
        result.modifiers.new("Weighted normals", "WEIGHTED_NORMAL")
    return place(result, target_collection, parent)


def cylinder(name, start, end, radius, surface, target_collection, parent=None, vertices=12):
    start_point = position(*start)
    end_point = position(*end)
    direction = end_point - start_point
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=direction.length, location=(start_point + end_point) / 2)
    result = bpy.context.object
    result.name = name
    result.rotation_euler = direction.to_track_quat("Z", "Y").to_euler()
    result.data.materials.append(surface)
    return place(result, target_collection, parent)


def tube(name, path, radius, surface, target_collection, parent=None, sides=8):
    vertices = []
    faces = []
    points = [position(*point) for point in path]
    for index, point in enumerate(points):
        tangent = (points[min(index + 1, len(points) - 1)] - points[max(index - 1, 0)]).normalized()
        lateral = tangent.cross(Vector((1, 0, 0)))
        if lateral.length < 0.01:
            lateral = tangent.cross(Vector((0, 1, 0)))
        lateral.normalize()
        vertical = tangent.cross(lateral).normalized()
        for side in range(sides):
            angle = side * math.tau / sides
            vertices.append(point + radius * (lateral * math.cos(angle) + vertical * math.sin(angle)))
        if index:
            for side in range(sides):
                next_side = (side + 1) % sides
                faces.append(((index - 1) * sides + side, (index - 1) * sides + next_side, index * sides + next_side, index * sides + side))
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    result = bpy.data.objects.new(name, mesh)
    target_collection.objects.link(result)
    result.data.materials.append(surface)
    for polygon in result.data.polygons:
        polygon.use_smooth = True
    if parent is not None:
        world_matrix = result.matrix_world.copy()
        result.parent = parent
        result.matrix_world = world_matrix
    return result


def ring(name, center, radius, surface, target_collection, parent=None):
    path = []
    for index in range(33):
        angle = index * math.tau / 32
        path.append((center[0], center[1] + radius * math.sin(angle), center[2] + radius * math.cos(angle)))
    return tube(name, path, 0.025, surface, target_collection, parent, 6)


def mesh(name, points, faces, surface, target_collection, parent=None):
    vertex_data = [position(*point) for point in points]
    mesh_data = bpy.data.meshes.new(name)
    mesh_data.from_pydata(vertex_data, [], faces)
    mesh_data.update()
    result = bpy.data.objects.new(name, mesh_data)
    target_collection.objects.link(result)
    result.data.materials.append(surface)
    for polygon in result.data.polygons:
        polygon.use_smooth = True
    if parent is not None:
        world_matrix = result.matrix_world.copy()
        result.parent = parent
        result.matrix_world = world_matrix
    return result


def roof_height(across):
    center = BAY_CENTERS[0] if across < 0 else BAY_CENTERS[1]
    fraction = max(0.0, min(1.0, (across - center) / 6.5 + 0.5))
    return EAVE + ARCH_RISE * math.sin(math.pi * fraction) ** 0.86


def device(code, center):
    result = empty("DEVICE__" + code, center, devices)
    result["deviceCode"] = code
    return result


def indicator(code, center, parent):
    box("SIGNAL__" + code, center, (0.09, 0.07, 0.045), signal_off, devices, parent, 0.012)


def fan_blades(code, center, radius, parent):
    rotor = empty("ROTOR__" + code, center, devices, parent)
    for index in range(5):
        angle = math.tau * index / 5
        basis_y = math.sin(angle)
        basis_z = math.cos(angle)
        side_y = math.sin(angle + math.pi / 2)
        side_z = math.cos(angle + math.pi / 2)
        vertices = []
        for distance, half_width, pitch in ((radius * 0.17, radius * 0.1, 0.02), (radius * 0.9, radius * 0.2, 0.065)):
            for direction in (-1, 1):
                vertices.append((center[0] + pitch * direction, center[1] + basis_y * distance + side_y * half_width * direction, center[2] + basis_z * distance + side_z * half_width * direction))
        mesh("FAN_BLADE__" + code, vertices, [(0, 1, 3, 2)], aluminum, devices, rotor)
    cylinder("Fan hub", (center[0] - 0.06, center[1], center[2]), (center[0] + 0.06, center[1], center[2]), radius * 0.19, dark_steel, devices, rotor, 16)
    return rotor


def fan(code, center, radius, parent, target_collection):
    box("Fan powder-coated shroud", center, (0.3, radius * 2.25, radius * 2.25), dark_steel, target_collection, parent, 0.025)
    ring("Fan inlet rolled rim", (center[0] - 0.17, center[1], center[2]), radius * 0.96, galvanized, target_collection, parent)
    ring("Fan outlet rolled rim", (center[0] + 0.17, center[1], center[2]), radius * 0.96, galvanized, target_collection, parent)
    for index in range(8):
        angle = math.tau * index / 8
        offset_y = radius * 0.93 * math.sin(angle)
        offset_z = radius * 0.93 * math.cos(angle)
        cylinder("Fan guard spoke", (center[0] - 0.19, center[1], center[2]), (center[0] - 0.19, center[1] + offset_y, center[2] + offset_z), 0.009, galvanized, target_collection, parent, 6)
    fan_blades(code, (center[0] - 0.04, center[1], center[2]), radius * 0.82, parent)


bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
for old_collection in tuple(bpy.context.scene.collection.children):
    bpy.context.scene.collection.children.unlink(old_collection)

structure = collection("01_Structure")
devices = collection("02_Equipment")
galvanized = material("MAT_Galvanized_Steel", "A8B2B0", 0.84, 0.34)
steel_highlight = material("MAT_Galvanized_Light", "CED5D0", 0.82, 0.4)
dark_steel = material("MAT_Powdercoated_Graphite", "33454A", 0.58, 0.43)
concrete = material("MAT_Cast_Concrete", "8D918B", 0.0, 0.88)
rubber = material("MAT_EPDM_Black", "21272A", 0.0, 0.72)
blue_pipe = material("MAT_Polyethylene_Blue", "286879", 0.02, 0.42)
white_pipe = material("MAT_PVC_Ivory", "D5DCD4", 0.0, 0.44)
copper = material("MAT_Copper_Valve", "AA8056", 0.8, 0.36)
aluminum = material("MAT_Aluminum_Brushed", "B9C2BC", 0.86, 0.3)
pad_paper = material("MAT_CoolingPad_Dry", "9D8662", 0.0, 0.93)
sensor_white = material("MAT_Sensor_Polymer", "E3E8DD", 0.0, 0.38)
signal_off = material("MAT_Indicator_Off", "405747", 0.0, 0.28, 0.05)
led_off = material("MAT_LED_Diffuser", "F1DCC0", 0.0, 0.39, 0.04)
soil = material("MAT_Growing_Media", "584734", 0.0, 0.98)
terracotta = material("MAT_Irrigation_Valve", "A65439", 0.05, 0.49)

for index in range(14):
    frame_x = -13 + index * 2
    for bay_center in BAY_CENTERS:
        arch = []
        for sample in range(33):
            across = bay_center - 3.25 + sample * 6.5 / 32
            arch.append((frame_x, roof_height(across), across))
        tube("Bent galvanized roof bow", arch, 0.044, galvanized, structure, sides=10)
        cylinder("Roof bow tie", (frame_x, 3.93, bay_center - 3.14), (frame_x, 3.93, bay_center + 3.14), 0.018, galvanized, structure, vertices=8)
        for across in (bay_center - 2.95, bay_center + 2.95):
            cylinder("Diagonal knee brace", (frame_x, 3.15, across), (frame_x, roof_height(across) - 0.12, across + (0.37 if across < bay_center else -0.37)), 0.027, steel_highlight, structure)
    for across in (-6.5, 0, 6.5):
        cylinder("Square perimeter post", (frame_x, 0.28, across), (frame_x, 4.02, across), 0.062, galvanized, structure, vertices=8)
        box("Post base shoe", (frame_x, 0.12, across), (0.25, 0.24, 0.25), dark_steel, structure)
        box("Post concrete pad", (frame_x, 0.035, across), (0.39, 0.07, 0.39), concrete, structure)
    for across in (-6.5, 0, 6.5):
        box("Bolted bow shoe", (frame_x, 4.01, across), (0.17, 0.13, 0.15), dark_steel, structure)

for across in (-6.5, -5.0, -3.25, -1.5, 0, 1.5, 3.25, 5.0, 6.5):
    cylinder("Longitudinal roof purlin", (-13.06, roof_height(across) + 0.02, across), (13.06, roof_height(across) + 0.02, across), 0.032, steel_highlight, structure, vertices=10)
for across in (-6.56, 0, 6.56):
    box("Formed rain gutter", (0, 3.91, across), (26.3, 0.09, 0.27), galvanized, structure)
    box("Gutter rolled edge", (0, 3.98, across - 0.13), (26.3, 0.04, 0.025), steel_highlight, structure)
    box("Gutter rolled edge", (0, 3.98, across + 0.13), (26.3, 0.04, 0.025), steel_highlight, structure)
    cylinder("Rainwater downpipe", (12.66, 0.23, across), (12.66, 3.91, across), 0.044, blue_pipe, structure)

for side in (-1, 1):
    for index in range(27):
        across = -6.5 + index * 0.5
        box("Gable wall mullion", (side * 12.97, roof_height(across) / 2, across), (0.07, roof_height(across), 0.055), galvanized, structure)
    for level in (0.38, 1.35, 2.75, 3.92):
        box("Endwall horizontal rail", (side * 12.99, level, 0), (0.07, 0.06, 13.0), steel_highlight, structure)
    for across in (-6.5, 6.5):
        box("Concrete side curb", (0, 0.18, across), (26.4, 0.36, 0.24), concrete, structure)
    box("Concrete end curb", (side * 13.04, 0.18, 0), (0.25, 0.36, 13.1), concrete, structure)

for frame_x in (-13.13, 13.13):
    for across in (-1.18, 1.18):
        box("Sliding door jamb", (frame_x, 1.47, across), (0.12, 2.94, 0.09), dark_steel, structure)
    box("Sliding door header", (frame_x, 2.92, 0), (0.13, 0.11, 2.44), dark_steel, structure)
    box("Overhead door track", (frame_x - 0.14, 3.05, 0.53), (0.09, 0.08, 3.3), galvanized, structure)
    box("Sliding door translucent panel", (frame_x - 0.19, 1.47, 1.39), (0.055, 2.75, 1.12), sensor_white, structure)
    box("Sliding door handle", (frame_x - 0.26, 1.28, 0.91), (0.04, 0.18, 0.035), dark_steel, structure)

for bed_index, across in enumerate(BED_CENTERS):
    cylinder("Tomato support wire", (-10.57, 3.21, across), (10.57, 3.21, across), 0.008, steel_highlight, structure, vertices=6)
    for plant_index in range(26):
        plant_x = -10.5 + 21.0 * (plant_index + 0.5) / 26
        cylinder("Tomato crop support twine", (plant_x, 0.42, across), (plant_x, 3.2, across), 0.003, white_pipe, structure, vertices=5)
    for support_x in (-10.4, -5.2, 0, 5.2, 10.4):
        cylinder("Crop wire hanger", (support_x, 3.22, across), (support_x, roof_height(across) - 0.1, across), 0.013, galvanized, structure, vertices=8)

irrigation = device("IRRIGATION", (-11.7, 0, 5.25))
box("Service skid", (-11.65, 0.14, 5.18), (2.3, 0.27, 1.24), dark_steel, devices, irrigation, 0.025)
for vessel_x, vessel_height in ((-12.25, 1.48), (-11.58, 1.15)):
    cylinder("Screen filter vessel", (vessel_x, 0.38, 5.4), (vessel_x, vessel_height, 5.4), 0.23, blue_pipe, devices, irrigation, 24)
    cylinder("Filter inspection cap", (vessel_x, vessel_height, 5.4), (vessel_x, vessel_height + 0.12, 5.4), 0.25, dark_steel, devices, irrigation, 24)
    cylinder("Filter pressure port", (vessel_x, vessel_height + 0.1, 5.4), (vessel_x, vessel_height + 0.28, 5.4), 0.04, copper, devices, irrigation)
box("Pump motor housing", (-10.96, 0.64, 5.45), (0.55, 0.48, 0.42), galvanized, devices, irrigation, 0.055)
cylinder("Pump volute", (-11.21, 0.64, 5.45), (-11.54, 0.64, 5.45), 0.22, blue_pipe, devices, irrigation, 20)
for index, across in enumerate(BED_CENTERS):
    valve_x = -10.85 + index * 0.32
    cylinder("Solenoid zone valve", (valve_x, 0.54, 4.83), (valve_x, 0.93, 4.83), 0.09, copper, devices, irrigation)
    box("Valve coil enclosure", (valve_x, 0.98, 4.83), (0.18, 0.15, 0.18), dark_steel, devices, irrigation, 0.025)
    cylinder("Bed supply branch", (-11.4, 0.36, 5.25), (-11.4, 0.36, across), 0.025, blue_pipe, devices, irrigation)
    cylinder("Bed dripline", (-10.5, 0.32, across), (10.5, 0.32, across), 0.012, rubber, devices, irrigation, 8)
    for emitter_index in range(26):
        emitter_x = -10.5 + 21.0 * (emitter_index + 0.5) / 26
        box("Pressure-compensating emitter", (emitter_x, 0.325, across), (0.045, 0.03, 0.044), dark_steel, devices, irrigation)
cylinder("Irrigation header", (-11.75, 1.18, 5.4), (-10.4, 1.18, 5.4), 0.047, blue_pipe, devices, irrigation)
indicator("IRRIGATION", (-10.78, 0.99, 5.55), irrigation)

co2 = device("CO2_SUPPLY", (-11.75, 0, -5.55))
for cylinder_index in range(2):
    supply_x = -12.08 + cylinder_index * 0.58
    cylinder("CO2 pressure vessel", (supply_x, 0.16, -5.55), (supply_x, 1.72, -5.55), 0.22, blue_pipe, devices, co2, 20)
    cylinder("Cylinder valve collar", (supply_x, 1.72, -5.55), (supply_x, 1.87, -5.55), 0.1, copper, devices, co2)
box("Regulator and shutoff", (-10.95, 1.38, -5.55), (0.39, 0.29, 0.26), dark_steel, devices, co2, 0.025)
cylinder("CO2 cross header", (-10.52, 2.05, -5.55), (-10.52, 2.05, 5.45), 0.018, white_pipe, devices, co2)
for across in BED_CENTERS:
    cylinder("CO2 perforated distribution tube", (-10.52, 2.05, across), (10.53, 2.05, across), 0.014, white_pipe, devices, co2, 8)
indicator("CO2_SUPPLY", (-10.93, 1.44, -5.4), co2)

for bay_center in BAY_CENTERS:
    suffix = "N" if bay_center < 0 else "S"
    pad_code = "WET_PAD_" + suffix
    pad = device(pad_code, (-12.94, 2.2, bay_center))
    box("Cooling pad outer frame", (-12.98, 2.2, bay_center), (0.28, 2.9, 2.72), dark_steel, devices, pad)
    box("WET_SURFACE__" + pad_code, (-13.15, 2.2, bay_center), (0.06, 2.53, 2.44), pad_paper, devices, pad)
    for index in range(38):
        across = bay_center - 1.17 + index * 2.34 / 37
        box("Corrugated cellulose channel", (-13.19, 2.2, across), (0.025, 2.51, 0.022), copper if index % 5 == 0 else pad_paper, devices, pad)
    box("Cooling recirculation trough", (-13.22, 0.65, bay_center), (0.54, 0.27, 2.79), blue_pipe, devices, pad, 0.03)
    cylinder("Cooling feed manifold", (-13.19, 3.61, bay_center - 1.23), (-13.19, 3.61, bay_center + 1.23), 0.04, blue_pipe, devices, pad)
    cylinder("Cooling water return", (-12.7, 0.8, bay_center + 1.27), (-12.7, 3.59, bay_center + 1.27), 0.034, blue_pipe, devices, pad)
    box("Cooling water strainer", (-12.56, 0.71, bay_center + 1.27), (0.31, 0.38, 0.25), dark_steel, devices, pad, 0.025)
    indicator(pad_code, (-13.26, 3.55, bay_center + 1.26), pad)

    exhaust_code = "EXHAUST_" + suffix
    exhaust = device(exhaust_code, (12.93, 2.45, bay_center))
    fan(exhaust_code, (12.94, 2.4, bay_center), 0.73, exhaust, devices)
    box("Fan weather hood", (13.21, 3.27, bay_center), (0.68, 0.06, 1.91), galvanized, devices, exhaust)
    for louver_index in range(6):
        box("Exhaust gravity louver", (13.19, 1.82 + louver_index * 0.22, bay_center), (0.05, 0.045, 1.5), aluminum, devices, exhaust)
    indicator(exhaust_code, (13.23, 3.17, bay_center + 0.81), exhaust)

shade = device("SHADE", (0, 3.76, 0))
box("Shade drive gearbox", (0, 3.74, 0), (0.63, 0.36, 0.38), dark_steel, devices, shade, 0.025)
cylinder("Shade drive shaft", (-11.3, 3.73, 0), (11.3, 3.73, 0), 0.043, galvanized, devices, shade, 10)
for along in (-9, -4.5, 0, 4.5, 9):
    for across in (-5.8, -0.8, 0.8, 5.8):
        cylinder("Shade cable guide", (along, 3.83, 0), (along, 3.83, across), 0.008, steel_highlight, devices, shade, 6)
indicator("SHADE", (0.19, 3.91, 0.21), shade)

lamps = device("SUPPLEMENTAL_LIGHT", (0, 3.25, -4.4))
for across in BED_CENTERS:
    for along in (-8.7, -5.2, -1.7, 1.8, 5.3, 8.8):
        box("LED horticulture extrusion", (along, 3.21, across), (1.27, 0.10, 0.24), aluminum, devices, lamps, 0.015)
        box("LED_EMITTER", (along, 3.148, across), (1.17, 0.015, 0.16), led_off, devices, lamps)
        for hanger_x in (along - 0.52, along + 0.52):
            cylinder("LED suspension cable", (hanger_x, 3.29, across), (hanger_x, roof_height(across) - 0.18, across), 0.006, rubber, devices, lamps, 6)
        box("LED driver", (along + 0.51, 3.29, across), (0.22, 0.12, 0.26), dark_steel, devices, lamps, 0.01)
indicator("SUPPLEMENTAL_LIGHT", (0.55, 3.31, -4.4), lamps)

side_vent = device("SIDE_VENT", (0, 3.66, -6.48))
box("Vent drive motor", (0, 3.66, -6.48), (0.53, 0.29, 0.27), dark_steel, devices, side_vent, 0.025)
for across in (-6.49, 6.49):
    cylinder("Side roll-up film shaft", (-12, 1.23, across), (12, 1.23, across), 0.043, galvanized, devices, side_vent, 10)
    cylinder("Side vent gear rack", (-12, 3.62, across), (12, 3.62, across), 0.018, steel_highlight, devices, side_vent, 8)
indicator("SIDE_VENT", (0.23, 3.74, -6.62), side_vent)

for bay_center in BAY_CENTERS:
    suffix = "N" if bay_center < 0 else "S"
    for index, along in enumerate((-7.0, 0.0, 7.0), 1):
        code = f"HAF_{suffix}_{index}"
        fan_across = bay_center + (1.35 if bay_center < 0 else -1.35)
        haf = device(code, (along, 3.15, fan_across))
        cylinder("HAF ceiling hanger", (along, 3.23, fan_across), (along, roof_height(fan_across) - 0.16, fan_across), 0.019, galvanized, devices, haf)
        fan(code, (along, 3.13, fan_across), 0.33, haf, devices)
        indicator(code, (along, 3.53, fan_across + 0.22), haf)

    roof_code = "ROOF_VENT_" + suffix
    roof = device(roof_code, (0, roof_height(bay_center) + 0.04, bay_center))
    for edge_x in (-2.6, 2.6):
        box("Vent jamb", (edge_x, roof_height(bay_center) + 0.05, bay_center), (0.07, 0.08, 1.15), galvanized, devices, roof)
    panel = empty("ROOF_PANEL__" + roof_code, (0, roof_height(bay_center) + 0.09, bay_center - 0.51), devices, roof)
    box("Roof vent aluminum sash", (0, roof_height(bay_center) + 0.11, bay_center), (5.16, 0.055, 1.03), aluminum, devices, panel)
    box("Roof vent polycarbonate infill", (0, roof_height(bay_center) + 0.14, bay_center), (4.91, 0.015, 0.79), sensor_white, devices, panel)
    for along in (-2.45, 0, 2.45):
        box("Vent hinge", (along, roof_height(bay_center) + 0.17, bay_center - 0.5), (0.12, 0.09, 0.08), dark_steel, devices, panel)
    indicator(roof_code, (2.51, roof_height(bay_center) + 0.26, bay_center + 0.5), roof)


def sensor(code, center, shape="air"):
    root = device(code, center)
    if shape == "root":
        cylinder("Root-zone probe stem", (center[0], center[1] - 0.38, center[2]), (center[0], center[1] + 0.09, center[2]), 0.018, dark_steel, devices, root, 8)
        box("Root-zone sealed head", (center[0], center[1] + 0.11, center[2]), (0.21, 0.13, 0.14), sensor_white, devices, root, 0.016)
    elif shape == "flow":
        cylinder("Electromagnetic flowmeter body", (center[0] - 0.19, center[1], center[2]), (center[0] + 0.19, center[1], center[2]), 0.11, blue_pipe, devices, root, 16)
        box("Flowmeter transmitter", (center[0], center[1] + 0.25, center[2]), (0.24, 0.31, 0.18), sensor_white, devices, root, 0.025)
    else:
        cylinder("Sensor mounting stem", (center[0], center[1] + 0.09, center[2]), (center[0], center[1] + 0.65, center[2]), 0.014, galvanized, devices, root, 8)
        box("Aspirated sensor enclosure", center, (0.23, 0.3, 0.16), sensor_white, devices, root, 0.022)
        for slot_index in range(4):
            box("Sensor ventilation slot", (center[0], center[1] - 0.1 + slot_index * 0.055, center[2] + 0.085), (0.16, 0.012, 0.013), dark_steel, devices, root)
    indicator(code, (center[0] + 0.07, center[1] - 0.04, center[2] + 0.1), root)


sensor("SENSOR_OUTDOOR", (-16, 2.6, 6))
sensor("SENSOR_AIR_N", (-3, 2.2, -3.25))
sensor("SENSOR_AIR_S", (4, 2.2, 3.25))
sensor("SENSOR_CO2", (0, 2.1, -2.2))
sensor("SENSOR_LIGHT", (-3, 2.55, 3.35))
for index, across in enumerate(BED_CENTERS, 1):
    sensor(f"SENSOR_ROOT_{index}", (5.8, 0.47, across), "root")
sensor("SENSOR_FLOW", (-10.75, 0.95, 5.25), "flow")


def optimize_export(target_collection):
    candidates = [object_ref for object_ref in target_collection.objects if object_ref.type == "MESH"]
    for object_ref in candidates:
        if not object_ref.modifiers:
            continue
        bpy.ops.object.select_all(action="DESELECT")
        object_ref.select_set(True)
        bpy.context.view_layer.objects.active = object_ref
        for modifier in tuple(object_ref.modifiers):
            bpy.ops.object.modifier_apply(modifier=modifier.name)

    groups = {}
    for object_ref in candidates:
        if object_ref.name.startswith(("SIGNAL__", "WET_SURFACE__")):
            continue
        parent_name = object_ref.parent.name if object_ref.parent else ""
        surface_name = object_ref.data.materials[0].name
        groups.setdefault((parent_name, surface_name), []).append(object_ref)

    for siblings in groups.values():
        if len(siblings) < 2:
            continue
        bpy.ops.object.select_all(action="DESELECT")
        for object_ref in siblings:
            object_ref.select_set(True)
        bpy.context.view_layer.objects.active = siblings[0]
        bpy.ops.object.join()


SOURCE.parent.mkdir(parents=True, exist_ok=True)
EXPORT.mkdir(parents=True, exist_ok=True)
bpy.context.scene.unit_settings.system = "METRIC"
bpy.context.scene.unit_settings.scale_length = 1.0
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(SOURCE))

for target_collection, filename in ((structure, "greenhouse-structure.glb"), (devices, "greenhouse-equipment.glb")):
    optimize_export(target_collection)
    bpy.ops.object.select_all(action="DESELECT")
    for object_ref in target_collection.objects:
        object_ref.select_set(True)
    bpy.ops.export_scene.gltf(
        filepath=str(EXPORT / filename),
        export_format="GLB",
        use_selection=True,
        export_apply=True,
        export_yup=True,
        export_extras=True,
    )
    print(f"EXPORTED {filename}: {sum(object_ref.type == 'MESH' for object_ref in target_collection.objects)} meshes")

print(f"SOURCE {SOURCE}")
