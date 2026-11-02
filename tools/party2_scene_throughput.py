#!/usr/bin/env python3
"""Benchmark full source terrain build and cleanup in a disposable Fabric server.

Reason: queue optimizations need complete 13M/6M-cell measurements, native tick
latency, and independent final-world verification. The existing scene_queue
fixture separately checks light/heightmaps/block entities/ordinary callbacks.
No production entrypoints or worlds are modified; run Gradle before this tool.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25690)
    parser.add_argument("--run-dir", type=Path, default=Path("build/mygo-party2-scene-throughput"))
    parser.add_argument("--scene", choices=("rocket_launch", "take_flight"), default="rocket_launch")
    parser.add_argument("--budget-ms", default="4", help="Fixed nonnegative milliseconds, or adaptive (queue.flushAdaptive()).")
    parser.add_argument("--load-ms", type=int, default=0, help="Explicit busy-loop fixture before END_SERVER_TICK (0..40 ms).")
    parser.add_argument("--wait-chunks", action="store_true", help="Wait for native scene tickets before flushing; phase wall time includes those ticks.")
    parser.add_argument("--compare", type=Path, help="Earlier native report with the same scene/load; add timing ratios.")
    parser.add_argument("--prepare-only", action="store_true", help="Freeze/compile the snapshot and config without starting a server.")
    parser.add_argument("--use-prepared", action="store_true", help="Run the existing immutable snapshot; do not replace it after a rebuild.")
    args = parser.parse_args()
    if args.budget_ms != "adaptive" and (not args.budget_ms.isdecimal() or int(args.budget_ms) > 40):
        parser.error("--budget-ms must be 0..40 or adaptive")
    if not 0 <= args.load_ms <= 40:
        parser.error("--load-ms must be 0..40")
    root = Path(__file__).resolve().parents[1]
    run = (root / args.run_dir).resolve()
    if root / "build" not in run.parents or "mygo" not in run.name:
        raise SystemExit("Use a mygo-named isolated directory inside build/.")
    if (run / "party-scene-throughput-report.json").exists():
        raise SystemExit("Use a fresh run directory to retain prior evidence.")
    if args.prepare_only and args.use_prepared:
        parser.error("Choose --prepare-only or --use-prepared")
    if not args.use_prepared:
        subprocess.run([sys.executable, str(root / "tools/mygo_test_server.py"), "--prepare-only", "--port", str(args.port), "--run-dir", str(run)], cwd=root, check=True)
    snapshot = run / "mod-snapshot"
    java = Path(json.loads((run / "validation-environment.json").read_text())["java"])
    runtime = (root / "build/mygo-server-test/runtime-classpath.txt").read_text().strip().replace(
        str(root / "build/classes/java/main"), str(snapshot / "classes")).replace(
        str(root / "build/resources/main"), str(snapshot / "resources"))
    if not args.use_prepared:
        subprocess.run([str(java.with_name("javac")), "--release", "21", "-proc:none", "-encoding", "UTF-8", "-cp", runtime,
                        "-d", str(snapshot / "classes"), str(root / "tools/party2_scene_throughput/PartySceneThroughputProbe.java")], cwd=root, check=True)
    manifest = snapshot / "resources/fabric.mod.json"
    descriptor = json.loads(manifest.read_text())
    entrypoint = "com.example.pvp.validation.PartySceneThroughputProbe"
    config_path = run / "party-scene-throughput-config.json"
    config = {"scene": args.scene, "budgetMillis": args.budget_ms, "loadMillis": args.load_ms}
    if args.wait_chunks:
        config["waitChunks"] = True
    if args.use_prepared:
        if entrypoint not in descriptor["entrypoints"]["main"] or json.loads(config_path.read_text()) != config:
            raise SystemExit("Prepared snapshot/config does not match the requested benchmark")
    else:
        descriptor["entrypoints"]["main"].append(entrypoint)
        manifest.write_text(json.dumps(descriptor, indent=2) + "\n")
        config_path.write_text(json.dumps(config) + "\n")
    production_paths = ["com/example/pvp/arena/MapBuildQueue.class", "com/example/pvp/arena/ArenaWorld.class",
                        "com/example/pvp/arena/party/PartyMapBlueprint.class", "com/example/pvp/mixin/PartySnapshotImportMixin.class"]
    provenance = {"productionClassSHA256": {name: sha256(snapshot / "classes" / name) for name in production_paths},
                  "geometrySHA256": sha256(snapshot / f"resources/pvp/party/geometry/{args.scene}.json.gz"),
                  "fixtureSHA256": sha256(root / "tools/party2_scene_throughput/PartySceneThroughputProbe.java"),
                  "fixtureOptions": config,
                  "testType": "Complete original native terrain queue; no client or module gameplay. Real elapsed server ticks; verification excluded from phase timing."}
    provenance_path = run / "party-scene-throughput-provenance.json"
    if args.use_prepared:
        frozen = json.loads(provenance_path.read_text())
        if frozen["productionClassSHA256"] != provenance["productionClassSHA256"] or frozen["geometrySHA256"] != provenance["geometrySHA256"]:
            raise SystemExit("Prepared snapshot changed after freezing")
        provenance = frozen
    else:
        provenance_path.write_text(json.dumps(provenance, indent=2) + "\n")
    if args.prepare_only:
        print(json.dumps({"prepared": str(run), **provenance}), flush=True)
        return
    # Native chunk work can coincide with GC safepoints; retain GC timing so
    # throughput gains do not hide pauses caused by the test JVM's heap.
    gc_log = run / "scene-throughput-gc.log"
    command = [str(java), "-Xms256M", "-Xmx2G", "-Xlog:gc*:file=scene-throughput-gc.log:time,uptime,level,tags", f"-Dfabric.dli.config={root / '.gradle/loom-cache/launch.cfg'}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-classpath", runtime, "net.fabricmc.devlaunchinjector.Main", "nogui"]
    with (run / "scene-throughput-server.log").open("w") as output:
        result = subprocess.run(command, cwd=run, stdout=output, stderr=subprocess.STDOUT, timeout=1200)
    report = run / "party-scene-throughput-report.json"
    if not report.exists():
        raise SystemExit("No native report; inspect " + str(run / "scene-throughput-server.log"))
    data = json.loads(report.read_text()) | provenance | {"gcLog": str(gc_log)}
    if args.compare:
        baseline = json.loads(args.compare.read_text())
        if any(baseline.get(key) != data.get(key) for key in ("scene", "loadMillis", "geometrySHA256")):
            raise SystemExit("Comparison requires identical scene, load, and geometry SHA256")
        data["comparison"] = {"baseline": str(args.compare.resolve()), "baselineBudgetMillis": baseline["budgetMillis"],
                              "phases": [{"phase": row["phase"], "baselineWallMillis": old["wallMillis"],
                                          "currentWallMillis": row["wallMillis"], "speedup": old["wallMillis"] / row["wallMillis"],
                                          "flushCPUSpeedup": old["flush"]["totalMillis"] / row["flush"]["totalMillis"]}
                                         for old, row in zip(baseline["phases"], data["phases"], strict=True)]}
    report.write_text(json.dumps(data, indent=2) + "\n")
    print(json.dumps({"report": str(report), **data}), flush=True)
    if result.returncode or not data.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
