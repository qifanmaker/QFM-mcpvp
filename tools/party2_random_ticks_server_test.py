#!/usr/bin/env python3
"""Run native Party random-tick sampling fixtures only inside a disposable Fabric snapshot."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25660)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-party2-random-ticks"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = (root / args.run_dir).resolve()
    if root / "build" not in run_dir.parents or "mygo" not in run_dir.name:
        raise SystemExit("Use a mygo-named isolated directory inside build/.")
    report_path = run_dir / "party-random-ticks-report.json"
    if report_path.exists():
        raise SystemExit("Use a fresh run directory to retain previous evidence.")
    subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"), "--prepare-only",
                    "--port", str(args.port), "--run-dir", str(run_dir)], cwd=root, check=True)
    snapshot = run_dir / "mod-snapshot"
    java = Path(json.loads((run_dir / "validation-environment.json").read_text())["java"])
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip().replace(
        str(root / "build/classes/java/main"), str(snapshot / "classes")).replace(
        str(root / "build/resources/main"), str(snapshot / "resources"))
    # Compile the exact helper source into this disposable snapshot too, so this
    # independent native sampling test can run while other new rounds are still
    # being integrated. It does not mutate build/classes or the distributable.
    subprocess.run([str(java.with_name("javac")), "--release", "21", "-g", "-proc:none", "-encoding", "UTF-8",
                    "-cp", runtime, "-d", str(snapshot / "classes"),
                    str(root / "src/main/java/com/example/pvp/arena/party/PartyRandomTicks.java"),
                    str(root / "tools/party2_random_ticks_regression/PartyRandomTicksProbe.java")], cwd=root, check=True)
    manifest = snapshot / "resources/fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    descriptor["entrypoints"]["main"].append("com.example.pvp.validation.PartyRandomTicksProbe")
    manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
    command = [str(java), "-Xms256M", "-Xmx2G",
               f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-classpath", runtime, "net.fabricmc.devlaunchinjector.Main", "nogui"]
    with (run_dir / "random-ticks-server.log").open("w") as output:
        result = subprocess.run(command, cwd=run_dir, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    if not report_path.exists():
        raise SystemExit(f"No report: inspect {run_dir / 'random-ticks-server.log'}")
    report = json.loads(report_path.read_text())
    print(json.dumps({"report": str(report_path), **report}), flush=True)
    if result.returncode or not report.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
