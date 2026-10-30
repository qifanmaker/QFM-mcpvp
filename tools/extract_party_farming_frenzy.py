#!/usr/bin/env python3
"""Extract420's complete farm island, lower clone and exact native crop rules.

Reason: a mature-crop/native-MINED contest needs the original terraced plots,
barn, seasonal multipliers and59 AIR-replant operations. Review: preserve the
complete logical farm platform and ridge rather than the small mechanic box;
exclude the adjacent orange and jungle arenas. Never execute source commands.
"""
from __future__ import annotations

import argparse
import functools
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
from extract_party_geometry import AnvilWorld, extract, supported_block_names

MINIMUM = (-191,27,-290)
MAXIMUM = (-142,69,-251)
FUNCTIONS = ["farming_frenzy","farming_frenzy_start","farming_frenzy_tick","farming_frenzy_end",
             "4_4_all_tick","timer_decrement","last_timer_title","scoreboard_data","give_ready_ups",
             "ready_up_tellraw","placements","end_minigame","start_minigame","quickplayminigame",
             "replay_minigame","set_up_minigame"]
CROPS = [("wheat","wheat",2,"wheat",7,[1,2,4]),("melons","melon",6,"melon",None,[1,2]),
         ("beetroots","beetroots",1,"beetroot",3,[1,2,4]),("potatoes","potatoes",2,"potato",7,[1,2,4]),
         ("pumpkins","pumpkin",6,"pumpkin",None,[1,2]),("carrots","carrots",2,"carrot",7,[1,2,4]),
         ("netherWarts","nether_wart",1,"nether_wart",3,[1,2,4]),("cocoaBeans","cocoa",4,"cocoa_beans",2,[1,2])]


def operation(line):
    match = re.search(r"(?:^| run )(clone|fill|setblock) (.+)$",line)
    if not match:
        return None
    action, words = match[1], match[2].split()
    row = {"op":action,"from":list(map(int,words[:3]))}
    if action == "setblock":
        assert len(words)==4
        row["state"]=words[3]
    else:
        row["to"]=list(map(int,words[3:6]))
        if action == "clone":
            assert len(words)==9
            row["destination"]=list(map(int,words[6:9]))
        else:
            row["state"]=words[6]
            if len(words)>7:
                assert len(words)==9 and words[7]=="replace"
                row["replace"]=words[8]
    return row


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world",type=Path,required=True)
    parser.add_argument("--minecraft-sources",type=Path,required=True)
    parser.add_argument("--output",type=Path,default=Path("src/main/resources/pvp/party"))
    args=parser.parse_args()
    world=AnvilWorld(args.world)
    world.chunk=functools.lru_cache(None)(world.chunk)
    supported=supported_block_names(args.minecraft_sources)
    # Six1.21.1 crop registrations use BlockKeys instead of a literal string;
    # the common extractor's literal-register scan therefore misses them.
    # Resolve only keys actually passed to Blocks.register, never substitute.
    with zipfile.ZipFile(args.minecraft_sources) as minecraft:
        native=minecraft.read("net/minecraft/block/Blocks.java").decode()
        keys=minecraft.read("net/minecraft/block/BlockKeys.java").decode()
        for key,name in re.findall(r'RegistryKey<Block>\s+(\w+)\s*=\s*of\("([^"]+)"\)',keys):
            if re.search(r'register\(\s*BlockKeys\.'+re.escape(key)+r'\s*,',native):
                supported.add("minecraft:"+name)
    with zipfile.ZipFile(args.world/"datapacks/data.zip") as archive:
        raw={name:archive.read("data/mcp/function/"+name+".mcfunction") for name in FUNCTIONS}
        sources={name:value.decode() for name,value in raw.items()}
        setup=[op for line in sources["farming_frenzy"].splitlines() if "currentRule variables matches 2" in line and (op:=operation(line))]
        replant=[op for line in sources["farming_frenzy_tick"].splitlines() if (op:=operation(line))]
        assert len(setup)==15 and len(replant)==59
        banners=[]
        for cx in range(MINIMUM[0]//16,MAXIMUM[0]//16+1):
            for cz in range(MINIMUM[2]//16,MAXIMUM[2]//16+1):
                for entity in world.chunk(cx,cz).get("block_entities",[]):
                    if not all(MINIMUM[i]<=entity[k]<=MAXIMUM[i] for i,k in enumerate(("x","y","z"))):
                        continue
                    assert entity["id"]=="minecraft:banner",entity["id"]
                    banners.append({"position":[entity[k] for k in ("x","y","z")],
                                    "patterns":[{"pattern":p["pattern"],"color":p["color"]} for p in entity.get("patterns",[])]})
        assert len(banners)==13
        specs={"farming_frenzy":(MINIMUM,MAXIMUM),"farming_frenzy_template":((-183,28,-284),(-160,31,-259))}
        for name,(minimum,maximum) in specs.items():
            geometry=extract(world,name,{"min":minimum,"max":maximum,"functions":FUNCTIONS,"notes":[
                "Complete logical rectangular farm island with north/west ridges, barn/roof/ladder,13 banners, soul sand/cocoa logs and lower clone storage."
                if name=="farming_frenzy" else "Exact24x4x26 lower immutable clone; original rule2 copies all2496 cells including779 air toY35.",
                "The source farm platform is X=-190..-143,Z=-289..-252; the extra one-block outline preserves ridge/barn overhangs. Adjacent orange and jungle minigames are excluded.",
                "All19 decorative gravel cells retain their original Y34 dirt supports. Visible farm/ridges end atY55;11 functional southwest ridge boundary columns continue throughY69, retaining all154 barriers aboveY55.",
                "The original farm has no water blocks; do not invent irrigation or replace native growth with direct age changes.",
                "No source commands, raw entity NBT or executable block-entity data are imported. Banner patterns are restored through a separate safe whitelist."]},archive,supported)
            assert len(geometry["blocks"])==(9606 if name=="farming_frenzy" else 1717)
            encoded=json.dumps(geometry,ensure_ascii=False,separators=(",",":")).encode()
            target=args.output/"geometry"/(name+".json.gz");target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(gzip.compress(encoded,compresslevel=9,mtime=0))
            print(name,geometry["size"],len(geometry["blocks"]),"blocks",len(geometry["palette"]),"states")
    audit={"formatVersion":1,"sourceId":420,"sourceVersion":"1.21.10","dimension":"minecraft:overworld",
        "anchor":[-166,36,-271],"bounds":{"min":MINIMUM,"max":MAXIMUM},"spawn":[-152.5,36,-275.5],
        "sourceFunctionSha256":{n:hashlib.sha256(v).hexdigest() for n,v in raw.items()},"sourceFunctions":sources,
        "setupOperations":setup,"replantOperations":replant,"banners":sorted(banners,key=lambda b:b["position"]),
        "crops":[{"season":i+1,"objective":obj,"block":"minecraft:"+block,"points":points,"gift":"minecraft:"+gift,"matureAge":age,"giftTotals":totals}
                 for i,(obj,block,points,gift,age,totals) in enumerate(CROPS)],
        "timing":{"initialTimer":1800,"actualProcessedTicks":1799,"seasonInterval":200,"firstSeasonTick":200,
                  "seasonTicks":list(range(200,1800,200)),"pregrowthTicks":60,"pregrowthSpeed":"max(1800,450*initialPlayers)","playRandomTickSpeed":200},
        "nativeBreak":[{"block":"minecraft:"+block,"age":age} for _,block,_,_,age,_ in CROPS]
                      +[{"block":"minecraft:melon_stem","age":None},{"block":"minecraft:pumpkin_stem","age":None}],
        "rules":["Native Adventure iron hoe with exact maturity state predicates; ordinary stems may be broken without points, attached stems are not on the tool list.",
                 "Native MINED block-stat deltas drive points. Existing season doubles count before the material multiplier. Rewards are native inventory items only for exact source gift totals1/2/4 (fruit/cocoa1/2).",
                 "No block/entity tile drops: source manual rewards are not native crop drops. Kill scoped dropped hoes and seed/wart/cocoa items before giving rewards.",
                 "Harvest and timer decrement precede59 source-order AIR-replant operations; next season is selected after these, so boundary harvest uses prior season. Repeated seasons are legal.",
                 "The extra4_4_all_tick line repairs dirt beneath adventure players to default farmland after the core tick. Trampling is not mined-stat harvest.",
                 "Each complete farm section receives native random-position ticks; preserve60 pre-growth ticks and freeze the rest of intro, then200 while playing. Never increment age directly.",
                 "All players share the original spawn. Original player teams collisionRule=never; no invented private lanes or selling interface."],
        "compatibility":["Use1.21.1 CAN_BREAK/BlockPredicatesChecker state predicates and UnbreakableComponent(false), not the newer tooltip_display syntax.",
                         "Source global gamerules, objectives, teams, schedules and armor-stand season selection become scoped native mechanics; never execute these source command strings.",
                         "Full scene native terrain changes, including growth, gate changes and fruit landings, are tracked through PartyRound.tracksTerrainChanges.",
                         "Current matchmaking uses2..10 players; source also supports solo but its shared achievement/economy presentation is outside the five-game format."]}
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/"farming_frenzy_rules.json").write_text(json.dumps(audit,ensure_ascii=False,separators=(",",":"))+"\n")


if __name__=="__main__":
    main()
