#!/usr/bin/env python3
"""Extract422's complete cloned stage and ten native piston-platform chips.

Reason: Match Me Not is eight rounds of hidden native item drops and piston
growth, not a matching GUI. Review: preserve complete clone air, exact chip
colors, all original banner patterns and source function hashes; never import
commands or arbitrary entity/block-entity NBT into the shared server.
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

FUNCTIONS = ["match_me_not", "match_me_not_rules", "match_me_not_start", "match_me_not_tick", "match_me_not_end",
             "player_circle", "player_circle_place_player", "rotate_entity", "timer_decrement", "last_timer_title",
             "give_ready_ups", "ready_up_tellraw", "placements"]
SPECS = {"match_me_not_arena": ((-350, -12, -385), (-298, 5, -319))}
SPECS.update({f"match_me_not_chip_{seat+1}": ((-361, 6-4*seat, -324), (-355, 8-4*seat, -318)) for seat in range(10)})


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
        data = {name: extract(world, name, {"min": bounds[0], "max": bounds[1], "functions": FUNCTIONS,
                       "notes": ["Source three adjacent clones combine into the complete53x18x67 scene; translation(350,12,885)."
                                 if name.endswith("arena") else "Exact7x3x7 chip; clone includes all147 cells, no rotation.",
                                 "All command/entity NBT omitted. Original visible banner patterns are rebuilt from a safe whitelist."]},
                                     archive, supported) for name, bounds in SPECS.items()}
    output = args.output / "geometry"
    output.mkdir(parents=True, exist_ok=True)
    for name, geometry in data.items():
        encoded = json.dumps(geometry, ensure_ascii=False, separators=(",", ":")).encode()
        (output / (name + ".json.gz")).write_bytes(gzip.compress(encoded, compresslevel=9, mtime=0))
    banners = []
    lo, hi = SPECS["match_me_not_arena"]
    for cx in range(lo[0]//16, hi[0]//16+1):
        for cz in range(lo[2]//16, hi[2]//16+1):
            for entity in world.chunk(cx, cz).get("block_entities", []):
                if entity["id"] == "minecraft:banner" and all(lo[i] <= entity[k] <= hi[i] for i, k in enumerate(("x", "y", "z"))):
                    banners.append({"pos": [entity["x"], entity["y"], entity["z"]],
                                    "patterns": [{"color": layer["color"], "pattern": layer["pattern"]}
                                                 for layer in entity.get("patterns", [])]})
    if len(banners) != 33 or len(data["match_me_not_arena"]["blocks"]) != 4654:
        raise ValueError("Unexpected original422 source geometry")
    rules = {
        "sourceId": 422, "sourceVersion": "1.21.10", "minimumPlayers": 2, "maximumPlayers": 10,
        "functionSha256": {name: hashlib.sha256(value).hexdigest() for name, value in raw.items()},
        "functions": {name: value.decode() for name, value in raw.items()},
        "rules": {"internalRounds": 8, "choiceTicks": 200, "preparationCounter": 5,
                  "intro": "Entry(26,1,533), nativeLevitation80tick at0; originalrules at60 createfield/circle/frames with20levitationticks remaining; expiry80; PLAY160 does not replaylevitation.",
                  "preparationBoundary": "Counter5 also executes the first active choice tick; only four pure preparation ticks.",
                  "materialCount": "4 for2..4 players,5 for5..6,6 for7..8,7 for9..10; retain initial player count after forfeit.",
                  "materialItems": ["brick", "iron_ingot", "gold_ingot", "netherite_ingot", "nether_brick", "copper_ingot", "diamond"],
                  "materials": ["bricks", "iron_block", "gold_block", "netherite_block", "nether_bricks", "copper_block", "diamond_block"],
                  "nativeInput": "Actually drop the supplied item. Source material-order1..7 overwrites simultaneous drops; highest index wins.",
                  "rawScore": "Unique nonzero material across active players adds1, duplicate or no choice adds0; total0..8, ties preserved.",
                  "revealCounterAt60": {str(n): 440 if n <= 4 else 440-20*(n-4) for n in range(2,11)},
                  "scoreCounter": 500, "powerOffCounter": 510, "finalDelayTicks": 40,
                  "circle": "center(26,2,533), radius10 forN<=8/radius12 for9..10, integer360//N angle step; players face center.",
                  "pistons": "Native21 upward pistons atY-1 cross; redstone7x7 atY-2 during500..510 pushes the material layer/floor/player once.",
                  "noRandomDefault": "Unselected0 never becomes a random material. Eight rounds, not one global200tick game."},
        "banners": banners,
        "sourceCorrections": [
            "Source end clears only liveY-6..16 despite importing scene topY17; owned full-volume cleanup clears that layer too.",
            "Global distance0.1 uniqueness guard excludes self; native cages keep distinct players farther apart. Scoped UUID counts also prevent overlapping-player exploits.",
        ],
        "adaptations": [
            "Keep original native item drops,21 piston motion/collision,49 redstone pulse and gravity-enabled dropped revealitems; no virtual item/teleport-on-score substitution.",
            "Preserve clone air and opt into scoped WorldChunk terrain-change tracking because native piston moves bypass BlockItem.onPlaced.",
            "Native ItemFrame entities are blank, invisible, fixed, silent and invulnerable. Use1.21.1 defaultFacingDOWN and its actual attachment center for revealitem placement.",
            "Use scoped UUID/seat records instead of global teams, markers, scoreboards, schedules, functions and gamerules. Restore33 safe banner pattern lists.",
            "Normal five-round tournament and forfeit handling replace original ready-up/board payouts/offline pause;8 rounds are internal to this one minigame.",
        ],
        "geometry": {name: {"bounds": value["bounds"], "blocks": len(value["blocks"]), "replacements": value["replacements"],
                             "omitted": value["omitted"], "omittedBlockEntityNbt": value["omittedBlockEntityNbt"]} for name, value in data.items()},
    }
    (args.output / "match_me_not_rules.json").write_text(json.dumps(rules, ensure_ascii=False, indent=2)+"\n")
    print(f"422: {len(data)} geometries, {len(banners)} safe original banners")


if __name__ == "__main__":
    main()
