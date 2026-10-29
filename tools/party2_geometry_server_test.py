#!/usr/bin/env python3
"""Verify every geometry cell through the real Fabric1.21.1 decoder.

Reason: Cave Parkour's million cells require streaming parsing. Compare the
decoder's complete translated output with an independent JSON reference, not
just a few scene blocks. Compile instrumentation into a disposable snapshot.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25606)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-party2-geometry"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = (root / args.run_dir).resolve()
    if root / "build" not in run_dir.parents or "mygo" not in run_dir.name:
        raise SystemExit("Use a mygo-named isolated directory inside build/.")
    if (run_dir / "party-geometry-report.json").exists():
        raise SystemExit("Use a fresh run directory to retain earlier evidence.")
    subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"), "--prepare-only",
                    "--port", str(args.port), "--run-dir", str(run_dir)], cwd=root, check=True)
    snapshot = run_dir / "mod-snapshot"
    references = []
    for source in sorted((snapshot / "resources/pvp/party/geometry").glob("*.json.gz")):
        with gzip.open(source, "rt") as stream:
            geometry = json.load(stream)
        minimum = geometry["bounds"]["min"]
        suffixes = []
        for state in geometry["palette"]:
            # The existing pale-hanging-moss replacement is name-only. Vanilla
            # therefore supplies hanging_roots' waterlogged=false default.
            defaults = {"waterlogged": "false"} if state["Name"] == "minecraft:hanging_roots" else {}
            properties = ",".join(f"{key}={value}" for key, value in sorted((defaults | state.get("Properties", {})).items()))
            suffixes.append(f"|{state['Name']}|{properties}\n")
        digest = hashlib.sha256()
        for x, y, z, state in geometry["blocks"]:
            digest.update(f"{x+minimum[0]+601},{y+minimum[1]+47},{z+minimum[2]-777}{suffixes[state]}".encode())
        references.append({"name": source.name.removesuffix(".json.gz"), "count": len(geometry["blocks"]),
                           "sha256": digest.hexdigest()})
    (run_dir / "party-geometry-expected.json").write_text(json.dumps(references) + "\n")
    environment = json.loads((run_dir / "validation-environment.json").read_text())
    java = Path(environment["java"])
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip().replace(
        str(root / "build/classes/java/main"), str(snapshot / "classes")).replace(
        str(root / "build/resources/main"), str(snapshot / "resources"))
    subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-cp", runtime, "-d", str(snapshot / "classes"),
                    str(root / "tools/party2_geometry_regression/PartyGeometryProbe.java")], cwd=root, check=True)
    manifest = snapshot / "resources/fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    descriptor["entrypoints"]["main"].append("com.example.pvp.validation.PartyGeometryProbe")
    manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
    command = [str(java), "-Xms256M", "-Xmx2G", f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-classpath", runtime, "net.fabricmc.devlaunchinjector.Main", "nogui"]
    with (run_dir / "geometry-server.log").open("w") as output:
        result = subprocess.run(command, cwd=run_dir, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    report_path = run_dir / "party-geometry-report.json"
    if not report_path.exists():
        raise SystemExit(f"No geometry report: inspect {run_dir / 'geometry-server.log'}")
    report = json.loads(report_path.read_text())
    print(json.dumps({"report": str(report_path), **report}), flush=True)
    if result.returncode or not report.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
