#!/usr/bin/env python3
"""Build MyGO 1.21.1 assets so vanilla clients can display tool-based guns.

The original PUBG-themed imports do not match the CS:GO arsenal. All 34 gun
silhouettes, materials, poses, utilities, scope and synthetic sounds are now
authored here under this repository's MIT license; no Valve game assets are
redistributed. Requires Python 3 and ffmpeg (also ffprobe for optional licensed audio overrides).
Run from any directory. The committed ZIP is the runtime input, so server builds
do not require Python or ffmpeg. ZIP timestamps are fixed for reproducible SHA-1.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import shutil
import struct
import subprocess
import tempfile
import wave
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
STAGING = ROOT / "build/mygo-resource-pack"
OUTPUT = ROOT / "src/main/resources/pvp/mygo"
GUN_NAMES = ("glock usp_s p2000 p250 fiveseven tec9 cz75 duals deagle revolver "
             "mac10 mp9 mp7 mp5sd ump45 p90 bizon galil famas ak47 m4a4 m4a1s "
             "aug sg553 ssg08 awp g3sg1 scar20 nova xm1014 mag7 sawedoff m249 negev").split()
# 71006 is the knife and 71010 the scope icon. Keep this sequence aligned with
# MyGoWeapon; selectors and all animation offsets are checked against the enum.
GUN_MODELS = dict(zip(GUN_NAMES, (value for value in range(71001, 71037) if value not in (71006, 71010))))

# Reason: Minecraft's locally predicted block steps would overlap the server's
# positional MyGO footsteps. Audit against 1.21.1 sounds.json, asset SHA-1
# 09eab141cce7550f9a74c65db0e9abbb342cf50f; silence only movement events while
# this optional pack is active, leaving block hits/breaks/placements untouched.
VANILLA_STEP_MATERIALS = ("amethyst_block amethyst_cluster ancient_debris anvil azalea azalea_leaves "
    "bamboo bamboo_wood bamboo_wood_hanging_sign basalt big_dripleaf bone_block calcite candle cave_vines "
    "chain cherry_leaves cherry_sapling cherry_wood cherry_wood_hanging_sign chiseled_bookshelf cobweb copper "
    "copper_bulb copper_grate coral_block decorated_pot deepslate deepslate_bricks deepslate_tiles dripstone_block "
    "flowering_azalea froglight frogspawn fungus gilded_blackstone glass grass gravel hanging_roots hanging_sign "
    "heavy_core honey_block ladder lantern lodestone mangrove_roots metal moss moss_carpet mud mud_bricks "
    "muddy_mangrove_roots nether_bricks nether_gold_ore nether_ore nether_sprouts nether_wood "
    "nether_wood_hanging_sign netherite_block netherrack nylium packed_mud pink_petals pointed_dripstone "
    "polished_deepslate polished_tuff powder_snow rooted_dirt roots sand scaffolding sculk sculk_catalyst "
    "sculk_sensor sculk_shrieker sculk_vein shroomlight slime_block small_dripleaf snow soul_sand soul_soil "
    "spawner sponge spore_blossom stem stone suspicious_gravel suspicious_sand trial_spawner tuff tuff_bricks "
    "vault vine wart_block weeping_vines wet_grass wet_sponge wood wool").split()
SURFACES = ("stone", "metal", "wood", "dirt", "water")
ENVIRONMENT_EVENTS = {
    **{"step." + surface: (4, .23 if surface != "metal" else .29, 16) for surface in SURFACES},
    **{"land." + surface: (2, .39 if surface != "metal" else .48, 20) for surface in SURFACES},
    "grenade.pin": (1, .24, 12),
    "grenade.bounce.hard": (3, .18, 24),
    "grenade.bounce.metal": (3, .28, 24),
    "grenade.bounce.soft": (3, .17, 20),
    "smoke.end": (1, .8, 24),
    "jump": (2, .16, 12),
    "equip": (2, .23, 12),
}


def write_json(path: str, data: dict) -> None:
    target = STAGING / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")


def write_text(path: str, content: str) -> None:
    target = STAGING / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)


def write_png(path: str, width: int, height: int, pixels: list[tuple[int, int, int, int]]) -> None:
    def chunk(kind: bytes, content: bytes) -> bytes:
        return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", zlib.crc32(kind + content))

    raw = b"".join(b"\0" + bytes(channel for px in pixels[y * width:(y + 1) * width]
                                    for channel in px) for y in range(height))
    content = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    content += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    target = STAGING / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)


MATERIALS = {
    "metal": (62, 69, 76), "dark": (26, 31, 37), "silver": (135, 148, 155),
    "wood": (122, 65, 33), "tan": (168, 145, 95), "olive": (77, 93, 61),
    "red": (196, 46, 47), "glass": (28, 151, 169), "grip": (36, 38, 40),
}


def box(a, b, material="metal", rotation=None):
    element = {"from": a, "to": b, "faces": {
        face: {"texture": "#" + material, "uv": [0, 0, 16, 16]}
        for face in ("north", "south", "east", "west", "up", "down")}}
    if rotation:
        element["rotation"] = rotation
    return element


def sight(z=5, y=10):
    return [box([7.5, y, z], [8.5, y + .7, z + .5], "dark")]


def optic(z=6, length=4, large=False):
    width = 1.6 if large else 1.15
    y = 11.7 if large else 11.2
    return [box([7.5, 10, z + 1], [8.5, y, z + 1.6], "metal"),
            box([8 - width, y, z], [8 + width, y + 1.8, z + length], "dark"),
            box([7.8 - width, y - .2, z - .5], [8.2 + width, y + 2, z + .3], "metal"),
            box([8 - width, y, z - .55], [8 + width, y + 1.8, z - .5], "glass")]


def pistol(length=8, slide="dark", frame="grip", width=1.25, start=3, grip_z=9):
    return [box([8 - width, 8.2, start], [8 + width, 10.1, start + length], slide),
            box([7.2, 8.6, start - .25], [8.8, 9.6, start], "dark"),
            box([7.05, 7.5, start + 1.2], [8.95, 8.3, start + length], frame),
            box([6.9, 3.2, grip_z], [9.1, 8.2, grip_z + 2.8], frame),
            box([7.25, 5.4, grip_z - 2.7], [8.75, 5.9, grip_z + .2], "metal"),
            box([7.25, 5.9, grip_z - 2.7], [8.75, 7.7, grip_z - 2.2], "metal"),
            *sight(start + .6, 10.1), *sight(start + length - 1, 10.1)]


def rifle(material="dark", stock="dark", front=3.4, barrel=0, magazine="dark", curved=False):
    result = [box([6.65, 8.1, 6.4], [9.35, 10.15, 11.4], "metal"),
              box([6.5, 8, front], [9.5, 9.9, 6.5], material),
              box([7.4, 8.7, barrel], [8.6, 9.55, front], "dark"),
              box([6.95, 3.8, 10], [9.05, 8.1, 12], "grip"),
              box([7.3, 8.5, 11.4], [8.7, 9.6, 13.2], "metal"),
              box([6.7, 6.8, 13], [9.3, 10, 16], stock),
              box([7, 5, 7.5], [9, 8.1, 9.5], magazine),
              box([7, 2.5, 6.8 if curved else 7.5], [9, 5.2, 9.2 if curved else 9.5], magazine),
              *sight(front, 9.9), *sight(10, 10.15)]
    return result


def tube_magazine(front=1, rear=8, material="metal"):
    return [box([7.25, 7.25, front], [8.75, 8.35, rear], material)]


def models():
    """Original recognisable silhouettes: barrel points -Z; each gun has unique geometry.

    Cuboids stay in Minecraft's legal bounds and shared materials are inside
    textures/item so vanilla 1.21.1 stitches every face without OptiFine.
    """
    guns = {}
    guns["glock"] = pistol(length=7.3, start=3.4, grip_z=8.6)
    guns["usp_s"] = pistol(length=6.9, start=4.5, grip_z=9.3) + [
        box([7.05, 8.1, .2], [8.95, 10, 4.5], "dark"),
        box([7.5, 8.6, .1], [8.5, 9.5, .2], "metal")]
    guns["p2000"] = pistol(length=7.1, start=3.9, slide="metal", grip_z=9.5) + [
        box([7.4, 9.6, 11], [8.6, 10.6, 11.6], "dark")]
    guns["p250"] = pistol(length=7.7, start=3.5, frame="tan", grip_z=9.5) + [
        box([7, 7, 4.6], [9, 7.6, 7], "metal")]
    guns["fiveseven"] = pistol(length=8.2, start=3, width=1.1, frame="metal", grip_z=9.1) + [
        box([7, 2.4, 9.1], [9, 3.3, 11.9], "dark")]
    guns["tec9"] = [box([6.9, 8.5, 1.6], [9.1, 10.7, 11.8], "metal"),
        box([7.4, 9, .2], [8.6, 10, 1.6], "dark"),
        box([7, 3.1, 10.5], [9, 8.5, 13], "grip"),
        box([7.15, .6, 6.6], [8.85, 8.5, 8.4], "dark"),
        box([7.1, 6.3, 8.3], [8.9, 7, 11.1], "metal"), *sight(2, 10.7), *sight(10.8, 10.7)]
    for z in [2.4, 3.6, 4.8]:
        guns["tec9"].append(box([6.85, 9.1, z], [9.15, 10, z + .5], "dark"))
    guns["cz75"] = pistol(length=8, start=2.5, slide="metal", grip_z=8.8) + [
        box([7.1, 4.8, 3.2], [8.9, 8.2, 5.1], "dark"),
        box([7, 2, 8.7], [9, 3.3, 11.7], "metal")]
    guns["duals"] = []
    for shift in [-3, 3]:
        for part in pistol(length=7.8, start=3.1, slide="silver", frame="wood", grip_z=9.2):
            part["from"][0] += shift
            part["to"][0] += shift
            guns["duals"].append(part)
    guns["deagle"] = pistol(length=10, start=1.9, slide="silver", frame="grip", width=1.6, grip_z=9.4) + [
        box([7.3, 10.1, 2.2], [8.7, 10.7, 10.8], "metal")]
    guns["revolver"] = [box([7.25, 8.8, .8], [8.75, 10.3, 7.4], "silver"),
        box([6.4, 7.6, 7], [9.6, 10.8, 9.8], "metal"),
        box([7, 8, 9.8], [9, 10, 12], "silver"),
        box([6.8, 3.3, 9.6], [9.2, 8.1, 12.6], "wood"),
        box([7.4, 5.8, 6.3], [8.6, 6.4, 10.1], "silver"),
        box([7.4, 6.3, 6.3], [8.6, 8.3, 6.9], "silver"),
        box([7.5, 10, 10.5], [8.5, 11.1, 11.3], "dark"), *sight(1.3, 10.3)]
    for y, z in [(8, 7.2), (9.5, 7.2), (8, 8.6), (9.5, 8.6)]:
        guns["revolver"].append(box([6.35, y, z], [6.4, y + .7, z + .7], "dark"))
    guns["mac10"] = [box([6.35, 8, 4.1], [9.65, 11.2, 11.7], "metal"),
        box([7.25, 8.9, 1.8], [8.75, 10, 4.1], "dark"),
        box([6.8, 4.4, 8.2], [9.2, 8, 10.8], "grip"),
        box([7.1, .4, 8.6], [8.9, 4.5, 10.4], "metal"),
        box([7.45, 9.9, 11.7], [8.55, 10.5, 15.8], "silver"),
        box([7.45, 6.2, 15.3], [8.55, 10.5, 16], "silver"), *sight(4.8, 11.2)]
    guns["mp9"] = rifle(material="grip", stock="dark", front=2.6)
    guns["mp9"][5] = box([7.3, 6.5, 13], [8.7, 10.1, 15.5], "metal")
    guns["mp9"] += [box([7, 4.4, 3.7], [9, 8, 5.3], "grip"), box([6.4, 9.7, 4], [9.6, 10.3, 8], "dark")]
    guns["mp7"] = [box([6.5, 7.6, 4], [9.5, 10.5, 11], "dark"),
        box([7.35, 8.6, 1.2], [8.65, 9.5, 4], "metal"),
        box([6.9, 3, 8.8], [9.1, 7.8, 11.5], "grip"),
        box([7.2, .8, 9], [8.8, 3.2, 11], "metal"),
        box([7.1, 4.5, 4.5], [8.9, 7.7, 6.1], "grip"),
        box([6.6, 9.3, 10.8], [7.2, 10, 15.7], "silver"),
        box([8.8, 9.3, 10.8], [9.4, 10, 15.7], "silver"),
        box([6.5, 6.8, 15.4], [9.5, 10.3, 16], "dark"), *sight(4.2, 10.5), *sight(10.2, 10.5)]
    guns["mp5sd"] = rifle(material="grip", stock="grip", front=3.7, curved=True)
    guns["mp5sd"][2] = box([6.85, 7.9, -.5], [9.15, 10.2, 4.3], "dark")
    guns["mp5sd"] += [box([7.3, 10.1, 4.6], [8.7, 11.4, 5.3], "metal")]
    guns["ump45"] = rifle(material="dark", stock="grip", front=2.4, magazine="metal")
    guns["ump45"][5] = box([7.25, 6.5, 13.1], [8.75, 10.3, 16], "dark")
    for z in [3, 4.2, 5.4]:
        guns["ump45"].append(box([6.45, 8.7, z], [9.55, 9.35, z + .5], "metal"))
    guns["p90"] = [box([6.1, 7.2, 3.3], [9.9, 10, 14.8], "olive"),
        box([6.5, 10.1, 4.2], [9.5, 11.2, 13.5], "tan"),
        box([7.3, 8.1, 1], [8.7, 9.3, 3.3], "metal"),
        box([6.6, 3.6, 7.1], [9.4, 7.3, 9.3], "grip"),
        box([6.4, 3.6, 9.3], [9.6, 4.8, 15], "olive"),
        box([6.1, 4.7, 13], [9.9, 7.4, 15.5], "olive"),
        box([7.1, 11.2, 7], [8.9, 13, 8.5], "dark"),
        box([7.35, 11.6, 6.95], [8.65, 12.6, 7], "glass")]
    guns["bizon"] = rifle(material="grip", stock="metal", front=2.5, curved=True)
    guns["bizon"] = guns["bizon"][:6] + guns["bizon"][8:] + [
        box([6.5, 5.7, 2.2], [9.5, 8.1, 8.5], "dark"),
        box([7, 10, 2.8], [9, 10.8, 6.4], "metal")]
    guns["galil"] = rifle(material="tan", stock="grip", front=2.1, curved=True)
    guns["galil"] += [box([6.3, 8.5, 3.5], [9.7, 9.4, 5.5], "grip"),
                       box([9.35, 9, 8.8], [10.3, 9.8, 9.6], "silver")]
    guns["famas"] = [box([6.5, 7.9, 5.3], [9.5, 10, 15.5], "dark"),
        box([7.4, 8.6, 0], [8.6, 9.5, 5.3], "metal"),
        box([6.5, 5.7, 12], [9.5, 8, 16], "grip"),
        box([7, 3.5, 7.2], [9, 7.9, 9.2], "grip"),
        box([7, 2.6, 11], [9, 7.9, 12.9], "metal"),
        box([6.9, 10, 6.5], [9.1, 12.5, 7.3], "dark"),
        box([6.9, 10, 12], [9.1, 12.5, 12.8], "dark"),
        box([6.9, 12, 6.5], [9.1, 12.7, 12.8], "dark"), *sight(1, 9.5)]
    guns["ak47"] = rifle(material="wood", stock="wood", front=2.9, curved=True)
    guns["ak47"] += [box([7.35, 9.8, 1.7], [8.65, 10.6, 6.6], "metal"),
                      box([7.45, 9.6, .9], [8.55, 11.2, 1.7], "dark"),
                      box([9.3, 8.9, 8.8], [10.25, 9.6, 9.5], "silver")]
    guns["ak47"][3] = box([6.95, 3.8, 10], [9.05, 8.1, 12], "wood")
    guns["m4a4"] = rifle(material="dark", stock="grip", front=2.9, magazine="silver")
    guns["m4a4"] += [box([7, 10.15, 7.1], [9, 12, 7.8], "dark"),
                      box([7, 10.15, 10.5], [9, 12, 11.2], "dark"),
                      box([7, 11.5, 7.1], [9, 12.2, 11.2], "dark")]
    guns["m4a1s"] = rifle(material="grip", stock="dark", front=4.4, barrel=-1, magazine="metal")
    guns["m4a1s"][2] = box([7, 8.25, -1.6], [9, 10, 3], "dark")
    guns["m4a1s"][7] = box([7, 4.1, 7.5], [9, 5.2, 9.5], "metal")
    guns["m4a1s"] += [box([7.2, 10.2, 7.4], [8.8, 11.7, 8], "dark"),
                       box([7.2, 11.2, 7.4], [8.8, 11.8, 10.5], "dark")]
    guns["aug"] = [box([6.5, 7.5, 5.1], [9.5, 10, 15.3], "olive"),
        box([7.4, 8.65, -.4], [8.6, 9.55, 5.1], "metal"),
        box([6.4, 6, 12], [9.6, 8.1, 16], "olive"),
        box([7, 3.9, 7.9], [9, 7.6, 10], "grip"),
        box([7.1, 3.4, 11.2], [8.9, 7.6, 13.2], "dark"),
        box([7.1, 4.7, 3.8], [8.9, 8.2, 5.6], "grip"), *optic(6.9, 3.7)]
    guns["sg553"] = rifle(material="olive", stock="dark", front=2, curved=True, magazine="tan")
    guns["sg553"][5] = box([7.2, 6.8, 13.2], [8.8, 10, 16], "metal")
    guns["sg553"] += optic(6.2, 3.4)
    guns["ssg08"] = rifle(material="metal", stock="tan", front=4.5, barrel=-2, magazine="dark")
    guns["ssg08"][5] = box([7.1, 6.8, 13], [8.9, 9.7, 16], "tan")
    guns["ssg08"][7] = box([7.1, 4, 7.5], [8.9, 5.2, 9.5], "dark")
    guns["ssg08"] += optic(6, 4.4) + [box([9.3, 9, 10], [11.4, 9.8, 10.7], "silver")]
    guns["awp"] = rifle(material="olive", stock="olive", front=5, barrel=-3, magazine="dark")
    guns["awp"][0] = box([6.3, 7.5, 6.4], [9.7, 10.15, 12.5], "olive")
    guns["awp"][5] = box([6.4, 6.2, 12.7], [9.6, 9.8, 16], "olive")
    guns["awp"] += optic(5.4, 5.8, large=True) + [
        box([9.3, 9.2, 10.4], [11.7, 10, 11.2], "silver"),
        box([6.5, 4.2, 5.2], [7.2, 8.1, 6.1], "metal"),
        box([8.8, 4.2, 5.2], [9.5, 8.1, 6.1], "metal")]
    guns["g3sg1"] = rifle(material="olive", stock="olive", front=1.5, barrel=-2, magazine="silver")
    guns["g3sg1"] += optic(5.2, 5.2) + [box([6.3, 9.9, 12.8], [9.7, 10.8, 15.9], "dark")]
    guns["scar20"] = rifle(material="tan", stock="tan", front=1.7, barrel=-1.5, magazine="dark")
    guns["scar20"][0] = box([6.5, 8, 5.4], [9.5, 10.8, 11.4], "tan")
    guns["scar20"] += optic(5.7, 4.6) + [box([6.9, 3.7, 2.1], [7.5, 8, 3], "dark"),
                                          box([8.5, 3.7, 2.1], [9.1, 8, 3], "dark")]
    guns["nova"] = [box([7, 8.4, -1], [9, 10.1, 8], "metal"),
        box([6.6, 8, 7.2], [9.4, 10, 11.4], "dark"),
        box([6.7, 6.8, 3.4], [9.3, 8.4, 7], "wood"),
        box([7.1, 7.3, -.5], [8.9, 8.4, 7], "metal"),
        box([7, 4.5, 10.1], [9, 8.2, 12.1], "wood"),
        box([6.7, 6.2, 11.4], [9.3, 9.1, 16], "wood"), *sight(0, 10.1)]
    guns["xm1014"] = [box([7.1, 8.7, -.7], [8.9, 10.3, 7], "metal"),
        box([6.5, 8.1, 6.8], [9.5, 10.3, 11.6], "dark"),
        box([6.5, 7.1, 2.8], [9.5, 8.7, 6.8], "grip"),
        *tube_magazine(-.3, 7), box([7, 3.8, 10], [9, 8.2, 11.8], "grip"),
        box([7.4, 8.6, 11.4], [8.6, 9.6, 15.3], "silver"),
        box([6.8, 6.4, 14.6], [9.2, 10, 16], "dark"), *sight(0, 10.3), *sight(9.8, 10.3)]
    guns["mag7"] = [box([6.3, 8, 4.4], [9.7, 10.9, 12], "dark"),
        box([7.2, 8.6, 1.1], [8.8, 10, 4.4], "metal"),
        box([6.7, 4.2, 9], [9.3, 8.1, 11.6], "grip"),
        box([6.9, 1.2, 9.3], [9.1, 4.3, 11.3], "metal"),
        box([6.5, 6.7, 3.5], [9.5, 8.1, 6.4], "grip"),
        box([7.3, 6.4, 6], [8.7, 7, 9.1], "metal"), *sight(4.5, 10.9), *sight(11, 10.9)]
    guns["sawedoff"] = [box([6.9, 8.7, 2], [9.1, 10.5, 8.8], "metal"),
        box([7.2, 7.2, 2.4], [8.8, 8.7, 8.8], "metal"),
        box([6.5, 6.8, 5], [9.5, 8.7, 8.3], "wood"),
        box([6.7, 8, 8.5], [9.3, 10.3, 11.6], "metal"),
        box([6.8, 4.8, 10.5], [9.2, 8.2, 13.9], "wood"), *sight(2.4, 10.5)]
    guns["m249"] = rifle(material="olive", stock="tan", front=1.5, barrel=-2, magazine="olive")
    guns["m249"][6:8] = [box([5.5, 2.7, 7], [10.5, 8.1, 10.8], "olive")]
    guns["m249"] += [box([7.2, 10.2, 6.7], [8.8, 12.3, 7.4], "dark"),
                      box([7.2, 11.8, 6.7], [8.8, 12.5, 9.5], "dark"),
                      box([6.6, 3.5, .8], [7.2, 8.3, 1.8], "metal"),
                      box([8.8, 3.5, .8], [9.4, 8.3, 1.8], "metal")]
    guns["negev"] = rifle(material="dark", stock="olive", front=1, barrel=-3, magazine="dark")
    guns["negev"][6:8] = [box([5.3, 2, 6.2], [10.7, 8.1, 10.1], "dark")]
    guns["negev"] += [box([6.4, 10.1, 4], [9.6, 10.8, 8.7], "olive"),
                       box([7.15, 10.7, 5.8], [8.85, 12.2, 6.4], "dark"),
                       box([6.5, 2.5, .1], [7.2, 8.2, 1.1], "metal"),
                       box([8.8, 2.5, .1], [9.5, 8.2, 1.1], "metal")]
    guns["knife"] = [box([7.4, 8, 1], [8.6, 9, 9.5], "silver"),
        box([7.7, 8.25, 0], [8.3, 8.75, 1.5], "silver"),
        box([6.3, 7.5, 9.4], [9.7, 9.5, 10.2], "dark"),
        box([7, 7.8, 10], [9, 9.2, 15], "grip"),
        box([6.9, 7.7, 14.8], [9.1, 9.3, 15.5], "metal")]
    assert set(guns) == set(GUN_MODELS) | {"knife"}
    return guns

def build_visuals():
    for index, (name, base) in enumerate(MATERIALS.items()):
        rng = random.Random(172 + index)
        pixels = []
        for y in range(16):
            for x in range(16):
                change = rng.randint(-9, 9)
                if name == "wood":
                    change += int(7 * math.sin(x * 1.4 + y / 5))
                elif name == "grip" and (x + y) % 3 == 0:
                    change -= 12
                pixels.append(tuple(max(0, min(255, channel + change)) for channel in base) + (255,))
        write_png(f"assets/pvp/textures/item/mygo/{name}.png", 16, 16, pixels)

    for name, elements in models().items():
        data = {
            "parent": "minecraft:block/block", "ambientocclusion": False,
            "credit": "Original MyGO cuboid model by qifan_maker; MIT; generated reproducibly",
            "gui_light": "front", "textures": {**{name: "pvp:item/mygo/" + name for name in MATERIALS},
                                                       "particle": "pvp:item/mygo/silver"},
            "elements": elements,
            "display": {
                "firstperson_righthand": {"rotation": [0, 0, 0], "translation": [1, 3, -3], "scale": [1, 1, 1]},
                "firstperson_lefthand": {"rotation": [0, 0, 0], "translation": [1, 3, -3], "scale": [1, 1, 1]},
                "thirdperson_righthand": {"rotation": [90, 0, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
                "thirdperson_lefthand": {"rotation": [90, 0, 0], "translation": [0, 2.5, 0], "scale": [0.9, 0.9, 0.9]},
                "gui": {"rotation": [25, -65, 0], "translation": [0, 0, 0], "scale": [1.05, 1.05, 1.05]},
                "ground": {"rotation": [0, 0, 90], "translation": [0, 2, 0], "scale": [0.65, 0.65, 0.65]},
                "fixed": {"rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            }}
        write_json(f"assets/pvp/models/item/mygo/{name}.json", data)
        if name in GUN_MODELS:
            for suffix, kick, tilt in [("fire", .6, -3), ("reload", -1.8, -24)]:
                display = json.loads(json.dumps(data["display"]))
                for hand in ["firstperson_righthand", "firstperson_lefthand"]:
                    display[hand]["translation"][1] += kick
                    display[hand]["rotation"][2] += tilt
                write_json(f"assets/pvp/models/item/mygo/{name}_{suffix}.json", {
                    "parent": f"pvp:item/mygo/{name}", "display": display})

    # Predicates use >= semantics. Restore vanilla at each custom value + 1 so
    # unrelated tools with larger CustomModelData do not inherit a gun model.
    def override(item, entries):
        data = {"parent": "minecraft:item/handheld", "textures": {"layer0": "minecraft:item/" + item}, "overrides": []}
        for value, name in entries:
            data["overrides"].extend([
                {"predicate": {"custom_model_data": value}, "model": "pvp:item/mygo/" + name},
                {"predicate": {"custom_model_data": value + 1}, "model": "pvp:item/mygo/vanilla_" + item},
            ])
        write_json(f"assets/minecraft/models/item/{item}.json", data)
        write_json(f"assets/pvp/models/item/mygo/vanilla_{item}.json", {
            "parent": "minecraft:item/handheld", "textures": {"layer0": "minecraft:item/" + item}})

    entries = []
    for name, value in GUN_MODELS.items():
        entries.extend([(value, name), (value + 100, name + "_fire"), (value + 200, name + "_reload")])
    override("iron_pickaxe", sorted(entries))
    override("iron_sword", [(71006, "knife")])
    utility_visuals = [("he", "olive"), ("flash", "silver"), ("smoke", "dark"),
                       ("molotov", "red"), ("incendiary", "red"), ("decoy", "tan")]
    for i, (name, material) in enumerate(utility_visuals):
        base = json.loads((STAGING / "assets/pvp/models/item/mygo/knife.json").read_text())
        base["textures"]["particle"] = "pvp:item/mygo/" + material
        base["elements"] = [box([5, 3, 5], [11, 11, 11], material),
                            box([6, 11, 6], [10, 13, 10], "dark"),
                            box([9, 6, 8], [11, 14, 9], "silver"),
                            box([5.5, 7, 4.9], [10.5, 9, 5], "red")]
        write_json(f"assets/pvp/models/item/mygo/{name}.json", base)
    utility_entries = [(72001, "he"), (72002, "flash"), (72003, "smoke"),
                       (72004, "fog"), (72005, "molotov"), (72006, "incendiary"), (72007, "decoy")]
    # Keep the historical slime-ball fixture and map the actual runtime fire charges too.
    override("slime_ball", utility_entries)
    override("fire_charge", [(72005, "molotov"), (72006, "incendiary")])
    for name, elements in {
        "c4": [box([3, 3, 5], [13, 10, 11], "olive"), box([4, 10, 6], [12, 12, 10], "dark"),
               box([5, 10.1, 5.8], [10, 11.7, 6], "glass"), box([3, 6, 4.7], [13, 6.7, 5], "red"),
               box([10, 4, 4.7], [10.7, 10, 5], "silver")],
        "kit": [box([4, 3, 7], [6, 11, 9], "tan"), box([10, 3, 7], [12, 11, 9], "tan"),
                box([5, 10, 7], [11, 12, 9], "silver"), box([5, 11, 7], [7, 15, 9], "silver"),
                box([9, 11, 7], [11, 15, 9], "silver")],
    }.items():
        data = json.loads((STAGING / "assets/pvp/models/item/mygo/knife.json").read_text())
        data["elements"] = elements
        data["textures"]["particle"] = "pvp:item/mygo/" + ("olive" if name == "c4" else "silver")
        write_json(f"assets/pvp/models/item/mygo/{name}.json", data)
    override("redstone", [(73001, "c4")])
    override("shears", [(73002, "kit")])
    fog_pixels = []
    for y in range(64):
        for x in range(64):
            radius = math.hypot(x - 31.5, y - 31.5)
            fog_pixels.append((170, 176, 180, 255 if radius < 29 else 0))
    write_png("assets/pvp/textures/item/mygo/fog.png", 64, 64, fog_pixels)
    write_json("assets/pvp/models/item/mygo/fog.json", {"textures": {"fog": "pvp:item/mygo/fog", "particle": "pvp:item/mygo/fog"},
        "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "faces": {
            "north": {"texture": "#fog", "uv": [0, 0, 16, 16]},
            "south": {"texture": "#fog", "uv": [0, 0, 16, 16]}}}]})
    write_json("assets/minecraft/models/item/carved_pumpkin.json", {
        "parent": "minecraft:block/carved_pumpkin", "overrides": [
            {"predicate": {"custom_model_data": 71010}, "model": "pvp:item/mygo/scope"},
            {"predicate": {"custom_model_data": 71011}, "model": "minecraft:block/carved_pumpkin"}]})
    write_json("assets/pvp/models/item/mygo/scope.json", {
        "parent": "minecraft:item/generated", "textures": {"layer0": "pvp:item/mygo/scope_icon", "particle": "pvp:item/mygo/scope_icon"},
        "display": {"head": {"scale": [0, 0, 0]}}})
    size = 512
    scope = []
    icon = []
    for y in range(size):
        for x in range(size):
            dx, dy = x + 0.5 - size / 2, y + 0.5 - size / 2
            r = math.hypot(dx, dy)
            if r > 238:
                pixel = (4, 9, 12, 232)
            elif r > 230:
                pixel = (61, 81, 86, 255)
            elif r > 226:
                pixel = (7, 19, 22, 255)
            elif (abs(dx) < 1.2 and 9 < abs(dy) < 180) or (abs(dy) < 1.2 and 9 < abs(dx) < 180):
                pixel = (12, 28, 22, 205)
            elif ((35 < abs(dx) < 39 or 70 < abs(dx) < 74 or 105 < abs(dx) < 109)
                  and abs(dy) < 6) or ((35 < abs(dy) < 39 or 70 < abs(dy) < 74 or 105 < abs(dy) < 109)
                                        and abs(dx) < 6):
                pixel = (90, 150, 119, 220)
            else:
                pixel = (0, 0, 0, 0)
            scope.append(pixel)
    write_png("assets/minecraft/textures/misc/pumpkinblur.png", size, size, scope)
    for y in range(32):
        for x in range(32):
            r = math.hypot(x - 15.5, y - 15.5)
            icon.append((52, 75, 82, 255) if 10 < r < 14 else
                        (21, 142, 163, 220) if r <= 10 else (0, 0, 0, 0))
    write_png("assets/pvp/textures/item/mygo/scope_icon.png", 32, 32, icon)
    write_png("pack.png", 32, 32, icon)


SOUNDS = {
    "glock": (.20, 195), "usp_s": (.13, 310), "p2000": (.21, 205),
    "p250": (.23, 180), "fiveseven": (.19, 240), "tec9": (.20, 220),
    "cz75": (.18, 230), "duals": (.21, 200), "deagle": (.37, 95), "revolver": (.40, 83),
    "mac10": (.16, 290), "mp9": (.14, 320), "mp7": (.15, 305),
    "mp5sd": (.12, 370), "ump45": (.22, 180), "p90": (.15, 330), "bizon": (.17, 270),
    "galil": (.27, 140), "famas": (.24, 170), "ak47": (.31, 112),
    "m4a4": (.26, 155), "m4a1s": (.16, 280), "aug": (.27, 150), "sg553": (.30, 125),
    "ssg08": (.48, 100), "awp": (.65, 68), "g3sg1": (.37, 115), "scar20": (.36, 130),
    "nova": (.43, 60), "xm1014": (.40, 73), "mag7": (.39, 85), "sawedoff": (.46, 55),
    "m249": (.32, 108), "negev": (.30, 105), "reload": (0.70, 0),
    "empty": (0.11, 0), "plant": (0.25, 960), "defuse": (0.45, 680),
    "explode": (1.6, 42),
    "hit": (.08, 1200), "headshot": (.12, 1500),
    "throw": (.15, 700), "flash": (.55, 1750), "smoke": (1.0, 120),
}


def normalize_ogg_serial(path: Path, serial: int):
    """ffmpeg randomizes Ogg serials; fix them and their Ogg CRC for stable ZIPs."""
    data = bytearray(path.read_bytes())
    start = 0
    while start < len(data):
        if data[start:start + 4] != b"OggS":
            raise ValueError("Invalid Ogg page")
        segments = data[start + 26]
        end = start + 27 + segments + sum(data[start + 27:start + 27 + segments])
        data[start + 14:start + 18] = struct.pack("<I", serial)
        data[start + 22:start + 26] = b"\0" * 4
        crc = 0
        for byte in data[start:end]:
            crc ^= byte << 24
            for _ in range(8):
                crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
        data[start + 22:start + 26] = struct.pack("<I", crc)
        start = end
    path.write_bytes(data)


def movement_and_utility_samples(name: str, duration: float, seed: int, rate: int = 22050):
    """Original boot/gear/material Foley, never sampled from another game's audio.

    Layers use short heel/toe transients, filtered grit and damped material
    resonances. Distinct deterministic variants avoid repeating one identical
    footstep, and mono preserves Minecraft's positional panning/attenuation.
    """
    rng = random.Random(seed)
    slow = smooth = 0.0
    variant = rng.uniform(.93, 1.07)
    result = []
    for i in range(round(duration * rate)):
        t = i / rate
        noise = rng.uniform(-1, 1)
        slow = slow * .88 + noise * .12
        smooth = smooth * .35 + noise * .65
        grit = smooth - slow
        attack = 1 - math.exp(-t * 2800)
        if name.startswith(("step.", "land.")):
            kind, surface = name.split(".")
            landing = kind == "land"
            impact = math.exp(-t * (31 if landing else 47))
            toe_time = .046 if surface == "water" else .027
            toe = math.exp(-(t - toe_time) * 65) if t >= toe_time else 0
            frequency = {"stone": 102, "metal": 122, "wood": 155, "dirt": 82, "water": 78}[surface] * variant
            body = math.sin(2 * math.pi * (frequency * t - 24 * t * t))
            value = (.44 * body + .36 * grit) * impact + .21 * grit * toe
            value += slow * math.exp(-t * 17) * .14  # short cloth/sole scuff
            if surface == "metal":
                value += sum(.10 * math.sin(2 * math.pi * f * variant * t)
                             * math.exp(-t * d) for f, d in [(710, 16), (1190, 22), (1780, 31)])
            elif surface == "wood":
                value += sum(.13 * math.sin(2 * math.pi * f * variant * t)
                             * math.exp(-t * d) for f, d in [(230, 27), (470, 33), (820, 42)])
            elif surface == "dirt":
                value = .75 * value + .55 * slow * math.exp(-t * 23)
                value += .19 * grit * math.exp(-((t - .055) / .045) ** 2)
            elif surface == "water":
                value = .4 * value + .65 * slow * math.exp(-t * 15)
                value += .22 * grit * math.exp(-((t - .045) / .035) ** 2)
                value += .08 * math.sin(2 * math.pi * (900 * t + 600 * t * t)) * math.exp(-t * 20)
            if landing:
                value = value * 1.2 + .24 * math.sin(2 * math.pi * 58 * t) * math.exp(-t * 18)
                value += .16 * grit * math.exp(-((t - .10) / .045) ** 2)
        elif name == "grenade.pin":
            value = 0.0
            for start, amount in [(0, .55), (.065, .35), (.14, .2)]:
                if t >= start:
                    age = t - start
                    value += amount * (grit + .45 * math.sin(2 * math.pi * 1560 * age)) * math.exp(-age * 95)
            value += .10 * smooth * math.exp(-((t - .05) / .035) ** 2)
        elif name.startswith("grenade.bounce."):
            material = name.rsplit(".", 1)[1]
            value = (.45 * grit + .40 * math.sin(2 * math.pi * 245 * variant * t)) * math.exp(-t * 62)
            if material == "metal":
                value += .19 * math.sin(2 * math.pi * 1420 * variant * t) * math.exp(-t * 20)
                value += .10 * math.sin(2 * math.pi * 2350 * variant * t) * math.exp(-t * 30)
            elif material == "soft":
                value = .42 * value + .60 * slow * math.exp(-t * 39)
        elif name == "throw":
            value = .55 * grit * math.exp(-((t - .055) / .035) ** 2)
            value += .20 * slow * math.exp(-t * 20)
        elif name == "explode":
            value = .75 * slow * math.exp(-t * 3.8) + .33 * grit * math.exp(-t * 24)
            value += .5 * math.sin(2 * math.pi * (52 * t - 7 * t * t)) * math.exp(-t * 6)
            value += .11 * smooth * math.exp(-((t - .105) / .07) ** 2)
        elif name == "flash":
            value = .65 * grit * math.exp(-t * 45) + .24 * slow * math.exp(-t * 15)
            value += .14 * math.sin(2 * math.pi * 3100 * t) * math.exp(-t * 8)
        elif name in ("smoke", "smoke.end"):
            ending = name.endswith(".end")
            envelope = math.sin(min(1, t / .06) * math.pi / 2) * (1 - t / duration) ** (1.1 if ending else .6)
            value = (.38 * grit + .35 * slow) * envelope
            if not ending:
                value += .24 * grit * math.exp(-t * 50)
        elif name in ("jump", "equip"):
            value = .27 * grit * math.exp(-((t - .04) / .035) ** 2)
            value += .18 * slow * math.exp(-t * 17)
            if name == "equip":
                for start in [.018, .09, .17]:
                    if t >= start:
                        age = t - start
                        value += .19 * (grit + .45 * math.sin(2 * math.pi * 1180 * age)) * math.exp(-age * 85)
        else:
            raise ValueError("Unknown Foley recipe: " + name)
        fade = min(1, max(0, (duration - t) / .015))
        result.append(struct.pack("<h", int(max(-.98, min(.98, value * attack * fade)) * 30000)))
    return b"".join(result)


def encode_original_audio(wav_path: Path, output: Path, samples: bytes, serial: int, rate: int = 22050):
    with wave.open(str(wav_path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(samples)
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav_path),
                    "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "3", str(output)], check=True)
    normalize_ogg_serial(output, serial)


def build_sounds():
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is required to encode original OGG sounds")
    sound_json = {}
    with tempfile.TemporaryDirectory(prefix="mygo-sound-") as temp:
        for index, (name, (duration, frequency)) in enumerate(SOUNDS.items()):
            rate = 22050
            rng = random.Random(301 + index)
            samples = []
            previous = 0.0
            for i in range(round(duration * rate)):
                t = i / rate
                noise = rng.uniform(-1, 1)
                previous = previous * 0.78 + noise * 0.22
                if name in ("plant", "defuse"):
                    pulse = (t % 0.12) < 0.065
                    value = 0.35 * math.sin(2 * math.pi * frequency * t) * pulse * math.exp(-t * 2)
                elif name in ("reload", "empty"):
                    points = [0.0] if name == "empty" else [0.0, 0.19, 0.43, 0.60]
                    envelope = sum(math.exp(-(t - start) * 85) for start in points if t >= start)
                    value = (0.6 * noise + 0.4 * math.sin(2 * math.pi * 1100 * t)) * envelope * 0.5
                else:
                    decay = 4 if name == "explode" else 19 if name == "awp" else 28
                    envelope = math.exp(-t * decay)
                    tone = math.sin(2 * math.pi * (frequency * t - 22 * t * t))
                    value = (0.5 * noise + 0.7 * previous + 0.55 * tone) * envelope
                    if name == "explode":
                        value += previous * math.exp(-t * 1.8) * 0.65
                samples.append(struct.pack("<h", int(max(-1, min(1, value)) * 30000)))
            wav_path = Path(temp) / (name + ".wav")
            audio = movement_and_utility_samples(name, duration, 1300 + index) if name in {
                "throw", "explode", "flash", "smoke"} else b"".join(samples)
            output = STAGING / f"assets/pvp/sounds/mygo/{name}.ogg"
            encode_original_audio(wav_path, output, audio, 71001 + index)
            sound_json["mygo." + name] = {"sounds": [{"name": "pvp:mygo/" + name,
                                                       "attenuation_distance": 40 if name in ("ak47", "awp", "explode") else 24}]}
        for index, (name, (variants, duration, distance)) in enumerate(ENVIRONMENT_EVENTS.items()):
            choices = []
            for variant in range(variants):
                filename = name.replace(".", "/") + "_" + str(variant + 1)
                audio = movement_and_utility_samples(name, duration, 1900 + index * 8 + variant)
                encode_original_audio(Path(temp) / "foley.wav", STAGING / f"assets/pvp/sounds/mygo/{filename}.ogg",
                                      audio, 74001 + index * 8 + variant)
                choices.append({"name": "pvp:mygo/" + filename, "attenuation_distance": distance})
            sound_json["mygo." + name] = {"sounds": choices}
    write_json("assets/pvp/sounds.json", sound_json)
    muted = {"block." + material + ".step" for material in VANILLA_STEP_MATERIALS}
    muted.update("entity." + kind + "." + action for kind in ("player", "generic")
                 for action in ("swim", "splash", "small_fall", "big_fall"))
    muted.add("entity.player.splash.high_speed")
    write_json("assets/minecraft/sounds.json", {name: {"replace": True, "sounds": []} for name in sorted(muted)})


def authorized_audio_overrides(directory: Path | None, license_file: Path | None) -> list[str]:
    """Optional local input for audio the operator already has rights to distribute.

    Paths are relative to assets/pvp/sounds/mygo (e.g. glock.ogg or
    step/stone_1.ogg); no asset is downloaded or extracted from another game.
    A supplied license/attribution file is included verbatim with the pack.
    """
    if directory is None:
        return []
    if not directory.is_dir() or license_file is None or not license_file.is_file():
        raise SystemExit("--authorized-audio-dir requires a directory and --audio-license-file containing redistribution terms/credits")
    sounds = json.loads((STAGING / "assets/pvp/sounds.json").read_text())
    imported = []
    for event in sounds.values():
        for sound in event["sounds"]:
            relative = sound["name"].removeprefix("pvp:mygo/") + ".ogg"
            source = directory / relative
            if not source.is_file():
                continue
            info = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,channels,sample_rate", "-of", "json", str(source)]))
            stream = info.get("streams", [{}])[0]
            if stream.get("codec_name") != "vorbis" or stream.get("channels") != 1 or int(stream.get("sample_rate", 0)) not in (22050, 44100, 48000):
                raise SystemExit("Positional imported audio must be mono Ogg Vorbis: " + str(source))
            shutil.copyfile(source, STAGING / "assets/pvp/sounds/mygo" / relative)
            imported.append(relative)
    if not imported:
        raise SystemExit("No recognized .ogg files found in --authorized-audio-dir")
    shutil.copyfile(license_file, STAGING / "MYGO_IMPORTED_AUDIO_LICENSE.txt")
    return sorted(imported)


def preview():
    """Optional developer preview: project the actual model cuboids, plus the scope."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return
    rows = math.ceil(len(GUN_MODELS) / 4)
    canvas = Image.new("RGB", (1440, rows * 230 + 250), "#e8edf0")
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 18), "MyGO CS:GO Arsenal | 34 original vanilla-client models | MIT | Minecraft 1.21.1", fill="#17272e")
    for n, (name, elements) in enumerate((name, model) for name, model in models().items() if name in GUN_MODELS):
        ox, oy = 175 + (n % 4) * 360, 165 + (n // 4) * 230
        draw.rounded_rectangle((ox - 162, oy - 112, ox + 175, oy + 103), 12, fill="#25343b")
        draw.text((ox - 140, oy - 94), name.upper().replace("_", "-"), fill="white")
        def proj(x, y, z):
            return (ox + (8 - z) * 11 + (x - 8) * 5, oy + (8 - y) * 11 + (x - 8) * 3)
        for element in sorted(elements, key=lambda el: el["from"][0] + el["from"][2] * 0.01):
            (x0, y0, z0), (x1, y1, z1) = element["from"], element["to"]
            name_key = element["faces"]["up"]["texture"][1:]
            rgb = MATERIALS[name_key]
            for points, factor in [([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], .65),
                                   ([(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)], 1.3),
                                   ([(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)], 1)]:
                color = tuple(min(255, int(c * factor)) for c in rgb)
                draw.polygon([proj(*point) for point in points], fill=color, outline="#111b20")
    scope = Image.open(STAGING / "assets/minecraft/textures/misc/pumpkinblur.png").resize((180, 180))
    canvas.paste(scope, (1225, rows * 230 + 50), scope)
    draw.text((24, rows * 230 + 90), "All silhouettes and synthetic sounds are generated from repository source.", fill="#17272e")
    draw.text((24, rows * 230 + 120), "This is a cuboid inspection preview, not an in-game renderer screenshot.", fill="#17272e")
    target = ROOT / "build/mygo-resource-pack-preview.png"
    canvas.save(target)
    print(f"Preview: {target}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--authorized-audio-dir", type=Path,
                        help="Optional already-licensed mono OGGs, relative to sounds/mygo; never downloads game assets")
    parser.add_argument("--audio-license-file", type=Path,
                        help="Redistribution license and attribution text for the optional audio overrides")
    args = parser.parse_args()
    if args.audio_license_file and not args.authorized_audio_dir:
        parser.error("--audio-license-file requires --authorized-audio-dir")
    if STAGING.exists():
        shutil.rmtree(STAGING)
    STAGING.mkdir(parents=True)
    write_json("pack.mcmeta", {"pack": {"pack_format": 34,
               "description": "MyGO CS-style Arsenal | 34 guns, utilities, surface footsteps and scope | 1.21.1"}})
    # Reason: the client renders the selected-block box through the lines core
    # shader, independently of block textures/models. MyGO's client-only input
    # targets must stay invisible, so discard this resource-pack line pass.
    write_text("assets/minecraft/shaders/core/rendertype_lines.fsh", "#version 150\n\nvoid main() {\n    discard;\n}\n")
    build_visuals()
    build_sounds()
    imported_audio = authorized_audio_overrides(args.authorized_audio_dir, args.audio_license_file)
    shutil.copyfile(ROOT / "LICENSE", STAGING / "MYGO_ASSETS_LICENSE.txt")
    write_json("MYGO_ASSETS_source.json", {
        "license": "MIT", "author": "qifan_maker", "kind": "original procedural assets with separately licensed audio overrides" if imported_audio else "original procedural assets",
        "generator": "tools/build_mygo_resource_pack.py", "valve_assets_included": None if imported_audio else False,
        "weapons": [{"name": name, "model": model, "sound": "mygo." + name} for name, model in GUN_MODELS.items()],
        "materials": list(MATERIALS), "poses": ["base", "fire", "reload"],
        "audio": {"generated_license": "MIT", "style": "original synthesized boot, material and utility Foley",
                  "events": ["mygo." + name for name in [*SOUNDS, *ENVIRONMENT_EVENTS]],
                  "surface_groups": list(SURFACES), "step_variants_per_surface": 4, "landing_variants_per_surface": 2,
                  "operator_supplied_files": imported_audio,
                  "operator_supplied_license": "MYGO_IMPORTED_AUDIO_LICENSE.txt" if imported_audio else None,
                  "valve_audio_policy": "https://store.steampowered.com/video_policy",
                  "rights_note": "No original Valve sound assets are downloaded or redistributed by the default generator. Optional overrides require the operator's own redistribution rights."},
    })
    OUTPUT.mkdir(parents=True, exist_ok=True)
    destination = OUTPUT / "mygo-pack.zip"
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for source in sorted(STAGING.rglob("*")):
            if source.is_file():
                info = zipfile.ZipInfo(str(source.relative_to(STAGING)), date_time=(2024, 8, 8, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, source.read_bytes())
    sha1 = hashlib.sha1(destination.read_bytes()).hexdigest()
    (OUTPUT / "mygo-pack.sha1").write_text(sha1 + "\n")
    delivery = ROOT / "dist/mygo-csgo-utilities-sound-resource-pack.zip"
    delivery.parent.mkdir(exist_ok=True)
    shutil.copyfile(destination, delivery)
    print(f"Created {destination} ({destination.stat().st_size:,} bytes), SHA-1 {sha1}")
    print(f"Manual-upload copy: {delivery}")
    preview()


if __name__ == "__main__":
    main()
