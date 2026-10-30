#!/usr/bin/env python3
"""Extract the complete 411 Tedious Tasks field and immutable clone data.

Reason: eight native tasks share the original four-pedestal arena, tree and
note-block templates. A generic obstacle room or virtual crafting/trading GUI
would lose source gameplay. Review: preserve every block state and clone air,
audit all referenced functions, never execute commands or import entity NBT.
"""
from __future__ import annotations

import argparse
import functools
import gzip
import hashlib
import json
from pathlib import Path
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld, extract, supported_block_names

FUNCTIONS = ["tedious_tasks", "tedious_tasks_start", "tedious_tasks_tick", "tedious_tasks_end",
             "finish_tasks", "checkpoint", "checkpoint2", "timer_decrement"]
FUNCTIONS += [f"player_{seat}_{suffix}" for seat in range(1, 5)
              for suffix in ("next_task", "reset_task", "ring_task", "regen")]
FUNCTIONS += ["give_ready_ups", "ready_up_tellraw", "last_timer_title", "placements", "team_placements"]
SPECS = {
    "tedious_tasks": ((8, -17, -348), (60, 41, -296)),
    "tedious_tree": ((52, 15, -304), (56, 22, -300)),
    "tedious_notes_horizontal": ((11, 15, -339), (13, 16, -339)),
    "tedious_notes_vertical": ((11, 15, -344), (11, 16, -342)),
    "tedious_goal_horizontal": ((12, 15, -345), (14, 16, -345)),
    "tedious_goal_vertical": ((17, 15, -345), (17, 16, -343)),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    world = AnvilWorld(args.world)
    world.chunk = functools.lru_cache(None)(world.chunk)
    supported = supported_block_names(args.minecraft_sources)
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        raw = {name: archive.read("data/mcp/function/" + name + ".mcfunction") for name in FUNCTIONS}
        sources = {name: text.decode("utf-8") for name, text in raw.items()}
        geometries = {
            name: extract(world, name, {"min": bounds[0], "max": bounds[1], "functions": FUNCTIONS,
                          "notes": ["Original complete 411 field including its upper room and source clone storage."
                                    if name == "tedious_tasks" else
                                    "Exact immutable source clone; Java copies all bounds including air.",
                                    "No original commands, entity NBT or block-entity NBT are executed."]}, archive, supported)
            for name, bounds in SPECS.items()
        }
    output = args.output / "geometry"
    output.mkdir(parents=True, exist_ok=True)
    for name, geometry in geometries.items():
        encoded = json.dumps(geometry, ensure_ascii=False, separators=(",", ":")).encode()
        packed = gzip.compress(encoded, compresslevel=9, mtime=0)
        (output / (name + ".json.gz")).write_bytes(packed)
        print(f"{name}: {len(geometry['blocks'])} blocks, {len(packed)} bytes")
    audit = {
        "sourceId": 411, "sourceVersion": "1.21.10", "minimumPlayers": 2, "maximumPlayers": 4,
        "functionSha256": {name: hashlib.sha256(content).hexdigest() for name, content in raw.items()},
        "functions": sources,
        "rules": {
            "timeLimitTicks": 4800, "finishThreshold": "initial count minus one finishes; timeout is also final",
            "tasks": ["native player kill", "native pearl to central gold", "exactly30 native bell rings then20tick check",
                      "harvest warped tree and natively craft wooden axe", "eight native villager trades to nether star",
                      "visit all four pedestals and return home", "three native note blocks note1 to note0",
                      "instant damageII then regenerate to source health20"],
            "randomization": "Each player independently samples without replacement from eight remaining tasks; death retries the current task.",
            "rawScore": "4*completed tasks + checkpoint recency rank; zero progress initially retains source Points3.",
            "combat": "Native modern PvP; natural food regeneration disabled; regenerationII and saturationII maintained.",
            "gracePeriod": "Immediate at2 players; after1 finisher at3 players; after2 finishers at4 players. Non-kill tasks receive weakness255.",
            "death": "Keep inventory; evaluate earned goals before the death reset, then reset only the current/new task at the same pedestal. Preserve the original delayed bell deadline, not merely30 rings. Native60tick join protection with fall exception plus source60tick resistance255 starting2ticks after death.",
            "bells": [[34, -9, -327], [34, -9, -317], [39, -9, -322], [29, -9, -322]],
            "workstations": [[34, -12, -324], [34, -12, -320], [36, -12, -322], [32, -12, -322]],
            "treeDestinations": [[32, -12, -334], [32, -12, -314], [42, -12, -324], [22, -12, -324]],
            "noteDestinations": [[33, -12, -344], [33, -12, -300], [56, -12, -323], [12, -12, -323]],
            "tradeOrder": [["lapis_lazuli", "copper_ingot"], ["netherite_ingot", "emerald"],
                           ["iron_ingot", "gold_ingot"], ["amethyst_shard", "nether_star"],
                           ["gold_ingot", "diamond"], ["copper_ingot", "iron_ingot"],
                           ["emerald", "amethyst_shard"], ["diamond", "netherite_ingot"]],
        },
        "sourceCorrections": [
            "Seat1 next_task wrongly invokes checkpoint as seat2; dispatch by the actual completing player.",
            "reset_task schedules nonexistent player_regen; native respawn and per-tick regeneration replace that dangling helper.",
            "finish_tasks adds points via never-created taskplayer; preserve its actual no-op (no invented bonus).",
            "Note comparison is guarded to the current note task; the source omits that guard but its cleanup normally makes it implicit.",
        ],
        "adaptations": [
            "Use scoped player records, owned native entities and tracked geometry instead of global objectives, schedules, selectors and gamerules.",
            "Native bell-ring statistics are sampled as per-task deltas without erasing persistent player statistics.",
            "Players may disrupt other active players' task trees, bells, note blocks and native merchants; cross-match interaction remains forbidden.",
            "The source's clone storage is immutable data; source upper templates are preserved as geometry, without executable command or block-entity NBT.",
            "Death interception retains real inventory goals and kill counters until the completion-before-reset check; pre-revival health avoids treating the simulated full-health respawn as task8 success.",
            "The source enables immediate respawn and refreshes each pedestal spawnpoint. Positional predicates after an intercepted death observe the home position on the first revived task tick, not the lethal-hit position; source client respawn-packet/tick phase is variable. Thus all-other-pedestals visited plus respawn home can complete task6, while a lethal hit on gold alone does not preserve task2 completion.",
            "Original291 team variant, ready-up dialogue, achievements and resource-pack music remain outside the411 free-for-all module.",
        ],
        "geometry": {name: {"bounds": data["bounds"], "blocks": len(data["blocks"]),
                            "replacements": data["replacements"], "omitted": data["omitted"],
                            "omittedBlockEntityNbt": data["omittedBlockEntityNbt"]}
                     for name, data in geometries.items()},
    }
    (args.output / "tedious_tasks_rules.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
