#!/usr/bin/env python3
"""Convert the credited LesRaisins Dust II world into inert MyGO geometry.

Why: MyGO needs a real downloaded CS map with independently audited spawns.
Copying a whole save would also import player data and world logic. This tool
reads only Anvil region members from the pinned ZIP, never extracts or executes
source files, and emits block states plus our separately reviewed layout.

Usage from the repository root:
    python3 tools/extract_mygo_map.py --archive /path/to/dust2.zip

layout.json contains sourceMin/sourceMax (inclusive source crop), origin (source
coordinate mapped to arena center), and the runtime schema fields consumed by
MyGoMapBlueprint. All runtime layout coordinates are relative to that origin.
Only Python's standard library and the existing read-only NbtReader are used.
"""

from __future__ import annotations

import argparse
from collections import Counter
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile
import zlib

from extract_party_geometry import NbtReader, supported_block_names


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
OMIT = {
    "minecraft:command_block", "minecraft:chain_command_block",
    "minecraft:repeating_command_block", "minecraft:structure_block",
    "minecraft:jigsaw", "minecraft:spawner",
}
REGION_PATTERN = re.compile(r"(?:[^/]+/)?region/r\.(-?\d+)\.(-?\d+)\.mca")
MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_REGION_BYTES = 16 * 1024 * 1024
MAX_CHUNK_BYTES = 8 * 1024 * 1024
MAX_BLOCKS = 1_000_000
# MapBuildQueue flushes with neighbor notifications. Build every architectural
# support before decorations so a later wall placement cannot remove a sign,
# ladder or carpet whose support had not yet been pasted.
ATTACHED_SUFFIXES = (
    "_door", "_trapdoor", "_sign", "_wall_sign", "_hanging_sign", "_wall_hanging_sign",
    "_banner", "_wall_banner", "_head", "_wall_head", "_skull", "_wall_skull",
    "_button", "_pressure_plate", "_carpet", "_torch", "_sapling", "_flower", "_tulip",
)
ATTACHED_BLOCKS = {
    "ladder", "lantern", "soul_lantern", "torch", "wall_torch", "redstone_torch",
    "redstone_wall_torch", "lever", "flower_pot", "short_grass", "tall_grass",
    "fern", "large_fern", "dead_bush", "vine", "lily_pad", "sea_pickle",
    "campfire", "soul_campfire", "redstone_wire", "tripwire", "tripwire_hook",
    "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy",
    "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac",
    "rose_bush", "peony", "brown_mushroom", "red_mushroom", "sugar_cane", "cactus",
    "bamboo", "snow", "rail", "powered_rail", "detector_rail", "activator_rail",
}


def attachment_priority(state: dict) -> int:
    name = state["Name"].removeprefix("minecraft:")
    return int(name in ATTACHED_BLOCKS or name.startswith("potted_") or name.endswith(ATTACHED_SUFFIXES))


def bounded_decompress(data: bytes, compression: int) -> bytes:
    if compression == 3:
        if len(data) > MAX_CHUNK_BYTES:
            raise ValueError("Uncompressed chunk exceeds limit")
        return data
    if compression not in (1, 2):
        raise ValueError(f"Unsupported Anvil compression {compression}")
    decoder = zlib.decompressobj(31 if compression == 1 else zlib.MAX_WBITS)
    result = decoder.decompress(data, MAX_CHUNK_BYTES + 1)
    if len(result) > MAX_CHUNK_BYTES or decoder.unconsumed_tail or decoder.unused_data or not decoder.eof:
        raise ValueError("Truncated or oversized compressed chunk")
    return result


class ArchivedWorld:
    """Read only recognized region files; ZIP paths never reach the filesystem."""

    def __init__(self, archive: zipfile.ZipFile):
        self.archive = archive
        self.members = {}
        self.regions = {}
        self.chunks = {}
        if len(archive.infolist()) > 4096:
            raise ValueError("Unexpectedly large source archive index")
        for member in archive.infolist():
            path = PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in member.filename:
                raise ValueError("Unsafe source ZIP member name")
            matched = REGION_PATTERN.fullmatch(member.filename)
            if matched:
                region = tuple(map(int, matched.groups()))
                if region in self.members or not 8192 <= member.file_size <= MAX_REGION_BYTES:
                    raise ValueError("Duplicate or oversized Anvil region")
                self.members[region] = member
        if not self.members:
            raise ValueError("Source archive contains no overworld Anvil regions")

    def chunk(self, x: int, z: int):
        key = (x, z)
        if key in self.chunks:
            return self.chunks[key]
        region = (x // 32, z // 32)
        if region not in self.regions:
            member = self.members.get(region)
            if member is None:
                raise ValueError(f"Crop contains missing region {region}")
            self.regions[region] = self.archive.read(member)
        data = self.regions[region]
        index = ((x % 32) + (z % 32) * 32) * 4
        offset = int.from_bytes(data[index:index + 3], "big") * 4096
        sector_count = data[index + 3]
        if offset < 8192 or sector_count == 0 or offset + sector_count * 4096 > len(data):
            raise ValueError(f"Crop contains missing or invalid chunk {key}")
        length = int.from_bytes(data[offset:offset + 4], "big")
        if length < 2 or length + 4 > sector_count * 4096:
            raise ValueError(f"Invalid Anvil chunk length {key}")
        payload = bounded_decompress(data[offset + 5:offset + 4 + length], data[offset + 4])
        root = NbtReader(payload).root()
        chunk = dict(root.get("Level", root))
        if "DataVersion" in root:
            chunk["DataVersion"] = root["DataVersion"]
        if (chunk.get("xPos"), chunk.get("zPos")) != key:
            raise ValueError(f"Chunk coordinate mismatch {key}")
        self.chunks[key] = chunk
        return chunk

    def blocks(self, minimum: list[int], maximum: list[int]):
        for cx in range(minimum[0] // 16, maximum[0] // 16 + 1):
            for cz in range(minimum[2] // 16, maximum[2] // 16 + 1):
                chunk = self.chunk(cx, cz)
                for section in chunk.get("Sections", chunk.get("sections", [])):
                    sy = section["Y"] * 16
                    if sy > maximum[1] or sy + 15 < minimum[1]:
                        continue
                    modern = section.get("block_states", {})
                    palette = section.get("Palette", modern.get("palette", []))
                    packed = section.get("BlockStates", modern.get("data", ()))
                    if not palette:
                        continue
                    if len(palette) > 1 and not packed:
                        raise ValueError("Multi-state section lacks packed blockstates")
                    bits = max(4, (len(palette) - 1).bit_length())
                    per_long = 64 // bits
                    if "DataVersion" not in chunk:
                        raise ValueError("Source chunk lacks DataVersion")
                    padded = chunk["DataVersion"] >= 2529
                    expected = (4096 + per_long - 1) // per_long if padded else (4096 * bits + 63) // 64
                    if packed and len(packed) != expected:
                        raise ValueError(f"Invalid packed blockstate count in chunk {(cx, cz)}")
                    for y in range(max(sy, minimum[1]), min(sy + 15, maximum[1]) + 1):
                        for z in range(max(cz * 16, minimum[2]), min(cz * 16 + 15, maximum[2]) + 1):
                            for x in range(max(cx * 16, minimum[0]), min(cx * 16 + 15, maximum[0]) + 1):
                                index = ((y - sy) << 8) | ((z & 15) << 4) | (x & 15)
                                state = palette_index(packed, index, bits, padded)
                                if state >= len(palette):
                                    raise ValueError("Packed blockstate index exceeds palette")
                                yield (x, y, z), palette[state]


def palette_index(packed, index: int, bits: int, padded: bool) -> int:
    if not packed:
        return 0
    mask = (1 << bits) - 1
    if padded:
        per_long = 64 // bits
        return (packed[index // per_long] >> ((index % per_long) * bits)) & mask
    bit = index * bits
    word, shift = divmod(bit, 64)
    value = (packed[word] & ((1 << 64) - 1)) >> shift
    if shift + bits > 64:
        value |= (packed[word + 1] & ((1 << 64) - 1)) << (64 - shift)
    return value & mask


def convert_state(original: dict, replacements: Counter, properties: Counter):
    name = original["Name"]
    if name in AIR or name in OMIT:
        return None
    state = copy.deepcopy(original)
    stable = {
        "minecraft:sand": "minecraft:sandstone",
        "minecraft:red_sand": "minecraft:red_sandstone",
        "minecraft:gravel": "minecraft:andesite",
        "minecraft:anvil": "minecraft:iron_block",
        "minecraft:chipped_anvil": "minecraft:iron_block",
        "minecraft:damaged_anvil": "minecraft:iron_block",
        "minecraft:water": "minecraft:glass",
        "minecraft:lava": "minecraft:orange_stained_glass",
        "minecraft:grass": "minecraft:short_grass",
        "minecraft:grass_path": "minecraft:dirt_path",
    }
    target = stable.get(name)
    if name.endswith("_concrete_powder"):
        target = name.removesuffix("_powder")
    if target:
        state = {"Name": target}
        replacements[f"{name} -> {target}"] += 1
    values = state.get("Properties", {})
    if "waterlogged" in values and values["waterlogged"] != "false":
        values["waterlogged"] = "false"
        properties["waterlogged -> false"] += 1
    if name.endswith("_leaves") and values.get("persistent") != "true":
        values["persistent"] = "true"
        properties["leaves persistent -> true"] += 1
    if name.endswith("campfire") and values.get("lit") != "false":
        values["lit"] = "false"
        properties["campfire lit -> false"] += 1
    if values:
        state["Properties"] = values
    return state


def coordinate(value, label: str) -> list[int]:
    if not isinstance(value, list) or len(value) != 3 or any(type(n) is not int for n in value):
        raise ValueError(f"{label} requires three integer coordinates")
    return value


def extract(archive: Path, layout: dict, provenance: dict, supported: set[str]):
    if archive.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("Source ZIP exceeds size limit")
    archive_data = archive.read_bytes()
    if len(archive_data) > MAX_ARCHIVE_BYTES:
        raise ValueError("Source ZIP exceeds size limit")
    digest = hashlib.sha256(archive_data).hexdigest()
    if digest != provenance["archiveSha256"]:
        raise ValueError("Source ZIP checksum differs from reviewed provenance")
    minimum = coordinate(layout["sourceMin"], "sourceMin")
    maximum = coordinate(layout["sourceMax"], "sourceMax")
    origin = coordinate(layout["origin"], "origin")
    size = [maximum[i] - minimum[i] + 1 for i in range(3)]
    if any(n <= 0 for n in size) or size[0] > 321 or size[1] > 161 or size[2] > 321:
        raise ValueError("Source crop exceeds isolated arena limits")
    result = {k: copy.deepcopy(v) for k, v in layout.items() if k not in {"sourceMin", "sourceMax", "origin"}}
    result["bounds"] = {
        "min": [minimum[i] - origin[i] for i in range(3)],
        "max": [maximum[i] - origin[i] for i in range(3)],
    }
    palette, indices, cells = [], {}, []
    replacements, properties, omitted, entities = Counter(), Counter(), Counter(), Counter()
    with zipfile.ZipFile(io.BytesIO(archive_data)) as zipped:
        world = ArchivedWorld(zipped)
        for position, original in world.blocks(minimum, maximum):
            state = convert_state(original, replacements, properties)
            if state is None:
                if original["Name"] in OMIT:
                    omitted[original["Name"]] += 1
                continue
            if state["Name"] not in supported:
                raise ValueError(f"Unsupported 1.21.1 block {state['Name']}; add a reviewed conversion")
            key = json.dumps(state, sort_keys=True, separators=(",", ":"))
            if key not in indices:
                indices[key] = len(palette)
                palette.append(state)
            cells.append([position[i] - origin[i] for i in range(3)] + [indices[key]])
            if len(cells) > MAX_BLOCKS:
                raise ValueError("Source crop exceeds MyGO block count limit")
        for chunk in world.chunks.values():
            for entity in chunk.get("TileEntities", chunk.get("block_entities", [])):
                if all(minimum[i] <= entity.get(axis, minimum[i] - 1) <= maximum[i]
                       for i, axis in enumerate(("x", "y", "z"))):
                    entities[entity.get("id", "unknown")] += 1
    if not cells:
        raise ValueError("Source crop contains no blocks")
    cells.sort(key=lambda cell: (attachment_priority(palette[cell[3]]), cell[1], cell[0], cell[2]))
    result["palette"], result["blocks"] = palette, cells
    result["source"] = provenance
    manifest = {
        "formatVersion": 1, "mapId": result["id"],
        "source": provenance, "sourceMin": minimum, "sourceMax": maximum, "origin": origin,
        "bounds": result["bounds"], "size": size,
        "blockCount": len(cells), "paletteCount": len(palette),
        "blockOrder": "Architectural supports first, then dependent decorations; each group ordered by y, x, z ascending.",
        "dependentDecorationCount": sum(attachment_priority(palette[cell[3]]) for cell in cells),
        "blockReplacements": dict(sorted(replacements.items())),
        "propertyChanges": dict(sorted(properties.items())),
        "omittedExecutableBlocks": dict(sorted(omitted.items())),
        "omittedBlockEntityNbt": dict(sorted(entities.items())),
        "notes": ["Static block states only; no entities, block entity NBT, player data, datapacks or source resource packs.",
                  "Runtime layout coordinates are relative to origin; source crop coordinates are inclusive."],
    }
    return result, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--layout", type=Path, default=Path("tools/assets/mygo-dust2/layout.json"))
    parser.add_argument("--source", type=Path, default=Path("tools/assets/mygo-dust2/source.json"))
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/mygo/maps/dust2.json.gz"))
    parser.add_argument("--manifest", type=Path, default=Path("tools/assets/mygo-dust2/manifest.json"))
    parser.add_argument("--minecraft-sources", type=Path)
    args = parser.parse_args()
    source_jar = args.minecraft_sources
    if source_jar is None:
        candidates = sorted(Path(".gradle/loom-cache/minecraftMaven/net/minecraft").glob("**/1.21.1-*/*-sources.jar"))
        if not candidates:
            parser.error("Pass --minecraft-sources pointing to the mapped Minecraft 1.21.1 sources jar")
        source_jar = candidates[0]
    layout_data = args.layout.read_bytes()
    provenance = json.loads(args.source.read_text(encoding="utf-8"))
    result, manifest = extract(args.archive, json.loads(layout_data), provenance, supported_block_names(source_jar))
    raw = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(raw, compresslevel=9, mtime=0)
    manifest.update({"layoutSha256": hashlib.sha256(layout_data).hexdigest(),
                     "geometrySha256": hashlib.sha256(compressed).hexdigest(),
                     "geometryBytes": len(compressed),
                     "minecraftSourcesSha256": hashlib.sha256(source_jar.read_bytes()).hexdigest()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(compressed)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "manifest": str(args.manifest),
                      "blocks": len(result["blocks"]), "states": len(result["palette"]),
                      "bytes": len(compressed), "sha256": manifest["geometrySha256"],
                      "replacements": manifest["blockReplacements"], "properties": manifest["propertyChanges"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
