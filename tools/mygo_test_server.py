#!/usr/bin/env python3
"""Launch an isolated Fabric 1.21.1 runtime from Loom's existing classpath.

Reason: MyGO needs real protocol/server checks without modifying or stopping a
developer's running server, sharing its world, or adding test npm dependencies
to the mod. Run ./gradlew build first; this script does not invoke Gradle.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sys
import uuid


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--legacy-config", action="store_true",
                        help="Write an old unversioned empty pack URL to verify configuration migration.")
    parser.add_argument("--previous-cdn-config", action="store_true",
                        help="Verify a version-2 server migrates the previous broken texture-pack CDN.")
    args = parser.parse_args()
    if args.legacy_config and args.previous_cdn_config:
        parser.error("Choose one legacy resource-pack fixture")
    root = Path(__file__).resolve().parents[1]
    run_dir = (args.run_dir or root / "build/mygo-server-test").resolve()
    # A mistaken CLI argument must not overwrite an ordinary Minecraft server.
    if root / "build" not in run_dir.parents or "mygo" not in run_dir.name:
        raise SystemExit("The isolated run directory must be a mygo-named directory inside build/.")
    if args.port == 25565 or not 1024 <= args.port <= 65535:
        raise SystemExit("Choose an unprivileged test port other than the ordinary 25565 server.")
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", args.port))
        except OSError as error:
            raise SystemExit(f"Test port {args.port} is occupied; no running server was changed: {error}")

    java_home = os.environ.get("MYGO_TEST_JAVA_HOME")
    candidates = ([Path(java_home) / "bin/java"] if java_home else []) + sorted(
        (Path.home() / ".gradle/jdks").glob("*/**/Contents/Home/bin/java")
    )
    java = next((candidate for candidate in candidates if candidate.is_file()), None)
    if java is None:
        raise SystemExit("JDK 21 not found; set MYGO_TEST_JAVA_HOME to its installation directory.")
    arg_file = root / "build/loom-cache/argFiles/runServer"
    prepared_classpath = root / "build/mygo-server-test/runtime-classpath.txt"
    launch_file = root / ".gradle/loom-cache/launch.cfg"
    if not launch_file.is_file() or not (arg_file.is_file() or prepared_classpath.is_file()):
        raise SystemExit("Loom server launch files are missing; run ./gradlew -I tools/mygo_prepare_test.gradle mygoPrepareTestRuntime first.")
    if prepared_classpath.is_file():
        runtime_classpath = prepared_classpath.read_text().strip()
    else:
        lines = arg_file.read_text().splitlines()
        if lines[0] != "-classpath" or len(lines) != 2:
            raise SystemExit("Unexpected Loom server argument format; prepare with tools/mygo_prepare_test.gradle.")
        runtime_classpath = lines[1]
    classes = root / "build/classes/java/main"
    resources = root / "build/resources/main"
    if not classes.is_dir() or not resources.is_dir():
        raise SystemExit("Compiled mod classes/resources are missing. Run ./gradlew build first.")

    run_dir.mkdir(parents=True, exist_ok=True)
    # Snapshot compiled output so rebuilding the project cannot alter this run.
    snapshot = run_dir / "mod-snapshot"
    snapshot.mkdir(exist_ok=True)
    for source, name in [(classes, "classes"), (resources, "resources")]:
        destination = snapshot / name
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(source, destination)
    classpath = runtime_classpath.replace(str(classes), str(snapshot / "classes")).replace(
        str(resources), str(snapshot / "resources")
    )

    eula = root / "run/eula.txt"
    if not eula.is_file() or "eula=true" not in eula.read_text():
        raise SystemExit("The project run/eula.txt does not record acceptance; accept the Minecraft EULA first.")
    shutil.copyfile(eula, run_dir / "eula.txt")
    properties = {
        "server-ip": "127.0.0.1", "server-port": str(args.port),
        "online-mode": "false", "enforce-secure-profile": "false",
        "motd": "Isolated MyGO protocol validation", "max-players": "12",
        "view-distance": "4", "simulation-distance": "4", "spawn-protection": "0",
        "level-name": "mygo-validation-world", "level-type": "minecraft:flat",
        "generator-settings": '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}',
        "generate-structures": "false", "allow-flight": "true", "pvp": "true",
        "difficulty": "peaceful", "enable-rcon": "false", "enable-query": "false",
        "enable-command-block": "false", "function-permission-level": "2",
        "op-permission-level": "4", "pause-when-empty-seconds": "0",
    }
    (run_dir / "server.properties").write_text(
        "# Isolated validation server. Never used by the normal run/ server.\n" +
        "\n".join(f"{key}={value}" for key, value in properties.items()) + "\n"
    )
    names = [f"MyGoCheck{letter}" for letter in "ABCDEF"]
    operators = []
    for name in names:
        raw = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
        raw[6] = (raw[6] & 0x0F) | 0x30
        raw[8] = (raw[8] & 0x3F) | 0x80
        operators.append({"uuid": str(uuid.UUID(bytes=bytes(raw))), "name": name,
                          "level": 4, "bypassesPlayerLimit": True})
    (run_dir / "ops.json").write_text(json.dumps(operators, indent=2) + "\n")
    pack_config = run_dir / "config/pvp/mygo/resource-pack.json"
    pack_config.parent.mkdir(parents=True, exist_ok=True)
    pack_settings = {
        "bindAddress": "127.0.0.1", "httpPort": args.port + 1,
        "advertisedHost": "127.0.0.1", "packUrl": "", "loadTimeoutSeconds": 10,
    }
    # Ordinary protocol checks explicitly keep the local HTTP fixture. The
    # legacy fixture deliberately omits the version to exercise public-URL migration.
    if args.previous_cdn_config:
        # The texture fix must reach already-upgraded live servers, not only fresh configs.
        pack_settings.update(configVersion=2, packUrl="https://qfmaker.com/upload/files/5df4ab7h.zip")
    elif not args.legacy_config:
        pack_settings["configVersion"] = 1
    pack_config.write_text(json.dumps(pack_settings, indent=2) + "\n")
    command = [str(java), "-Xms256M", "-Xmx2G", f"-Dfabric.dli.config={launch_file}",
               "-Dfabric.dli.env=server", "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer",
               "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
               "-classpath", classpath, "net.fabricmc.devlaunchinjector.Main", "nogui"]
    (run_dir / "validation-environment.json").write_text(json.dumps({
        "root": str(root), "runDirectory": str(run_dir), "port": args.port,
        "java": str(java), "minecraft": "1.21.1", "operators": names,
        "testType": "headless protocol integration; no client image/audio rendering",
    }, indent=2) + "\n")
    print(f"Isolated MyGO runtime prepared: {run_dir}; 127.0.0.1:{args.port}", flush=True)
    if args.prepare_only:
        return
    os.chdir(run_dir)
    os.execv(java, command)


if __name__ == "__main__":
    main()
