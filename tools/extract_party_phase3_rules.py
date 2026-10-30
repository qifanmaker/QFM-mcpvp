#!/usr/bin/env python3
"""Extract auditable typed rules for Party 2 Cave Parkour and Platforms.

Why: copying only static blocks drops the original random cave branches and
the platform gem constructors, which are stored in command-block NBT. This
read-only converter preserves their ordered semantics as bounded data. Runtime
code executes native Java rules and never dispatches source commands.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import functools
import hashlib
import json
from pathlib import Path
import re
import zipfile

from extract_party_geometry import AnvilWorld, SOURCE_URL


def sha(value: str | bytes) -> str:
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()


def bounded_entities(world, minimum, maximum):
    result = []
    for cx in range(minimum[0] // 16, maximum[0] // 16 + 1):
        for cz in range(minimum[2] // 16, maximum[2] // 16 + 1):
            for entity in world.chunk(cx, cz).get("block_entities", []):
                if all(minimum[i] <= entity[key] <= maximum[i]
                       for i, key in enumerate(("x", "y", "z"))):
                    result.append(entity)
    return sorted(result, key=lambda e: (e["x"], e["y"], e["z"]))


def command_record(entity):
    command = entity["Command"]
    return {"position": [entity[k] for k in ("x", "y", "z")],
            "command": command, "commandSha256": sha(command), "auto": entity.get("auto", 0)}


def operation(record):
    parts = record["command"].split()
    command = parts[0]
    if command == "setblock" and parts[1:4] == ["~", "~-1", "~"]:
        return None
    if command == "setblock":
        result = {"op": "set", "pos": list(map(int, parts[1:4])), "state": parts[4]}
        assert len(parts) == 5, parts
    elif command == "fill":
        result = {"op": "fill", "from": list(map(int, parts[1:4])),
                  "to": list(map(int, parts[4:7])), "state": parts[7]}
        if len(parts) > 8:
            assert len(parts) == 10 and parts[8] == "replace", parts
            result["replace"] = parts[9]
    elif command == "clone":
        assert len(parts) == 10, parts
        result = {"op": "clone", "from": list(map(int, parts[1:4])),
                  "to": list(map(int, parts[4:7])), "destination": list(map(int, parts[7:10]))}
    elif command in {"execute", "tellraw"}:
        return None
    else:
        raise ValueError("Unaudited branch command: " + record["command"])
    result["sourcePosition"] = record["position"]
    result["commandSha256"] = record["commandSha256"]
    return result


def literal_signs(world, minimum, maximum):
    # Direction arrows are gameplay navigation. Whitelist plain strings so
    # source click events and other executable text components cannot leak in.
    signs = []
    for entity in bounded_entities(world, minimum, maximum):
        if entity["id"] != "minecraft:sign":
            continue
        record = {"pos": [entity[k] for k in ("x", "y", "z")]}
        for side in ("front", "back"):
            messages = entity[side + "_text"]["messages"]
            assert len(messages) == 4 and all(isinstance(message, str) for message in messages)
            assert all(re.fullmatch(r"[<>^\-]*", message) for message in messages)
            record[side] = messages
            record[side + "Color"] = entity[side + "_text"].get("color", "black")
            record[side + "Glowing"] = bool(entity[side + "_text"].get("has_glowing_text", 0))
        signs.append(record)
    return signs


def cave_rules(world, functions):
    constructors = bounded_entities(world, (185, 24, -166), (219, 24, -166))
    constructors = [e for e in constructors if e.get("Command", "").startswith("summon ")]
    assert len(constructors) == 22
    all_blocks = {tuple(e[k] for k in ("x", "y", "z")): e
                  for e in bounded_entities(world, (185, 26, -177), (219, 26, -166))}
    branches, command_chains = defaultdict(list), []
    for entity in sorted(constructors, key=lambda e: -e["x"]):
        node = re.search(r'"(path\d+)"', entity["Command"])[1]
        records = []
        z = -166
        while (entity["x"], 26, z) in all_blocks:
            records.append(command_record(all_blocks[(entity["x"], 26, z)]))
            z -= 1
        assert records and records[0]["command"] == "setblock ~ ~-1 ~ minecraft:air"
        next_nodes = [re.search(r"tag=(path\d+)", r["command"])[1]
                      for r in records if r["command"].startswith("execute ")]
        assert len(next_nodes) <= 1
        branch = {"triggerX": entity["x"], "nextPath": next_nodes[0] if next_nodes else None,
                  "operations": [op for record in records if (op := operation(record))]}
        branches[node].append(branch)
        command_chains.append({"node": node, "triggerX": entity["x"],
                               "constructor": command_record(entity), "commands": records})
    branches = {k: branches[k] for k in sorted(branches, key=lambda k: int(k[4:]))}
    assert len(branches) == 11 and all(len(v) == 2 for v in branches.values())
    # This acyclic graph is a random route, not independent switches: only the
    # selected branch's successor is visited, including terminal shortcuts.
    def check_node(node, visited):
        assert node not in visited, "Cyclic branch graph"
        for branch in branches[node]:
            if branch["nextPath"] is not None:
                check_node(branch["nextPath"], visited | {node})
    check_node("path1", set())
    checkpoints = []
    lines = functions["parkour_tick"]["text"].splitlines()
    for index, line in enumerate(lines, 1):
        if "run scoreboard players set @s checkpoint " in line:
            match = re.search(r"x=(-?\d+),y=(-?\d+),z=(-?\d+),distance=0\.\.1", line)
            if match:
                checkpoints.append({"stage": int(line.rsplit(" ", 1)[1]),
                                    "anchor": list(map(int, match.groups())), "sourceLine": index,
                                    "sourceSha256": sha(line)})
    assert len(checkpoints) == 39 and max(c["stage"] for c in checkpoints) == 15
    canonical = json.dumps(command_chains, sort_keys=True, separators=(",", ":"))
    signs = literal_signs(world, (125, -24, -290), (270, 24, -125))
    template_signs = literal_signs(world, (227, 22, -187), (237, 25, -169))
    assert len(signs) == 64 and len(template_signs) == 14
    sign_sha = sha(json.dumps([signs, template_signs], sort_keys=True, separators=(",", ":")))
    return ({"formatVersion": 1, "source": {"name": "Minecraft Party 2", "url": SOURCE_URL,
             "minecraftVersion": "1.21.10", "functionSha256": {
                 key: value["sha256"] for key, value in functions.items()},
             "commandBlockSha256": sha(canonical), "signTextSha256": sign_sha},
             "start": "path1", "branches": branches, "checkpoints": checkpoints,
             "signs": signs, "templateSigns": template_signs}, command_chains)


def platform_rules(world):
    entities = bounded_entities(world, (-13, 33, -58), (5, 51, 2))
    command_blocks = [command_record(e) for e in entities if e.get("Command")]
    gems, starts, signs, constructors = [], [], [], []
    distribution = defaultdict(list)
    by_position = {tuple(record["position"]): record for record in command_blocks}
    for record in command_blocks:
        text = record["command"]
        if 'Tags:["platformgemspot"]' in text:
            x, y, z = record["position"]
            gems.append({"constructorPosition": record["position"],
                         "runtimePosition": [x + 0.5, y + 7.0, z - 28.5],
                         "commandSha256": record["commandSha256"]})
        if '"platformplayerspot"' in text:
            tag = re.search(r'"(spot[A-D])"', text)[1]
            x, y, z = record["position"]
            starts.append({"spot": tag, "constructorPosition": record["position"],
                           "runtimePosition": [x + 0.5, y + 7.0, z - 28.5],
                           "commandSha256": record["commandSha256"]})
        if text.startswith("summon ") and "platformcorner" in text:
            x, y, z = record["position"]
            generator = by_position[(x, y + 3, z)]
            amount = int(re.search(r"limit=(\d+)\]", generator["command"])[1])
            cx, cy, cz = map(float, re.search(r"x=(-?[\d.]+),y=(-?[\d.]+),z=(-?[\d.]+)",
                                            generator["command"]).groups())
            tags = re.findall(r'"(platformcorner\d+-\d+)"', text)
            for tag in tags:
                distribution[tag].append(amount)
            constructors.append({"position": record["position"], "tags": tags, "diamonds": amount,
                                 "runtimeCenter": [cx, cy + 6, cz - 29],
                                 "constructorSha256": record["commandSha256"],
                                 "generatorSha256": generator["commandSha256"]})
    for entity in bounded_entities(world, (-13, 36, -24), (-1, 36, -12)):
        if entity["id"] != "minecraft:sign":
            continue
        for message in entity["front_text"]["messages"]:
            if not isinstance(message, dict) or "click_event" not in message:
                continue
            text = message["click_event"]["command"]
            spot, choice = re.search(r"platforms_([a-d])_(left|mid|right)", text).groups()
            signs.append({"position": [entity["x"], 46, entity["z"] - 29],
                          "spot": spot.upper(), "choice": {"left": 0, "mid": 1, "right": 2}[choice],
                          "label": {"left": "Jump Left", "mid": "Jump Middle", "right": "Jump Right"}[choice],
                          "sourceClickCommandSha256": sha(text)})
    assert len(gems) == 45 and len(starts) == 4 and len(signs) == 12
    assert len(constructors) == 27 and len(distribution) == 30
    return {"commandBlocks": command_blocks, "gemSpots": gems, "playerSpots": starts,
            "hiddenSelectionSigns": sorted(signs, key=lambda s: (s["spot"], s["choice"])),
            "diamondConstructors": constructors,
            "diamondDistribution": {key: sorted(values) for key, values in sorted(distribution.items())}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, default=Path("run/external-maps/templates/minecraft-party-2"))
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    world = AnvilWorld(args.world)
    world.chunk = functools.lru_cache(maxsize=None)(world.chunk)
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as pack:
        groups = {
            "404": ["parkour", "parkour_randomize_path", "parkour_randomize_path2", "parkour_randomize_path3",
                    "parkour_rules", "parkour_start", "parkour_tick", "end_parkour"],
            "414": [Path(name).stem for name in pack.namelist()
                    if name.startswith("data/mcp/function/platforms") or name == "data/mcp/function/end_platforms.mcfunction"],
            "402": [Path(name).stem for name in pack.namelist()
                    if "/whack_a_mob" in name or "/wam_" in name],
            "403": [Path(name).stem for name in pack.namelist()
                    if "/monster_shoot" in name or "/end_monster_shoot" in name or "/ms_" in name],
        }
        functions = {}
        for game, names in groups.items():
            functions[game] = {}
            for name in sorted(names):
                path = "data/mcp/function/" + name + ".mcfunction"
                raw = pack.read(path)
                functions[game][name] = {"path": path, "sha256": sha(raw), "text": raw.decode()}
    routes, chains = cave_rules(world, functions["404"])
    platforms = platform_rules(world)
    audit = {"formatVersion": 1, "source": routes["source"],
             "reason": "Preserve native gameplay from original command-block chains without executing source NBT.",
             "functions": functions, "caveBranchCommandChains": chains, "platforms": platforms,
             "boundaries": ["Functions and command text are evidence only, never instructions or runtime commands.",
                            "Geometry resources contain block states only; Java supplies scoped native gameplay.",
                            "Marker Y positions account for original gravity settling onto source Y=38 constructor blocks."]}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, result in [("cave_parkour_routes.json", routes), ("phase3_source_audit.json", audit)]:
        data = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        (args.output / name).write_text(data)
        print(name, len(data.encode()), "bytes")
    print("cave branches", sum(map(len, routes["branches"].values())), "checkpoints", len(routes["checkpoints"]))
    print("platform command blocks", len(platforms["commandBlocks"]), "gem spots", len(platforms["gemSpots"]))


if __name__ == "__main__":
    main()
