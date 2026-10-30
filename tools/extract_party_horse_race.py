#!/usr/bin/env python3
"""Extract the active Nether Horse Race, its three routes and checkpoint rules.

Reason: the current 416 entry switches to the Nether. The old 1438/-839 end
cleanup is not its active course. Preserve all three fortress routes, original
gold checkpoints and bounded gate clones; do not substitute a flat horse race.
Review: source commands/NBT are audit data only. Interpret only literal bounded
fill/setblock/clone operations and checkpoint/respawn numbers into native data.
"""
from __future__ import annotations

import argparse
import functools
import gzip
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld, extract, supported_block_names

# Native gravel supports the hidden Y18 horse-name signs. Cutting at Y18
# removed those supports; following every cut gravel column down reaches the
# original Y0 bedrock floor. Keep the complete original vertical foundation.
MINIMUM = (-32, 0, -1190)
MAXIMUM = (145, 96, -1008)
FUNCTIONS = ["horse_race", "horse_race1", "horse_race2", "horse_race_generate",
             "horse_race_generate2", "horse_race_rules", "horse_race_start",
             "horse_race_tick", "end_horse_race", "hr_reset_to_checkpoint",
             "checkpoint", "checkpoint2", "assign_finish_threshold"]


def between(value, encoded):
    if ".." not in encoded:
        return value == int(encoded)
    low, high = encoded.split("..")
    return (not low or int(low) <= value) and (not high or value <= int(high))


def selected(line, path):
    tags = re.findall(r"tag=hr_path([123])", line)
    scores = re.findall(r"if score hr_path variables matches ([\d.]+)", line)
    return all(int(tag) == path for tag in tags) and all(between(path, score) for score in scores)


def bounded(point):
    assert all(MINIMUM[i] <= point[i] <= MAXIMUM[i] for i in range(3)), point
    return point


def operations(text, path):
    result = []
    for line in text.splitlines():
        if not selected(line, path):
            continue
        match = re.search(r" run (fill|clone|setblock) (.*)$", line)
        if not match:
            continue
        op, words = match[1], match[2].split()
        row = {"op": op, "from": bounded(list(map(int, words[:3])))}
        if op == "setblock":
            assert len(words) == 4, line
            row["state"] = words[3]
        else:
            row["to"] = bounded(list(map(int, words[3:6])))
            if op == "clone":
                assert len(words) == 9, line
                row["destination"] = bounded(list(map(int, words[6:9])))
                bounded([row["destination"][i] + abs(row["to"][i] - row["from"][i]) for i in range(3)])
            else:
                row["state"] = words[6]
                if len(words) > 7:
                    assert words[7] == "replace" and len(words) == 9, line
                    row["replace"] = words[8]
        result.append(row)
    return result


def selector(line):
    return {k: float(v) for k, v in re.findall(r"\b(x|y|z)=(-?[\d.]+)", line)}


def score_range(encoded):
    if ".." not in encoded:
        return [int(encoded), int(encoded)]
    a, b = encoded.split("..")
    return [int(a), int(b)]


def centered(token, axis):
    return float(token) + (0.5 if axis != 1 and "." not in token else 0)


def plain(component):
    """Literal display text only; selectors, score readers and all clicks are discarded."""
    if isinstance(component, str):
        return component
    if isinstance(component, list):
        return "".join(plain(part) for part in component)
    if not isinstance(component, dict):
        return ""
    value = plain(component.get("text", component.get("fallback", "")))
    arguments = [plain(part) for part in component.get("with", [])]
    for index, argument in enumerate(arguments, 1):
        value = value.replace("%" + str(index) + "$s", argument)
    for argument in arguments:
        value = value.replace("%s", argument, 1)
    return value + plain(component.get("extra", []))


def source_signs(world):
    result = {}
    for cx in range(MINIMUM[0] // 16, MAXIMUM[0] // 16 + 1):
        for cz in range(MINIMUM[2] // 16, MAXIMUM[2] // 16 + 1):
            for entity in world.chunk(cx, cz).get("block_entities", []):
                point = tuple(entity[k] for k in ("x", "y", "z"))
                if entity["id"] != "minecraft:sign" or not all(MINIMUM[i] <= point[i] <= MAXIMUM[i] for i in range(3)):
                    continue
                payload = {"position": point, "waxed": bool(entity.get("is_waxed", 0))}
                for label in ("front", "back"):
                    face = entity.get(label + "_text", {})
                    messages = [plain(message) for message in face.get("messages", ["", "", "", ""])]
                    assert len(messages) == 4
                    payload[label] = {"messages": messages, "color": face.get("color", "black"),
                                      "glowing": bool(face.get("has_glowing_text", 0))}
                if point[1:] == (18, -1103) and 55 <= point[0] <= 64:
                    payload["ownerSeat"] = point[0] - 55
                result[point] = payload
    assert len(result) == 86
    return result


def positions(a, b):
    return itertools.product(*(range(min(a[i], b[i]), max(a[i], b[i]) + 1) for i in range(3)))


def route_signs(world, initial, operations):
    signs = dict(initial)
    cells = {}
    def block(point):
        if point not in cells:
            cells[point] = next(world.blocks(point, point))[1]["Name"]
        return cells[point]
    for operation in operations:
        start = tuple(operation["from"])
        end = tuple(operation.get("to", start))
        if operation["op"] == "clone":
            minimum = tuple(min(start[i], end[i]) for i in range(3))
            copies = []
            for source in positions(start, end):
                target = tuple(source[i] - minimum[i] + operation["destination"][i] for i in range(3))
                copies.append((target, block(source), signs.get(source)))
            for target, state, payload in copies:
                cells[target] = state
                if payload is None:
                    signs.pop(target, None)
                else:
                    signs[target] = payload | {"position": target}
        else:
            for point in positions(start, end):
                if operation.get("replace") is None or block(point) == operation["replace"]:
                    cells[point] = operation["state"].split("[", 1)[0]
                    signs.pop(point, None)
    return [signs[position] for position in sorted(signs)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        raw = {name: archive.read("data/mcp/function/" + name + ".mcfunction") for name in FUNCTIONS}
        text = {name: value.decode("utf-8") for name, value in raw.items()}
        assert "in the_nether run function mcp:horse_race2" in text["horse_race1"]
        routes = {}
        for path in range(1, 4):
            checkpoints = []
            tick_lines = text["horse_race_tick"].splitlines()
            for index, line in enumerate(tick_lines):
                if not selected(line, path) or " if block ~ ~-1 ~ minecraft:gold_block run scoreboard players set @s hcheckpoint " not in line:
                    continue
                target = int(line.rsplit(" ", 1)[-1])
                if target == 0:  # Lap completion is processed before this source list.
                    continue
                center = selector(line)
                allowed = re.search(r"hcheckpoint=([\d.]+)", line)[1]
                sound = re.search(r"hcheckpoint=([\d.]+)", tick_lines[index - 1]) if index and " run playsound " in tick_lines[index - 1] else None
                checkpoints.append({"target": target, "allowed": score_range(allowed),
                                    "soundAllowed": score_range(sound[1] if sound else allowed),
                                    "center": [center[a] for a in ("x", "y", "z")], "radius": 6})
            returns = {}
            for line in text["hr_reset_to_checkpoint"].splitlines():
                if not selected(line, path) or " run tp @s " not in line:
                    continue
                checkpoint = int(re.search(r"hcheckpoint=(\d+)", line)[1])
                tokens = line.split(" run tp @s ", 1)[1].split()
                returns[str(checkpoint)] = [centered(t, i) for i, t in enumerate(tokens)]
            assert sorted({c["target"] for c in checkpoints}) == list(range(1, 10 if path == 3 else 12))
            assert len(checkpoints) == [13, 11, 9][path - 1]  # Path 1 also accepts the two lower-route alternatives.
            assert set(returns) == {str(i) for i in range(10 if path == 3 else 12)}
            routes[str(path)] = {"name": ["High fortress", "Into the fortress", "Around the fortress"][path - 1],
                                 "ticks": 3500 if path == 3 else 4200,
                                 "unitsPerLap": 10 if path == 3 else 12,
                                 "operations": operations(text["horse_race_generate2"], path),
                                 "checkpointsInSourceOrder": checkpoints, "respawns": returns}
        world = AnvilWorld(args.world / "DIM-1")
        world.chunk = functools.lru_cache(None)(world.chunk)
        initial_signs = source_signs(world)
        for route in routes.values():
            route["signs"] = route_signs(world, initial_signs, route["operations"])
        geometry = extract(world, "horse_race",
                           {"min": MINIMUM, "max": MAXIMUM, "functions": FUNCTIONS,
                            "notes": ["Active Nether fortress, all three original routes and all reachable gold checkpoints.",
                                      "Includes surrounding natural Nether ground/lava and upper cliffs, original lower observation room and gate-clone sources.",
                                      "Distant natural Nether terrain outside this 178x97x183 scenery crop is omitted; course coordinates/heights are unchanged.",
                                      "Includes original Y0 bedrock/netherrack foundation so native gravel retains all original vertical supports, including the hidden Y18 owner-name signs.",
                                      "Three original generator branches apply bounded native gate mutations, preserving clone air and source ordering."]},
                           archive, supported_block_names(args.minecraft_sources))
    audit = {"formatVersion": 1, "sourceId": 416, "anchor": [56, 45, -1099],
             "bounds": {"min": MINIMUM, "max": MAXIMUM},
             "sourceFunctionSha256": {n: hashlib.sha256(b).hexdigest() for n, b in raw.items()},
             "sourceFunctions": text, "routes": routes,
             "navigation": {"sourceSignCount": len(initial_signs), "perRouteSignCounts": {p: len(r["signs"]) for p, r in routes.items()},
                            "policy": "Safe literal front/back text, base dye color, glowing and wax state only. No click events, commands, selectors, block inventories or raw block-entity NBT are copied.",
                            "dynamicHorseNameSigns": "The hidden ten owner-name signs use the current local racer name, never evaluate original global selectors."},
             "finish": {"center": [50.5, 35, -1102.0], "radius": 6, "blockBelowPlayer": "minecraft:gold_block", "laps": 2},
             "spawnX": [53.5, 51.5, 49.5, 47.5, 54.5, 52.5, 50.5, 48.5, 46.5, 45.5],
             "spawnY": 35, "spawnZ": -1102.5,
             "nativeHorse": {"movementSpeed": .23, "jumpStrength": .85, "tame": True, "saddled": True,
                             "oddSeats": "zombie_horse", "evenSeats": "skeleton_horse", "invulnerable": True},
             "rules": ["Uniform random path 1..3, two laps, N-1 finish threshold for this server free-for-all.",
                       "Checkpoints read the PLAYER gold block at feet-1, within a 6-block sphere and original inclusive previous-checkpoint ranges. Some ranges intentionally permit skipping one earlier marker.",
                       "Every checkpoint invokes original checkpoint/checkpoint2 ordering; final raw is 10*totalCheckpoints plus last-checkpoint recency. Initial Points is 3 until the first checkpoint event.",
                       "Fire resets the same horse to its checkpoint and delays automatic remount five ticks; source dismount is immediately undone while ride tag remains active.",
                       "Right-click/drop flag rebuilds only the owner's horse at its checkpoint; immediate unlimited use, no invented cooldown.",
                       "Owner is tethered within five blocks of their own horse. All players receive FireResistance0, Resistance99, Weakness99, Saturation99 and NightVision0 each source tick.",
                       "The source horse team selects ordinary minecraft:horse, while racers are undead horses, so its NEVER collision setting has no effect on actual race horses. Native horse collision is retained."],
             "compatibility": ["1.21.1's negative unburnt Fire cooldown must not trigger resets; test actual burning Fire>0 and clear it each tick.",
                               "Native zombie/skeleton horse attributes use GENERIC_MOVEMENT_SPEED/GENERIC_JUMP_STRENGTH and saddle inventory instead of newer equipment component NBT.",
                               "Raw source entity/item selectors become exact per-player UUID/token ownership; wrong-horse interaction is rejected before mounting rather than teleporting intruders afterwards.",
                               "Source level.dat has doMobLoot=false; source horses suppress their own XP/loot without changing the shared world gamerule.",
                               "The original global timer, team, scoreboard, forceload and datapack schedules are never executed."]}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "geometry").mkdir(exist_ok=True)
    encoded = json.dumps(geometry, ensure_ascii=False, separators=(",", ":")).encode()
    (args.output / "geometry/horse_race.json.gz").write_bytes(gzip.compress(encoded, compresslevel=9, mtime=0))
    (args.output / "horse_race_rules.json").write_text(json.dumps(audit, ensure_ascii=False, separators=(",", ":")) + "\n")
    print("horse_race", geometry["size"], len(geometry["blocks"]), "blocks", len(geometry["palette"]), "states", len(gzip.compress(encoded, mtime=0)), "gzip bytes")
    print("routes", {p: (len(r["operations"]), len(r["checkpointsInSourceOrder"])) for p, r in routes.items()})


if __name__ == "__main__":
    main()
