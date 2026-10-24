#!/usr/bin/env python3
"""Audit the shipped MyGO map without the source ZIP or a Minecraft runtime.

Why: changing imported geometry or layout must not silently trap a spawn, bury
a bomb site, or disconnect either team from a site. This reproducible check
reads the same compressed blueprint shipped in the Mod. Every imported floor
and roof is legal; hidden outside-ground exclusion columns are forbidden.

Run from any directory:
    python3 /path/to/pvp/tools/audit_mygo_map.py
    python3 tools/audit_mygo_map.py --blueprint /path/to/another.json.gz

This is an OFFLINE APPROXIMATION, not Minecraft's collision engine. Routes use
integer foot positions, two clear cells, at most a one-block jump, and at most
a three-block drop. Slabs/stairs are approximated as support in the cell below;
carpet and thin decorations are treated as clear. Player width, exact partial
block shapes, jump timing, sprinting and fall damage are not simulated. Server
spawn checks and actual client traversal remain necessary.

Output is JSON; exit 0 means the static audit passed, 1 means an audit failed,
and 2 means the input could not be read or decoded. All positions and heights
are relative to the arena center, exactly as encoded in the blueprint.
"""

from __future__ import annotations

import argparse
from collections import Counter
import gzip
import heapq
import json
from pathlib import Path
import sys


DEFAULT_BLUEPRINT = Path(__file__).resolve().parents[1] / "src/main/resources/pvp/mygo/maps/dust2.json.gz"
AIR = {"air", "cave_air", "void_air"}
THIN_DECORATIONS = {
    "short_grass", "tall_grass", "dead_bush", "fern", "large_fern", "vine",
    "torch", "wall_torch", "redstone_torch", "redstone_wall_torch", "lever",
    "tripwire", "tripwire_hook", "ladder",
}
THIN_SUFFIXES = ("_sign", "_banner", "_button", "_pressure_plate", "_carpet")
NON_SUPPORT = {"water", "lava", "chain", "flower_pot", "sea_pickle", "lantern", "soul_lantern"}
PARTIAL_SUPPORT_SUFFIXES = (
    "_wall", "_fence", "_fence_gate", "_pane", "_door", "_head", "_skull",
)


def position(value):
    if not isinstance(value, list) or len(value) != 3 or any(type(n) is not int for n in value):
        raise ValueError("A position must contain three integer coordinates")
    return tuple(value)


def bounds(value):
    minimum, maximum = position(value["min"]), position(value["max"])
    if any(minimum[i] > maximum[i] for i in range(3)):
        raise ValueError("Inverted map bounds")
    return minimum, maximum


def contains(box, pos):
    return all(box[0][i] <= pos[i] <= box[1][i] for i in range(3))


def short_name(state):
    return state["Name"].removeprefix("minecraft:")


def clear(state):
    name = short_name(state)
    return name in AIR or name in THIN_DECORATIONS or name.endswith(THIN_SUFFIXES)


def support(state, full=False):
    name = short_name(state)
    if clear(state) or name in NON_SUPPORT or name.endswith(PARTIAL_SUPPORT_SUFFIXES):
        return False
    properties = state.get("Properties", {})
    if name.endswith("_trapdoor"):
        return properties.get("open") == "false" and (not full or properties.get("half") == "top")
    if full and name.endswith("_slab"):
        return properties.get("type") in ("top", "double")
    if full and name.endswith("_stairs"):
        return properties.get("half") == "top"
    return True


class Blueprint:
    def __init__(self, data):
        if data["formatVersion"] != 1:
            raise ValueError("Unsupported MyGO blueprint format")
        self.data = data
        self.area = bounds(data["playBounds"])
        self.geometry = bounds(data["bounds"])
        self.palette = data["palette"]
        if not self.palette:
            raise ValueError("Empty block palette")
        self.cells = {}
        for encoded in data["blocks"]:
            if not isinstance(encoded, list) or len(encoded) != 4:
                raise ValueError("A block requires x, y, z and a palette index")
            pos = position(encoded[:3])
            index = encoded[3]
            if type(index) is not int or not 0 <= index < len(self.palette):
                raise ValueError("Invalid block palette index")
            if pos in self.cells or not contains(self.geometry, pos):
                raise ValueError("Duplicate or out-of-bounds block position")
            self.cells[pos] = self.palette[index]
        self.air = {"Name": "minecraft:air"}

    def state(self, x, y, z):
        return self.cells.get((x, y, z), self.air)

    def playable(self, pos):
        return contains(self.area, pos)

    def can_stand(self, pos, full=False):
        x, y, z = pos
        return (self.playable(pos) and clear(self.state(x, y, z))
                and clear(self.state(x, y + 1, z)) and support(self.state(x, y - 1, z), full))

    def walk_nodes(self):
        minimum, maximum = self.area
        return {(x, y, z) for x in range(minimum[0], maximum[0] + 1)
                for z in range(minimum[2], maximum[2] + 1)
                for y in range(minimum[1], maximum[1] + 1) if self.can_stand((x, y, z))}

    def routes(self, start, targets, nodes):
        """Weighted four-neighbor search; vertical movement is mildly penalized."""
        pending = set(targets)
        queue, distances, previous, found = [(0, start)], {start: 0}, {}, {}
        while queue and pending:
            distance, pos = heapq.heappop(queue)
            if distance != distances[pos]:
                continue
            if pos in pending:
                path, cursor = [pos], pos
                while cursor in previous:
                    cursor = previous[cursor]
                    path.append(cursor)
                found[pos] = path[::-1]
                pending.remove(pos)
            x, y, z = pos
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                for dy in (0, 1, -1, -2, -3):
                    neighbor = (x + dx, y + dy, z + dz)
                    if neighbor not in nodes or (dy > 0 and not clear(self.state(x, y + 2, z))):
                        continue
                    cost = distance + 5 + abs(dy)
                    if cost < distances.get(neighbor, float("inf")):
                        distances[neighbor], previous[neighbor] = cost, pos
                        heapq.heappush(queue, (cost, neighbor))
        return found


def audit(data):
    blueprint = Blueprint(data)
    errors, spawns, sites = [], [], []
    if "outsideGround" in data:
        errors.append("Hidden forbidden columns must not invalidate reachable terrain")
    if not all(contains(blueprint.area, pos) for pos in blueprint.geometry):
        errors.append("The playable region must include the entire imported map")
    roof = list(blueprint.geometry[1])
    roof[1] += 3
    if not contains(blueprint.area, roof):
        errors.append("The highest roof must have standing and jumping headroom")
    for side in ("T", "CT"):
        encoded_spawns = data["spawns"][side]
        if len(encoded_spawns) != 5:
            errors.append(f"{side} must have exactly five spawn points")
        buy_zone = bounds(data["buyZones"][side])
        for index, encoded in enumerate(encoded_spawns, 1):
            label, pos = side + str(index), position(encoded["pos"])
            if not contains(buy_zone, pos) or not blueprint.can_stand(pos, full=True):
                errors.append(f"{label} lacks playable buy-zone clearance or a full upper support face: {pos}")
            spawns.append((label, pos))
    if len({pos for _, pos in spawns}) != len(spawns):
        errors.append("Spawn positions overlap")
    if [site["name"] for site in data["sites"]] != ["A", "B"]:
        errors.append("Sites must be ordered A, B")
    for site in data["sites"]:
        pos = position(site["pos"])
        if not contains(bounds(site), pos) or not blueprint.can_stand(pos, full=True):
            errors.append(f"Site {site['name']} lacks playable clearance or a full upper support face: {pos}")
        sites.append((site["name"], pos))
    unstable = Counter(state["Name"] for state in blueprint.cells.values()
                       if short_name(state) in {"sand", "red_sand", "gravel", "anvil", "chipped_anvil", "damaged_anvil"}
                       or short_name(state).endswith("_concrete_powder"))
    if unstable:
        errors.append("Unstabilized gravity blocks: " + str(dict(unstable)))
    report = {"status": "fail" if errors else "pass", "map": data["id"],
              "method": "offline approximate collision grid; client traversal still required",
              "coordinates": "relative to arena center", "maxJump": 1, "maxDrop": 3,
              "spawnChecks": len(spawns), "siteChecks": len(sites),
              "entireMapPlayable": True, "routes": []}
    if not errors:
        nodes = blueprint.walk_nodes()
        report["walkableNodes"] = len(nodes)
        for label, start in spawns:
            found = blueprint.routes(start, {pos for _, pos in sites}, nodes)
            for site, end in sites:
                path = found.get(end)
                if path is None:
                    errors.append(f"{label} cannot reach site {site}")
                    continue
                report["routes"].append({"from": label, "to": site, "steps": len(path) - 1,
                                          "height": [min(pos[1] for pos in path), max(pos[1] for pos in path)]})
    if errors:
        report.update(status="fail", errors=errors)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--blueprint", type=Path, default=DEFAULT_BLUEPRINT)
    args = parser.parse_args()
    try:
        with gzip.open(args.blueprint, "rt", encoding="utf-8") as stream:
            report = audit(json.load(stream))
    except (OSError, EOFError, ValueError, KeyError, TypeError, IndexError) as error:
        print(json.dumps({"status": "error", "error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
    return int(report["status"] != "pass")


if __name__ == "__main__":
    sys.exit(main())
