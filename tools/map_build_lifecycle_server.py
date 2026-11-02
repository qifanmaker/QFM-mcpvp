#!/usr/bin/env python3
"""Compile the slow-build lifecycle observer into a disposable server snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=25704)
    parser.add_argument('--run-dir', type=Path, default=Path('build/mygo-map-build-lifecycle'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run = args.run_dir.resolve()
    if root / 'build' not in run.parents or 'mygo' not in run.name or args.port == 25565:
        raise SystemExit('Use an isolated port and a mygo-named directory inside build/.')
    if (run / 'map-build-lifecycle-native.json').exists():
        raise SystemExit('Use a fresh run directory to preserve previous evidence.')
    subprocess.run([sys.executable, str(root / 'tools/mygo_test_server.py'), '--prepare-only',
                    '--port', str(args.port), '--run-dir', str(run)], cwd=root, check=True)
    snapshot = run / 'mod-snapshot'
    java = Path(json.loads((run / 'validation-environment.json').read_text())['java'])
    runtime = (root / 'build/mygo-server-test/runtime-classpath.txt').read_text().strip()
    runtime = runtime.replace(str(root / 'build/classes/java/main'), str(snapshot / 'classes'))
    runtime = runtime.replace(str(root / 'build/resources/main'), str(snapshot / 'resources'))
    source = root / 'tools/map_build_lifecycle/MapBuildLifecycleProbe.java'
    subprocess.run([str(java.with_name('javac')), '--release', '21', '-proc:none', '-encoding', 'UTF-8',
                    '-cp', runtime, '-d', str(snapshot / 'classes'), str(source)], cwd=root, check=True)
    manifest = snapshot / 'resources/fabric.mod.json'
    descriptor = json.loads(manifest.read_text())
    descriptor['entrypoints']['main'].append('com.example.pvp.validation.MapBuildLifecycleProbe')
    manifest.write_text(json.dumps(descriptor, indent=2) + '\n')
    provenance = {}
    for name in ['match/Match', 'arena/MapBuildQueue', 'arena/MapWorkBudget']:
        file = 'com/example/pvp/' + name + '.class'
        provenance[name] = {'buildSHA256': hashlib.sha256((root / 'build/classes/java/main' / file).read_bytes()).hexdigest(),
                            'snapshotSHA256': hashlib.sha256((snapshot / 'classes' / file).read_bytes()).hexdigest()}
    (run / 'map-build-lifecycle-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    command = [str(java), '-Xms256M', '-Xmx2G', f'-Dfabric.dli.config={root / ".gradle/loom-cache/launch.cfg"}',
               '-Dfabric.dli.env=server', '-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer',
               '-Dfile.encoding=UTF-8', '-classpath', runtime, 'net.fabricmc.devlaunchinjector.Main', 'nogui']
    os.chdir(run)
    os.execv(str(java), command)


if __name__ == '__main__':
    main()
