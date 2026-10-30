#!/usr/bin/env python3
"""Extract426's whole original End island/course and literal path generators.

Reason: pearls use actual collision geometry, hidden light checkpoints, arrows,
End gateways and1280cell cloned tunnels. Rebuilding simple platforms would lose
routes and physical difficulty. Review: read-onlyDIM1 extraction, full island/
pillars/light states preserved; safe signs and raw gateway metadata are data,
never executable source commands. Remote gateway policy is a scoped runtime duty.
"""
from __future__ import annotations
import argparse,functools,gzip,hashlib,json,pathlib,re,sys,zipfile
sys.dont_write_bytecode=True
from extract_party_geometry import AnvilWorld,extract,supported_block_names
MINIMUM=(-64,0,-140);MAXIMUM=(140,106,95)
CORE=['pearl_hurl','pearl_hurl_end','pearl_hurl_finish','pearl_hurl_generate','pearl_hurl_generate2','pearl_hurl_rules','pearl_hurl_start','pearl_hurl_tick','pearl_hurl_tick2']
HELPERS=['assign_finish_threshold','checkpoint','checkpoint2','timer_decrement','last_timer_title','player_line','rotate_entity','ready_up_tellraw','give_ready_ups','placements']

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',type=pathlib.Path,required=True);p.add_argument('--minecraft-sources',type=pathlib.Path,required=True);p.add_argument('--gateway-world',type=pathlib.Path,help='Optional original1.21.10 sandbox world after native gateway exit resolution; never executes its datapack.');p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('src/main/resources/pvp/party'));a=p.parse_args()
 w=AnvilWorld(a.world/'DIM1');w.chunk=functools.lru_cache(None)(w.chunk)
 with zipfile.ZipFile(a.world/'datapacks/data.zip')as z:
  raw={n:z.read('data/mcp/function/'+n+'.mcfunction')for n in CORE+HELPERS}
  g=extract(w,'pearl_hurl',{'min':MINIMUM,'max':MAXIMUM,'functions':CORE,'notes':['Full original End island,obsidian pillars and all3routes, including light/barrier navigation and source tunnel templates.','Actual source non-air bounds[-62,0,-138]..[137,104,91] verified inside this crop; zero original entities.','43safe navigation signs and22native gateways reconstructed separately; no source function runs.']},z,supported_block_names(a.minecraft_sources))
 signs=[];gates=[]
 for x in range(MINIMUM[0]//16,MAXIMUM[0]//16+1):
  for z in range(MINIMUM[2]//16,MAXIMUM[2]//16+1):
   for be in w.chunk(x,z).get('block_entities',[]):
    pos=[be[k]for k in ('x','y','z')]
    if not all(MINIMUM[i]<=pos[i]<=MAXIMUM[i]for i in range(3)):continue
    if be['id']=='minecraft:sign':signs.append({'position':pos,'waxed':bool(be.get('is_waxed',False)),**{face:{'messages':be.get(face+'_text',{}).get('messages',['']*4),'color':be.get(face+'_text',{}).get('color','black'),'glowing':bool(be.get(face+'_text',{}).get('has_glowing_text',False))}for face in ['front','back']}})
    if be['id']=='minecraft:end_gateway':gates.append({'position':pos,'age':be.get('Age',200),'exact':bool(be.get('ExactTeleport',False)),'exit':be.get('exit_portal')})
  # Root may resolve missing exits with an isolated original-version world. Keep
 # the immutable main geometry; only consume its native portalNBT/remote chunks.
 remote_world=AnvilWorld(a.gateway_world/'DIM1')if a.gateway_world else w
 remote_world.chunk=functools.lru_cache(None)(remote_world.chunk)
 if a.gateway_world:
  for gate in gates:
   x,y,z=gate['position'];updated=next((b for b in remote_world.chunk(x//16,z//16).get('block_entities',[])if b.get('id')=='minecraft:end_gateway'and[b['x'],b['y'],b['z']]==gate['position']),None)
   if updated and updated.get('exit_portal'):gate['exit']=updated['exit_portal']
 remote_specs={};unresolved=[];remote_gateways=[]
 for gate in gates:
  if gate['exit']is None:unresolved.append(gate['position']);continue
  x,y,z=gate['exit'];key=(x//16,z//16)
  if key not in remote_specs:remote_specs[key]={'gateways':[],'paths':set()}
  remote_specs[key]['gateways'].append(gate['position']);remote_specs[key]['paths'].add(1 if gate['position']==[-17,37,50]else 2 if gate['position']==[-55,14,-29]else 3)
 remotes=[];remote_payload={}
 with zipfile.ZipFile(a.world/'datapacks/data.zip')as data_pack:
  for index,((cx,cz),meta)in enumerate(sorted(remote_specs.items())):
   minimum=((cx-1)*16,0,(cz-1)*16);maximum=((cx+2)*16-1,255,(cz+2)*16-1);name='pearl_hurl_remote_'+str(index)
   missing=[]
   for x in range(cx-1,cx+2):
    for z in range(cz-1,cz+2):
     try:remote_world.chunk(x,z)
     except(FileNotFoundError,ValueError):missing.append([x,z])
   row={'name':name,'bounds':{'min':minimum,'max':maximum},'paths':sorted(meta['paths']),'sourceGateways':meta['gateways'],'missingSourceChunks':missing}
   if not missing:
    payload=extract(remote_world,name,{'min':minimum,'max':maximum,'functions':CORE,'notes':['Actual source remote End island3x3chunks,fullheight; preserves native gateway exit search and pearl landing.','Not moved beside the main island: all source relativeX/Z are retained.']},data_pack,supported_block_names(a.minecraft_sources))
    row['blocks']=len(payload['blocks']);remote_payload[name]=payload
    for x in range(cx-1,cx+2):
     for z in range(cz-1,cz+2):
      for b in remote_world.chunk(x,z).get('block_entities',[]):
       if b.get('id')=='minecraft:end_gateway':remote_gateways.append({'position':[b[k]for k in['x','y','z']],'age':b.get('Age',200),'exact':bool(b.get('ExactTeleport',False)),'exit':b.get('exit_portal')})
   remotes.append(row)
 ops=[]
 for i,line in enumerate(raw['pearl_hurl_generate2'].decode().splitlines(),1):
  m=re.fullmatch(r'execute if score path variables matches ([\d.]+) run (fill|clone|setblock) (.+)',line)
  if not m:continue
  lo,hi=map(int,m[1].split('..'))if'..'in m[1]else(int(m[1]),int(m[1]));paths=list(range(lo,hi+1))
  args=m[3].split();row={'line':i,'paths':paths,'type':m[2]}
  if m[2]=='setblock':row.update(position=list(map(int,args[:3])),state=args[3])
  elif m[2]=='fill':row.update(min=list(map(int,args[:3])),max=list(map(int,args[3:6])),state=args[6],replace=args[8]if len(args)>8 else None)
  else:row.update(min=list(map(int,args[:3])),max=list(map(int,args[3:6])),destination=list(map(int,args[6:9])))
  ops.append(row)
 clauses=[]
 for i,line in enumerate(raw['pearl_hurl_tick2'].decode().splitlines(),1):
  m=re.search(r'@s\[x=(-?\d+),y=(-?\d+),z=(-?\d+),distance=0\.\.(\d+),scores=\{checkpoint=([^}]+)\}\] checkpoint (\d+)',line)
  if not m:continue
  prefix=re.search(r'path variables matches ([\d.]+)',line);key=prefix[1]if prefix else'1..3';lo,hi=map(int,key.split('..'))if'..'in key else(int(key),int(key));prev=m[5];a0,b0=map(int,prev.split('..'))if'..'in prev else(int(prev),int(prev))
  clauses.append({'line':i,'paths':list(range(lo,hi+1)),'position':list(map(int,m.group(1,2,3))),'radius':int(m[4]),'previousMin':a0,'previousMax':b0,'checkpoint':int(m[6])})
 assert len(signs)==43 and len(gates)==22 and len(clauses)==56
 result={'sourceId':426,'sourceVersion':'1.21.10','bounds':{'min':MINIMUM,'max':MAXIMUM},'anchor':[38,39,-24],'functions':{n:b.decode()for n,b in raw.items()},'functionSha256':{n:hashlib.sha256(b).hexdigest()for n,b in raw.items()},'signs':signs,'gateways':gates,'remoteGeometry':remotes,'remoteGateways':remote_gateways,'unresolvedGateways':unresolved,'gatewayWorldProvided':bool(a.gateway_world),'generation':ops,'checkpoints':clauses,'spawnRules':{'upToFour':[[64.5+i,39,-79.5]for i in range(4)],'five':[[x,39,-79]for x in [66,67,65,68,64]],'six':[[x,39,-79]for x in [65.5,66.5,64.5,67.5,63.5,68.5]],'largeLobbyYaw':180,'largeLobbyPitch':0,'initialSafeMarkers':[[64.5+min(i,3),39,-79.5]for i in range(10)],'entryPreviews':[[14.5,48,-113.5],[87.5,27,34.5],[30.5,17,-28.5],[115.5,29,-47.5]],'previewAssignment':'Slots1,5,9 usepreview1;2,6,10preview2;3,7preview3;4,8preview4. Shared native8secondintro replaces source ready-up presentation; sourceactual launch andLIGHT safetyremain.'},'rules':{'timeLimitTicks':3600,'pathWeights':[1,1,1],'finishCheckpoints':[23,21,19],'finishedCheckpoints':[24,22,20],'firstPearlTicks':11,'pearlReloadTicks':25,'nativeItemCooldownTicks':20,'defaultSpawn':[65.5,39,-79.5],'failureSpawn':[30.5,17,-28.5],'deathSpawn':[-20.5,19,-28.5],'score':'N*checkpoint plus earlier most-recent-checkpoint rank; same-tick ties. InitialPoints3.','finish':'Normal2..10freeforall N-1 or3600ticks. Actual native gateway transit with exact final checkpoint.'},'geometry':{'blocks':len(g['blocks']),'replacements':g['replacements'],'omitted':g['omitted']},'adaptations':['Full whole-island geometry preserves all source collision and light states; no route scaling.','Source datapack safety precedes entity ticks. An actual native pearl landing defers only its same-tick unchanged AIR position check to the next tick, allowing normal client slab settling; otherAIR andLIGHT0 rules are unchanged.','Sign front/back literal messages/colors/glow/waxed state restored safely. Native gateway exit handling is scoped to this match rather than generating terrain in another match.','Only local participants/effects/items/pearls/checkpoints and owned chunk leases may be changed. Sourceglobal commands/gamerules are never executed.'],'sourceQuirks':['Sourceinit time2400 is overwrittenbyplaystart3600; advertised1second reload actually25ticks.','Safe marker tracks every nonzeroLIGHT cell before logical CP checking, not just official checkpoints. AIR orLIGHT0 resets to latest exactmarker.','The conditional JonikHD developer shortcut is onlysourcegamemode0, outside normal2..10competitivequeue.']}
 if a.gateway_world:
  report_path=a.gateway_world.parent/'original-gateways-report.json'
  if not report_path.exists():raise ValueError('Original-version gateway provenance report is required')
  report_bytes=report_path.read_bytes();report=json.loads(report_bytes)
  if not(report['ok']and report['allTenResolved']and report['version']=='1.21.10'and not report['existingKnownExitsChanged']):raise ValueError('Gateway sandbox provenance failed')
  result['gatewayResolution']={'version':report['version'],'officialServerSha256':report['officialServerSha256'],'sourceSeed':report['sourceSeed'],'sourceWorldUntouched':report['sourceWorldUntouched'],'sourceFilesVerified':report['sourceFilesVerified'],'existingKnownExitsChecked':report['existingKnownExitsChecked'],'existingKnownExitsChanged':report['existingKnownExitsChanged'],'nativeResolvedExits':report['resolved'],'procedure':report['procedure'],'reportSha256':hashlib.sha256(report_bytes).hexdigest()}
  result['adaptations'].append('Ten unsaved exit links and their remote terrain were produced by an isolated official1.21.10 native gateway collision run at original seed69420, then read as inert geometry/NBT. All177 originalEnd files and26 knownlinks verified unchanged; finalruntime remains1.21.1.')
 a.output.mkdir(parents=True,exist_ok=True);(a.output/'geometry').mkdir(exist_ok=True)
 for stale in(a.output/'geometry').glob('pearl_hurl_remote_*.json.gz'):
  if stale.stem.removesuffix('.json')not in remote_payload:stale.unlink()
 for name,payload in remote_payload.items():(a.output/('geometry/'+name+'.json.gz')).write_bytes(gzip.compress(json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
 (a.output/'geometry/pearl_hurl.json.gz').write_bytes(gzip.compress(json.dumps(g,ensure_ascii=False,separators=(',',':')).encode(),mtime=0));(a.output/'pearl_hurl_rules.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('Pearl Hurl',len(g['blocks']),'blocks',len(g['palette']),'states',len(signs),'signs',len(gates),'gateways',len(ops),'operations',len(clauses),'checkpointclauses;',len(remote_payload),'saved remote regions;',len(unresolved),'unresolved gateway exits')
if __name__=='__main__':main()
