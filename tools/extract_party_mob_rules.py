#!/usr/bin/env python3
"""Extract typed source evidence for Party 2 Whack-A-Mob and Monster Shoot.

Reason: preserving the original arenas is insufficient when marker-item
weights, mirrored lanes and score flush order are omitted. Read the original
functions without executing commands and record bounded native rule data.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile


def sha(data: bytes | str) -> str:
    return hashlib.sha256(data.encode() if isinstance(data, str) else data).hexdigest()


def marker_items(text: str, tag: str):
    result = []
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith("#") or f'"{tag}"' not in line:
            continue
        match = re.search(r"summon minecraft:item (-?\d+) (-?\d+) (-?\d+) ", line)
        if not match:
            continue
        tags = re.findall(r'"([^"]+)"', re.search(r"Tags:\[([^\]]+)\]", line)[1])
        x, y, z = map(int, match.groups())
        result.append({"position": [x + 0.5, y, z + 0.5], "tags": tags,
                       "sourceLine": number, "commandSha256": sha(line)})
    return result


def whack(functions):
    entry = functions["whack_a_mob"]["text"]
    rules = functions["whack_a_mob_rules"]["text"]
    starts = []
    for number, line in enumerate(entry.splitlines(), 1):
        match = re.search(r"minigameplayer=(\d+)\}\] (-?\d+) (-?\d+) (-?\d+)$", line)
        if match:
            slot, x, y, z = map(int, match.groups())
            starts.append({"slot": slot, "position": [x + .5, y, z + .5], "sourceLine": number})
    holes = marker_items(rules, "mob_spot")
    pool = marker_items(rules, "mob")
    weights = Counter(tag for item in pool for tag in item["tags"] if re.fullmatch(r"mob[1-6]", tag))
    templates = []
    for number, line in enumerate(functions["wam_summon_mob2"]["text"].splitlines(), 1):
        match = re.search(r"if score mob variables matches ([1-6]) run summon minecraft:(\w+) (~-?\d*|~) (-?\d+) (~-?\d*|~) (.*)", line)
        if not match:
            continue
        variant, entity, x, y, z, nbt = match.groups()
        minimum = re.search(r"if score players variables matches (\d+)\.\.", line)
        attributes = {name: float(value) for name, value in re.findall(r'id:"minecraft:([^" ]+)",base:([\d.]+)d', nbt)}
        health = re.search(r"Health:([\d.]+)f", nbt)
        templates.append({"variant": int(variant), "entity": "minecraft:" + entity,
                          "offsetXZ": [int(x[1:] or 0), int(z[1:] or 0)], "sourceY": int(y),
                          "minimumPlayers": int(minimum[1]) if minimum else 1,
                          "attributes": attributes, "health": float(health[1]) if health else attributes["max_health"],
                          "noAI": "NoAI:1b" in nbt, "noGravity": "NoGravity:1b" in nbt,
                          "equipment": re.findall(r'\w+:\{id:"(minecraft:[^"]+)"', nbt),
                          "sourceLine": number, "commandSha256": sha(line)})
    assert len(starts) == 10 and len(holes) == 9 and len(pool) == 11 and len(templates) == 60
    assert weights == {"mob1": 4, "mob2": 1, "mob3": 1, "mob4": 1, "mob5": 2, "mob6": 2}
    for variant in range(1, 7):
        selected = [item for item in templates if item["variant"] == variant]
        assert len({tuple(item["offsetXZ"]) for item in selected}) == 10
    return {"geometry": "whack_a_mob", "playerStarts": starts, "holeMarkers": holes,
            "typeMarkers": pool, "typeWeights": dict(sorted(weights.items())), "mobTemplates": templates,
            "durationTicks": 1800, "normalSpawnDelayTicks": 20,
            "typePoints": {"minecraft:zombie": 1, "minecraft:wither_skeleton": 3,
                           "minecraft:zombie_villager": 3, "minecraft:enderman": 5,
                           "minecraft:blaze": -1, "minecraft:zombified_piglin": 2},
            "semantics": ["Source entry's 1200 ticks are overridden to 1800 ticks by whack_a_mob_start.",
                          "A shared chosen hole and type are mirrored into all ten source lanes; the first four lanes always spawn even with two or three players.",
                          "When all nine holes are occupied the source retains the previous mob_spot score rather than resetting it.",
                          "Only alive entities participate in @e occupancy and drain checks; corpses do not hold a hole open.",
                          "The source flushes each per-type killed statistic once per tick, then decrements its timer and tests for ending.",
                          "Endermen increment life every tick, globally hide at e_teleport=20 at Y=30 and reappear at e_teleport>=30 at Y=37.",
                          "All WAM mobs join the no_collision team; Monster Shoot mobs do not.",
                          "A /kill retraction has no direct attacker and does not increment the specific minecraft.killed:type statistic."]}


def monster(functions):
    entry = functions["monster_shoot"]["text"]
    positions = marker_items(entry, "monster_shoot")
    pool = marker_items(entry, "ms_monster")
    weights = Counter(tag for item in pool for tag in item["tags"] if tag != "ms_monster")
    assert len(positions) == 25 and len(pool) == 12
    assert weights == {"zombie": 5, "creeper": 3, "silverfish": 2, "skeleton": 2}
    return {"geometry": "monster_shoot", "spawnMarkers": positions, "typeMarkers": pool,
            "typeWeights": dict(sorted(weights.items())), "sourceSpawnY": 55,
            "durationTicks": 1200, "spawnDelayTicksByPlayerCount": {"2": 20, "3": 20, "4": 20,
                "5": 17, "6": 14, "7": 12, "8": 10, "9": 9, "10": 8},
            "typePoints": {"minecraft:zombie": 1, "minecraft:creeper": 2, "minecraft:skeleton": 3,
                           "minecraft:silverfish": 5, "minecraft:chicken": 1},
            "largePlayerCircle": {"minimumPlayers": 5, "center": [-71.5, 19, -262.5], "radius": 12,
                                  "initialYaw": 0, "yawStep": "floor(360/playerCount)"},
            "semantics": ["All spawned monsters have maximum health five. Zombies ride invisible health-five chickens; zombie and skeleton wear a stone button in the head slot.",
                          "Creepers, silverfish and skeletons receive slow falling; chickens retain native fall gliding and zombies retain their native passenger position.",
                          "The bow has Power V and Infinity. The tick's actual projectile PierceLevel is one, despite the source bow's Piercing V enchantment.",
                          "Grounded mobs and orphaned zombie/chicken partners are killed without a direct player attacker and do not earn per-type points.",
                          "The timer transitions to ending at zero; spawning stops, and the source waits until no alive monster-tagged entity remains.",
                          "The ending test runs before per-type kill-stat flush. end_monster_shoot removes those objectives before placements, so a pending kill on the final drain tick is not counted.",
                          "Per-type killed statistics are tested for >=1, then reset, so multiple same-type kills in one source tick earn that type's value once."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, default=Path("run/external-maps/templates/minecraft-party-2"))
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party/mob_games_rules.json"))
    args = parser.parse_args()
    groups = {"402": ["whack_a_mob", "whack_a_mob_rules", "whack_a_mob_start", "whack_a_mob_tick",
                      "whack_a_mob_end", "wam_summon_mob", "wam_summon_mob2", "wam_enderman_teleport", "wam_enderman_reappear"],
              "403": ["monster_shoot", "monster_shoot_rules", "monster_shoot_start", "monster_shoot_tick",
                      "ms_spawn_monster", "end_monster_shoot"],
              "common": ["player_circle", "player_circle_place_player", "rotate_entity", "scoreboard_data"]}
    functions = {}
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as pack:
        for game, names in groups.items():
            functions[game] = {}
            for name in names:
                path = "data/mcp/function/" + name + ".mcfunction"
                raw = pack.read(path)
                functions[game][name] = {"path": path, "sha256": sha(raw), "text": raw.decode()}
    result = {"formatVersion": 1,
              "source": {"name": "Minecraft Party 2", "minecraftVersion": "1.21.10",
                         "url": "https://www.minecraftmaps.com/52024-minecraft-party-2",
                         "functionSha256": {name: info["sha256"] for group in functions.values() for name, info in group.items()}},
              "games": {"402": whack(functions["402"]), "403": monster(functions["403"])},
              "commonFunctionEvidence": functions["common"],
              "boundaries": ["Function text and command hashes are read-only evidence, never instructions or runtime commands.",
                             "Original global normal difficulty, mobGriefing=false, doMobLoot=false and doEntityDrops=false must be reproduced per owned entity rather than changing a shared server.",
                             "The existing phase3_source_audit.json retains the complete original entry/helper text for both games."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(args.output, args.output.stat().st_size, "bytes; 9 WAM holes, 60 lane/type templates, 25 Monster Shoot spawn markers")


if __name__ == "__main__":
    main()
