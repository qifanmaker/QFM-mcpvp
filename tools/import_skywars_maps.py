#!/usr/bin/env python3
"""Convert the credited legacy SkyWars downloads into sanitized 1.21.1 resources.

Requires Python 3.10+ and ``pip install nbtlib numpy pillow``. Download the source
schematics from the URLs recorded below, then run, for example::

    python tools/import_skywars_maps.py --forest /tmp/forest.schematic \
        --unresting-desert /tmp/desert.schematic --preview-dir /tmp/skywars-preview

Only explicit numeric block mappings are accepted. Original block-entity NBT,
entities and inventories are never copied. Source beds and operator/structure
blocks are removed. Both source downloads contain no glass spawn cages.
"""

from __future__ import annotations

import argparse
from collections import Counter, deque
from dataclasses import dataclass
import gzip
import hashlib
import io
import json
import math
from pathlib import Path

import nbtlib
import numpy as np
from PIL import Image, ImageDraw


DATA_VERSION = 3955
COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
WOODS = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")
STONE_SLABS = (
    "smooth_stone", "sandstone", "petrified_oak", "cobblestone", "brick",
    "stone_brick", "nether_brick", "quartz",
)
FACING = {"east": (1, 0, 0), "west": (-1, 0, 0), "south": (0, 0, 1), "north": (0, 0, -1)}
OPPOSITE = {"east": "west", "west": "east", "south": "north", "north": "south"}
COUNTERCLOCKWISE = {"east": "north", "north": "west", "west": "south", "south": "east"}
NEIGHBORS = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
REMOVED_IDS = {0, 26, 137, 166, 210, 211, 217, 255}


@dataclass(frozen=True)
class Preset:
    id: str
    name: str
    author: str
    source: str
    license: str
    anchor: tuple[int, int, int]
    outer_ground_y: int
    final_radius: int


PRESETS = {
    "forest": Preset(
        "forest", "林间群岛", "Aquila4",
        "https://www.planetminecraft.com/project/skywars-map-forest-theme/",
        "Author permits server use and incorporation into projects with full credit to Aquila4.",
        (46, 28, 43), 24, 12,
    ),
    "unresting_desert": Preset(
        "unresting_desert", "不眠沙漠", "Lord_Therd",
        "https://www.planetminecraft.com/project/free-download-skywars-map-the-unresting-desert/",
        "Author states FREE FOR EVERYONE TO USE; credit Lord_Therd when used on a server.",
        (65, 46, 52), 46, 8,
    ),
}


def state(name: str, **properties: str) -> tuple[str, tuple[tuple[str, str], ...]]:
    return "minecraft:" + name, tuple(sorted(properties.items()))


AIR = state("air")


def add(pos, delta):
    return tuple(a + b for a, b in zip(pos, delta))


def legacy_state(block_id: int, data: int, pos, source):
    """Map the complete material/metadata set used by the two source schematics."""
    if block_id in REMOVED_IDS:
        return AIR
    if block_id == 1 and data in range(7):
        return state(("stone", "granite", "polished_granite", "diorite", "polished_diorite",
                      "andesite", "polished_andesite")[data])
    simple = {2: "grass_block", 4: "cobblestone", 13: "gravel", 32: "dead_bush",
              48: "mossy_cobblestone", 58: "crafting_table", 172: "terracotta"}
    if block_id in simple and data == 0:
        return state(simple[block_id])
    if block_id == 3 and data in range(3):
        return state(("dirt", "coarse_dirt", "podzol")[data])
    if block_id == 5 and data in range(6):
        return state(WOODS[data] + "_planks")
    if block_id in (8, 9) and data in range(16):
        return state("water", level=str(data))
    if block_id == 12 and data in (0, 1):
        return state("red_sand" if data else "sand")
    if block_id == 17 and data in range(16):
        wood = WOODS[data & 3]
        axis = data & 12
        return state(wood + ("_wood" if axis == 12 else "_log"),
                     axis={0: "y", 4: "x", 8: "z", 12: "y"}[axis])
    if block_id == 18 and data in range(16):
        # Preserving authored tree canopies avoids leaf decay after importing.
        return state(WOODS[data & 3] + "_leaves", distance="7", persistent="true", waterlogged="false")
    if block_id == 24 and data in range(3):
        return state(("sandstone", "chiseled_sandstone", "cut_sandstone")[data])
    if block_id == 31 and data in range(3):
        return state(("dead_bush", "short_grass", "fern")[data])
    if block_id == 35 and data in range(16):
        return state(COLORS[data] + "_wool")
    if block_id == 38 and data in range(9):
        return state(("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
                      "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy")[data])
    if block_id == 43 and data in range(16):
        # Legacy seamless double sandstone has the smooth sandstone texture.
        if data == 8:
            return state("smooth_stone")
        if data == 9:
            return state("smooth_sandstone")
        if data == 15:
            return state("smooth_quartz")
        if data >= 8:
            raise ValueError(f"Unsupported legacy double slab metadata {data} at {pos}")
        return state(STONE_SLABS[data] + "_slab", type="double", waterlogged="false")
    if block_id == 44 and data in range(16):
        return state(STONE_SLABS[data & 7] + "_slab", type="top" if data & 8 else "bottom", waterlogged="false")
    if block_id in (54, 146) and data in (2, 3, 4, 5):
        return state("chest" if block_id == 54 else "trapped_chest",
                     facing={2: "north", 3: "south", 4: "west", 5: "east"}[data],
                     type="single", waterlogged="false")
    if block_id == 65 and data in (2, 3, 4, 5):
        return state("ladder", facing={2: "north", 3: "south", 4: "west", 5: "east"}[data], waterlogged="false")
    if block_id in (67, 109, 128) and data in range(8):
        return state({67: "cobblestone_stairs", 109: "stone_brick_stairs", 128: "sandstone_stairs"}[block_id],
                     facing=("east", "west", "south", "north")[data & 3],
                     half="top" if data & 4 else "bottom", shape="straight", waterlogged="false")
    if block_id == 77 and (data & 7) in range(6):
        direction = data & 7
        return state("stone_button", face="ceiling" if direction == 0 else "floor" if direction == 5 else "wall",
                     facing={0: "north", 1: "east", 2: "west", 3: "south", 4: "north", 5: "north"}[direction],
                     powered="true" if data & 8 else "false")
    if block_id == 81 and data in range(16):
        return state("cactus", age=str(data))
    if block_id in (85, 188) and data == 0:
        return state("oak_fence" if block_id == 85 else "spruce_fence", waterlogged="false",
                     north="false", south="false", east="false", west="false")
    if block_id == 97 and data in range(6):
        # Keep the appearance while preventing authored terrain spawning silverfish.
        return state(("stone", "cobblestone", "stone_bricks", "mossy_stone_bricks",
                      "cracked_stone_bricks", "chiseled_stone_bricks")[data])
    if block_id in (125, 126) and (data & 7) in range(6):
        return state(WOODS[data & 7] + "_slab",
                     type="double" if block_id == 125 else "top" if data & 8 else "bottom", waterlogged="false")
    if block_id == 139 and data in (0, 1):
        return state("mossy_cobblestone_wall" if data else "cobblestone_wall", waterlogged="false",
                     up="true", north="none", south="none", east="none", west="none")
    if block_id == 194 and data in range(16):
        is_upper = bool(data & 8)
        lower = source.get(add(pos, (0, -1, 0)), (0, 0)) if is_upper else (block_id, data)
        upper = (block_id, data) if is_upper else source.get(add(pos, (0, 1, 0)), (0, 0))
        if lower[0] != block_id or upper[0] != block_id:
            raise ValueError(f"Incomplete legacy birch door at {pos}")
        return state("birch_door", facing=("east", "south", "west", "north")[lower[1] & 3],
                     half="upper" if is_upper else "lower", hinge="right" if upper[1] & 1 else "left",
                     open="true" if lower[1] & 4 else "false", powered="true" if upper[1] & 2 else "false")
    raise ValueError(f"Unknown legacy block {block_id}:{data} at {pos}; refusing to lose source blocks")


def full_cube(block):
    name, props = block
    name = name.removeprefix("minecraft:")
    if name.endswith("_slab"):
        return dict(props)["type"] == "double"
    return name not in ("air", "water", "short_grass", "fern", "dead_bush", "oxeye_daisy", "poppy",
                        "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
                        "pink_tulip", "ladder", "stone_button", "cactus", "chest", "trapped_chest") \
        and not name.endswith(("_leaves", "_fence", "_wall", "_stairs", "_door"))


def resolve_connections(blocks):
    """Preserve fence/wall arms and stair corners without requiring physics updates."""
    for pos, block in list(blocks.items()):
        name, props_tuple = block
        props = dict(props_tuple)
        if name.endswith(("_fence", "_wall")):
            wall = name.endswith("_wall")
            for direction, delta in FACING.items():
                neighbor = blocks.get(add(pos, delta), AIR)
                connected = neighbor[0].endswith("_wall" if wall else "_fence") or full_cube(neighbor)
                props[direction] = ("low" if connected else "none") if wall else str(connected).lower()
            blocks[pos] = (name, tuple(sorted(props.items())))
        elif name.endswith("_stairs"):
            facing, half = props["facing"], props["half"]

            def stair_at(at):
                neighbor = blocks.get(at, AIR)
                return dict(neighbor[1]) if neighbor[0].endswith("_stairs") else None

            def orientation_differs(direction):
                neighbor = stair_at(add(pos, FACING[direction]))
                return not neighbor or neighbor["facing"] != facing or neighbor["half"] != half

            front = stair_at(add(pos, FACING[facing]))
            behind = stair_at(add(pos, FACING[OPPOSITE[facing]]))
            if front and front["half"] == half and (FACING[front["facing"]][0] == 0) != (FACING[facing][0] == 0) \
                    and orientation_differs(OPPOSITE[front["facing"]]):
                props["shape"] = "outer_left" if front["facing"] == COUNTERCLOCKWISE[facing] else "outer_right"
            elif behind and behind["half"] == half and (FACING[behind["facing"]][0] == 0) != (FACING[facing][0] == 0) \
                    and orientation_differs(behind["facing"]):
                props["shape"] = "inner_left" if behind["facing"] == COUNTERCLOCKWISE[facing] else "inner_right"
            blocks[pos] = (name, tuple(sorted(props.items())))


def connected_components(source):
    remaining = set(source)
    components = []
    while remaining:
        first = min(remaining)
        remaining.remove(first)
        component, queue = {first}, deque([first])
        while queue:
            pos = queue.popleft()
            for delta in NEIGHBORS:
                neighbor = add(pos, delta)
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    component.add(neighbor)
                    queue.append(neighbor)
        components.append(component)
    return components


def choose_spawn(component, chests, blocks, ground_y):
    center_x = sum(pos[0] for pos in chests) / len(chests)
    center_z = sum(pos[2] for pos in chests) / len(chests)
    candidates = []
    for floor in component:
        if not full_cube(blocks[floor]):
            continue
        spawn = add(floor, (0, 1, 0))
        if any(add(spawn, (0, dy, 0)) in blocks for dy in range(13)):
            continue
        if any(add(spawn, (dx, dy, dz)) in blocks
               for dx in range(-1, 2) for dz in range(-1, 2) for dy in range(9, 13)):
            continue
        score = abs(floor[1] - ground_y) * 20 + (spawn[0] - center_x) ** 2 + (spawn[2] - center_z) ** 2
        candidates.append((score, spawn))
    if not candidates:
        raise ValueError(f"No supported, cage-clear spawn near {center_x}, {center_z}")
    return min(candidates)[1]


def relative(pos, anchor):
    return tuple(a - b for a, b in zip(pos, anchor))


def coordinates(pos):
    return dict(zip(("x", "y", "z"), pos))


def write_structure(path, blocks):
    palette = sorted(set(blocks.values()))
    indices = {block: index for index, block in enumerate(palette)}
    compound = nbtlib.Compound
    entries = []
    for name, properties in palette:
        entry = compound({"Name": nbtlib.String(name)})
        if properties:
            entry["Properties"] = compound({k: nbtlib.String(v) for k, v in properties})
        entries.append(entry)
    mins = tuple(min(pos[axis] for pos in blocks) for axis in range(3))
    maxs = tuple(max(pos[axis] for pos in blocks) for axis in range(3))
    root = compound({
        "DataVersion": nbtlib.Int(DATA_VERSION),
        "size": nbtlib.List[nbtlib.Int]([maxs[i] - mins[i] + 1 for i in range(3)]),
        "palette": nbtlib.List[compound](entries),
        "blocks": nbtlib.List[compound]([
            compound({"pos": nbtlib.List[nbtlib.Int](pos), "state": nbtlib.Int(indices[blocks[pos]])})
            for pos in sorted(blocks, key=lambda p: (p[1], p[2], p[0]))
        ]),
        "entities": nbtlib.List[compound](),
    })
    buffer = io.BytesIO()
    nbtlib.File(root).write(buffer)
    path.write_bytes(gzip.compress(buffer.getvalue(), compresslevel=9, mtime=0))
    return len(palette)


def block_color(block):
    name = block[0].removeprefix("minecraft:")
    if name.endswith("_leaves"):
        return (57, 118, 67)
    if name.endswith(("_log", "_wood")):
        return (113, 80, 48)
    if name == "grass_block":
        return (115, 159, 73)
    if name in ("short_grass", "fern", "cactus"):
        return (100, 146, 60)
    if "sandstone" in name or name == "sand":
        return (215, 190, 133)
    if "dirt" in name or name == "podzol":
        return (135, 104, 67)
    if "chest" in name:
        return (226, 152, 40)
    if name == "water":
        return (63, 139, 199)
    if "terracotta" in name:
        return (159, 112, 86)
    if "wool" in name:
        return (184, 153, 153)
    if any(word in name for word in ("planks", "fence", "door", "ladder")):
        return (164, 131, 86)
    return (147, 149, 143)


def render_preview(path, preset, blocks, spawns, chests):
    # Top projection and a side projection make the island gaps and vertical
    # geometry inspectable; metadata markers use exactly the serialized positions.
    scale = 6
    min_x, max_x = min(p[0] for p in blocks), max(p[0] for p in blocks)
    min_z, max_z = min(p[2] for p in blocks), max(p[2] for p in blocks)
    min_y, max_y = min(p[1] for p in blocks), max(p[1] for p in blocks)
    width = (max_x - min_x + 1) * scale + 80
    top_height = (max_z - min_z + 1) * scale + 90
    side_height = (max_y - min_y + 1) * scale + 90
    image = Image.new("RGB", (max(width, 850), top_height + side_height + 45), "#182236")
    draw = ImageDraw.Draw(image)
    draw.text((20, 14), f"{preset.id} | source: {preset.author} | top view", fill="white")
    columns = {}
    side = {}
    for pos, block in blocks.items():
        x, y, z = pos
        if y >= columns.get((x, z), (-10000, None))[0]:
            columns[(x, z)] = (y, block)
        if z >= side.get((x, y), (-10000, None))[0]:
            side[(x, y)] = (z, block)
    for (x, z), (_, block) in columns.items():
        px, pz = 40 + (x - min_x) * scale, 40 + (z - min_z) * scale
        draw.rectangle((px, pz, px + scale - 1, pz + scale - 1), fill=block_color(block))
    for chest in chests:
        px, pz = 40 + (chest["x"] - min_x) * scale, 40 + (chest["z"] - min_z) * scale
        draw.ellipse((px - 2, pz - 2, px + 4, pz + 4), fill="#ffdd48")
    for index, pos in enumerate(spawns):
        px, pz = 40 + (pos[0] - min_x) * scale, 40 + (pos[2] - min_z) * scale
        draw.rectangle((px - 4, pz - 4, px + 4, pz + 4), fill="#5fdbef", outline="white")
        draw.text((px + 6, pz - 6), str(index + 1), fill="white")
    cx, cz = 40 - min_x * scale, 40 - min_z * scale
    radius = preset.final_radius * scale
    draw.ellipse((cx - radius, cz - radius, cx + radius, cz + radius), outline="#f77f87", width=2)
    draw.text((20, top_height - 30), "Cyan = spawn; yellow = chest; pink = final circle", fill="white")
    draw.text((20, top_height + 5), "Side view (looking north)", fill="white")
    for (x, y), (_, block) in side.items():
        px, py = 40 + (x - min_x) * scale, top_height + 40 + (max_y - y) * scale
        draw.rectangle((px, py, px + scale - 1, py + scale - 1), fill=block_color(block))
    image.save(path)


def convert(source_path, preset, output_root, preview_dir):
    raw = source_path.read_bytes()
    root = nbtlib.load(source_path)
    width, height, length = (int(root[key]) for key in ("Width", "Height", "Length"))
    if width * height * length > 16_000_000:
        raise ValueError("Schematic dimensions exceed the converter safety limit")
    ids = np.asarray(root["Blocks"], dtype=np.uint8).astype(np.uint16)
    if "AddBlocks" in root:
        extra = np.asarray(root["AddBlocks"], dtype=np.uint8)
        for i in range(ids.size):
            ids[i] |= int((extra[i // 2] >> (4 if i & 1 else 0)) & 15) << 8
    data = np.asarray(root["Data"], dtype=np.uint8)
    ids, data = ids.reshape(height, length, width), data.reshape(height, length, width)
    source = {(int(x), int(y), int(z)): (int(ids[y, z, x]), int(data[y, z, x]))
              for y, z, x in zip(*np.where(ids))}
    if any(block_id in (20, 95, 102, 160) for block_id, _ in source.values()):
        raise ValueError("Unreviewed glass geometry: identify source cages before converting a new download")
    blocks = {pos: legacy_state(block_id, metadata, pos, source)
              for pos, (block_id, metadata) in source.items()}
    blocks = {pos: block for pos, block in blocks.items() if block != AIR}
    resolve_connections(blocks)
    components = connected_components(blocks)
    chest_positions = {pos for pos, block in blocks.items() if block[0] in ("minecraft:chest", "minecraft:trapped_chest")}
    spawn_components = []
    for component in components:
        chests = sorted(component & chest_positions)
        if len(chests) == 3:
            spawn_components.append((component, chests))
    if len(spawn_components) != 12 or len(chest_positions) != 44:
        raise ValueError(f"Source topology changed: {len(spawn_components)} spawn islands, {len(chest_positions)} chests")
    anchor_x, _, anchor_z = preset.anchor
    spawn_components.sort(key=lambda item: math.atan2(
        sum(p[2] for p in item[1]) / 3 - anchor_z,
        sum(p[0] for p in item[1]) / 3 - anchor_x) % (2 * math.pi))
    source_spawns = [choose_spawn(component, chests, blocks, preset.outer_ground_y)
                     for component, chests in spawn_components]
    chest_owners = {pos: index for index, (_, chests) in enumerate(spawn_components) for pos in chests}
    normalized = {relative(pos, preset.anchor): block for pos, block in blocks.items()}
    spawns = [relative(pos, preset.anchor) for pos in source_spawns]
    chests = []
    for source_pos in sorted(chest_positions):
        if add(source_pos, (0, 1, 0)) in blocks or not full_cube(blocks.get(add(source_pos, (0, -1, 0)), AIR)):
            raise ValueError(f"Chest lacks support or has a blocked lid: {source_pos}")
        owner = chest_owners.get(source_pos, -1)
        chests.append({**coordinates(relative(source_pos, preset.anchor)),
                       "tier": "SPAWN" if owner >= 0 else "CENTER", "spawnIndex": owner})
    maximum_radius = math.ceil(max(math.hypot(x, z) for x, _, z in normalized)) + 4
    if maximum_radius >= 176 or len(normalized) > 600_000:
        raise ValueError("Imported map exceeds one arena region")
    safe_center = any(math.hypot(pos[0], pos[2]) <= preset.final_radius and full_cube(block)
                      and add(pos, (0, 1, 0)) not in normalized and add(pos, (0, 2, 0)) not in normalized
                      for pos, block in normalized.items())
    if not safe_center:
        raise ValueError("The final circle contains no supported two-block landing space")
    output = output_root / preset.id
    output.mkdir(parents=True, exist_ok=True)
    palette_size = write_structure(output / "terrain.nbt", normalized)
    if palette_size > 4096:
        raise ValueError("Imported palette exceeds the runtime safety limit")
    metadata = {
        "id": preset.id, "name": preset.name, "author": preset.author, "source": preset.source,
        "license": preset.license, "sourceSha256": hashlib.sha256(raw).hexdigest(),
        "maxRadius": maximum_radius, "finalRadius": preset.final_radius,
        "spawns": [coordinates(pos) for pos in spawns], "chests": chests,
        "adaptations": ["移除原始实体、方块实体 NBT 和箱子物资，由模组统一刷新全部箱子。",
                        "森林原有床已移除；银鱼方块转为同外观普通方块，树叶保留不衰减。",
                        "原图无玻璃出生笼，使用模组的十格玻璃笼与五秒倒计时。"],
    }
    (output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if preview_dir:
        preview_dir.mkdir(parents=True, exist_ok=True)
        render_preview(preview_dir / f"{preset.id}.png", preset, normalized, spawns, chests)
    print(json.dumps({"id": preset.id, "blocks": len(normalized), "palette": palette_size,
                      "spawns": len(spawns), "chests": len(chests), "maxRadius": maximum_radius,
                      "removedSourceBlocks": dict(Counter(str(v[0]) for v in source.values() if v[0] in REMOVED_IDS)),
                      "sourceSha256": metadata["sourceSha256"]}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--forest", type=Path)
    parser.add_argument("--unresting-desert", type=Path)
    parser.add_argument("--output-root", type=Path,
                        default=Path(__file__).resolve().parents[1] / "src/main/resources/maps/skywars")
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args()
    if not args.forest and not args.unresting_desert:
        parser.error("provide at least one downloaded source schematic")
    for key in PRESETS:
        source_path = getattr(args, key)
        if source_path:
            convert(source_path, PRESETS[key], args.output_root, args.preview_dir)


if __name__ == "__main__":
    main()
