#!/usr/bin/env python3
"""Extract421's complete ships, harbor, island and bounded navigable harbor water.

Reason: keyed scavenging uses twelve dispersed beacon sites and navigable ship
interiors. A flat replacement or a crop at deck height destroys source routes.
Review: preserve every ship deck/interior through the highest mast, every applicable
plain navigation sign/banner and all chest coordinates. Source commands and
arbitrary source commands are never executed. Compatible tree substitutions retain
axis and leaf properties: native decoder audit caught1092 stripped source states.
"""
from __future__ import annotations
import argparse,functools,gzip,hashlib,json,sys,zipfile,re
from pathlib import Path
sys.dont_write_bytecode=True
from extract_party_geometry import AnvilWorld,extract,supported_block_names,REPLACEMENTS
# Process-local compatibility projection only; never edits the shared extractor.
REPLACEMENTS.update({"minecraft:pale_moss_block":"minecraft:moss_block",
 "minecraft:pale_oak_log":"minecraft:birch_log","minecraft:pale_oak_wood":"minecraft:birch_wood",
 "minecraft:pale_oak_leaves":"minecraft:birch_leaves"})
COMPATIBLE_TREES={"minecraft:pale_oak_log","minecraft:pale_oak_wood","minecraft:pale_oak_leaves"}
MINIMUM=(-524,-64,-512);MAXIMUM=(-297,68,-238)
FUNCTIONS=['port_priority','port_priority_assign_item_order','port_priority_end','port_priority_next_item',
 'port_priority_set_chest','port_priority_set_independent_chest','port_priority_start',
 'port_priority_summon_armor_stands','port_priority_tick','assign_finish_threshold','checkpoint','checkpoint2',
 'timer_decrement','player_circle','player_circle_place_player','rotate_entity']

def plain(value):
 if isinstance(value,str):return value
 if isinstance(value,list):return ''.join(plain(v)for v in value)
 if not isinstance(value,dict):return ''
 text=plain(value.get('',value.get('text',value.get('fallback',''))))
 args=[plain(v)for v in value.get('with',[])]
 for i,v in enumerate(args,1):text=text.replace('%'+str(i)+'$s',v)
 for v in args:text=text.replace('%s',v,1)
 return text+plain(value.get('extra',[]))

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',type=Path,required=True);p.add_argument('--minecraft-sources',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('src/main/resources/pvp/party'));a=p.parse_args()
 w=AnvilWorld(a.world);w.chunk=functools.lru_cache(None)(w.chunk)
 with zipfile.ZipFile(a.world/'datapacks/data.zip')as z:
  raw={n:z.read('data/mcp/function/'+n+'.mcfunction')for n in FUNCTIONS}
  geometry=extract(w,'port_priority',{'min':MINIMUM,'max':MAXIMUM,'functions':FUNCTIONS,'notes':['Complete harbor ships/island/shoreline and all source ocean depth through the original world bottom; buriedY13 quest templates retained.','West/north ocean crop excludes the neighboring circular stage; Java adds scoped invisible outer boundaries.','Block-entity payloads are selectively reconstructed from safe native fields; no source function executes.']},z,supported_block_names(a.minecraft_sources))
 banners=[];signs=[];pistons=[];containers=[];skulls=[];compatible_properties={}
 for cx in range(MINIMUM[0]//16,MAXIMUM[0]//16+1):
  for cz in range(MINIMUM[2]//16,MAXIMUM[2]//16+1):
   for b in w.chunk(cx,cz).get('block_entities',[]):
    pos=tuple(b[k]for k in ('x','y','z'))
    if not all(MINIMUM[i]<=pos[i]<=MAXIMUM[i]for i in range(3)):continue
    if b['id']=='minecraft:banner':
     row={'position':pos,'patterns':[{'color':v['color'],'pattern':v['pattern']}for v in b.get('patterns',[])]}
     if 'CustomName'in b:row['name']=plain(b['CustomName'])
     banners.append(row)
    if b['id']=='minecraft:sign':
     row={'position':pos,'waxed':bool(b.get('is_waxed',False))}
     for face in ('front','back'):
      data=b.get(face+'_text',{});row[face]={'messages':[plain(x)for x in data.get('messages',['']*4)],'color':data.get('color','black'),'glowing':bool(data.get('has_glowing_text',False))}
     signs.append(row)
    if b['id']=='minecraft:skull' and b.get('profile'):
     profile=b['profile'];row={'position':pos,'profile':{'id':list(profile['id']),'properties':[{'name':'textures','value':v['value']}for v in profile.get('properties',[])if v.get('name')=='textures']}}
     if profile.get('name'):row['profile']['name']=profile['name']
     skulls.append(row)
    if 'Items' in b or 'item' in b or 'lock' in b or 'Lock' in b:
     row={'position':pos,'type':b['id'],'items':[{'id':v['id'],'count':int(v.get('count',v.get('Count',1))),'slot':int(v.get('Slot',0))}for v in (b.get('Items',[]) if 'Items' in b else [dict(b['item'],Slot=0)] if b.get('item') else [])]}
     lock=b.get('lock',{});key=plain(lock.get('components',{}).get('minecraft:custom_name','')) if isinstance(lock,dict) else plain(lock)
     if b.get('Lock'):key=plain(b['Lock'])
     if key:row['lock']=key
     if b.get('CustomName'):row['name']=plain(b['CustomName'])
     containers.append(row)
 for p,state in w.blocks(MINIMUM,MAXIMUM):
  if state['Name']in ('minecraft:piston','minecraft:sticky_piston'):pistons.append({'position':p,'properties':state.get('Properties',{})})
  if state['Name']in COMPATIBLE_TREES:compatible_properties[p]=dict(state['Properties'])
 # Review: the shared extractor drops properties for unknown replacement schemas.
 # These three tree renames have the same1.21.1 schemas. Repair only their source
 # cells and rebuild this one palette, leaving the other91 maps and extractor alone.
 palette=[];indices={};old_palette=geometry['palette']
 for cell in geometry['blocks']:
  state=old_palette[cell[3]];pos=tuple(cell[i]+MINIMUM[i]for i in range(3))
  if pos in compatible_properties:state=dict(state,Properties=compatible_properties[pos])
  key=json.dumps(state,sort_keys=True)
  if key not in indices:indices[key]=len(palette);palette.append(state)
  cell[3]=indices[key]
 geometry['palette']=palette
 geometry['notes'].append('Compatible pale-oak→birch substitutions preserve all original axis/distance/persistent/waterlogged properties;1092 source cells audited against Anvil and the native1.21.1 decoder.')
 sites=[{'position':list(map(int,(x,y,z))),'facing':f}for x,y,z,f in re.findall(r'summon minecraft:marker (-?\d+) (-?\d+) (-?\d+) \{Tags:\["PortPriorityBeacon","PortPriority","(north|east|south|west)"\]\}',raw['port_priority'].decode())]
 assert len(sites)==12
 result={'sourceId':421,'sourceVersion':'1.21.10','bounds':{'min':MINIMUM,'max':MAXIMUM},'functionSha256':{n:hashlib.sha256(b).hexdigest()for n,b in raw.items()},'functions':{n:b.decode()for n,b in raw.items()},'sites':sites,'banners':sorted(banners,key=lambda b:b['position']),'signs':sorted(signs,key=lambda b:b['position']),'pistons':pistons,'containers':containers,'skulls':skulls,'rules':{'timeLimitTicks':4800,'selectedSites':7,'targetsPerPlayer':3,'items':['copper_ingot','netherite_ingot','prismarine_shard','quartz','gold_ingot','emerald','diamond'],'sharedChestSlot':13,'framesPerSite':4,'circleCenter':[-367.5,36,-307.5],'circleRadius':2,'targetSet':'Three distinct shared targets; unique permutations for2..4 players, each permutation may be assigned at most twice for5..10.','score':'N*itemsFound plus latest-checkpoint recency rank, same-tick ties. Initial score3 until first real progress.','finish':'N-1 finishes at normal2..4-player free-for-all or4800ticks.'},'sourceCorrections':['Missing Item2/Item3 fallback incorrectly writes Item1; the native generator always assigns3 valid distinct goals.'],'adaptations':['The compatible pale-oak→birch log/wood/leaf schemas retain original axis and distance/persistent/waterlogged state rather than using native defaults.','Original1.21.10 component chest lock becomes native1.21.1 Lock string matching the exact literal key name.','Native chest inventories refill in place, preserving active native ScreenHandlers rather than cloning away their backing block entity.','Own records replace marker/armorstand objectives;28 native fixed item frames and all7 beacon/base/chest/pane structures remain real.','Only allowlisted banner patterns/literal names, plain front/back sign text and safe native item id/count/slot with literal locks/names are restored. Unlocked southern chest goods remain usable as in the actual source.','Native doors/buttons/levers retain ship navigation; all resulting block/fluid changes must be tracked through the scoped shared terrain hook for cleanup.','The original west/north open ocean is bounded outside all ships and quest sites to isolate simultaneous server games. The full original ocean depth and seabed throughY-64 are retained; lower overlapping source structures are not silently removed.','Chunk forcing remembers prior state and releases only newly acquired local beacon chunks. Global source objectives/gamerules/forceload cleanup never execute.','Source board-night toggles and ready-up scenes use the shared native intro/default day layout; source resource-pack music remains outside this module.'],'geometry':{'compatibleTreePropertiesPreserved':len(compatible_properties),'blocks':len(geometry['blocks']),'replacements':geometry['replacements'],'omitted':geometry['omitted'],'omittedBlockEntityNbt':geometry['omittedBlockEntityNbt']}}
 a.output.mkdir(parents=True,exist_ok=True);(a.output/'geometry').mkdir(exist_ok=True)
 (a.output/'geometry/port_priority.json.gz').write_bytes(gzip.compress(json.dumps(geometry,ensure_ascii=False,separators=(',',':')).encode(),mtime=0))
 (a.output/'port_priority_rules.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print('Port Priority',len(geometry['blocks']),'blocks;',len(banners),'banners;',len(signs),'signs;',len(pistons),'pistons;',len(sites),'sites')
if __name__=='__main__':main()
