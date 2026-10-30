#!/usr/bin/env python3
"""Extract423's complete2160-block desert, including buried original terrain.

Reason: regenerating the1.21.10 noise terrain or shortening the course changes
actual landings. Review: exact vertical RLE reduces storage without dropping a
single saved block; only safe blockstates/container/spawner data is retained.
"""
from __future__ import annotations
import argparse,collections,functools,gzip,hashlib,json,sys,zipfile,zlib
from pathlib import Path
sys.dont_write_bytecode=True
from extract_party_geometry import AnvilWorld,AIR,REPLACEMENTS,OMIT_BLOCKS,supported_block_names,NbtReader
MINIMUM=(-83,-64,-2060);MAXIMUM=(-41,255,127)
FUNCTIONS=['rocket_launch','rocket_launch_rules','rocket_launch_start','rocket_launch_tick','rocket_launch_finish','rocket_launch_end','checkpoint','checkpoint2','give_ready_ups','ready_up_tellraw','minigame_points_ranking','4_4_all_tick','start_minigame','void_minigame','end_minigame']

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',type=Path,required=True);p.add_argument('--minecraft-sources',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('src/main/resources/pvp/party'));a=p.parse_args()
 w=AnvilWorld(a.world/'dimensions/new_dimensions/sky_dimension');w.chunk=functools.lru_cache(None)(w.chunk);supported=supported_block_names(a.minecraft_sources);palette=[];indices={};sections={};bes=[];replacements=collections.Counter()
 def state_index(s):
  s=dict(s);name=s['Name'];replacement=REPLACEMENTS.get(name)
  if replacement:s['Name']=replacement
  if s['Name']in OMIT_BLOCKS:raise ValueError('Executable block inRocketLaunch crop')
  if s['Name']not in supported:raise ValueError('Unsupported source block:'+s['Name'])
  key=json.dumps(s,sort_keys=True,separators=(',',':'))
  if key not in indices:indices[key]=len(palette);palette.append(s)
  return indices[key]
 for cx in range(MINIMUM[0]//16,MAXIMUM[0]//16+1):
  for cz in range(MINIMUM[2]//16,MAXIMUM[2]//16+1):
   c=w.chunk(cx,cz)
   for e in c.get('block_entities',[]):
    if all(MINIMUM[i]<=e[k]<=MAXIMUM[i]for i,k in enumerate(('x','y','z'))):
     if e['id']=='minecraft:chest':bes.append({'type':'chest','pos':[e['x'],e['y'],e['z']],'lootTable':e.get('LootTable'),'lootSeed':e.get('LootTableSeed',0),'items':e.get('Items',[])})
     elif e['id']=='minecraft:mob_spawner':
      fields={k:e[k]for k in ['Delay','MinSpawnDelay','MaxSpawnDelay','SpawnCount','MaxNearbyEntities','RequiredPlayerRange','SpawnRange']if k in e}
      bes.append({'type':'spawner','pos':[e['x'],e['y'],e['z']],'entityType':e.get('SpawnData',{}).get('entity',{}).get('id'),'fields':fields})
     else:raise ValueError('Unreviewed blockentity:'+e['id'])
   for sec in c.get('sections',[]):
    y=sec['Y'];raw=sec.get('block_states',{});pal=raw.get('palette',[])
    if y*16>MAXIMUM[1]or y*16+15<MINIMUM[1]or not pal:continue
    local=[-1 if s['Name']in AIR else state_index(s)for s in pal];bits=max(4,(len(pal)-1).bit_length());sections[(cx,cz,y)]=(local,raw.get('data',()),bits,64//bits,(1<<bits)-1,pal)
 a.output.mkdir(parents=True,exist_ok=True);(a.output/'geometry').mkdir(exist_ok=True);target=a.output/'geometry/rocket_launch.json.gz';counts=collections.Counter();run_count=0;nonair=0;digest=hashlib.sha256()
 header={'formatVersion':2,'runAxis':'y','encoding':'exact_vertical_rle','sourceId':423,'sourceDimension':'new_dimensions:sky_dimension','bounds':{'min':MINIMUM,'max':MAXIMUM},'palette':palette}
 with target.open('wb')as raw:
  with gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,compresslevel=9)as g:
   g.write((json.dumps(header,separators=(',',':'))[:-1]+',"runs":[').encode());first=True;buffer=[]
   for x in range(MINIMUM[0],MAXIMUM[0]+1):
    for z in range(MINIMUM[2],MAXIMUM[2]+1):
     begin=MINIMUM[1];prior=-1
     for y in range(MINIMUM[1],MAXIMUM[1]+2):
      value=-1
      if y<=MAXIMUM[1]:
       sec=sections.get((x//16,z//16,y//16))
       if sec:
        local,packed,bits,per,mask,pal=sec;i=((y&15)<<8)|((z&15)<<4)|(x&15);pid=0 if not packed else(packed[i//per]>>((i%per)*bits))&mask;value=local[pid]
        if value>=0:
         nonair+=1;counts[palette[value]['Name']]+=1
         if pal[pid]['Name']in REPLACEMENTS:replacements[pal[pid]['Name']]+=1
      if y==MINIMUM[1]:prior=value
      elif value!=prior:
       if prior>=0:
        row=[x-MINIMUM[0],begin-MINIMUM[1],z-MINIMUM[2],y-begin,prior];encoded=json.dumps(row,separators=(',',':'));digest.update(encoded.encode());buffer.append((''if first else',')+encoded);first=False;run_count+=1
        if len(buffer)>=4096:g.write(''.join(buffer).encode());buffer.clear()
       begin=y;prior=value
   if buffer:g.write(''.join(buffer).encode())
   g.write(('],"nonAirBlocks":'+str(nonair)+',"runCount":'+str(run_count)+',"runsSha256":'+json.dumps(digest.hexdigest())+'}').encode())
 entities=[]
 for path in (a.world/'dimensions/new_dimensions/sky_dimension/entities').glob('*.mca'):
  _,rx,rz,_=path.name.split('.')
  if not(MINIMUM[0]//512<=int(rx)<=MAXIMUM[0]//512 and MINIMUM[2]//512<=int(rz)<=MAXIMUM[2]//512):continue
  data=path.read_bytes()
  for slot in range(1024):
   off=int.from_bytes(data[slot*4:slot*4+3],'big')*4096
   if not off:continue
   length=int.from_bytes(data[off:off+4],'big');compression=data[off+4];payload=data[off+5:off+4+length]
   payload=zlib.decompress(payload)if compression==2 else gzip.decompress(payload)if compression==1 else payload
   for entity in NbtReader(payload).root().get('Entities',[]):
    pos=entity.get('Pos',[])
    if len(pos)!=3 or not all(MINIMUM[i]<=pos[i]<=MAXIMUM[i]+1 for i in range(3)):continue
    if entity.get('id')!='minecraft:chest_minecart':raise ValueError('Unreviewed RocketLaunch sceneentity')
    entities.append({'type':'chest_minecart','pos':pos,'rotation':entity.get('Rotation',[0,0]),'lootTable':entity.get('LootTable'),'lootSeed':entity.get('LootTableSeed',0),'items':entity.get('Items',[])})
 with zipfile.ZipFile(a.world/'datapacks/data.zip')as z:fs={n:z.read('data/mcp/function/'+n+'.mcfunction')for n in FUNCTIONS}
 rules={'sourceId':423,'sourceVersion':'1.21.10','functionSha256':{n:hashlib.sha256(b).hexdigest()for n,b in fs.items()},'functions':{n:b.decode()for n,b in fs.items()},'scene':{'bounds':header['bounds'],'nonAirBlocks':nonair,'runCount':run_count,'runsSha256':digest.hexdigest(),'stateCounts':dict(counts),'replacements':dict(replacements),'completeBuriedTerrain':True,'encoding':'ExactRLE, no changedblockor length/heightcompression;30,106,880-voxel sourcevolume; airimplicitinblank arena.'},'blockEntities':bes,'entities':entities,'rules':{'oneRocketFlightDuration':2,'sourceSpawn':[-62,138,120],'sourceAnchor':[-62,101,120],'courseDistance':2160,'sourceWorldBottom':-64,'sourceWorldTopExclusive':320,'sceneTopInclusive':255,'noSourceTimeLimit':True,'finishCount':'all initialparticipants;forfeit excludes leaver','score':'(max(0,117-floorZ)+(distance>=2160?floorY-196:0))*N+strictlylaterfinishers','backwards':'Beforecurrentdistancerefresh;floorMarkerZ-5<floorPlayerZ<=115','groundExceptions':['red_sandstone','light'],'sideClamp':[-80,-45],'sourceDisableElytraMovementCheck':True,'sourceIntroCompletion':True,'markerCreatedAtIntroTick':60},'adaptations':['All sourceblocks retained inexact43x320x2188crop, full2160+margin, uncompressedphysicalcoordinates.','Source iron_chain is sameblock renamedchain in1.21.1.','Never run sourceglobalfunctions/commands/NBT.','NativeFireworkItem andElytraphysics, no virtualboost or refill.','Per-player sourceequipment/effects/five-round results scoped tothissession.','Sourceprestartpracticeandground/far/backwardcompletionretained; sharedINTROcompletionhookpreservesspectatorsandabsolutecheckpointclock.']}
 (a.output/'rocket_launch_rules.json').write_text(json.dumps(rules,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'nonAir':nonair,'runs':run_count,'bytes':target.stat().st_size,'blockEntities':len(bes),'sha':digest.hexdigest()}))
if __name__=='__main__':main()
