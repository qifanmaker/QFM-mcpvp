#!/usr/bin/env python3
"""Run SkyWars lifecycle regression checks in a throwaway Fabric server.

Reason: JUnit cannot exercise Minecraft's asynchronous entity loading, container
replacement drops, or chunk tickets. Only the copied build snapshot receives the
test entrypoint; the production mod and the normal run/ server are untouched.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25593)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-skywars-regression"))
    parser.add_argument("--baseline", action="store_true",
                        help="Record the original repeated-loot and hidden-drop failures.")
    parser.add_argument("--baseline-ref", default="80eadba",
                        help="Git revision containing the original implementations (default: 80eadba).")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    run_dir = (root / args.run_dir).resolve()
    if run_dir.exists() and (run_dir / "skywars-results.json").exists():
        raise SystemExit("Use a fresh run directory to retain the previous test evidence.")
    subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"),
                    "--prepare-only", "--port", str(args.port), "--run-dir", str(run_dir)],
                   cwd=root, check=True)
    environment = json.loads((run_dir / "validation-environment.json").read_text())
    environment["testType"] = "headless Fabric server lifecycle regression; no client rendering"
    environment["baselineRef"] = args.baseline_ref if args.baseline else None
    (run_dir / "validation-environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    java = Path(environment["java"])
    classes = run_dir / "mod-snapshot/classes"
    resources = run_dir / "mod-snapshot/resources"
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip()
    runtime = runtime.replace(str(root / "build/classes/java/main"), str(classes)).replace(
        str(root / "build/resources/main"), str(resources))
    if args.baseline:
        # Compile the original implementations only into this disposable snapshot.
        # Do not reset the workspace or mutate the production build output.
        baseline = run_dir / "baseline-sources/com/example/pvp/arena/skywars"
        baseline.mkdir(parents=True, exist_ok=True)
        sources = []
        for name in ("SkyWarsLayout", "SkyWarsLootPolicy", "SkyWarsMapGenerator"):
            source = baseline / f"{name}.java"
            source.write_bytes(subprocess.check_output([
                "git", "show", f"{args.baseline_ref}:src/main/java/com/example/pvp/arena/skywars/{name}.java"], cwd=root))
            sources.append(str(source))
        subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                        "-cp", runtime, "-d", str(classes), *sources], cwd=root, check=True)
        (run_dir / "baseline-commit.txt").write_bytes(subprocess.check_output([
            "git", "rev-parse", "--verify", args.baseline_ref], cwd=root))
    subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-cp", runtime, "-d", str(classes),
                    str(root / "tools/skywars_regression/SkyWarsServerRegression.java")],
                   cwd=root, check=True)
    manifest = resources / "fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    descriptor["entrypoints"]["main"].append("com.example.pvp.validation.SkyWarsServerRegression")
    manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
    command = [str(java), "-Xms256M", "-Xmx2G",
               f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server",
               "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               f"-Dskywars.validation.baseline={str(args.baseline).lower()}",
               "-Dfile.encoding=UTF-8", "-classpath", runtime,
               "net.fabricmc.devlaunchinjector.Main", "nogui"]
    with (run_dir / "server-console.log").open("w") as log:
        process = subprocess.Popen(command, cwd=run_dir, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            code = process.wait(timeout=240)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=20)
            raise SystemExit(f"Server regression timed out; see {log.name}")
    report_file = run_dir / "skywars-results.json"
    if not report_file.exists():
        raise SystemExit(f"Server exited {code} without a report; see {run_dir / 'server-console.log'}")
    report = json.loads(report_file.read_text())
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if code or not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
