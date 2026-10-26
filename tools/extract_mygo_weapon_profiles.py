#!/usr/bin/env python3
"""Extract auditable CS:GO handling attributes; no Valve code or media is copied.

Reason: class-wide spread/mobility and integer shot periods erased differences
between weapons. Resolve the original prefabs (including repeated KV sections)
before exporting numerical attributes, so runtime handling has a single source.
Review: duplicate dictionaries must merge; scalar child values override parents;
the known source hash and all 34 enum names are checked before replacing output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

SOURCE_URL = "https://raw.githubusercontent.com/SteamTracking/GameTracking-CS2/e63fc7fdb8dfb4f1873f0db214c0cc16613d5beb/csgo/scripts/items/items_game.txt"
SOURCE_SHA256 = "469cf0ef8a2c53503f0845b60d3c0dc576625dfbbfb9aff885ee8282b45beaec"
PREFABS = {
    "GLOCK": "glock", "USP_S": "usp_silencer", "P2000": "hkp2000", "P250": "p250",
    "FIVESEVEN": "fiveseven", "TEC9": "tec9", "CZ75": "cz75a", "DUALS": "elite",
    "DEAGLE": "deagle", "REVOLVER": "revolver", "MAC10": "mac10", "MP9": "mp9",
    "MP7": "mp7", "MP5SD": "mp5sd", "UMP45": "ump45", "P90": "p90", "BIZON": "bizon",
    "GALIL": "galilar", "FAMAS": "famas", "AK47": "ak47", "M4A4": "m4a1",
    "M4A1S": "m4a1_silencer", "AUG": "aug", "SG553": "sg556", "SSG08": "ssg08",
    "AWP": "awp", "G3SG1": "g3sg1", "SCAR20": "scar20", "NOVA": "nova",
    "XM1014": "xm1014", "MAG7": "mag7", "SAWEDOFF": "sawedoff", "M249": "m249", "NEGEV": "negev",
}
PREFIXES = ("inaccuracy ", "recovery ", "recoil ", "spread", "max player speed", "zoom time ",
            "flinch velocity ", "cycletime", "time between burst", "attack movespeed")
KEYS = {"is full auto", "has burst mode", "has silencer", "is revolver", "penetration"}


def merge(target: dict, source: dict) -> dict:
    for key, value in source.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            merge(target[key], value)
        else:
            target[key] = value
    return target


def parse_keyvalues(text: str) -> dict:
    tokens = iter(token for token in re.findall(r'//[^\n]*|"(?:\\.|[^"\\])*"|[{}]|[^\s{}"]+', text)
                  if not token.startswith("//"))

    def scalar(token: str) -> str:
        return token[1:-1] if token.startswith('"') else token

    def read() -> dict:
        result = {}
        for token in tokens:
            if token == "}":
                return result
            key, value = scalar(token), next(tokens)
            value = read() if value == "{" else scalar(value)
            if isinstance(value, dict) and isinstance(result.get(key), dict):
                merge(result[key], value)
            else:
                result[key] = value
        return result

    return read()


def extract(source: bytes) -> dict:
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Unexpected items_game snapshot; review provenance before exporting")
    prefabs = parse_keyvalues(source.decode("utf-8"))["items_game"]["prefabs"]

    def attributes(name: str, chain: tuple[str, ...] = ()) -> dict:
        if name in chain:
            raise ValueError("Cyclic prefab inheritance")
        prefab, result = prefabs[name], {}
        for parent in prefab.get("prefab", "").split():
            result.update(attributes(parent, chain + (name,)))
        result.update(prefab.get("attributes", {}))
        return result

    weapons = {}
    for name, prefab in PREFABS.items():
        attrs = attributes("weapon_" + prefab + "_prefab")
        weapons[name] = {key: float(value) for key, value in sorted(attrs.items())
                         if key in KEYS or key.startswith(PREFIXES)}
    return {"source": SOURCE_URL, "sourceSha256": SOURCE_SHA256,
            "notes": ["CS:GO legacy 1.38.7.9; numeric attributes resolved through prefab inheritance.",
                      "Spread/inaccuracy values are milli-tangent cone values; speeds are Source units/second.",
                      "Animation deploy/reload durations are not supplied by this table.",
                      "Minecraft recoil smoothing and movement integration remain engine adaptations."],
            "weapons": weapons}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("items_game", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "src/main/resources/pvp/mygo/csgo-weapon-profiles.json")
    args = parser.parse_args()
    result = extract(args.items_game.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Exported {len(result['weapons'])} handling profiles to {args.output}")


if __name__ == "__main__":
    main()
