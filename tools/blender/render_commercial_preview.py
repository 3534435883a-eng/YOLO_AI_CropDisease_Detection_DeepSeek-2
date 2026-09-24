"""Render a review-only commercial greenhouse concept from greenhouse-v2.blend."""

import math
import random
from pathlib import Path

import bpy
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[2]
REVIEW = ROOT / "assets" / "greenhouse" / "review"
REVIEW.mkdir(parents=True, exist_ok=True)
EXTERIOR = REVIEW / "commercial-greenhouse-exterior-v5.png"
INTERIOR = REVIEW / "commercial-greenhouse-interior-v5.png"
DRY_INTERIOR = REVIEW / "commercial-greenhouse-interior-dry-v5.png"
SCENE_COPY = REVIEW / "commercial-greenhouse-concept-v5.blend"
BED_CENTERS = (-4.4, -2.25, 2.25, 4.4)
RNG = random.Random(20260924)


def linear(hex_color):
    values = [int(hex_color[index:index + 2], 16) / 255 for index in (0, 2, 4)]
    return tuple(((v + 0.055) / 1.055) ** 2.4 if v > 0.04045 else v / 12.92
                 for v in values) + (1.0,)


def principled(name, color, roughness=0.5, metallic=0.0, subsurface=0.0):
    material = bpy.data.materials.new(name)
    material.diffuse_color = linear(color)
    material.use_nodes = True
    shader = material.node_tree.nodes.get("Principled BSDF")
    shader.inputs["Base Color"].default_value = linear(color)
    shader.inputs["Roughness"].default_value = roughness
    shader.inputs["Metallic"].default_value = metallic
    shader.inputs["Subsurface Weight"].default_value = subsurface
    return material, shader


def textured_ground(name, color, roughness, scale, bump_strength):
    material, shader = principled(name, color, roughness)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    noise = nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = scale
    noise.inputs["Detail"].default_value = 4
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.22
    ramp.color_ramp.elements[0].color = linear(color)
    ramp.color_ramp.elements[1].position = 0.8
    ramp.color_ramp.elements[1].color = linear("5C4A37" if name.endswith("Soil") else "7B805D")
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = bump_strength
    bump.inputs["Distance"].default_value = 0.08
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], shader.inputs["Base Color"])
    links.new(noise.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    if name.endswith("Soil"):
        shader.inputs["Coat Weight"].default_value = 0.18
        shader.inputs["Coat Roughness"].default_value = 0.4
    return material


def image_ground(name, image_name, normal_name, repeat, roughness):
    material, shader = principled(name, "FFFFFF", roughness)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    coordinate = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeVectorMath")
    mapping.operation = "MULTIPLY"
    mapping.inputs[1].default_value = (*repeat, 1)
    links.new(coordinate.outputs["UV"], mapping.inputs[0])
    base_path = ROOT / "YOLO_AI_CropDisease_Detection_Vue" / "public" / "textures" / "greenhouse"
    color_texture = nodes.new("ShaderNodeTexImage")
    color_texture.image = bpy.data.images.load(str(base_path / image_name), check_existing=True)
    color_texture.extension = "REPEAT"
    links.new(mapping.outputs["Vector"], color_texture.inputs["Vector"])
    links.new(color_texture.outputs["Color"], shader.inputs["Base Color"])
    normal_texture = nodes.new("ShaderNodeTexImage")
    normal_texture.image = bpy.data.images.load(str(base_path / normal_name), check_existing=True)
    normal_texture.image.colorspace_settings.name = "Non-Color"
    normal_texture.extension = "REPEAT"
    links.new(mapping.outputs["Vector"], normal_texture.inputs["Vector"])
    normal_map = nodes.new("ShaderNodeNormalMap")
    normal_map.inputs["Strength"].default_value = 0.4
    links.new(normal_texture.outputs["Color"], normal_map.inputs["Color"])
    links.new(normal_map.outputs["Normal"], shader.inputs["Normal"])
    return material


def leaf_material(name, dark, light, aging=False):
    material, shader = principled(name, dark, 0.66, subsurface=0.30)
    shader.inputs["Subsurface Radius"].default_value = (0.5, 1.0, 0.35)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    uv = nodes.new("ShaderNodeUVMap")
    uv.uv_map = "Leaflet UV"
    noise = nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 34
    noise.inputs["Detail"].default_value = 3
    links.new(uv.outputs["UV"], noise.inputs["Vector"])
    colors = nodes.new("ShaderNodeValToRGB")
    colors.color_ramp.elements[0].position = 0.25
    colors.color_ramp.elements[0].color = linear(dark)
    colors.color_ramp.elements[1].position = 0.75
    colors.color_ramp.elements[1].color = linear(light)
    links.new(noise.outputs["Fac"], colors.inputs["Fac"])
    color_output = colors.outputs["Color"]

    split = nodes.new("ShaderNodeSeparateXYZ")
    links.new(uv.outputs["UV"], split.inputs["Vector"])
    offset = nodes.new("ShaderNodeMath")
    offset.operation = "SUBTRACT"
    offset.inputs[1].default_value = 0.5
    links.new(split.outputs["Y"], offset.inputs[0])
    distance = nodes.new("ShaderNodeMath")
    distance.operation = "ABSOLUTE"
    links.new(offset.outputs[0], distance.inputs[0])
    midrib = nodes.new("ShaderNodeMapRange")
    midrib.clamp = True
    midrib.inputs["From Min"].default_value = 0.0
    midrib.inputs["From Max"].default_value = 0.055
    midrib.inputs["To Min"].default_value = 0.24
    midrib.inputs["To Max"].default_value = 0.0
    links.new(distance.outputs[0], midrib.inputs["Value"])
    vein_color = nodes.new("ShaderNodeMixRGB")
    vein_color.blend_type = "MIX"
    vein_color.inputs[2].default_value = linear("A4BD75" if aging else "77B772")
    links.new(midrib.outputs["Result"], vein_color.inputs[0])
    links.new(color_output, vein_color.inputs[1])
    color_output = vein_color.outputs["Color"]

    if aging:
        edge = nodes.new("ShaderNodeMapRange")
        edge.clamp = True
        edge.interpolation_type = "SMOOTHSTEP"
        edge.inputs["From Min"].default_value = 0.29
        edge.inputs["From Max"].default_value = 0.48
        edge.inputs["To Max"].default_value = 0.72
        links.new(distance.outputs[0], edge.inputs["Value"])
        yellow = nodes.new("ShaderNodeMixRGB")
        yellow.inputs[2].default_value = linear("B7AA64")
        links.new(edge.outputs["Result"], yellow.inputs[0])
        links.new(color_output, yellow.inputs[1])
        color_output = yellow.outputs["Color"]
    links.new(color_output, shader.inputs["Base Color"])

    fine = nodes.new("ShaderNodeTexWave")
    fine.wave_type = "BANDS"
    fine.bands_direction = "DIAGONAL"
    fine.inputs["Scale"].default_value = 18
    fine.inputs["Distortion"].default_value = 1.0
    links.new(uv.outputs["UV"], fine.inputs["Vector"])
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.12
    bump.inputs["Distance"].default_value = 0.0015
    links.new(fine.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    translucent = nodes.new("ShaderNodeBsdfTranslucent")
    translucent.inputs["Color"].default_value = linear("74B970" if not aging else "A9B777")
    output = nodes.get("Material Output")
    links.remove(output.inputs["Surface"].links[0])
    blend = nodes.new("ShaderNodeMixShader")
    blend.inputs[0].default_value = 0.14
    links.new(shader.outputs[0], blend.inputs[1])
    links.new(translucent.outputs[0], blend.inputs[2])
    links.new(blend.outputs[0], output.inputs["Surface"])
    return material


def fruit_material(name, dark, light, spotted=False):
    material, shader = principled(name, dark, 0.57)
    if "Specular IOR Level" in shader.inputs:
        shader.inputs["Specular IOR Level"].default_value = 0.28
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    coord = nodes.new("ShaderNodeTexCoord")
    micro = nodes.new("ShaderNodeTexNoise")
    micro.inputs["Scale"].default_value = 38
    micro.inputs["Detail"].default_value = 3
    links.new(coord.outputs["Object"], micro.inputs["Vector"])
    colors = nodes.new("ShaderNodeValToRGB")
    colors.color_ramp.elements[0].position = 0.26
    colors.color_ramp.elements[0].color = linear(dark)
    colors.color_ramp.elements[1].position = 0.76
    colors.color_ramp.elements[1].color = linear(light)
    links.new(micro.outputs["Fac"], colors.inputs["Fac"])
    color_output = colors.outputs["Color"]
    if spotted:
        marks = nodes.new("ShaderNodeTexNoise")
        marks.inputs["Scale"].default_value = 14
        links.new(coord.outputs["Object"], marks.inputs["Vector"])
        mask = nodes.new("ShaderNodeValToRGB")
        mask.color_ramp.elements[0].position = 0.62
        mask.color_ramp.elements[1].position = 0.72
        links.new(marks.outputs["Fac"], mask.inputs["Fac"])
        spots = nodes.new("ShaderNodeMixRGB")
        spots.inputs[2].default_value = linear("8D7654" if "Ripe" in name else "87935F")
        links.new(mask.outputs["Color"], spots.inputs[0])
        links.new(color_output, spots.inputs[1])
        color_output = spots.outputs["Color"]
    links.new(color_output, shader.inputs["Base Color"])
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.18
    bump.inputs["Distance"].default_value = 0.0013
    links.new(micro.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    return material


def gravel_material():
    material, shader = principled("MAT_Concept_Irregular_Gravel_Aisle", "898B7E", 0.91)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    coord = nodes.new("ShaderNodeTexCoord")
    stones = nodes.new("ShaderNodeTexVoronoi")
    stones.inputs["Scale"].default_value = 18
    links.new(coord.outputs["Object"], stones.inputs["Vector"])
    gravel = nodes.new("ShaderNodeValToRGB")
    gravel.color_ramp.elements[0].position = 0.08
    gravel.color_ramp.elements[0].color = linear("454C48")
    gravel.color_ramp.elements[1].position = 0.68
    gravel.color_ramp.elements[1].color = linear("ADA99A")
    gravel.color_ramp.elements.new(0.34).color = linear("7F847A")
    links.new(stones.outputs["Distance"], gravel.inputs["Fac"])
    broad = nodes.new("ShaderNodeTexNoise")
    broad.inputs["Scale"].default_value = 0.8
    links.new(coord.outputs["Object"], broad.inputs["Vector"])
    value = nodes.new("ShaderNodeValToRGB")
    value.color_ramp.elements[0].color = linear("A5A89B")
    value.color_ramp.elements[1].color = linear("F1EDDB")
    links.new(broad.outputs["Fac"], value.inputs["Fac"])
    combine = nodes.new("ShaderNodeMixRGB")
    combine.blend_type = "MULTIPLY"
    combine.inputs[0].default_value = 0.6
    links.new(gravel.outputs["Color"], combine.inputs[1])
    links.new(value.outputs["Color"], combine.inputs[2])
    links.new(combine.outputs["Color"], shader.inputs["Base Color"])
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.32
    bump.inputs["Distance"].default_value = 0.009
    links.new(stones.outputs["Distance"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], shader.inputs["Normal"])
    return material


def box(name, location, dimensions, material, collection, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(material)
    for previous in tuple(obj.users_collection):
        previous.objects.unlink(obj)
    collection.objects.link(obj)
    if bevel:
        modifier = obj.modifiers.new("Soft manufactured edges", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
        obj.modifiers.new("Weighted normals", "WEIGHTED_NORMAL")
    return obj


def surface(name, vertices, faces, material, collection):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    obj.data.materials.append(material)
    for polygon in mesh.polygons:
        polygon.use_smooth = True
    return obj


def arch_height(across):
    bay = -3.25 if across < 0 else 3.25
    fraction = max(0.0, min(1.0, (across - bay) / 6.5 + 0.5))
    return 4.0 + 1.7 * math.sin(math.pi * fraction) ** 0.86


def roof_film(collection, material):
    for bay in (-3.25, 3.25):
        vertices = []
        faces = []
        for along in range(27):
            x = -13.0 + along
            for across_step in range(33):
                across = bay - 3.25 + across_step * 6.5 / 32
                vertices.append((x, -across, arch_height(across) + 0.045))
        for along in range(26):
            for across_step in range(32):
                first = along * 33 + across_step
                faces.append((first, first + 33, first + 34, first + 1))
        surface("Diffuse polyethylene roof film", vertices, faces, material, collection)
    for across in (-6.505, 6.505):
        surface("Rolled side polyethylene film",
                [(-13, -across, 1.15), (13, -across, 1.15),
                 (13, -across, 3.91), (-13, -across, 3.91)],
                [(0, 1, 2, 3)], material, collection)
    for x in (-13.01, 13.01):
        for across_a, across_b in ((-6.5, -1.25), (1.25, 6.5)):
            surface("Translucent end wall film",
                    [(x, -across_a, 0.35), (x, -across_b, 0.35),
                     (x, -across_b, 3.91), (x, -across_a, 3.91)],
                    [(0, 1, 2, 3)], material, collection)


def add_aisle_details(collection, green_material, aged_material):
    detail_rng = random.Random(560)
    for group, material in (("Small fallen green tomato leaves", green_material),
                            ("Small fallen aging tomato leaves", aged_material)):
        vertices = []
        faces = []
        count = 18 if "green" in group else 9
        for _ in range(count):
            center = Vector((detail_rng.uniform(-11.5, 11.5),
                             detail_rng.uniform(-0.48, 0.48), 0.071))
            angle = detail_rng.uniform(0, math.tau)
            direction = Vector((math.cos(angle), math.sin(angle), 0))
            lateral = Vector((-direction.y, direction.x, 0))
            size = detail_rng.uniform(0.035, 0.07)
            start = len(vertices)
            vertices.extend((center - direction * size,
                             center - lateral * size * 0.42,
                             center + direction * size * 0.72,
                             center + lateral * size * 0.42))
            faces.append(tuple(range(start, start + 4)))
        surface(group, vertices, faces, material, collection)

    vertices = []
    faces = []
    for _ in range(22):
        x = detail_rng.uniform(-11.5, 11.5)
        y = detail_rng.choice((-1, 1)) * detail_rng.uniform(0.52, 0.60)
        for blade in range(3):
            angle = blade * math.tau / 3 + detail_rng.uniform(-0.25, 0.25)
            lean = Vector((math.cos(angle), math.sin(angle), 0))
            root = Vector((x, y, 0.071))
            first = len(vertices)
            vertices.extend((root - lean * 0.012,
                             root + lean * 0.012,
                             root + lean * detail_rng.uniform(0.035, 0.065)
                             + Vector((0, 0, detail_rng.uniform(0.035, 0.075)))))
            faces.append((first, first + 1, first + 2))
    surface("Sparse aisle edge weeds", vertices, faces, green_material, collection)


def add_tomatoes(collection, leaf_materials, fruit_materials, stem_material,
                 dew_material, dew_collection):
    fruit_vertices = []
    fruit_faces = []
    fruit_indices = []
    stem_curve = bpy.data.curves.new("Tomato vine and petioles", "CURVE")
    stem_curve.dimensions = "3D"
    stem_curve.bevel_depth = 0.011
    stem_curve.bevel_resolution = 2
    stem_curve.resolution_u = 2

    def stem_path(points):
        spline = stem_curve.splines.new("POLY")
        spline.points.add(len(points) - 1)
        for point, coords in zip(spline.points, points):
            point.co = (*coords, 1)

    def make_leaf_mesh(variant):
        vertices = []
        faces = []
        indices = []
        leaf_uvs = []
        dew_vertices = []
        dew_faces = []
        leaf_rng = random.Random(441 + variant)

        def droplet(center, radius):
            start = len(dew_vertices)
            for ring in range(5):
                polar = math.pi * ring / 4
                for sector in range(8):
                    azimuth = math.tau * sector / 8
                    dew_vertices.append(center + Vector((
                        radius * math.sin(polar) * math.cos(azimuth),
                        radius * math.sin(polar) * math.sin(azimuth),
                        radius * 0.55 * math.cos(polar))))
            for ring in range(4):
                for sector in range(8):
                    a = start + ring * 8 + sector
                    b = start + ring * 8 + (sector + 1) % 8
                    dew_faces.append((a, b, b + 8, a + 8))

        def leaflet(root, direction, length, half_width, material_index, curl):
            sideways = Vector((-direction.y, direction.x, 0)).normalized()
            start = len(vertices)
            for step in range(9):
                fraction = step / 8
                envelope = math.sin(math.pi * fraction) ** 0.72
                tooth = 0.78 if step in (2, 4, 6) else 1.0
                spread = half_width * envelope * tooth
                midline = root + direction * (length * fraction)
                midline.z += curl * math.sin(math.pi * fraction) - 0.045 * fraction ** 2
                roll = 0.014 * math.sin(math.pi * fraction) * (1 if variant == 1 else -1)
                vertices.extend((midline - sideways * spread + Vector((0, 0, roll)),
                                 midline,
                                 midline + sideways * spread - Vector((0, 0, roll))))
                leaf_uvs.extend(((fraction, 0.0), (fraction, 0.5), (fraction, 1.0)))
            for strip in range(8):
                a = start + strip * 3
                faces.extend(((a, a + 1, a + 4, a + 3),
                              (a + 1, a + 2, a + 5, a + 4)))
                indices.extend((material_index, material_index))
            if leaf_rng.random() < 0.07:
                center = root + direction * (length * 0.56)
                center.z += curl * math.sin(math.pi * 0.56) - 0.045 * 0.56 ** 2 + 0.006
                droplet(center + sideways * leaf_rng.uniform(-0.025, 0.025),
                        leaf_rng.uniform(0.007, 0.012))

        for level in range(12):
            z = 0.43 + level * 0.165
            for side in range(3):
                angle = level * 2.15 + side * math.tau / 3 + variant * 0.21
                direction = Vector((math.cos(angle), math.sin(angle), 0))
                lateral = Vector((-direction.y, direction.x, 0))
                length = (0.40 + 0.07 * math.sin(level * 2.1 + side)) * leaf_rng.uniform(0.88, 1.13)
                root = Vector((0.018 * math.sin(level), 0.018 * math.cos(level), z))
                for pair in range(3):
                    fraction = 0.22 + pair * 0.20
                    branch_root = root + direction * (length * fraction)
                    branch_root.z += 0.025 * pair
                    for branch_side in (-1, 1):
                        branch_direction = (direction * 0.61 + lateral * branch_side * 0.79).normalized()
                        blade_root = branch_root + branch_direction * 0.045
                        blade_length = (0.15 + pair * 0.025) * leaf_rng.uniform(0.86, 1.17)
                        material_index = 2 if level < 5 and leaf_rng.random() < 0.07 else (level + side + pair) % 2
                        leaflet(blade_root, branch_direction, blade_length,
                                0.045 + pair * 0.008, material_index,
                                leaf_rng.uniform(0.012, 0.045))
                terminal_root = root + direction * (length * 0.77)
                terminal_root.z += 0.055
                leaflet(terminal_root, direction, 0.24 * leaf_rng.uniform(0.90, 1.12),
                        0.078, (level + side) % len(leaf_materials), 0.035)

        mesh = bpy.data.meshes.new(f"Tomato compound foliage variant {variant + 1}")
        mesh.from_pydata(vertices, [], faces)
        mesh.update()
        uv_layer = mesh.uv_layers.new(name="Leaflet UV")
        for loop in mesh.loops:
            uv_layer.data[loop.index].uv = leaf_uvs[loop.vertex_index]
        for material in leaf_materials:
            mesh.materials.append(material)
        for polygon, index in zip(mesh.polygons, indices):
            polygon.material_index = index
            polygon.use_smooth = True
        dew_mesh = bpy.data.meshes.new(f"Dew droplets foliage variant {variant + 1}")
        dew_mesh.from_pydata(dew_vertices, [], dew_faces)
        dew_mesh.update()
        dew_mesh.materials.append(dew_material)
        for polygon in dew_mesh.polygons:
            polygon.use_smooth = True
        return mesh, dew_mesh

    stem_path([(0, 0, 0), (0.018, -0.015, 0.55), (-0.02, 0.02, 1.15),
               (0.025, -0.015, 1.72), (0.0, 0.01, 2.25)])
    for level in range(12):
        z = 0.43 + level * 0.165
        for side in range(3):
            angle = level * 2.15 + side * math.tau / 3
            direction = Vector((math.cos(angle), math.sin(angle), 0))
            length = 0.40 + 0.07 * math.sin(level * 2.1 + side)
            root = Vector((0.018 * math.sin(level), 0.018 * math.cos(level), z))
            stem_path((root, root + direction * length + Vector((0, 0, 0.06))))

    for tier in range(3):
        z = 0.72 + tier * 0.47
        for fruit in range(4):
            angle = tier * 1.8 + fruit * math.tau / 4
            center = Vector((0.16 * math.cos(angle), 0.16 * math.sin(angle),
                             z + (fruit % 2) * 0.045))
            radius = 0.055 + 0.008 * (fruit % 3)
            first = len(fruit_vertices)
            for ring in range(7):
                polar = math.pi * ring / 6
                for sector in range(10):
                    azimuth = math.tau * sector / 10
                    lobing = 1 + 0.025 * math.cos(5 * azimuth + fruit * 0.4) * math.sin(polar) ** 2
                    fruit_vertices.append(center + Vector((
                        radius * lobing * math.sin(polar) * math.cos(azimuth),
                        radius * lobing * math.sin(polar) * math.sin(azimuth),
                        radius * (0.84 + 0.025 * math.sin(fruit + tier)) * math.cos(polar))))
            for ring in range(6):
                for sector in range(10):
                    a = first + ring * 10 + sector
                    b = first + ring * 10 + (sector + 1) % 10
                    fruit_faces.append((a, b, b + 10, a + 10))
                    ripe = tier == 0 or (tier == 1 and fruit < 2)
                    fruit_indices.append((3 if tier == 0 and fruit == 0 else 0) if ripe
                                         else (4 if tier == 2 and fruit == 2 else 1))
            for sepal in range(5):
                direction = math.tau * sepal / 5
                fruit_vertices.extend((
                    center + Vector((0, 0, radius * 0.83)),
                    center + Vector((radius * 0.54 * math.cos(direction),
                                     radius * 0.54 * math.sin(direction), radius * 0.8)),
                    center + Vector((radius * 0.8 * math.cos(direction + 0.25),
                                     radius * 0.8 * math.sin(direction + 0.25), radius * 0.58)),
                ))
                corner = len(fruit_vertices) - 3
                fruit_faces.append((corner, corner + 1, corner + 2))
                fruit_indices.append(2)
            stem_path(((0, 0, z + 0.12), (center.x, center.y, center.z + radius)))

    foliage_variants = tuple(make_leaf_mesh(variant) for variant in range(3))

    fruit_mesh = bpy.data.meshes.new("Red and green tomatoes shared geometry")
    fruit_mesh.from_pydata(fruit_vertices, [], fruit_faces)
    fruit_mesh.update()
    for material in fruit_materials:
        fruit_mesh.materials.append(material)
    for polygon, index in zip(fruit_mesh.polygons, fruit_indices):
        polygon.material_index = index
        polygon.use_smooth = True

    for bed in BED_CENTERS:
        for index in range(26):
            along = -10.5 + 21.0 * (index + 0.5) / 26
            location = (along, -bed, 0.34)
            angle = RNG.uniform(-0.5, 0.5)
            scale = RNG.uniform(0.87, 1.10)
            foliage, dew = foliage_variants[(index + round(bed * 4)) % 3]
            for name, data in (("Tomato vine", stem_curve),
                               ("Tomato compound leaves", foliage),
                               ("Tomatoes red and green", fruit_mesh)):
                obj = bpy.data.objects.new(f"{name} {bed:+.2f} {index + 1:02d}", data)
                collection.objects.link(obj)
                obj.location = location
                obj.rotation_euler.z = angle
                obj.scale = (scale, scale, scale)
                if data == stem_curve:
                    obj.data.materials.clear()
                    obj.data.materials.append(stem_material)
            dew_obj = bpy.data.objects.new(f"DEW_HUMIDITY_{bed:+.2f}_{index + 1:02d}", dew)
            dew_collection.objects.link(dew_obj)
            dew_obj.location = location
            dew_obj.rotation_euler.z = angle
            dew_obj.scale = (scale, scale, scale)


scene = bpy.context.scene
scene.unit_settings.system = "METRIC"
scene.unit_settings.scale_length = 1.0
concept = bpy.data.collections.new("COL_Concept_Crops_Enclosure")
scene.collection.children.link(concept)
dew_collection = bpy.data.collections.new("COL_Dew_Humidity_Overlay")
scene.collection.children.link(dew_collection)
dew_collection["state"] = "dew_enabled"
dew_collection["control"] = "humidity_or_leaf_wetness_sensor"

soil = image_ground("MAT_Concept_Wet_Soil_PBR", "brown_mud_diff_1k.jpg",
                    "brown_mud_nor_gl_1k.jpg", (20, 2), 0.62)
site = image_ground("MAT_Concept_Grass_PBR", "grass_diff_1k.jpg",
                    "grass_nor_gl_1k.jpg", (36, 36), 0.95)
concrete = image_ground("MAT_Concept_Concrete_PBR", "concrete_diff_1k.jpg",
                        "concrete_nor_gl_1k.jpg", (18, 8), 0.87)
aisle = gravel_material()
leaf_a = leaf_material("MAT_Concept_Leaf_Mature_SSS", "315E38", "4A8849")
leaf_b = leaf_material("MAT_Concept_Leaf_Young_SSS", "477D40", "6BA35A")
leaf_old = leaf_material("MAT_Concept_Leaf_Aging_Edge", "637B44", "86985B", aging=True)
red = fruit_material("MAT_Concept_Tomato_Ripe_Matte", "9E3024", "C84C36")
green = fruit_material("MAT_Concept_Tomato_Green_Matte", "68834A", "94A85E")
red_spot = fruit_material("MAT_Concept_Tomato_Ripe_Spots", "9E3024", "C84C36", spotted=True)
green_spot = fruit_material("MAT_Concept_Tomato_Green_Spots", "68834A", "94A85E", spotted=True)
stem, _ = principled("MAT_Concept_Vine", "51783C", 0.75)
dew, dew_shader = principled("MAT_Concept_Leaf_Dew", "DDF7EC", 0.065)
dew_shader.inputs["Transmission Weight"].default_value = 0.95
dew_shader.inputs["IOR"].default_value = 1.33
film, film_shader = principled("MAT_Concept_Polyethylene_Film", "B9CECC", 0.46)
film_shader.inputs["Transmission Weight"].default_value = 0.52
film_shader.inputs["IOR"].default_value = 1.38
film_nodes = film.node_tree.nodes
film_links = film.node_tree.links
film_output = film_nodes.get("Material Output")
film_links.remove(film_output.inputs["Surface"].links[0])
film_coord = film_nodes.new("ShaderNodeTexCoord")
film_dust = film_nodes.new("ShaderNodeTexNoise")
film_dust.inputs["Scale"].default_value = 0.95
film_dust.inputs["Detail"].default_value = 2
film_links.new(film_coord.outputs["Object"], film_dust.inputs["Vector"])
film_color = film_nodes.new("ShaderNodeValToRGB")
film_color.color_ramp.elements[0].color = linear("B5C6C4")
film_color.color_ramp.elements[1].color = linear("D6DEDA")
film_links.new(film_dust.outputs["Fac"], film_color.inputs["Fac"])
film_links.new(film_color.outputs["Color"], film_shader.inputs["Base Color"])
film_scratches = film_nodes.new("ShaderNodeTexWave")
film_scratches.wave_type = "BANDS"
film_scratches.bands_direction = "X"
film_scratches.inputs["Scale"].default_value = 95
film_scratches.inputs["Distortion"].default_value = 18
film_links.new(film_coord.outputs["Object"], film_scratches.inputs["Vector"])
film_bump = film_nodes.new("ShaderNodeBump")
film_bump.inputs["Strength"].default_value = 0.045
film_bump.inputs["Distance"].default_value = 0.0007
film_links.new(film_scratches.outputs["Fac"], film_bump.inputs["Height"])
film_links.new(film_bump.outputs["Normal"], film_shader.inputs["Normal"])
transparent = film_nodes.new("ShaderNodeBsdfTransparent")
blend = film_nodes.new("ShaderNodeMixShader")
fresnel = film_nodes.new("ShaderNodeFresnel")
fresnel.inputs["IOR"].default_value = 1.38
opacity = film_nodes.new("ShaderNodeMapRange")
opacity.clamp = True
opacity.inputs["From Min"].default_value = 0.03
opacity.inputs["From Max"].default_value = 0.45
opacity.inputs["To Min"].default_value = 0.32
opacity.inputs["To Max"].default_value = 0.78
film_links.new(fresnel.outputs["Fac"], opacity.inputs["Value"])
film_links.new(opacity.outputs["Result"], blend.inputs[0])
film_links.new(transparent.outputs[0], blend.inputs[1])
film_links.new(film_shader.outputs[0], blend.inputs[2])
film_links.new(blend.outputs[0], film_output.inputs["Surface"])

box("Ground around greenhouse", (0, 0, -0.35), (150, 150, 0.55), site, concept)
box("Perimeter concrete foundation", (0, 0, -0.04), (26.75, 13.45, 0.15), concrete, concept, 0.055)
box("Central varied gravel service aisle", (0, 0, 0.045), (25.4, 1.25, 0.035), aisle, concept, 0.015)
for across in BED_CENTERS:
    box("Raised wet tomato bed", (0, -across, 0.17), (22.2, 1.05, 0.34), soil, concept, 0.09)
roof_film(concept, film)
add_tomatoes(concept, (leaf_a, leaf_b, leaf_old),
             (red, green, leaf_a, red_spot, green_spot), stem, dew, dew_collection)
add_aisle_details(concept, leaf_b, leaf_old)

# The original source includes several long cylinders whose parent transforms
# turn them upright. Exclude those malformed meshes in this review copy.
for obj in bpy.data.objects:
    if obj.type == "MESH" and any((obj.matrix_world @ Vector(corner)).z > 7.0 for corner in obj.bound_box):
        obj.hide_render = True

drip, _ = principled("MAT_Concept_Drip_Polymer", "1C2827", 0.58)
for across in BED_CENTERS:
    bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=0.023, depth=21.0,
                                        location=(0, -across - 0.22, 0.37))
    pipe = bpy.context.object
    pipe.name = "Visible drip irrigation lateral"
    pipe.rotation_euler.y = math.pi / 2
    pipe.data.materials.append(drip)
    for previous in tuple(pipe.users_collection):
        previous.objects.unlink(pipe)
    concept.objects.link(pipe)

sensor_shell, _ = principled("MAT_Concept_IoT_Sensor", "DFE5DF", 0.36)
sensor_face, _ = principled("MAT_Concept_IoT_Louver", "273C3D", 0.72)
for along, across in ((-4.0, -3.25), (4.0, 3.25)):
    box("Mounted IoT temperature-humidity sensor", (along, -across, 3.65),
        (0.2, 0.15, 0.29), sensor_shell, concept, 0.018)
    for slot in range(3):
        box("Sensor vent slot", (along, -across - 0.078, 3.58 + 0.053 * slot),
            (0.13, 0.008, 0.011), sensor_face, concept)

for name in ("MAT_Galvanized_Steel", "MAT_Galvanized_Light", "MAT_Aluminum_Brushed"):
    material = bpy.data.materials.get(name)
    if material:
        shader = material.node_tree.nodes.get("Principled BSDF")
        shader.inputs["Roughness"].default_value = 0.42
        shader.inputs["Metallic"].default_value = 0.8

world = bpy.data.worlds.new("Clear agricultural daylight")
world.use_nodes = True
background = world.node_tree.nodes.get("Background")
background.inputs["Color"].default_value = linear("A5C7E0")
background.inputs["Strength"].default_value = 0.95
scene.world = world

sun_data = bpy.data.lights.new("Soft afternoon sunlight", "SUN")
sun = bpy.data.objects.new("Soft afternoon sunlight", sun_data)
concept.objects.link(sun)
sun.rotation_euler = (math.radians(27), math.radians(-25), math.radians(-32))
sun_data.energy = 1.65
sun_data.angle = math.radians(10)

sky_fill_data = bpy.data.lights.new("Diffuse daylight through film", "AREA")
sky_fill = bpy.data.objects.new("Diffuse daylight through film", sky_fill_data)
concept.objects.link(sky_fill)
sky_fill.location = (0, 0, 10)
sky_fill_data.shape = "RECTANGLE"
sky_fill_data.size = 30
sky_fill_data.size_y = 18
sky_fill_data.energy = 2800

for index, (along, across) in enumerate(((-5.5, -2.8), (4.5, 2.8)), 1):
    patch_data = bpy.data.lights.new(f"Soft film-diffused daylight patch {index}", "AREA")
    patch = bpy.data.objects.new(f"Soft film-diffused daylight patch {index}", patch_data)
    concept.objects.link(patch)
    patch.location = (along, across, 5.25)
    patch_data.shape = "DISK"
    patch_data.size = 7.0
    patch_data.energy = 460

camera_data = bpy.data.cameras.new("Physical 45mm review camera")
camera = bpy.data.objects.new("Physical 45mm review camera", camera_data)
concept.objects.link(camera)
camera.location = (29.0, -32.0, 13.2)
focus = Vector((0.0, 0.0, 2.0))
camera.rotation_euler = (focus - camera.location).to_track_quat("-Z", "Y").to_euler()
camera_data.type = "PERSP"
camera_data.lens = 45
camera_data.sensor_width = 36
camera_data.dof.use_dof = True
camera_data.dof.focus_distance = (focus - camera.location).length
camera_data.dof.aperture_fstop = 8.0
scene.camera = camera

scene.render.engine = "CYCLES"
scene.cycles.samples = 48
scene.cycles.use_denoising = True
scene.render.resolution_x = 1920
scene.render.resolution_y = 1080
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.filepath = str(EXTERIOR)
scene.render.film_transparent = False
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
scene.view_settings.exposure = 0.0

bpy.ops.render.render(write_still=True)

interior_data = bpy.data.cameras.new("Physical 35mm interior review camera")
interior_camera = bpy.data.objects.new("Physical 35mm interior review camera", interior_data)
concept.objects.link(interior_camera)
interior_camera.location = (8.0, 3.25, 1.72)
interior_focus = Vector((-3.0, 3.25, 1.55))
interior_camera.rotation_euler = (interior_focus - interior_camera.location).to_track_quat("-Z", "Y").to_euler()
interior_data.lens = 33
interior_data.sensor_width = 36
interior_data.dof.use_dof = True
interior_data.dof.focus_distance = (interior_focus - interior_camera.location).length
interior_data.dof.aperture_fstop = 6.3
scene.camera = interior_camera
scene.render.filepath = str(INTERIOR)
scene.view_settings.exposure = 0.42
bpy.ops.render.render(write_still=True)
dew_collection.hide_render = True
scene.render.filepath = str(DRY_INTERIOR)
bpy.ops.render.render(write_still=True)
dew_collection.hide_render = False
scene.camera = camera
scene.render.filepath = str(EXTERIOR)
scene.view_settings.exposure = 0.0
bpy.context.preferences.filepaths.save_version = 0
bpy.ops.wm.save_as_mainfile(filepath=str(SCENE_COPY))
print(f"REVIEW_RENDER {EXTERIOR}")
print(f"REVIEW_RENDER {INTERIOR}")
print(f"REVIEW_RENDER {DRY_INTERIOR}")
print(f"REVIEW_SCENE {SCENE_COPY}")
