#!/usr/bin/env python3
"""Build MyGO 1.21.1 assets so vanilla clients can display tool-based guns.

Weapon Pack 1.1 models by Pelmeshka_ are adapted under MIT; see the pinned source
and license in tools/assets/mygo-weapon-pack. Knife, utilities, scope and synthetic
sounds are authored here. Requires Python 3 and ffmpeg.
Run from any directory. The committed ZIP is the runtime input, so server builds
do not require Python or ffmpeg. ZIP timestamps are fixed for reproducible SHA-1.
"""
from __future__ import annotations

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
UPSTREAM = ROOT / "tools/assets/mygo-weapon-pack"
GUN_MODELS = {
    "p9": (71001, "p18c"), "mp5": (71002, "ump"), "ak": (71003, "akm"),
    "m4": (71004, "m416"), "aw": (71005, "awm"), "m16": (71011, "m16a4"),
    "scar": (71012, "scar-l"), "mini14": (71013, "mini14"), "mk14": (71014, "mk14"),
    "p90": (71015, "p90"), "uzi": (71016, "uzi"), "vector": (71017, "vector"),
    "tommy": (71018, "tommy_gun"), "m24": (71019, "m24"), "s686": (71020, "s686"),
}


def imported_models():
    """Convert CIT selectors and use item/ so vanilla 1.21.1 stitches the PNGs.

    A PNG outside the block/item atlas directories still appears purple/black
    even when it exists in the ZIP. Keep every item material in the vanilla
    atlas input instead of relying on OptiFine to discover custom folders.
    """
    for name, (_, source) in GUN_MODELS.items():
        data = json.loads((UPSTREAM / (source + ".json")).read_text())
        data["textures"] = {key: "pvp:item/mygo/" + source for key in data["textures"]}
        data["textures"].setdefault("particle", "pvp:item/mygo/" + source)
        data["gui_light"] = "front"
        data["credit"] = "Weapon Pack 1.1 by Pelmeshka_ (MIT), adapted for MyGO; made with Blockbench"
        write_json(f"assets/pvp/models/item/mygo/{name}.json", data)
        texture = STAGING / f"assets/pvp/textures/item/mygo/{source}.png"
        texture.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(UPSTREAM / (source + ".png"), texture)
        for suffix, kick, tilt in [("fire", 0.6, -3), ("reload", -1.8, -24)]:
            display = json.loads(json.dumps(data.get("display", {})))
            for hand in ["firstperson_righthand", "firstperson_lefthand"]:
                pose = display.setdefault(hand, {})
                position = pose.setdefault("translation", [0, 0, 0])
                position[1] += kick
                rotation = pose.setdefault("rotation", [0, 0, 0])
                rotation[2] += tilt
            write_json(f"assets/pvp/models/item/mygo/{name}_{suffix}.json", {
                "parent": f"pvp:item/mygo/{name}", "display": display})
    for file in ["LICENSE.txt", "source.json"]:
        shutil.copyfile(UPSTREAM / file, STAGING / ("WEAPON_PACK_" + file))


def write_json(path: str, data: dict) -> None:
    target = STAGING / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")


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


def models():
    """Distinct slide, stock, barrel, magazine and optic geometry, all within 0..16."""
    return {
        "p9": [
            box([6.5, 8, 3], [9.5, 10, 12], "silver"),
            box([7, 8.4, 1.8], [9, 9.6, 3], "dark"),
            box([6.7, 3, 9], [9.3, 8, 11.8], "grip"),
            box([7, 7, 5.5], [9, 8, 10], "dark"),
            box([7.4, 5.3, 6.2], [8.6, 5.8, 9.2], "metal"),
            box([7.4, 5.8, 6.2], [8.6, 7.4, 6.7], "metal"),
            box([7.7, 9.9, 3.8], [8.3, 10.5, 4.3], "red"),
            box([6.8, 9.9, 11], [9.2, 10.5, 11.5], "dark"),
        ],
        "mp5": [
            box([6.6, 8, 4], [9.4, 10.2, 12.5], "dark"),
            box([7.3, 8.6, 0.8], [8.7, 9.6, 4], "metal"),
            box([6.4, 7.7, 3.5], [9.6, 9.8, 6.4], "grip"),
            box([7, 2.5, 6.8], [9, 8, 8.7], "metal"),
            box([6.8, 3.7, 10], [9.2, 8.1, 12], "grip"),
            box([7.2, 8.5, 12.5], [8.8, 9.8, 15], "silver"),
            box([6.6, 7.2, 14.8], [9.4, 11, 16], "dark"),
            box([7, 10.2, 4], [9, 11, 4.7], "metal"),
            box([7, 10.2, 11], [9, 11, 11.7], "metal"),
        ],
        "ak": [
            box([6.7, 8, 6], [9.3, 10.3, 11.5], "metal"),
            box([6.6, 7.5, 3.3], [9.4, 9.8, 6.5], "wood"),
            box([7.3, 8.8, 0], [8.7, 9.7, 3.5], "dark"),
            box([7.55, 10, 1.5], [8.45, 11.5, 2.2], "dark"),
            box([7.4, 9.7, 2.5], [8.6, 10.5, 6.3], "metal"),
            box([6.8, 4.4, 7.3], [9.2, 8, 9.7], "dark"),
            box([6.8, 2, 6.4], [9.2, 4.8, 9.2], "dark"),
            box([6.8, 4.1, 10.3], [9.2, 8, 12.2], "wood"),
            box([7, 8.5, 11.5], [9, 9.7, 13], "metal"),
            box([6.7, 6.8, 12.8], [9.3, 10, 16], "wood"),
            box([9.2, 8.8, 9], [10.2, 9.5, 9.8], "silver"),
        ],
        "m4": [
            box([6.6, 8, 7], [9.4, 10.3, 11.8], "dark"),
            box([6.4, 8, 3.5], [9.6, 10, 7.1], "tan"),
            box([7.35, 8.65, 0], [8.65, 9.55, 3.7], "metal"),
            box([7, 5, 7.4], [9, 8, 9.4], "silver"),
            box([7, 2.4, 6.9], [9, 5.4, 9], "silver"),
            box([6.8, 4.1, 10], [9.2, 8, 12], "tan"),
            box([7.2, 8.5, 11.5], [8.8, 9.7, 14.8], "metal"),
            box([6.5, 6.8, 13.7], [9.5, 10.1, 16], "tan"),
            box([6.8, 10.3, 7.8], [9.2, 12.2, 9.8], "dark"),
            box([7.3, 10.7, 7.75], [8.7, 11.8, 7.85], "glass"),
            box([7.2, 10, 3], [8.8, 11.6, 3.6], "dark"),
        ],
        "aw": [
            box([6.4, 7.5, 7], [9.6, 9.3, 13.5], "olive"),
            box([7, 9, 5], [9, 10.3, 12], "metal"),
            box([7.35, 9.3, 0], [8.65, 10.1, 5.5], "dark"),
            box([6.5, 6.5, 12], [9.5, 9.4, 16], "olive"),
            box([6.8, 4.8, 10.4], [9.2, 7.8, 12.3], "grip"),
            box([7, 4.5, 8], [9, 7.8, 10], "dark"),
            box([7.3, 10.1, 7.5], [8.7, 11.5, 8.2], "metal"),
            box([7.3, 10.1, 10.5], [8.7, 11.5, 11.2], "metal"),
            box([6.6, 11.2, 6], [9.4, 13.3, 11.8], "dark"),
            box([6.3, 10.9, 5.2], [9.7, 13.6, 6.5], "metal"),
            box([6.7, 11.3, 5.1], [9.3, 13.2, 5.2], "glass"),
            box([9, 9.1, 10.3], [11.3, 10.1, 11.1], "silver"),
        ],
        "knife": [
            box([7.4, 8, 1], [8.6, 9, 9.5], "silver"),
            box([7.7, 8.25, 0], [8.3, 8.75, 1.5], "silver"),
            box([6.3, 7.5, 9.4], [9.7, 9.5, 10.2], "dark"),
            box([7, 7.8, 10], [9, 9.2, 15], "grip"),
            box([6.9, 7.7, 14.8], [9.1, 9.3, 15.5], "metal"),
        ],
    }


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
        write_json(f"assets/pvp/models/item/mygo/{name}.json", {
            "parent": "minecraft:block/block", "ambientocclusion": False,
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
            }})

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

    imported_models()
    entries = []
    for name, (value, _) in GUN_MODELS.items():
        entries.extend([(value, name), (value + 100, name + "_fire"), (value + 200, name + "_reload")])
    override("iron_pickaxe", sorted(entries))
    override("iron_sword", [(71006, "knife")])
    for i, (name, material) in enumerate([("he", "olive"), ("flash", "silver"), ("smoke", "dark")]):
        base = json.loads((STAGING / "assets/pvp/models/item/mygo/knife.json").read_text())
        base["textures"]["particle"] = "pvp:item/mygo/" + material
        base["elements"] = [box([5, 3, 5], [11, 11, 11], material),
                            box([6, 11, 6], [10, 13, 10], "dark"),
                            box([9, 6, 8], [11, 14, 9], "silver"),
                            box([5.5, 7, 4.9], [10.5, 9, 5], "red")]
        write_json(f"assets/pvp/models/item/mygo/{name}.json", base)
    override("slime_ball", [(72001, "he"), (72002, "flash"), (72003, "smoke"), (72004, "fog")])
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
    "p9": (0.20, 195), "mp5": (0.17, 260), "ak": (0.28, 112),
    "m4": (0.23, 155), "aw": (0.65, 68), "reload": (0.70, 0),
    "empty": (0.11, 0), "plant": (0.25, 960), "defuse": (0.45, 680),
    "explode": (1.6, 42),
    "m16": (.23, 160), "scar": (.26, 132), "mini14": (.31, 200), "mk14": (.32, 97),
    "p90": (.15, 320), "uzi": (.13, 350), "vector": (.12, 295), "tommy": (.24, 145),
    "m24": (.55, 82), "s686": (.48, 58), "hit": (.08, 1200), "headshot": (.12, 1500),
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
                    decay = 4 if name == "explode" else 19 if name == "aw" else 28
                    envelope = math.exp(-t * decay)
                    tone = math.sin(2 * math.pi * (frequency * t - 22 * t * t))
                    value = (0.5 * noise + 0.7 * previous + 0.55 * tone) * envelope
                    if name == "explode":
                        value += previous * math.exp(-t * 1.8) * 0.65
                samples.append(struct.pack("<h", int(max(-1, min(1, value)) * 30000)))
            wav_path = Path(temp) / (name + ".wav")
            with wave.open(str(wav_path), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(rate)
                wav.writeframes(b"".join(samples))
            output = STAGING / f"assets/pvp/sounds/mygo/{name}.ogg"
            output.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav_path),
                            "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "3", str(output)], check=True)
            normalize_ogg_serial(output, 71001 + index)
            sound_json["mygo." + name] = {"sounds": [{"name": "pvp:mygo/" + name,
                                                       "attenuation_distance": 40 if name in ("ak", "aw", "explode") else 24}]}
    write_json("assets/pvp/sounds.json", sound_json)


def preview():
    """Optional developer preview: project the actual model cuboids, plus the scope."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return
    canvas = Image.new("RGB", (1200, 830), "#e8edf0")
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 18), "MyGO | Original vanilla-client 3D models | Minecraft 1.21.1", fill="#17272e")
    for n, (name, elements) in enumerate(models().items()):
        ox, oy = 180 + (n % 3) * 400, 210 + (n // 3) * 300
        draw.rounded_rectangle((ox - 155, oy - 150, ox + 190, oy + 110), 16, fill="#25343b")
        draw.text((ox - 130, oy - 128), name.upper(), fill="white")
        def proj(x, y, z):
            return (ox + (8 - z) * 14 + (x - 8) * 7, oy + (8 - y) * 13 + (x - 8) * 4)
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
    canvas.paste(scope, (950, 635), scope)
    draw.text((24, 740), "P9: slide + grip | MP5: compact stock | AK: wood + curved magazine", fill="#17272e")
    draw.text((24, 765), "M4: tan stock + optic | AW: long barrel + scope | right: transparent-centered pumpkin overlay", fill="#17272e")
    target = ROOT / "build/mygo-resource-pack-preview.png"
    canvas.save(target)
    print(f"Preview: {target}")


def main():
    if STAGING.exists():
        shutil.rmtree(STAGING)
    STAGING.mkdir(parents=True)
    write_json("pack.mcmeta", {"pack": {"pack_format": 34,
               "description": "MyGO Arsenal | vanilla atlas fix, 15 MIT gun models, utilities and scope | 1.21.1"}})
    build_visuals()
    build_sounds()
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
    print(f"Created {destination} ({destination.stat().st_size:,} bytes), SHA-1 {sha1}")


if __name__ == "__main__":
    main()
