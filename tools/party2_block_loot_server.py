#!/usr/bin/env python3
"""Prepare isolated Fabric snapshot with test-only native block-loot fixtures."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = args.run_dir.resolve()
    # mygo_test_server owns the directory/port safety checks and copies an
    # immutable production snapshot. Instrumentation is never compiled to build/classes.
    subprocess.run([sys.executable, str(root / 'tools/mygo_test_server.py'), '--prepare-only',
                    '--port', str(args.port), '--run-dir', str(run_dir)], cwd=root, check=True)
    snapshot = run_dir / 'mod-snapshot'
    java = Path(json.loads((run_dir / 'validation-environment.json').read_text())['java'])
    runtime = (root / 'build/mygo-server-test/runtime-classpath.txt').read_text().strip().replace(
        str(root / 'build/classes/java/main'), str(snapshot / 'classes')).replace(
        str(root / 'build/resources/main'), str(snapshot / 'resources'))
    subprocess.run([str(java.with_name('javac')), '--release', '21', '-proc:none', '-encoding', 'UTF-8',
                    '-cp', runtime, '-d', str(snapshot / 'classes'),
                    str(root / 'tools/party2_block_loot_regression/PartyBlockLootProbe.java')], cwd=root, check=True)
    manifest = snapshot / 'resources/fabric.mod.json'
    descriptor = json.loads(manifest.read_text())
    descriptor['entrypoints']['main'].append('com.example.pvp.validation.PartyBlockLootProbe')
    manifest.write_text(json.dumps(descriptor, indent=2) + '\n')
    command = [str(java), '-Xms256M', '-Xmx2G', f'-Dfabric.dli.config={root / ".gradle/loom-cache/launch.cfg"}',
               '-Dfabric.dli.env=server', '-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer',
               '-Dfile.encoding=UTF-8', '-classpath', runtime, 'net.fabricmc.devlaunchinjector.Main', 'nogui']
    os.chdir(run_dir)
    os.execv(str(java), command)


if __name__ == '__main__':
    main()
