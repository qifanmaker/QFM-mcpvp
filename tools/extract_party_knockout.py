#!/usr/bin/env python3
"""Extract Knockout 406 without rewriting previously audited Party resources.

Reason: Knockout uses the original volcanic arena and a separately cloned upper
platform. A generic sumo platform loses both the lava death rule and the native
multi-level routes. Review: copy block states only, record all seven functions,
include clone air on placement, and retain commands/NBT solely as audit hashes.
"""
from __future__ import annotations

import argparse
import functools
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

from extract_party_geometry import AnvilWorld, extract, supported_block_names


FUNCTIONS = ["knockout", "knockout2", "knockout3", "knockout4",
             "knockout_respawn", "knockout_tick", "knockout_end"]
SPECS = {
    "knockout": {
        "min": (-466, 34, -148), "max": (-390, 61, -70),
        "functions": FUNCTIONS,
        "notes": [
            "Original volcanic arena, enclosing barrier, lava floor, platform paths and surrounding rock skyline.",
            "Crop surrounds the playable barrier by at least eight blocks and includes the original (-396,38,-80) respawn area; terrain outside this cosmetic crop is not ported.",
            "The upper central structure is cloned separately from knockout_upper, with source translation [-31,11,48].",
        ],
    },
    "knockout_upper": {
        "min": (-403, 41, -162), "max": (-393, 50, -152),
        "functions": ["knockout2", "knockout_end"],
        "notes": [
            "Exact knockout2 clone source; runtime destination [-434,52,-114]..[-424,61,-104].",
            "The Java clone clears all 1210 destination cells before overlaying non-air source blocks.",
        ],
    },
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True)
    parser.add_argument("--minecraft-sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/pvp/party/geometry"))
    args = parser.parse_args()
    world = AnvilWorld(args.world)
    world.chunk = functools.lru_cache(None)(world.chunk)
    supported = supported_block_names(args.minecraft_sources)
    with zipfile.ZipFile(args.world / "datapacks/data.zip") as pack:
        results = {name: extract(world, name, spec, pack, supported) for name, spec in SPECS.items()}
        hashes = {name: hashlib.sha256(pack.read("data/mcp/function/" + name + ".mcfunction")).hexdigest()
                  for name in FUNCTIONS}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, result in results.items():
        data = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode()
        payload = gzip.compress(data, compresslevel=9, mtime=0)
        (args.output / (name + ".json.gz")).write_bytes(payload)
        print(f"{name}: {len(result['blocks'])} blocks, {len(payload)} bytes, replacements={result['replacements']}")
    audit = {
        "sourceId": 406,
        "functionsSha256": hashes,
        "rules": {
            "lives": 3, "timerTicks": None, "nativePvp": True,
            "stickKnockback": [1, 2], "upgrade": "First native playerKillCount credit, including a recent knockoff death",
            "regeneration": {"durationTicks": 20, "amplifier": 44, "refreshEachTick": True},
            "inactiveGlowTicks": 300, "activity": "Actual damage_taken or damage_dealt custom statistic at least 1 (0.1 health)",
            "death": "Feet block lava force-kill; ordinary native lethal damage; one random pending respawn per tick",
            "respawnProtectionTicks": 60, "fallBypassesRespawnProtection": True,
            "rawScore": "All start at initial player count; third death sets elimination order; last survivor retains initial count",
        },
        "adaptations": [
            "The shared vote/introduction replaces global ready-up items, scheduled dialogue, music and advancement popups.",
            "Intercepted deaths reuse the player entity; next-tick random respawn restores native health/hurt state and 60-tick join protection.",
            "Only this match's players and token-tagged dropped sticks are affected; source global gamerules, objectives and entity scans are not run.",
            "Permanent departures do not wait for reconnect; shared five-round tournament handles forfeits and converts raw placement scores to tournament points.",
            "Doubles is not enabled by the current free-for-all Party session.",
        ],
        "geometry": {name: {"bounds": result["bounds"], "blocks": len(result["blocks"]),
                            "replacements": result["replacements"], "omitted": result["omitted"],
                            "omittedBlockEntityNbt": result["omittedBlockEntityNbt"]}
                     for name, result in results.items()},
    }
    (args.output.parent / "knockout_source_audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
