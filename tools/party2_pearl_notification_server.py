#!/usr/bin/env python3
"""Snapshot-only real native pearl success notification controls; never shipped."""
import argparse,json,os,subprocess,sys,hashlib
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',type=int,default=25682);parser.add_argument('--run-dir',type=Path,default=Path('build/mygo-party2-pearl-notification'));args=parser.parse_args()
    root=Path(__file__).resolve().parents[1];run=args.run_dir.resolve()
    subprocess.run([sys.executable,str(root/'tools/mygo_test_server.py'),'--prepare-only','--port',str(args.port),'--run-dir',str(run)],cwd=root,check=True)
    snapshot=run/'mod-snapshot';java=Path(json.loads((run/'validation-environment.json').read_text())['java']);runtime=(root/'build/mygo-server-test/runtime-classpath.txt').read_text().strip().replace(str(root/'build/classes/java/main'),str(snapshot/'classes')).replace(str(root/'build/resources/main'),str(snapshot/'resources'))
    sources=sorted((root/'tools/party2_pearl_notification_probe').glob('*.java'))
    subprocess.run([str(java.with_name('javac')),'--release','21','-proc:none','-encoding','UTF-8','-cp',runtime,'-d',str(snapshot/'classes'),*[str(p)for p in sources]],cwd=root,check=True)
    manifest=snapshot/'resources/fabric.mod.json';descriptor=json.loads(manifest.read_text());descriptor['entrypoints']['main'].append('com.example.pvp.validation.notification.NativePearlNotificationProbe');descriptor['mixins'].append('pearl-notification.mixins.json');manifest.write_text(json.dumps(descriptor,indent=2)+'\n')
    mixins={'required':True,'minVersion':'0.8','package':'com.example.pvp.validation.notification.mixin','compatibilityLevel':'JAVA_21','mixins':[p.stem for p in sources if p.stem!='NativePearlNotificationProbe'],'injectors':{'defaultRequire':1}};(snapshot/'resources/pearl-notification.mixins.json').write_text(json.dumps(mixins,indent=2)+'\n')
    names=['com/example/pvp/mixin/EnderPearlEntityMixin.class','com/example/pvp/arena/party/PearlHurlRound.class','com/example/pvp/arena/party/PartyRound.class','com/example/pvp/match/PortedGameEvents.class','com/example/pvp/match/MinecraftPartySession.class'];provenance={name:{'snapshotSHA256':hashlib.sha256((snapshot/'classes'/name).read_bytes()).hexdigest(),'buildSHA256':hashlib.sha256((root/'build/classes/java/main'/name).read_bytes()).hexdigest()}for name in names};(run/'native-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    command=[str(java),'-Xms256M','-Xmx2G',f'-Dfabric.dli.config={root/".gradle/loom-cache/launch.cfg"}','-Dfabric.dli.env=server','-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotServer','-Dfile.encoding=UTF-8','-classpath',runtime,'net.fabricmc.devlaunchinjector.Main','nogui'];os.chdir(run);os.execv(str(java),command)
if __name__=='__main__':main()
