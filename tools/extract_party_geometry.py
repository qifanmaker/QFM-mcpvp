#!/usr/bin/env python3
"""Extract original Minecraft Party 2 arena geometry for the 1.21.1 port.

Why: the original worlds target 1.21.10. Copying complete worlds would also run
global command logic; this tool keeps the original arena blocks while the mod
implements gameplay separately. Original map authors retain ownership of their
map; source: https://www.minecraftmaps.com/52024-minecraft-party-2

This is a read-only NBT/Anvil conversion. Commands, entity NBT, block entity NBT,
resource packs and datapack code are never copied or executed. The source
functions are hashed only to record which version defined the arena bounds.

Usage (from repository root):
    python3 tools/extract_party_geometry.py

Only Python's standard library is required. Output contains non-air blocks at
coordinates relative to bounds.min, preserving blockstate properties. Gzip
timestamps are fixed so repeated extraction produces identical resource bytes.
"""

from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib


SOURCE_URL = "https://www.minecraftmaps.com/52024-minecraft-party-2"
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
OMIT_BLOCKS = {
    "minecraft:command_block", "minecraft:chain_command_block",
    "minecraft:repeating_command_block", "minecraft:structure_block",
    "minecraft:jigsaw",
}
# Only Tile Capture's medium crop contains newer decorative plants. Retain the
# original collision role as closely as 1.21.1 permits; every replaced block is
# counted in the resource metadata so these nine approximations stay visible.
REPLACEMENTS: dict[str, str] = {
    "minecraft:wildflowers": "minecraft:pink_petals",
    "minecraft:firefly_bush": "minecraft:fern",
    "minecraft:pale_moss_carpet": "minecraft:moss_carpet",
    "minecraft:pale_hanging_moss": "minecraft:hanging_roots",
}


class NbtReader:
    """Minimal Java NBT reader, including the packed section long arrays."""

    def __init__(self, data: bytes):
        self.stream = io.BytesIO(data)

    def read(self, size: int) -> bytes:
        if size < 0:
            raise ValueError("Negative NBT length")
        result = self.stream.read(size)
        if len(result) != size:
            raise ValueError("Truncated NBT data")
        return result

    def number(self, fmt: str):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self) -> str:
        return self.read(self.number("H")).decode("utf-8", errors="replace")

    def value(self, tag: int):
        scalar = {1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}
        if tag in scalar:
            return self.number(scalar[tag])
        if tag == 7:
            return self.read(self.number("i"))
        if tag == 8:
            return self.string()
        if tag == 9:
            child, length = self.number("b"), self.number("i")
            if length < 0:
                raise ValueError("Negative NBT list length")
            return [self.value(child) for _ in range(length)]
        if tag == 10:
            result = {}
            while child := self.number("b"):
                name = self.string()
                result[name] = self.value(child)
            return result
        if tag in (11, 12):
            length = self.number("i")
            fmt = "i" if tag == 11 else "q"
            data = self.read(length * struct.calcsize(fmt))
            return struct.unpack(">" + fmt * length, data)
        raise ValueError(f"Unsupported NBT tag {tag}")

    def root(self):
        tag = self.number("b")
        self.string()
        return self.value(tag)


class AnvilWorld:
    def __init__(self, folder: Path):
        self.folder = folder
        self.regions: dict[tuple[int, int], bytes] = {}

    def chunk(self, x: int, z: int):
        region = (x // 32, z // 32)
        if region not in self.regions:
            path = self.folder / "region" / f"r.{region[0]}.{region[1]}.mca"
            self.regions[region] = path.read_bytes()
        data = self.regions[region]
        location = ((x % 32) + (z % 32) * 32) * 4
        offset = int.from_bytes(data[location:location + 3], "big") * 4096
        if offset == 0:
            raise ValueError(f"Source crop contains missing chunk {x}, {z}")
        length = int.from_bytes(data[offset:offset + 4], "big")
        compression = data[offset + 4]
        payload = data[offset + 5:offset + 4 + length]
        if compression == 1:
            payload = gzip.decompress(payload)
        elif compression == 2:
            payload = zlib.decompress(payload)
        elif compression != 3:
            raise ValueError(f"Unsupported chunk compression {compression}")
        return NbtReader(payload).root()

    def blocks(self, minimum, maximum):
        for cx in range(minimum[0] // 16, maximum[0] // 16 + 1):
            for cz in range(minimum[2] // 16, maximum[2] // 16 + 1):
                chunk = self.chunk(cx, cz)
                for section in chunk.get("sections", []):
                    sy = section["Y"] * 16
                    if sy > maximum[1] or sy + 15 < minimum[1]:
                        continue
                    states = section.get("block_states", {})
                    palette = states.get("palette", [])
                    if not palette:
                        continue
                    packed = states.get("data", ())
                    bits = max(4, (len(palette) - 1).bit_length())
                    per_long = 64 // bits
                    mask = (1 << bits) - 1
                    for y in range(max(sy, minimum[1]), min(sy + 15, maximum[1]) + 1):
                        for z in range(max(cz * 16, minimum[2]), min(cz * 16 + 15, maximum[2]) + 1):
                            for x in range(max(cx * 16, minimum[0]), min(cx * 16 + 15, maximum[0]) + 1):
                                index = ((y - sy) << 8) | ((z & 15) << 4) | (x & 15)
                                state_id = 0 if not packed else (
                                    (packed[index // per_long] >> ((index % per_long) * bits)) & mask
                                )
                                yield (x, y, z), palette[state_id]

    def block_entity_counts(self, minimum, maximum):
        counts = Counter()
        for cx in range(minimum[0] // 16, maximum[0] // 16 + 1):
            for cz in range(minimum[2] // 16, maximum[2] // 16 + 1):
                for entity in self.chunk(cx, cz).get("block_entities", []):
                    if all(minimum[axis] <= entity[coordinate] <= maximum[axis]
                           for axis, coordinate in enumerate(("x", "y", "z"))):
                        counts[entity["id"]] += 1
        return dict(counts)


def specifications():
    # Spleef uses the immutable clone source, not a potentially damaged playfield.
    specs = {
        "spleef_small": {
            "min": (-434, -26, -196), "max": (-339, 21, -109),
            "functions": ["spleef", "spleef2", "spleef3"],
            "notes": ["Original map 2 clone template. Runtime translation is (416,11,677)."],
        },
        "spleef_large": {
            "min": (-260, -2, -331), "max": (-231, 13, -302),
            "functions": ["spleef", "spleef2", "spleef3"],
            "notes": ["Original map 1 clone template. Runtime translation is (30,0,0).",
                      "Pregame barrier blocks are preserved; the gameplay session removes them at start."],
        },
        "red_light_green_light": {
            "min": (-513, 22, -134), "max": (-488, 34, 22),
            "functions": ["rlgl", "rlgl_start", "rlgl_tick"],
            "notes": ["Includes floor, obstacles and walls within the selected 26x13x157 crop.",
                      "Decorative entities and geometry outside the crop are not included."],
        },
    }
    for label, floor, half in [("small", 5, 13), ("medium", -9, 14),
                              ("large", -23, 16), ("crowd", -36, 17)]:
        specs["tile_capture_" + label] = {
            "min": (-124 - half - 3, floor - 3, -140 - half - 3),
            "max": (-124 + half + 3, floor + 5, -140 + half + 3),
            "floor": (floor, half),
            "functions": ["tile_capture", "tile_capture_start"],
            "notes": ["Playfield is reset to white wool exactly as in tile_capture_start.",
                      "Crop includes three blocks of surrounding geometry and floor-3 through floor+5."],
        }
    return specs


def next_stage_specifications():
    # Preserve the original clone sources and the complete playable footprint.
    # These crops deliberately contain no executable NBT: Java rounds reproduce
    # the separately audited random generators, interaction and scoring rules.
    return {
        "dropper_start_platform": {
            "min": (-144, 8, -48), "max": (-128, 9, -32),
            "functions": ["dropper", "dropper_generate", "dropper_generate2", "dropper_generate3"],
            "notes": ["Immutable 17x2x17 launch platform; dropper_generate clones it to (-192,318,-49)."],
        },
        "dropper_rings": {
            "min": (-144, 11, -48), "max": (-120, 26, -24),
            "functions": ["dropper_generate2", "dropper_generate3"],
            "notes": ["Sixteen 25x1x25 ring slices; color_cycle 0..15 selects source Y=26-color_cycle.",
                      "The Java round must reproduce source ring offsets and clear each destination slice before pasting."],
        },
        "dropper_pool": {
            "min": (-192, -64, -49), "max": (-176, -60, -33),
            "functions": ["dropper", "dropper_tick", "cleanup_dropper"],
            "notes": ["Original live tower base: Y=-64 is 289 smooth_quartz; Y=-63 is 225 water plus 64 rim blocks.",
                      "Source completion requires Y<=-61 and water at the player's feet; tower walls are generated separately."],
        },
        "rainbow_run": {
            "min": (-136, -64, -340), "max": (-44, -17, -245),
            "functions": ["rainbow_run", "rainbow_run_start", "rainbow_run_tick", "rainbow_run_end"],
            "notes": ["Exact immutable clone box in rainbow_run; runtime destination (-21,-64,501), translation (115,0,841).",
                      "Source wind-charge shooters are entities and are supplied separately by the Java round."],
        },
        "runner_small": {
            "min": (173, -57, -35), "max": (194, -50, -14),
            "functions": ["runner", "runner_start", "runner_tick2"],
            "notes": ["Exact runner clone source for <=4 players; destination (173,4,-3).",
                      "Oak planks occur only at source Y=-57 and -50, 256 blocks on each layer.",
                      "Clear the entire destination box before overlaying this template on runner_shell."],
        },
        "runner_large": {
            "min": (173, -57, -5), "max": (194, -50, 16),
            "functions": ["runner", "runner_start", "runner_tick2"],
            "notes": ["Exact runner clone source for >=5 players; destination (173,4,-3).",
                      "Oak planks occur only at source Y=-57 and -50, 484 blocks on each layer.",
                      "Clear the entire destination box before overlaying this template on runner_shell."],
        },
        "runner_shell": {
            "min": (168, -40, -8), "max": (199, 40, 23),
            "functions": ["runner", "runner_tick2", "runnerstillcheck"],
            "notes": ["Live shaft surrounds the function's floor area x173..194,z-3..18 and lower cleanup area x170..197,z-6..21.",
                      "Includes original walls, bedrock/nether decoration and bottom support; source shaft top ends at Y=40.",
                      "Live floor must be replaced by the immutable small/large template; distant spectator scenery outside this crop is omitted."],
        },
        "memory_lane_first": {
            "min": (-89, 35, -317), "max": (-7, 50, -303),
            "functions": ["memory", "memory_start", "memory_generate", "memory_wrong_choice", "memory_tick"],
            "notes": ["First original reference chamber and five player chambers, centered at Z=-311; includes penalty position (-83,40,-304).",
                      "Bounds include original chamber floors, roof overhangs, lapis reference-zone marker and button walls.",
                      "Canvas contents and stone/button reset are supplied by Java; source sign text NBT and exterior scenery are omitted."],
        },
        "memory_lane_second": {
            "min": (-89, 32, -557), "max": (-7, 52, -543),
            "functions": ["memory", "memory_start", "memory_generate", "memory_wrong_choice", "memory_tick"],
            "notes": ["Second original reference chamber and five player chambers, centered at Z=-551; includes penalty position (-79,38,-544).",
                      "Preserves source group's distinct geometry rather than copying the first group; source reference canvas is cloned down 3 and Z-240.",
                      "Canvas contents and stone/button reset are supplied by Java; source sign text NBT and exterior scenery are omitted."],
        },
        "meat_slap": {
            "min": (-139, 25, -316), "max": (-90, 56, -241),
            "functions": ["meat_slap", "meat_slap_start", "meat_slap_tag", "meat_slap_tick", "end_meat_slap"],
            "notes": ["Horizontal bounds are the forceload rectangle in meat_slap; live orange canyon includes lower walkways around Y=29.",
                      "Vertical bounds include original support at Y=25 and terrain through the highest non-air layer Y=56.",
                      "Marker entities, source day-cycle lamp changes, signs and decorative NBT are not copied."],
        },
        "one_shot_wonder": {
            "min": (108, 17, -57), "max": (142, 43, -25),
            "functions": ["oitq", "oitq_rules", "oitq_start", "oitq_tick", "oitq_end"],
            "notes": ["Horizontal bounds match oitq_tick player containment x108..142,z-57..-25; includes floor support and roof at Y=43.",
                      "All original initial spawn and random respawn marker positions lie inside this crop; entities and exterior scenery are omitted."],
        },
    }


def supported_block_names(source_jar: Path) -> set[str]:
    with zipfile.ZipFile(source_jar) as archive:
        source = archive.read("net/minecraft/block/Blocks.java").decode("utf-8")
    return {"minecraft:" + name for name in re.findall(r'register\(\s*"([^"]+)"', source)}


def extract(world, name, spec, data_pack, supported):
    minimum, maximum = spec["min"], spec["max"]
    blocks = {}
    omitted, replaced = Counter(), Counter()
    for position, original in world.blocks(minimum, maximum):
        block_name = original["Name"]
        if block_name in AIR:
            continue
        if block_name in OMIT_BLOCKS:
            omitted[block_name] += 1
            continue
        state = dict(original)
        if block_name in REPLACEMENTS:
            state = {"Name": REPLACEMENTS[block_name]}
            if block_name == "minecraft:wildflowers" and "Properties" in original:
                # Both flowers use facing and flower_amount in their blockstate.
                state["Properties"] = original["Properties"]
            replaced[block_name + " -> " + state["Name"]] += 1
        if state["Name"] not in supported:
            raise ValueError(f"{name}: unsupported 1.21.1 block {state['Name']}; add an explicit substitution")
        blocks[position] = state
    if "floor" in spec:
        floor, half = spec["floor"]
        for x in range(-124 - half, -124 + half + 1):
            for z in range(-140 - half, -140 + half + 1):
                blocks[(x, floor, z)] = {"Name": "minecraft:white_wool"}

    palette, indices, output = [], {}, []
    for position, state in sorted(blocks.items()):
        key = json.dumps(state, sort_keys=True)
        if key not in indices:
            indices[key] = len(palette)
            palette.append(state)
        output.append([position[axis] - minimum[axis] for axis in range(3)] + [indices[key]])
    functions = {
        function: hashlib.sha256(data_pack.read("data/mcp/function/" + function + ".mcfunction")).hexdigest()
        for function in spec["functions"]
    }
    return {
        "formatVersion": 1,
        "source": {"name": "Minecraft Party 2", "url": SOURCE_URL,
                   "minecraftVersion": "1.21.10", "functionSha256": functions},
        "bounds": {"min": minimum, "max": maximum},
        "size": [maximum[axis] - minimum[axis] + 1 for axis in range(3)],
        "palette": palette, "blocks": output,
        "notes": spec["notes"] + ["Block states only; no entities, block entity NBT, commands or source resource pack."],
        "omitted": dict(omitted), "replacements": dict(replaced),
        "omittedBlockEntityNbt": world.block_entity_counts(minimum, maximum),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, default=Path("run/external-maps/templates/minecraft-party-2"))
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party/geometry"))
    parser.add_argument("--minecraft-sources", type=Path,
                        help="1.21.1 mapped Minecraft sources jar; defaults to the local Loom cache")
    parser.add_argument("--only", nargs="+", help="Extract only these resource names, leaving other files byte-for-byte unchanged")
    args = parser.parse_args()
    source_jar = args.minecraft_sources
    if source_jar is None:
        candidates = sorted(Path(".gradle/loom-cache/minecraftMaven/net/minecraft").glob("**/1.21.1-*/*-sources.jar"))
        if not candidates:
            parser.error("Pass --minecraft-sources pointing to the mapped 1.21.1 sources jar")
        source_jar = candidates[0]
    supported = supported_block_names(source_jar)
    world = AnvilWorld(args.world)
    specs = specifications() | next_stage_specifications()
    if args.only:
        unknown = sorted(set(args.only) - specs.keys())
        if unknown:
            parser.error("Unknown resource names: " + ", ".join(unknown))
        specs = {name: specs[name] for name in args.only}
    # Prepare every selected result before writing, so unsupported blocks cannot
    # leave a partially updated set of resources.
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as data_pack:
        results = {name: extract(world, name, spec, data_pack, supported)
                   for name, spec in specs.items()}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, result in results.items():
        data = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        compressed = gzip.compress(data, compresslevel=9, mtime=0)
        (args.output / (name + ".json.gz")).write_bytes(compressed)
        print(f"{name}: {result['size']}, {len(result['blocks'])} blocks, "
              f"{len(result['palette'])} states, {len(compressed)} bytes; "
              f"omitted={result['omitted']}, replacements={result['replacements']}")


if __name__ == "__main__":
    main()
