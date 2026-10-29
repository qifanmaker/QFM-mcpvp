#!/usr/bin/env python3
"""Launch an isolated practice snapshot with a test-only combat event observer.

Reason: the AI's status label cannot prove Minecraft applied critical damage.
This observer is compiled only into a disposable runtime snapshot, keeping test
instrumentation out of the shipped mod. Start the protocol driver separately.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25647)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-practice-combat"))
    parser.add_argument("--prepared", action="store_true", help="Use an existing frozen baseline snapshot.")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = (root / args.run_dir).resolve()
    if not args.prepared:
        subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"), "--prepare-only",
                        "--port", str(args.port), "--run-dir", str(run_dir)], cwd=root, check=True)
    if root / "build" not in run_dir.parents or "mygo" not in run_dir.name:
        raise SystemExit("Use a mygo-named isolated directory inside build/.")
    environment = json.loads((run_dir / "validation-environment.json").read_text())
    java = Path(environment["java"])
    classes = run_dir / "mod-snapshot/classes"
    resources = run_dir / "mod-snapshot/resources"
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip()
    runtime = runtime.replace(str(root / "build/classes/java/main"), str(classes)).replace(
        str(root / "build/resources/main"), str(resources))
    subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-cp", runtime, "-d", str(classes),
                    str(root / "tools/practice_combat_regression/PracticeCombatProbe.java")], cwd=root, check=True)
    manifest = resources / "fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    entry = "com.example.pvp.validation.PracticeCombatProbe"
    if entry not in descriptor["entrypoints"]["main"]:
        descriptor["entrypoints"]["main"].append(entry)
    manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
    event_file = run_dir / "practice-combat-events.jsonl"
    if event_file.exists():
        raise SystemExit("Use a fresh run directory so earlier combat evidence is retained.")
    command = [str(java), "-Xms256M", "-Xmx2G",
               f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-classpath", runtime,
               "net.fabricmc.devlaunchinjector.Main", "nogui"]
    os.chdir(run_dir)
    os.execv(java, command)


if __name__ == "__main__":
    main()
