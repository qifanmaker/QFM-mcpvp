#!/usr/bin/env python3
"""Extract418's complete classroom and audited native action rules.

Reason: Steve Says needs its enclosed classroom, all16 desks,12 dynamic slots
and79 decorative banners; flat reaction buttons discard native game inputs.
Review: source function text/NBT is evidence only. Copy block states and
explicitly allowlisted banner patterns/literal names, never source commands.
"""
from __future__ import annotations
import argparse,functools,gzip,hashlib,json,sys,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
from extract_party_geometry import AnvilWorld,extract,supported_block_names

FUNCTIONS=['endss','ssarmorputon','ssclickchat','ssendceiling','ssendclickchat','ssendcrouch',
 'ssenddesk','ssenddrop','ssenddropconcrete','ssendjump','ssendnoteblocks','ssendpunch',
 'ssendstaystill','ssnexttask','ssnexttask2','steve_says','steve_says_rules','steve_says_start','steve_says_tick']
MINIMUM=(-679,32,-35);MAXIMUM=(-640,43,-10)
DURATIONS=[40,30,40,30,60,60,80,50,90,40,40]
TASKS=['Punch another player','Crouch','Put on boots','Jump','Look at the ceiling',
       'Click the sign once','Tune a note block three times','Drop the named stone',
       'Stand on a desk','Drop matching concrete','Stay still']

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',type=Path,required=True);p.add_argument('--minecraft-sources',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('src/main/resources/pvp/party'));args=p.parse_args()
 world=AnvilWorld(args.world);world.chunk=functools.lru_cache(None)(world.chunk)
 with zipfile.ZipFile(args.world/'datapacks/data.zip') as z:
  raw={n:z.read('data/mcp/function/'+n+'.mcfunction') for n in FUNCTIONS}
  geometry=extract(world,'steve_says',{'min':MINIMUM,'max':MAXIMUM,'functions':FUNCTIONS,'notes':['Complete enclosed source classroom and foundation.','Banner payloads are separately allowlisted; source commands and entities never execute.']},z,supported_block_names(args.minecraft_sources))
 banners=[];slots=[]
 for cx in range(MINIMUM[0]//16,MAXIMUM[0]//16+1):
  for cz in range(MINIMUM[2]//16,MAXIMUM[2]//16+1):
   for b in world.chunk(cx,cz).get('block_entities',[]):
    pos=tuple(b[k] for k in ('x','y','z'))
    if not all(MINIMUM[i]<=pos[i]<=MAXIMUM[i] for i in range(3)):continue
    assert b['id']=='minecraft:banner',b
    row={'position':pos,'patterns':[{'color':x['color'],'pattern':x['pattern']} for x in b.get('patterns',[])]}
    if 'CustomName' in b:
     assert isinstance(b['CustomName'],str)
     row['name']=b['CustomName']
    banners.append(row)
 for pos,state in world.blocks(MINIMUM,MAXIMUM):
  if state['Name']=='minecraft:light' and state.get('Properties',{}).get('level')=='0' and -670<=pos[0]<=-644 and pos[1]==33 and -34<=pos[2]<=-11:slots.append(pos)
 banners.sort(key=lambda x:x['position']);slots.sort()
 assert len(geometry['blocks'])==4547 and len(banners)==79 and len(slots)==12
 result={'sourceId':418,'sourceVersion':'1.21.10','functionSha256':{n:hashlib.sha256(v).hexdigest() for n,v in raw.items()},'functions':{n:v.decode() for n,v in raw.items()},'bounds':{'min':MINIMUM,'max':MAXIMUM},'banners':banners,'dynamicSlots':slots,'desks':[[x,33,z] for x in (-665,-660,-655,-650) for z in (-13.5,-19.5,-25.5,-31.5)],'host':{'position':[-674.5,33.5,-22.0],'yaw':-90,'profile':'entity/player/wide/steve','mainhand':'book','offhand':'apple'},'rules':{'actions':22,'poolSize':11,'durations':DURATIONS,'tasks':TASKS,'typeWeights':'type1=3,type2=1; exactly action12 additionally type3=1','randomizer':'Global1..3; note task forces2 (tune only).','score':'Correct requested action3, first4; correct refraining3. Requested but not completed0. Wrong action eliminates, retained points rank normally.','earlyEnd':'Between actions: all eliminated, or sole survivor strictly ahead of every original participant. Otherwise continue to22 actions.','timing':'Initial20ticks;20tick setup and20tick gap around each action; full source cycle ends at about2020ticks.'},'sourceCorrections':['False ceiling command teleports to old unrelated coordinates and forgets spectator; eliminate within this arena.','Return immediately after end; source ssnexttask continues and schedules another task after endss removes objectives.','Cancel all owned scheduled phases, including armor callback omitted by source cleanup.','Desk InteractionEntity has no callback in source; keep its native right-click consumption, completion is physical OnGround+trapdoor only.'],'adaptations':['Scoped player statistics deltas, effects, entities, collisions and cleanup replace global objectives/selectors/world gamerules.','Same-tick valid first completions share the4-point bonus instead of source selector iteration order.','1.21.1 lacks Mannequin: send an owned native PLAYER display with deterministic wide/steve fallback, book and apple, at original position/yaw, without registering a live player.','Lightning is an owned cosmetic native bolt for elimination feedback; no unrelated world or player damage.','The source sends only setup dialogue before ready-up. Existing Party intro replaces ready-up; all22 actions and40ticks of per-action pacing remain.','Original solo score thresholds/team-board achievements and source resource-pack sounds are not an additional action mode.'],'geometry':{'blocks':len(geometry['blocks']),'replacements':geometry['replacements'],'omitted':geometry['omitted'],'omittedBlockEntityNbt':geometry['omittedBlockEntityNbt'],'restoredBannerPayloads':len(banners)}}
 args.output.mkdir(parents=True,exist_ok=True);(args.output/'geometry').mkdir(exist_ok=True)
 (args.output/'geometry/steve_says.json.gz').write_bytes(gzip.compress(json.dumps(geometry,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
 (args.output/'steve_says_rules.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print('Steve Says:',len(geometry['blocks']),'blocks;',len(banners),'banners;',len(slots),'dynamic slots;',len(FUNCTIONS),'functions')
if __name__=='__main__':main()
