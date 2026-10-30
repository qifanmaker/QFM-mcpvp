#!/usr/bin/env python3
"""Extract 419's complete End scenery and both original seat clone templates.

Reason: Power Towers is a native note-block tuning contest followed by a real
concrete-powder collapse, not a block-building or parkour game. Review: keep all
surrounding tall rock structures, native seat states/clone air and the original
End height. Commands and raw entity/block-entity NBT remain audit data only.
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

FUNCTIONS = ["power_towers", "power_towers_start", "power_towers_tick", "power_towers_end",
             "timer_decrement", "minigame_points_ranking", "give_ready_ups", "ready_up_tellraw",
             "last_timer_title"]
SPECS = {
    "power_towers": ((-632, 0, -107), (-375, 255, 121)),
    "power_towers_west": ((-473, 12, -19), (-470, 14, -17)),
    "power_towers_east": ((-473, 12, -15), (-470, 14, -13)),
}
TOWERS = [(-500,-1),(-496,-9),(-496,-1),(-500,-9),(-497,-5),(-499,-5),
          (-494,-13),(-502,3),(-494,3),(-502,-13)]
SPAWNS = [(-501,-1),(-495,-9),(-495,-1),(-501,-9),(-496,-5),(-500,-5),
          (-493,-13),(-503,3),(-493,3),(-503,-13)]
DESTINATIONS = [(-502,-2),(-497,-10),(-497,-2),(-502,-10),(-498,-6),(-501,-6),
                (-495,-14),(-504,2),(-495,2),(-504,-14)]
WEST = {1,4,6,8,10}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party"))
    args = parser.parse_args()
    world = AnvilWorld(args.world / "DIM1")
    world.chunk = functools.lru_cache(None)(world.chunk)
    supported = supported_block_names(args.minecraft_sources)
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as archive:
        raw = {name: archive.read("data/mcp/function/" + name + ".mcfunction") for name in FUNCTIONS}
        source = {name: value.decode("utf-8") for name, value in raw.items()}
        assert "timeLeft variables 200" in source["power_towers_start"]
        assert "minecraft.custom:minecraft.tune_noteblock" in source["power_towers_start"]
        assert source["power_towers_tick"].index("run kill @e[type=minecraft:marker,tag=power_towers]") < source["power_towers_tick"].index("tag=east")
        for name, bounds in SPECS.items():
            geometry = extract(world, name, {"min": bounds[0], "max": bounds[1], "functions": FUNCTIONS,
                "notes": ["Complete 419 End scene and distant decorative rock towers; no coordinate scaling or cropped scenery."
                          if name == "power_towers" else "Exact 4x3x3 immutable seat source; runtime clone also copies air.",
                          "The complete scene was isolated by an empty-margin scan of X=-672..-320, Z=-192..192, Y=0..255.",
                          "Native End Y=0..255 is retained for the dynamic tower fill and falling-block lifecycle.",
                          "No source commands, entity NBT, block inventories or executable block-entity NBT are imported."]}, archive, supported)
            if name == "power_towers":
                assert len(geometry["blocks"]) == 68871
                assert not world.block_entity_counts(*bounds)
            encoded = json.dumps(geometry, ensure_ascii=False, separators=(",", ":")).encode()
            target = args.output / "geometry" / (name + ".json.gz")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(gzip.compress(encoded, compresslevel=9, mtime=0))
            print(name, geometry["size"], len(geometry["blocks"]), "blocks", len(geometry["palette"]), "states")
    seats = []
    for index, ((x,z),(sx,sz),(dx,dz)) in enumerate(zip(TOWERS, SPAWNS, DESTINATIONS), 1):
        west = index in WEST
        seats.append({"platform": index, "tower": [x,52,z], "spawn": [sx+.5,52,sz+.5],
                      "yaw": 90 if west else -90, "template": "west" if west else "east",
                      "destination": [dx,51,dz], "note": [dx if west else dx+3,52,z]})
    audit = {"formatVersion": 1, "sourceId": 419, "sourceVersion": "1.21.10", "dimension": "minecraft:the_end",
        "anchor": [-498,52,-5], "bounds": {"min": SPECS["power_towers"][0], "max": SPECS["power_towers"][1]},
        "functionSha256": {n: hashlib.sha256(v).hexdigest() for n,v in raw.items()}, "sourceFunctions": source,
        "entryAudit": {"active": ["quickplayminigame→in the_end power_towers", "set_up_minigame→in the_end power_towers",
                                  "replay_minigame→in the_end power_towers", "start_minigame→power_towers_start after4s",
                                  "4_4_all_tick→in the_end power_towers_tick"],
                       "sourceBlockEntities": 0, "sourceCommandBlocks": 0,
                       "completeStaticNonAir": 68871, "emptyMarginScan": [[-672,0,-192],[-320,255,192]]},
        "seats": seats, "eightPlayerPlatforms": [1,2,3,4,9,10,7,8],
        "powderColors": ["blue","red","lime","yellow","cyan","magenta","brown","green","purple","gray"],
        "timing": {"mashingTicks": 200, "emptyFallingTicksBeforeEnd": 15, "emptyCounterResetsOnFalling": False},
        "growth": {"initialMarkerY": 52, "acceptedTickDeltas": [1,7], "largerDeltasMoveMarker": False,
                   "fill": "air only, inclusive Y between markerY-1 and53; fail the entire fill outside original End0..255",
                   "alwaysFillEvenWithZeroDelta": True},
        "release": {"turnYaw": 180, "replace": "reinforced_deepslate atY52 → tripwire",
                    "removeY": 53, "nativeGhostBase": "reinforced_deepslate block_display translated -0.5,0,-0.5",
                    "compensation": "2 if total tune count>=2, 1 if count==1, otherwise0",
                    "markerBarrierCommands": "No effect: all markers have already been killed before these selectors run."},
        "scoring": {"nativeFallingThresholdFloorY": 56, "scoreOncePerEntity": True,
                    "raw": "TowerHeight: compensation plus each owned native colored falling_block whose floor(PosY)<=56",
                    "ranking": "Final Points is assigned TowerHeight after the source's intermediate ranking tellraw; five-game round ranking uses final TowerHeight."},
        "nativeRules": {"jumpStrength": 0, "weaknessAmplifier": 255, "doEntityDrops": False, "doTileDrops": False,
                        "sourceDimensionMinY": 0, "sourceDimensionHeight": 256, "fallingGravity": .04},
        "adaptations": ["Read per-round tune-stat deltas without resetting players' permanent custom statistics.",
                        "Any active local note block may be tuned; the clicking player's own tower grows, matching source player-stat attribution.",
                        "Only local player state, owned tower entities and tracked blocks are modified; no global objectives, teams, schedules or gamerules run.",
                        "Native falling entities retain gravity, collisions, tripwire landing behavior and source doEntityDrops=false via their own dropItem flag.",
                        "Original End build-height rejection and below-End entity expiration are translated to this round's arena coordinates.",
                        "The source solo60/70/80 achievement threshold is outside the multiplayer match mode; raw tower-height scoring is unchanged."]}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "power_towers_rules.json").write_text(json.dumps(audit, ensure_ascii=False, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
