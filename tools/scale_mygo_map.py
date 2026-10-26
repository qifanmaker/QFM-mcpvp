#!/usr/bin/env python3
"""Convert an oversized credited CS map into an isolated MyGO blueprint.

The source worlds are authored at a much larger block scale than the 320-block
MyGO arena. This tool performs a deterministic horizontal downsample while
keeping the source Y coordinates intact. Only static block states are emitted;
entities, commands, inventories and world metadata are intentionally omitted.
"""

from __future__ import annotations

import argparse
import copy
import gzip
import hashlib
import json
import math
import sys
import zipfile
from collections import Counter
from pathlib import Path

from extract_mygo_map import AIR, ArchivedWorld, attachment_priority, convert_state, supported_block_names


def target_coordinate(value: int, origin: int, scale: float) -> int:
    # Python's round uses bankers rounding; the explicit half-away rule keeps
    # mirrored map geometry stable when a source coordinate lands on .5.
    value = (value - origin) * scale
    return math.floor(value + 0.5) if value >= 0 else math.ceil(value - 0.5)


def state_rank(state: dict) -> int:
    name = state["Name"].removeprefix("minecraft:")
    # A solid architectural cell must win over a carpet/torch when several
    # source cells collapse into one downsampled coordinate.
    if attachment_priority(state):
        return 1
    if name.endswith(("_slab", "_stairs", "_wall", "_fence", "_pane", "_door", "_trapdoor")):
        return 2
    return 3


def convert(args: argparse.Namespace, supported: set[str]):
    source_min = args.source_min
    source_max = args.source_max
    origin = args.origin
    replacements, properties, omitted, entities = Counter(), Counter(), Counter(), Counter()
    cells: dict[tuple[int, int, int], dict] = {}
    with zipfile.ZipFile(args.archive) as archive:
        world = ArchivedWorld(archive)
        for position, original in world.blocks(source_min, source_max):
            state = convert_state(original, replacements, properties)
            if state is None:
                if original["Name"] not in AIR:
                    omitted[original["Name"]] += 1
                continue
            if state["Name"] not in supported:
                raise ValueError(f"Unsupported 1.21.1 block {state['Name']}; add a reviewed conversion")
            target = (target_coordinate(position[0], origin[0], args.scale),
                      position[1] - origin[1],
                      target_coordinate(position[2], origin[2], args.scale))
            if target[1] < args.target_min_y:
                continue
            current = cells.get(target)
            if current is None or state_rank(state) > state_rank(current):
                cells[target] = state
        for chunk in world.chunks.values():
            for entity in chunk.get("TileEntities", chunk.get("block_entities", [])):
                if all(source_min[i] <= entity.get(axis, source_min[i] - 1) <= source_max[i]
                       for i, axis in enumerate(("x", "y", "z"))):
                    entities[entity.get("id", "unknown")] += 1
    if not cells:
        raise ValueError("source crop contains no blocks")
    minimum = [min(pos[i] for pos in cells) for i in range(3)]
    maximum = [max(pos[i] for pos in cells) for i in range(3)]
    layout = {
        "formatVersion": 1,
        "id": args.map_id,
        "name": args.name,
        "bounds": {"min": minimum, "max": maximum},
        "playBounds": {"min": [minimum[0] - 2, minimum[1] - 2, minimum[2] - 2],
                       "max": [maximum[0] + 2, maximum[1] + 4, maximum[2] + 2]},
        "spawns": {"T": [], "CT": []},
        "buyZones": {"T": {"min": [0, 0, 0], "max": [0, 0, 0]},
                      "CT": {"min": [0, 0, 0], "max": [0, 0, 0]}},
        "sites": [],
        "spectator": [minimum[0] - 10, 2, minimum[2] - 10],
        "testAnchors": {},
        "palette": [],
        "blocks": [],
        "source": args.provenance,
    }
    palette, indices = [], {}
    encoded = []
    for pos, state in sorted(cells.items(), key=lambda item: (state_rank(item[1]), item[0][1], item[0][0], item[0][2])):
        key = json.dumps(state, sort_keys=True, separators=(",", ":"))
        if key not in indices:
            indices[key] = len(palette)
            palette.append(state)
        encoded.append([*pos, indices[key]])
    layout["palette"], layout["blocks"] = palette, encoded
    manifest = {
        "formatVersion": 1, "mapId": args.map_id, "source": args.provenance,
        "sourceMin": source_min, "sourceMax": source_max, "origin": origin,
        "scaleXZ": args.scale, "bounds": layout["bounds"],
        "size": [maximum[i] - minimum[i] + 1 for i in range(3)],
        "blockCount": len(encoded), "paletteCount": len(palette),
        "blockOrder": "Architectural supports first, then dependent decorations; each group ordered by y, x, z ascending.",
        "dependentDecorationCount": sum(attachment_priority(palette[cell[3]]) for cell in encoded),
        "blockReplacements": dict(sorted(replacements.items())),
        "propertyChanges": dict(sorted(properties.items())),
        "omittedExecutableBlocks": dict(sorted(omitted.items())),
        "omittedBlockEntityNbt": dict(sorted(entities.items())),
        "notes": ["Static block states only; no entities, block entity NBT, player data, datapacks or source resource packs.",
                  "Runtime layout coordinates are relative to the scaled source origin; Y coordinates remain at source scale."],
    }
    return layout, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--map-id", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--source-min", type=int, nargs=3, required=True)
    parser.add_argument("--source-max", type=int, nargs=3, required=True)
    parser.add_argument("--origin", type=int, nargs=3, required=True)
    parser.add_argument("--scale", type=float, required=True)
    parser.add_argument("--target-min-y", type=int, default=-10)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    args = parser.parse_args()
    args.provenance = json.loads(args.source.read_text(encoding="utf-8"))
    result, manifest = convert(args, supported_block_names(args.minecraft_sources))
    raw = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(raw, compresslevel=9, mtime=0)
    manifest.update({"layoutSha256": hashlib.sha256(raw).hexdigest(),
                     "geometrySha256": hashlib.sha256(compressed).hexdigest(),
                     "geometryBytes": len(compressed),
                     "minecraftSourcesSha256": hashlib.sha256(args.minecraft_sources.read_bytes()).hexdigest()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(compressed)
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "manifest": str(args.manifest),
                      "blocks": len(result["blocks"]), "states": len(result["palette"]),
                      "bytes": len(compressed), "sha256": manifest["geometrySha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
