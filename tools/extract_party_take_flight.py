#!/usr/bin/env python3
"""Extract427's complete underground race domain as exact native-state X runs.

Reason: Take Flight is a native Elytra/firework race through real terrain, not
an approximation made of rings. Review: retain every original non-air cell,
all four waypoint pads, sixteen boost candidates and the lower cage template;
do not add walls or replace source cave geometry with a hollow shell.
"""
from __future__ import annotations

import argparse
from collections import Counter
import functools
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AIR, OMIT_BLOCKS, REPLACEMENTS, AnvilWorld, supported_block_names

MINIMUM = (-410, -64, -770)
MAXIMUM = (-200, 64, -510)
ANCHOR = (-305, 101, -640)
CORE = ["take_flight", "take_flight_rules", "take_flight_start", "take_flight_tick", "take_flight_new_boost", "take_flight_end"]
HELPERS = ["checkpoint", "checkpoint2", "assign_finish_threshold", "timer_decrement", "last_timer_title", "give_ready_ups",
           "ready_up_tellraw", "start_minigame", "scoreboard_data", "default_settings", "tick", "4_4_all_tick",
           "quickplayminigame", "replay_minigame", "set_up_minigame", "schedule_clear_rules", "schedule_clear_start",
           "placements", "end_minigame", "void_minigame"]


def canonical(state):
    name = state["Name"]
    if name in OMIT_BLOCKS or name in AIR:
        return None
    if name in REPLACEMENTS:
        result = {"Name": REPLACEMENTS[name]}
        if name in {"minecraft:wildflowers", "minecraft:iron_chain"} and "Properties" in state:
            result["Properties"] = state["Properties"]
        return result
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    dimension = args.world / "dimensions/new_dimensions/sky_dimension"
    world = AnvilWorld(dimension)
    world.chunk = functools.lru_cache(None)(world.chunk)
    supported = supported_block_names(args.minecraft_sources)
    palette, indices = [], {}
    counts, omitted, replacements = Counter(), Counter(), Counter()
    block_count = 0
    for position, original in world.blocks(MINIMUM, MAXIMUM):
        state = canonical(original)
        if state is None:
            if original["Name"] in OMIT_BLOCKS:
                omitted[original["Name"]] += 1
            continue
        if state["Name"] not in supported:
            raise ValueError("Unsupported Take Flight block " + state["Name"])
        encoded = json.dumps(state, sort_keys=True, separators=(",", ":"))
        if encoded not in indices:
            indices[encoded] = len(palette)
            palette.append(state)
        counts[state["Name"]] += 1
        if original["Name"] != state["Name"]:
            replacements[original["Name"] + " -> " + state["Name"]] += 1
        block_count += 1
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        raw = {name: archive.read("data/mcp/function/" + name + ".mcfunction") for name in CORE + HELPERS}
    sources = {name: value.decode() for name, value in raw.items()}
    boosts = [{"waypoint": int(group), "position": [int(x), int(y), int(z)], "orientation": orientation}
              for x, y, z, group, orientation in re.findall(
                  r'summon marker (-?\d+) (-?\d+) (-?\d+) \{Tags:\["potential_boost","waypoint(\d)","(ns|ew)"\]\}',
                  sources["take_flight_start"])]
    assert len(boosts) == 16 and all(sum(row["waypoint"] == group for row in boosts) == 4 for group in range(1, 5))
    metadata = {"formatVersion": 2, "runAxis": "x", "nonAirBlocks": block_count,
                "source": {"name": "Minecraft Party 2", "minecraftVersion": "1.21.10", "dimension": "new_dimensions:sky_dimension",
                           "url": "https://www.minecraftmaps.com/52024-minecraft-party-2",
                           "functionSha256": {name: hashlib.sha256(raw[name]).hexdigest() for name in CORE}},
                "bounds": {"min": MINIMUM, "max": MAXIMUM},
                "size": [MAXIMUM[i] - MINIMUM[i] + 1 for i in range(3)],
                "blockCount": block_count, "palette": palette,
                "notes": ["Exact complete non-air terrain within the underground four-stage race domain, including solid interiors; no shell/pruning/ring substitute.",
                          "The source dimension is infinite noise-generated terrain. Natural side caves continue outside this explicit finite import; no artificial source-less walls or forced return is added.",
                          "Every source waypoint material pad, all16 potential boost coordinates, natural cave/mineshaft/geode obstacles and original bedrock floor are preserved.",
                          "Source anchor Y101 preserves sourceY coordinates in the normal ArenaWorld centered atY101, retaining the real source bottom atY-64.",
                          "Runs are [relativeX,relativeY,relativeZ,length,paletteIndex], length counts contiguous +X cells. They reproduce every original non-air cell exactly.",
                          "No raw source entities, functions, arbitrary block-entity NBT, loot tables or commands execute."],
                "omitted": dict(omitted), "replacements": dict(replacements),
                "omittedBlockEntityNbt": world.block_entity_counts(MINIMUM, MAXIMUM)}
    target = args.output / "geometry/take_flight.json.gz"
    target.parent.mkdir(parents=True, exist_ok=True)
    runs = 0
    with target.open("wb") as file, gzip.GzipFile(fileobj=file, mode="wb", mtime=0, compresslevel=9) as output:
        header = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"))[:-1] + ',"runs":['
        output.write(header.encode())
        current = None
        buffer = bytearray()

        def emit(row):
            nonlocal runs, buffer
            if runs:
                buffer.extend(b",")
            buffer.extend(("[%d,%d,%d,%d,%d]" % tuple(row)).encode())
            runs += 1
            if len(buffer) >= 1_048_576:
                output.write(buffer)
                buffer.clear()

        for (x, y, z), original in world.blocks(MINIMUM, MAXIMUM):
            state = canonical(original)
            if state is None:
                continue
            index = indices[json.dumps(state, sort_keys=True, separators=(",", ":"))]
            relative = [x - MINIMUM[0], y - MINIMUM[1], z - MINIMUM[2]]
            if current is not None and current[1] == relative[1] and current[2] == relative[2] and current[4] == index and current[0] + current[3] == relative[0]:
                current[3] += 1
            else:
                if current is not None:
                    emit(current)
                current = relative + [1, index]
        if current is not None:
            emit(current)
        output.write(buffer)
        output.write(b"]}")
    rules = {"sourceId": 427, "sourceVersion": "1.21.10", "dimension": "new_dimensions:sky_dimension", "anchor": ANCHOR,
             "bounds": {"min": MINIMUM, "max": MAXIMUM},
             "coreFunctionNames": CORE, "sourceFunctionSha256": {name: hashlib.sha256(value).hexdigest() for name, value in raw.items()},
             "sourceFunctions": sources, "boostCandidates": boosts,
             "waypoints": [{"block": block, "y": y} for block, y in [("minecraft:emerald_block", -20), ("minecraft:diamond_block", -48), ("minecraft:netherite_block", -25), ("minecraft:sponge", 10)]],
             "rules": {"timeLimitTicks": 2400, "laps": 3, "rocketRechargeTicks": 10, "rocketFlightDuration": 0,
                       "initialBoost": 81, "normalContactSubtract": 21, "normalBoostLimit": 20,
                       "highPingContactSubtract": 81, "highPingBoostLimit": 80, "highPingStepTicks": 4,
                       "sourceDefaultHighPing": False, "health": 1, "deathResistanceTicks": 40},
             "geometry": {"formatVersion": 2, "blockCount": block_count, "runCount": runs, "counts": dict(counts)},
             "adaptations": ["Only the explicitly bounded complete race domain is imported from the infinite source dimension; source-less walls/forced return are not introduced.",
                             "Own records replace global objectives and hidden respawn armorstands; all motion/elytra/rocket/damage inputs use native1.21.1 mechanics.",
                             "The default source high-ping setting is false; the native module retains both source counter/step branches as a selectable construction option.",
                             "Same-tick lap completions retain source checkpoint ties; scores use laps*N plus prior-checkpoint recency rank, not partial waypoint progress.",
                             "The normal five-round free-for-all ends at N-1 finishers or2400ticks; board economy/doubles/solo difficulty settings are outside this tournament."]}
    (args.output / "take_flight_rules.json").write_text(json.dumps(rules, ensure_ascii=False, separators=(",", ":")) + "\n")
    print("Take Flight", block_count, "exact blocks", runs, "X runs", len(palette), "states", "bytes", target.stat().st_size)


if __name__ == "__main__":
    main()
