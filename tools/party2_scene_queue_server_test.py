#!/usr/bin/env python3
"""Run native scene snapshot/run fixtures only in a disposable Fabric snapshot."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=25678)
    parser.add_argument('--run-dir',type=Path,default=Path('build/mygo-party2-scene-queue'))
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1];run=(root/args.run_dir).resolve()
    if root/'build'not in run.parents or 'mygo'not in run.name:raise SystemExit('Use a mygo-named directory inside build/.')
    if (run/'party-scene-queue-report.json').exists():raise SystemExit('Use a fresh run directory to retain prior evidence.')
    subprocess.run([sys.executable,str(root/'tools/mygo_test_server.py'),'--prepare-only','--port',str(args.port),'--run-dir',str(run)],cwd=root,check=True)
    snapshot=run/'mod-snapshot';java=Path(json.loads((run/'validation-environment.json').read_text())['java'])
    runtime=(root/'build/mygo-server-test/runtime-classpath.txt').read_text().strip().replace(str(root/'build/classes/java/main'),str(snapshot/'classes')).replace(str(root/'build/resources/main'),str(snapshot/'resources'))
    subprocess.run([str(java.with_name('javac')),'--release','21','-proc:none','-encoding','UTF-8','-cp',runtime,'-d',str(snapshot/'classes'),str(root/'tools/party2_scene_queue_regression/PartySceneQueueProbe.java')],cwd=root,check=True)
    manifest=snapshot/'resources/fabric.mod.json';descriptor=json.loads(manifest.read_text());descriptor['entrypoints']['main'].append('com.example.pvp.validation.PartySceneQueueProbe');manifest.write_text(json.dumps(descriptor,indent=2)+'\n')
    command=[str(java),'-Xms256M','-Xmx2G',f'-Dfabric.dli.config={root / ".gradle/loom-cache/launch.cfg"}','-Dfabric.dli.env=server','-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer','-Dfile.encoding=UTF-8','-classpath',runtime,'net.fabricmc.devlaunchinjector.Main','nogui']
    with (run/'scene-queue-server.log').open('w')as output:result=subprocess.run(command,cwd=run,stdout=output,stderr=subprocess.STDOUT,timeout=180)
    report=run/'party-scene-queue-report.json'
    if not report.exists():raise SystemExit('No native report; inspect '+str(run/'scene-queue-server.log'))
    data=json.loads(report.read_text());print(json.dumps({'report':str(report),**data}),flush=True)
    if result.returncode or not data.get('ok'):raise SystemExit(1)


if __name__=='__main__':main()
