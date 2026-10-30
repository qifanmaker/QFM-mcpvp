#!/usr/bin/env python3
"""Read-only extraction of417 Speed Builders' field and28 native templates.

Reason: copying a generic model loses original six-by-four-by-six air cells,
independent wood-to-concrete palettes and reset geometry. Review: extract only
known block states and whitelist visible sign/banner data; never import commands
or entity/block-entity NBT. Button commands are retained as audited source text.
"""
from __future__ import annotations

import argparse
import functools
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld, extract, supported_block_names

FUNCTIONS = ["speed_builders", "speed_builders_start", "speed_builders_tick", "speed_builders_end",
             "speed_builders_make_builds", "speed_builders_build_maker", "speed_builders_move_paint_brush",
             "speed_builders_summon_colors", "speed_builders_next_build", "checkpoint", "checkpoint2",
             "assign_finish_threshold", "timer_decrement"]
MINIMUM, MAXIMUM = (-389, 27, -180), (-337, 54, -100)
ORIGINS = [(x, 28, z) for z in (-153, -160, -167, -174)
           for x in (-343, -350, -357, -364, -371, -378, -385)]


def visible(value):
    """Keep display content/style only; no click, hover, selector or NBT query."""
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
            return visible(decoded) if isinstance(decoded, (str, dict, list)) else {"text": value}
        except (ValueError, TypeError):
            return {"text": value}
    if isinstance(value, list):
        return {"text": "".join(visible(child)["text"] for child in value)}
    if not isinstance(value, dict):
        return {"text": ""}
    result = {"text": value.get("text", value.get("fallback", value.get("translate", "")))}
    if "extra" in value:
        result["text"] += "".join(visible(child)["text"] for child in value["extra"])
    result.update({key: value[key] for key in ("color", "bold", "italic", "underlined") if key in value})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    world = AnvilWorld(args.world)
    world.chunk = functools.lru_cache(None)(world.chunk)
    supported = supported_block_names(args.minecraft_sources)
    specs = {"speed_builders_arena": (MINIMUM, MAXIMUM)}
    specs.update({f"speed_builders_pattern_{index}": (origin, tuple(v + d for v, d in zip(origin, (5, 3, 5))))
                  for index, origin in enumerate(ORIGINS, 1)})
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        raw = {name: archive.read("data/mcp/function/" + name + ".mcfunction") for name in FUNCTIONS}
        sources = {name: content.decode("utf-8") for name, content in raw.items()}
        geometries = {name: extract(world, name, {"min": bounds[0], "max": bounds[1], "functions": FUNCTIONS,
                       "notes": ["Complete ten-lane field and all28 immutable templates." if name.endswith("arena") else
                                 "Exact6x4x6 source model. Java initializes all144 cells including air.",
                                 "Command blocks omitted; scoped Java implements only the10 audited reset fills."]},
                                   archive, supported) for name, bounds in specs.items()}
    output = args.output / "geometry"
    output.mkdir(parents=True, exist_ok=True)
    for name, geometry in geometries.items():
        encoded = json.dumps(geometry, ensure_ascii=False, separators=(",", ":")).encode()
        (output / (name + ".json.gz")).write_bytes(gzip.compress(encoded, compresslevel=9, mtime=0))
    signs, banners, commands = [], [], []
    for cx in range(MINIMUM[0] // 16, MAXIMUM[0] // 16 + 1):
        for cz in range(MINIMUM[2] // 16, MAXIMUM[2] // 16 + 1):
            for entity in world.chunk(cx, cz).get("block_entities", []):
                pos = [entity[k] for k in ("x", "y", "z")]
                if not all(MINIMUM[i] <= pos[i] <= MAXIMUM[i] for i in range(3)):
                    continue
                if entity["id"] == "minecraft:sign":
                    sign = {"pos": pos}
                    for side in ("front", "back"):
                        text = entity.get(side + "_text", {})
                        sign[side] = [visible(line) for line in text.get("messages", [""] * 4)]
                        sign[side + "Color"] = text.get("color", "black")
                        sign[side + "Glowing"] = bool(text.get("has_glowing_text", False))
                    signs.append(sign)
                elif entity["id"] == "minecraft:banner":
                    banners.append({"pos": pos, "patterns": [{"color": row["color"], "pattern": row["pattern"]}
                                                             for row in entity.get("patterns", [])]})
                elif entity["id"] == "minecraft:command_block":
                    command = entity["Command"]
                    if command != "fill ~-7 ~-1 ~-10 ~8 ~6 ~-3 minecraft:air":
                        raise ValueError(f"Unexpected original417 command at{pos}: {command}")
                    commands.append({"pos": pos, "source": command, "sha256": hashlib.sha256(command.encode()).hexdigest()})
    blockers = [{"seat": int(seat) - 1, "pos": [int(x), int(y), int(z)]}
                for seat, x, y, z in re.findall(r"minigameplayer=(\d+).*?summon minecraft:interaction (-?\d+) (-?\d+) (-?\d+) \{Tags:\[\"FlowerBlocker\"\]\}", sources["speed_builders"])]
    # The earlier hand audit counted50, but the hashed source has40 summons:
    # two per odd seat and six per even seat. Never invent ten extra hitboxes.
    if len(blockers) != 40 or len(commands) != 10 or len(signs) != 37 or len(banners) != 12:
        raise ValueError("Unexpected original417 scene/interaction counts")
    audit = {
        "sourceId": 417, "sourceVersion": "1.21.10", "minimumPlayers": 2, "maximumPlayers": 10,
        "functions": sources,
        "functionSha256": {name: hashlib.sha256(value).hexdigest() for name, value in raw.items()},
        "templates": [{"name": name, "origin": list(ORIGINS[index - 1]), "cells": 144,
                       "nonAir": len(geometries[name]["blocks"])}
                      for index in range(1, 29) for name in [f"speed_builders_pattern_{index}"]],
        "rules": {"timeLimitTicks": 3600, "builds": 4, "templatePool": 28,
                  "selection": "Uniform without replacement; consume the source's unused fifth template after four palettes.",
                  "palette": "Independently permute8 concrete colors for the8 plank species per build; same four models for every player.",
                  "comparison": "All144 cells including air; target is preview translatedX-8, no reflection.",
                  "tickOrder": "Due1tick reset fill, replenish native items, restore all active previews, compare all active targets, rank/finish.",
                  "rawScore": "initialPoints3; after any checkpoint: builds*N plus number of later most-recent completions; same tick ties.",
                  "finishThreshold": "N-1 whenN<=7, N-2 whenN>=8", "jumpBoostAmplifier": 2, "weaknessAmplifier": 255,
                  "reset": {"commandRelativeMin": [-7, -1, -10], "commandRelativeMax": [8, 6, -3], "buttonTicks": 30},
                  "blockLoot": "Source defaults doTileDrops=false; suppress only native mined block drops in this round, without world gamerule mutation."},
        "flowerBlockers": blockers, "resetCommands": commands, "signs": signs, "banners": banners,
        "sourceCorrections": [
            "Player5/build2 unless-blocks has wrongZmax-148; restore the correct144 preview cells, preserving its effective visible result.",
            "Source drop cleanup names netherite_pickaxe although golden_pickaxe is supplied; remove owned round items reliably.",
            "Source switches finishers to spectator before adventure-only clear; clear their owned inventory at finish.",
        ],
        "adaptations": [
            "Source selectors/scoreboards/gamerules and commands are data only; native gameplay is scoped to this round's players/geometry/entities.",
            "Native oak button controls sound and30tick powered state; interpret audited fill one tick after an accepted rising-edge click.",
            "Preserve native Adventure CAN_BREAK/CAN_PLACE_ON, real mining/placement and preview editing; onPlaced tracks initial-air positions for cleanup.",
            "Rebuild37 safe signs and12 banner pattern lists from display-only data; no click/hover/NBT commands imported.",
            "Compact active players into source seats0..9; same-tick completion preserves tied checkpoint rank.",
        ],
        "geometry": {name: {"bounds": data["bounds"], "nonAir": len(data["blocks"]),
                             "replacements": data["replacements"], "omitted": data["omitted"],
                             "omittedBlockEntityNbt": data["omittedBlockEntityNbt"]}
                     for name, data in geometries.items()},
    }
    (args.output / "speed_builders_rules.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n")
    print(f"417: {len(geometries)} geometry resources, {len(blockers)} blockers, {len(signs)} signs, {len(banners)} banners")


if __name__ == "__main__":
    main()
