#!/usr/bin/env python3
"""Rebuild 407 Sky Fly from original fill/clone data, never its saved random layout.

Reason/pre-review: the game's 36 descending wall apertures depend on five exact
templates, including their air, iron-bar ledges and chains. A saved world crop
would freeze a previous random roll. Only literal, audited fill/clone operations
are interpreted as block data; no function, entity, command NBT or selector runs.
"""
from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import itertools
import json
from pathlib import Path
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld, AIR, OMIT_BLOCKS, REPLACEMENTS, SOURCE_URL, supported_block_names

FUNCTIONS = ["sky_fly", "sky_fly_generate", "sky_fly_generate2", "sky_fly_rules",
             "sky_fly_start", "sky_fly_tick", "sky_fly_score", "end_sky_fly", "cleanup_skyfly"]
TEMPLATES = {
    "sky_fly_ring_large": ((-53, 28, 15), (-47, 34, 21)),
    "sky_fly_ring_medium": ((-53, 28, 23), (-47, 34, 29)),
    "sky_fly_ring_medium_chain": ((-53, 28, 30), (-47, 34, 36)),
    "sky_fly_ring_small": ((-45, 28, 17), (-39, 34, 23)),
    "sky_fly_ring_small_chain": ((-45, 28, 24), (-39, 34, 30)),
}


def positions(minimum, maximum):
    return itertools.product(*(range(minimum[i], maximum[i] + 1) for i in range(3)))


def box(a, b):
    return tuple(min(a[i], b[i]) for i in range(3)), tuple(max(a[i], b[i]) for i in range(3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party/geometry"))
    args = parser.parse_args()
    world = AnvilWorld(args.world)
    supported = supported_block_names(args.minecraft_sources)
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        sources = {name: archive.read("data/mcp/function/" + name + ".mcfunction").decode("utf-8") for name in FUNCTIONS}
    hashes = {name: hashlib.sha256(source.encode()).hexdigest() for name, source in sources.items()}
    replaced = Counter()

    def adapt(state):
        state = dict(state)
        name = state["Name"]
        if name in OMIT_BLOCKS:
            raise ValueError("Unexpected executable source block in Sky Fly clone: " + name)
        if name in REPLACEMENTS:
            state["Name"] = REPLACEMENTS[name]
            replaced[name + " -> " + state["Name"]] += 1
        if state["Name"] not in supported:
            raise ValueError("Unsupported source block: " + state["Name"])
        return state

    # Keep the original lower observation platforms. The whole course above is
    # regenerated below; none of its saved random apertures are imported.
    base = {pos: adapt(state) for pos, state in world.blocks((-183, -47, -5), (-22, -43, 139))
            if state["Name"] not in AIR}
    operations = []
    for line in sources["sky_fly_generate"].splitlines():
        parts = line.strip().split()
        if not parts or parts[0] not in {"fill", "clone"}:
            continue
        values = [int(number) for number in parts[1:7]]
        minimum, maximum = box(tuple(values[:3]), tuple(values[3:]))
        if parts[0] == "fill":
            state = adapt({"Name": parts[7]})
            replacement = parts[9] if len(parts) == 10 and parts[8] == "replace" else None
            if len(parts) not in (8, 10):
                raise ValueError("Unsupported fill data: " + line)
            for pos in positions(minimum, maximum):
                if replacement is None or base.get(pos, {}).get("Name") == replacement:
                    base[pos] = state
        else:
            if len(parts) != 10:
                raise ValueError("Unsupported clone data: " + line)
            destination = tuple(int(number) for number in parts[7:10])
            source = dict(world.blocks(minimum, maximum))
            for pos in positions(minimum, maximum):
                target = tuple(pos[i] - minimum[i] + destination[i] for i in range(3))
                # Default clone copies air too, opening space in previously
                # generated quartz walls and in the launch platform.
                base[target] = adapt(source.get(pos, {"Name": "minecraft:air"}))
        operations.append(line)
    base = {pos: state for pos, state in base.items() if state["Name"] not in AIR}
    results = {"sky_fly_base": (base, (-183, -47, -5), (-22, 122, 139),
                ["Original literal fill/clone output plus underfloor observation platforms; saved random course never copied.",
                 "37 original scoring zones at source Y=-42; floor/finish detector Y=-40/-41.",
                 "Native Java overlays 36 randomly positioned apertures using air-inclusive template planes."])}
    # Each resource needs its own compatibility audit. Reusing the total after
    # all templates would falsely report chains in the base and unchained rings.
    replacements_by_resource = {"sky_fly_base": dict(replaced)}
    for name, (minimum, maximum) in TEMPLATES.items():
        replaced.clear()
        cells = dict(world.blocks(minimum, maximum))
        # The source clones the two front planes from its mutable workspace.
        # Include every air cell so cloning really cuts an opening in a wall.
        cells = {pos: adapt(cells.get(pos, {"Name": "minecraft:air"})) for pos in positions(minimum, maximum)}
        results[name] = (cells, minimum, maximum,
                         ["Immutable 7x7x7 source template, including air. Runtime selects original X-min or Z-min plane."])
        replacements_by_resource[name] = dict(replaced)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, (cells, minimum, maximum, notes) in results.items():
        palette, indices, encoded = [], {}, []
        for pos, state in sorted(cells.items()):
            key = json.dumps(state, sort_keys=True)
            if key not in indices:
                indices[key] = len(palette)
                palette.append(state)
            encoded.append([pos[i] - minimum[i] for i in range(3)] + [indices[key]])
        output = {"formatVersion": 1, "source": {"name": "Minecraft Party 2", "url": SOURCE_URL,
                    "minecraftVersion": "1.21.10", "functionSha256": hashes},
                  "bounds": {"min": minimum, "max": maximum},
                  "size": [maximum[i] - minimum[i] + 1 for i in range(3)],
                  "palette": palette, "blocks": encoded, "notes": notes,
                  "omitted": {}, "replacements": replacements_by_resource[name]}
        raw = json.dumps(output, ensure_ascii=False, separators=(",", ":")).encode()
        packed = gzip.compress(raw, compresslevel=9, mtime=0)
        (args.output / (name + ".json.gz")).write_bytes(packed)
        print(f"{name}: {len(cells)} cells, {len(palette)} states, {len(packed)} bytes")
    audit = {"source": SOURCE_URL, "sourceGameId": 407, "functionSha256": hashes,
             "literalBaseOperations": operations, "functions": sources,
             "runtimeRules": ["36 rings; decrement driver Y by 4 before each clone.",
                 "Template changes at rings 1,9,20,31,36; turns 9,16,22,27,31,34,36.",
                 "Uniform offsets before corners 1..8; after corners 1..5; other rings 1..11/2..11/3..11.",
                 "Score reads material at player's current X/Z and fixed source Y=-42 every tick, retaining prior score outside recognized zones.",
                 "No countdown or timed end: finish when every remaining participant has dead_bubble_coral_block at feet-2.",
                 "One attempt; iron-bar landing or Y<=-32 removes all Elytra; prior tick cached Y=-40..-32 disables jumping.",
                 "Every tick resistance99/weakness99; restore only this match's attributes, effects and Elytra."]}
    (args.output.parent / "sky_fly_source_audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
