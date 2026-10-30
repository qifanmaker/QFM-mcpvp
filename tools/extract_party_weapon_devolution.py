#!/usr/bin/env python3
"""Extract source 410 arena, four air-inclusive tower clones and audited rules.

Reason: the active weapon_devolution entry differs from obsolete gladiators;
scoped native combat needs the actual five weapon stages, four delayed gates
and original laser/snowball commands without executing global source selectors.
Review: leave existing geometry untouched, hash every dependency, whitelist
only literal source displays, and retain omitted decorative NBT as metadata.
"""
from __future__ import annotations
import argparse,functools,gzip,hashlib,json,re,sys,zipfile
from pathlib import Path
sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld,extract,supported_block_names
FUNCTIONS=['weapon_devolution','weapon_devolution_rules','weapon_devolution_start',
'weapon_devolution_tick','weapon_devolution_set_up_bars','weapon_devolution_release_players','end_gladiators']
COMMON=['checkpoint','checkpoint2','assign_finish_threshold','player_circle','player_circle_place_player','rotate_entity']
SENTRY=['shoot_arrow_1','add_proj_motion','trig/cos_rx','trig/sin_rx','trig/cos_ry','trig/sin_ry']
SPECS={
 'weapon_devolution_arena':{'min':(-295,17,-418),'max':(-212,50,-335),'functions':FUNCTIONS,
 'notes':['Complete source arena walls, doors and playable floor; no obsolete gladiators rules executed.','Four upper towers are cloned separately with Y+72, including air.']},
 'weapon_devolution_tower_1':{'min':(-291,-21,-414),'max':(-275,17,-398),'functions':['weapon_devolution_set_up_bars'],'notes':['Exact first clone source, translation [0,72,0]; all destination air must be copied.']},
 'weapon_devolution_tower_2':{'min':(-232,-21,-414),'max':(-216,15,-398),'functions':['weapon_devolution_set_up_bars'],'notes':['Exact second clone source, translation [0,72,0].']},
 'weapon_devolution_tower_3':{'min':(-232,-21,-355),'max':(-216,15,-339),'functions':['weapon_devolution_set_up_bars'],'notes':['Exact third clone source, translation [0,72,0].']},
 'weapon_devolution_tower_4':{'min':(-291,-21,-355),'max':(-275,15,-339),'functions':['weapon_devolution_set_up_bars'],'notes':['Exact fourth clone source, translation [0,72,0].']}}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',type=Path,required=True);p.add_argument('--minecraft-sources',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('src/main/resources/pvp/party'));a=p.parse_args()
 world=AnvilWorld(a.world);world.chunk=functools.lru_cache(None)(world.chunk)
 with zipfile.ZipFile(a.world/'datapacks/data.zip') as pack:
  raw={f'data/mcp/function/{n}.mcfunction':pack.read(f'data/mcp/function/{n}.mcfunction') for n in FUNCTIONS+COMMON}
  raw.update({f'data/sentry/function/{n}.mcfunction':pack.read(f'data/sentry/function/{n}.mcfunction') for n in SENTRY})
  maps={n:extract(world,n,s,pack,supported_block_names(a.minecraft_sources)) for n,s in SPECS.items()}
 text=raw['data/mcp/function/weapon_devolution_set_up_bars.mcfunction'].decode();bars=[]
 for line in text.splitlines():
  if not line.startswith('summon block_display '):continue
  m=re.match(r'summon block_display (-?\d+) (-?\d+) (-?\d+) .*Properties:\{(.*?)\}',line);assert m,line
  properties=dict(re.findall(r'(north|south|east|west):"(true|false)"',m[4]));assert len(properties)==4
  bars.append({'position':[int(m[1])+.5,int(m[2]),int(m[3])+.5],'properties':properties})
 assert len(bars)==48,len(bars)
 a.output.mkdir(parents=True,exist_ok=True);(a.output/'geometry').mkdir(exist_ok=True)
 for n,j in maps.items():
  payload=json.dumps(j,ensure_ascii=False,separators=(',',':')).encode();(a.output/'geometry'/(n+'.json.gz')).write_bytes(gzip.compress(payload,compresslevel=9,mtime=0));print(n,len(j['blocks']),j['replacements'])
 audit={'sourceId':410,'formatVersion':1,'sourceFunctionSha256':{n:hashlib.sha256(b).hexdigest() for n,b in raw.items()},
 'functions':{n:b.decode() for n,b in raw.items()},'bars':bars,'rules':{'durationTicks':2400,'weaponStages':['laser','netherite_axe','trident','wooden_sword','sharp_snowballs'],'maximumUpgradesPerTick':1,'laserCooldownTicks':19,'laserSpeed':10,'laserArrowDamage':1.25,'snowballSupplyTicks':30,'snowballMaximum':2,'snowArrowPierceLevel':100,'gateLayerTicks':[16,32,48],'towerTranslation':[0,72,0]},
 'adaptations':['Only native entities and players belonging to this match are affected.','Source global nearest snowball/arrow ownership is replaced by an exact one-to-one projectile pair.','Source trig floor differences and native arrow damage/motion are preserved; original global command blocks are not run.','Free-for-all five-round server series; source Doubles remains unexposed.'],
 'geometry':{n:{'bounds':j['bounds'],'blocks':len(j['blocks']),'replacements':j['replacements'],'omittedBlockEntityNbt':j['omittedBlockEntityNbt']} for n,j in maps.items()}}
 (a.output/'weapon_devolution_rules.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()
