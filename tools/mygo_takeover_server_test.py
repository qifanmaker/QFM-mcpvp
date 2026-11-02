#!/usr/bin/env python3
"""Start a frozen Fabric snapshot with a test-only MyGO death entry point.

Reason: vanilla damage is intentionally disabled in MyGO, and simultaneous
death callbacks must be tested after one complete damage batch. The protocol
driver still proves the first takeover with an ordinary firearm kill.
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
    parser.add_argument("--port", type=int, default=25677)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-death-takeover"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = (root / args.run_dir).resolve()
    if root / "build" not in run_dir.parents or "mygo" not in run_dir.name:
        raise SystemExit("Use a mygo-named isolated directory inside build/.")
    if (run_dir / "mygo-takeover-probe.jsonl").exists():
        raise SystemExit("Use a fresh directory to retain earlier death-batch evidence.")
    subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"), "--prepare-only",
                    "--port", str(args.port), "--run-dir", str(run_dir)], cwd=root, check=True)
    environment = json.loads((run_dir / "validation-environment.json").read_text())
    java = Path(environment["java"])
    classes = run_dir / "mod-snapshot/classes"
    resources = run_dir / "mod-snapshot/resources"
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip()
    runtime = runtime.replace(str(root / "build/classes/java/main"), str(classes)).replace(
        str(root / "build/resources/main"), str(resources))
    subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-cp", runtime, "-d", str(classes),
                    str(root / "tools/mygo_death_takeover/MyGoDeathTakeoverProbe.java")], cwd=root, check=True)
    manifest = resources / "fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    descriptor["entrypoints"]["main"].append("com.example.pvp.validation.MyGoDeathTakeoverProbe")
    manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
    command = [str(java), "-Xms256M", "-Xmx2G",
               f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-classpath", runtime,
               "net.fabricmc.devlaunchinjector.Main", "nogui"]
    os.chdir(run_dir)
    os.execv(java, command)


if __name__ == "__main__":
    main()
