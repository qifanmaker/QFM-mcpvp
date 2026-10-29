#!/usr/bin/env python3
"""Audit Party 2's active 405 End course without executing source commands.

Reason: boat_race_generate{,2,3} are old Overworld generators, but the current
boat_race_rules calls generate_mpm and its four 383-block-long End routes. A
faithful port needs that complete static course and its audited gate mutations,
not the unrelated PvP Boat Race arena or the obsolete six-route generator.
Pre-review: preserve all twelve source hashes, whitelist only bounded fill,
clone and literal arrow display data, and leave every other geometry untouched.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile
from extract_party_geometry import AnvilWorld, extract, supported_block_names

MINIMUM = (849852, 47, -247)
MAXIMUM = (850164, 117, 135)
FUNCTIONS = ['boat_race', 'boat_race_generate', 'boat_race_generate2', 'boat_race_generate3',
             'boat_race_generate4', 'boat_race_generate_mpm', 'boat_race_item_tick',
             'boat_race_new_boat', 'boat_race_rules', 'boat_race_start', 'boat_race_tick', 'end_boat_race']


def selected(line: str, path: int) -> bool:
    condition = re.search(r'(if|unless) score br_path variables matches (\d+)(?:\.\.(\d+))?', line)
    if condition is None:
        return True
    low, high = int(condition[2]), int(condition[3] or condition[2])
    matches = low <= path <= high
    return matches if condition[1] == 'if' else not matches


def operations(text: str, path: int):
    output = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#') or not selected(line, path):
            continue
        match = re.search(r'\b(fill|clone) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (.*)', line)
        if match:
            operation = {'op': match[1], 'from': list(map(int, match.group(2, 3, 4))),
                         'to': list(map(int, match.group(5, 6, 7)))}
            words = match[8].split()
            if operation['op'] == 'clone':
                operation['destination'] = list(map(int, words[:3]))
                assert len(words) == 3, line
            else:
                operation['state'] = words[0]
                if len(words) > 1:
                    if words[1] == 'replace':
                        operation['replace'] = words[2]
                    else:
                        assert words[1:] == ['hollow'], line
                        operation['hollow'] = True
            for vector in [operation['from'], operation['to'], operation.get('destination', operation['from'])]:
                assert all(MINIMUM[i] <= vector[i] <= MAXIMUM[i] for i in range(3)), line
            output.append(operation)
        elif 'summon minecraft:text_display' in line:
            match = re.search(r'summon minecraft:text_display ([\d.]+) ([\d.]+) (-?[\d.]+)', line)
            assert 'fallback:"←"' in line, line
            output.append({'op': 'arrow', 'position': [float(token) + (0.5 if axis != 1 and '.' not in token else 0) for axis, token in enumerate(match.group(1, 2, 3))],
                           'yaw': 90 if 'Rotation:[90.0f' in line else 0,
                           'scale': 16, 'background': 16711680, 'text': '←'})
    return output


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--world', type=Path, default=Path('/Users/qifan_maker/Documents/project/pvp/run/external-maps/templates/minecraft-party-2'))
    p.add_argument('--output', type=Path, default=Path('src/main/resources/pvp/party'))
    args = p.parse_args()
    sources = next(Path('/Users/qifan_maker/Documents/project/pvp/.gradle/loom-cache/minecraftMaven/net/minecraft').glob('**/*-sources.jar'))
    with zipfile.ZipFile(args.world / 'datapacks/data.zip') as archive:
        raw = {name: archive.read('data/mcp/function/' + name + '.mcfunction') for name in FUNCTIONS}
        live = raw['boat_race_generate_mpm'].decode('utf-8')
        assert 'random value 11..14' in live
        assert 'function mcp:boat_race_generate_mpm' in raw['boat_race_rules'].decode()
        # The old coordinates in boat_race_start/end are outside the active End
        # arena; select only the explicitly dimension-scoped live start gate.
        start = '\n'.join(line for line in raw['boat_race_start'].decode().splitlines()
                          if 'in the_end run fill ' in line)
        data = {'formatVersion': 1, 'sourceId': 405, 'anchor': [850008, 64, -56],
                'bounds': {'min': MINIMUM, 'max': MAXIMUM},
                'sourceFunctionSha256': {name: hashlib.sha256(content).hexdigest() for name, content in raw.items()},
                'sourceFunctionBytes': {name: len(content) for name, content in raw.items()},
                'activeFunctions': [name for name in FUNCTIONS if name not in ['boat_race_generate', 'boat_race_generate2', 'boat_race_generate3']],
                'obsoleteGenerators': ['boat_race_generate', 'boat_race_generate2', 'boat_race_generate3'],
                'routes': {str(path): operations(live, path) for path in range(11, 15)},
                'startOperations': operations(start, 11),
                'rules': {'ticks': 3600, 'laps': 2, 'remainingThreshold': 1,
                          'boatResetBelowY': 44, 'packedIceMarkerOffsetY': -0.5,
                          'waypointOffsetY': 10, 'lapCoralOffsetY': -1.5,
                          'recallCooldownTicks': 200, 'leashDistance': 15},
                'compatibility': ['1.21.1 BoatEntity.Type.BAMBOO is the source bamboo_raft.',
                                  'Player sits first and invisible rabbit second because 1.21.1 steering uses the first passenger.',
                                  'Source player 10 reset token disagrees (acacia_stairs/acacia_boat); scoped UUID ownership preserves the intended recall for all ten slots.',
                                  'Native invulnerable raft ignores legacy 1.21.1 boat fall destruction so bubble-column launches cannot break it.',
                                  'No source commands, original entity NBT or command block NBT are executed.']}
        geometry = extract(AnvilWorld(args.world / 'DIM1'), 'raft_racers',
                           {'min': MINIMUM, 'max': MAXIMUM, 'functions': FUNCTIONS,
                            'notes': ['Complete active End course footprint, 313x71x383; no track shrinking or folding.',
                                      'Four active routes br_path 11..14 are applied by audited fill/clone data.',
                                      'Includes original ice, bubble columns, gates, three light marker levels and 60 finish coral blocks.']},
                           archive, supported_block_names(sources))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'geometry').mkdir(exist_ok=True)
    for target, content in [(args.output / 'raft_racers_rules.json', data),
                            (args.output / 'geometry/raft_racers.json.gz', geometry)]:
        encoded = json.dumps(content, ensure_ascii=False, separators=(',', ':')).encode()
        target.write_bytes(gzip.compress(encoded, compresslevel=9, mtime=0) if target.suffix == '.gz' else encoded + b'\n')
    print('raft_racers:', geometry['size'], len(geometry['blocks']), 'blocks', len(geometry['palette']), 'states')
    print('active route operation counts:', {key: len(value) for key, value in data['routes'].items()})


if __name__ == '__main__':
    main()
