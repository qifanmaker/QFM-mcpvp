#!/usr/bin/env python3
"""Run complete resident-map pool regression in an isolated native Fabric snapshot."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=25706)
    parser.add_argument('--run-dir', type=Path, default=Path('build/mygo-party2-resident-maps'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run = args.run_dir.resolve()
    if root / 'build' not in run.parents or 'mygo' not in run.name or args.port == 25565:
        raise SystemExit('Use an isolated port and a mygo-named directory inside build/.')
    if (run / 'party-resident-maps-report.json').exists():
        raise SystemExit('Use a fresh run directory to retain previous evidence.')
    subprocess.run([sys.executable, str(root / 'tools/mygo_test_server.py'), '--prepare-only',
                    '--port', str(args.port), '--run-dir', str(run)], cwd=root, check=True)
    snapshot = run / 'mod-snapshot'
    java = Path(json.loads((run / 'validation-environment.json').read_text())['java'])
    runtime = (root / 'build/mygo-server-test/runtime-classpath.txt').read_text().strip()
    runtime = runtime.replace(str(root / 'build/classes/java/main'), str(snapshot / 'classes'))
    runtime = runtime.replace(str(root / 'build/resources/main'), str(snapshot / 'resources'))
    subprocess.run([str(java.with_name('javac')), '--release', '21', '-proc:none', '-encoding', 'UTF-8',
                    '-cp', runtime, '-d', str(snapshot / 'classes'), str(root / 'tools/party2_resident_maps/PartyResidentMapsProbe.java')],
                   cwd=root, check=True)
    manifest = snapshot / 'resources/fabric.mod.json'
    descriptor = json.loads(manifest.read_text())
    descriptor['entrypoints']['main'].append('com.example.pvp.validation.PartyResidentMapsProbe')
    manifest.write_text(json.dumps(descriptor, indent=2) + '\n')
    provenance = {}
    for name in ['arena/party/PartyResidentMaps', 'arena/party/PartyResidentCatalog', 'arena/MapBuildQueue', 'arena/ArenaWorld']:
        file = 'com/example/pvp/' + name + '.class'
        provenance[name] = {'buildSHA256': hashlib.sha256((root / 'build/classes/java/main' / file).read_bytes()).hexdigest(),
                            'snapshotSHA256': hashlib.sha256((snapshot / 'classes' / file).read_bytes()).hexdigest()}
    (run / 'party-resident-maps-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    command = [str(java), '-Xms512M', '-Xmx4G', f'-Dfabric.dli.config={root / ".gradle/loom-cache/launch.cfg"}',
               '-Dfabric.dli.env=server', '-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer',
               '-Dfile.encoding=UTF-8', '-classpath', runtime, 'net.fabricmc.devlaunchinjector.Main', 'nogui']
    log = run / 'resident-maps-server.log'
    with log.open('w') as output:
        result = subprocess.run(command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=1000)
    report = run / 'party-resident-maps-report.json'
    if not report.exists():
        raise SystemExit('No native report; inspect ' + str(log))
    data = json.loads(report.read_text())
    data['processExitCode'] = result.returncode
    data['serverErrors'] = [line for line in log.read_text().splitlines()
                            if re.search(r'Encountered an unexpected exception|Exception in thread|Caused by:|ERROR\]', line)]
    report.write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps({'report': str(report), 'ok': data.get('ok'), 'checks': len(data.get('checks', [])),
                      'profileCount': data.get('profileCount'), 'allProfilesWarmMillis': data.get('allProfilesWarmMillis'),
                      'sparseRocketResetMillis': data.get('sparseRocketResetMillis'),
                      'repeatRocketLeaseMillis': data.get('repeatRocketLeaseMillis'), 'error': data.get('error')}), flush=True)
    if result.returncode or not data.get('ok') or data['serverErrors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
