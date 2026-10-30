'use strict';

// Reason:417 must use real Adventure block items/golden-pickaxe mining, preserve
// preview editing, exact air comparison and native reset-button cooldown. Review:
// own25640 runtime only; position/time/clone fixtures are labeled. A builds all
// four random models with actual packets before later threshold clone fixtures.
// This is headless protocol validation, not a rendered-client acceptance claim.
const fs=require('node:fs'),path=require('node:path'),zlib=require('node:zlib'),crypto=require('node:crypto');
const mineflayer=require('mineflayer');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const name=id=>`MyGoCheck${id}`;
const pos=bot=>new Vec3(bot.probePosition.x,bot.probePosition.y,bot.probePosition.z);
const COLORS=['red_concrete','orange_concrete','yellow_concrete','lime_concrete','light_blue_concrete','blue_concrete','magenta_concrete','gray_concrete'];
const PLAN=['speed','rlgl','rainbow','runner','dropper'];
GAMES.speed={source:417,minimumPlayers:2,maximumPlayers:10};
const uuid=id=>{const b=crypto.createHash('md5').update(`OfflinePlayer:${name(id)}`).digest();b[6]=(b[6]&15)|48;b[8]=(b[8]&63)|128;const h=b.toString('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;};

class SpeedBuildersProbe extends PartyProbe {
  constructor(){
    super({port:25640,runDir:path.join(__dirname,'../build/mygo-party2-speed-builders')});
    this.completed={};this.seats={};this.rows=new Map();this.finalRows=[];this.finalHeaders=0;this.planned=false;
    this.source=(x,y,z)=>new Vec3(x+449,y+67,z+228); // Isolated region0 center(88,101,88), source anchor(-361,34,-140).
    this.templates=Array.from({length:28},(_,index)=>{
      const j=JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(this.root,`src/main/resources/pvp/party/geometry/speed_builders_pattern_${index+1}.json.gz`))));
      const cells=Array(144).fill('air');for(const [x,y,z,p]of j.blocks)cells[(y*6+z)*6+x]=j.palette[p].Name.replace('minecraft:','');
      return {id:index+1,cells};
    });
  }
  connect(id){
    const bot=/^[A-F]$/.test(id)?super.connect(id):this.connectExtra(id);
    this.completed[id]=0;
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');const build=text.match(/已复制建筑\s+(\d+)\/4/);
      if(build){this.completed[id]=Number(build[1]);this.log('buildCompleted',{id,count:this.completed[id]});}
      if(id!=='A')return;
      const header=text.match(/第\s+(\d+)\/5\s+轮排名/);
      if(header){this.round=Number(header[1]);this.rows.set(this.round,[]);if(this.planned)this.game=PLAN[this.round]||null;}
      if(this.rows.has(this.round)&&/第 \d+ 名.*MyGoCheck.*本轮/.test(text))this.rows.get(this.round).push(text);
      if(text.includes('Party 2 五轮总排名'))this.finalHeaders++;
      if(this.finalHeaders&&/第 \d+ 名.*MyGoCheck.*累计.*分/.test(text))this.finalRows.push(text);
    });
    return bot;
  }
  connectExtra(id){
    if(!/^[GH]$/.test(id))throw Error('Only G/H additional crowd clients are needed');
    const bot=mineflayer.createBot({host:'127.0.0.1',port:this.port,username:name(id),version:'1.21.1',auth:'offline'});
    this.bots[id]=bot;bot.probeId=id;bot.probeBlockUpdates=new Map();let authoritative;
    const write=bot._client.write.bind(bot._client);bot._client.write=(kind,packet)=>{
      if(authoritative&&(kind==='position'||kind==='position_look')){bot.entity?.position.set(authoritative.x,authoritative.y,authoritative.z);return;}
      return write(kind,packet);
    };
    bot._client.prependListener('position',packet=>{
      const before=authoritative||bot.entity?.position||new Vec3(0,0,0);
      const rel=a=>typeof packet.flags==='object'?packet.flags[a]:!!(packet.flags&{x:1,y:2,z:4}[a]);
      authoritative=new Vec3(packet.x+(rel('x')?before.x:0),packet.y+(rel('y')?before.y:0),packet.z+(rel('z')?before.z:0));bot.probePosition=authoritative;
      setTimeout(()=>{if(!this.closing)write('position',{x:authoritative.x,y:authoritative.y,z:authoritative.z,onGround:true});},100);
    });
    const world=packet=>{const state=packet.worldState||packet;bot.probeWorld=state.name??state.worldName??packet.worldName;};
    bot._client.on('login',world);bot._client.on('respawn',world);
    bot._client.on('set_title_text',packet=>{if(JSON.stringify(packet).includes('开始'))bot.probeStartedAt=Date.now();});
    bot.on('spawn',()=>{bot.physicsEnabled=false;bot.probeSpawned=true;});
    bot.on('messagestr',message=>this.chats.push({id,text:String(message).replace(/§./g,''),at:Date.now()}));
    bot.on('windowOpen',window=>{if(this.game&&JSON.stringify(window.title).includes('Party'))void this.vote(id,this.game).catch(e=>{this.voteError=e;});});
    bot.on('error',error=>this.log('clientError',{id,error:error.stack}));bot.on('kicked',reason=>{bot.probeKicked=reason;this.log('kicked',{id,reason});});
    bot.on('end',()=>{bot.probeSpawned=false;});return bot;
  }
  async condition(value){const token=`P2_SB_${++this.sequence}`;await this.console(`execute ${value} run say ${token}`,20);return this.serverLines.some(row=>row.text.includes(`] ${token}`));}
  async sprint(ticks){const start=this.serverLines.length;this.log('fixtureNote',{description:`Advance${ticks} actual native ticks and await completion.`});this.rawConsole(`tick sprint ${ticks}`);await this.wait(()=>this.serverLines.slice(start).some(row=>/Sprint completed/i.test(row.text)),'tick sprint completes',60000);await sleep(50);}
  preview(id){const seat=this.seats[id];return this.source(seat%2===0?-371:-348,34,-173+15*Math.floor(seat/2));}
  target(id){return this.preview(id).offset(-8,0,0);}
  button(id){const seat=this.seats[id];return this.source(seat%2===0?-373:-350,35,-166+15*Math.floor(seat/2));}
  inferSeats(ids){
    // Match regions are spaced512; first decimal source spawn identifies anchor offset for every subsequent isolated match.
    const first=pos(this.bots[ids[0]]),region=Math.round((first.x-88)/512),regionZ=Math.round((Math.min(...ids.map(id=>pos(this.bots[id]).z))-62)/512);
    this.source=(x,y,z)=>new Vec3(x+449+region*512,y+67,z+228+regionZ*512);
    for(const id of ids){const p=pos(this.bots[id]);this.seats[id]=Array.from({length:10},(_,seat)=>seat).find(seat=>p.distanceTo(this.source(seat%2===0?-372:-349,34,-166+15*Math.floor(seat/2)))<.1);}
    this.check('Every active player uses a compact exact source seat',ids.every(id=>Number.isInteger(this.seats[id]))&&new Set(ids.map(id=>this.seats[id])).size===ids.length,{seats:this.seats});
  }
  async hubBaseline(ids){
    this.log('fixtureNote',{description:'Normalize every queue entrant through the real /hub handler before capturing inventory/world snapshots; a prior interrupted test may have saved an Arena spectator.'});
    for(const id of ids)await this.console(`tag ${name(id)} add p2_sb_hub_baseline`);
    await this.console('execute as @a[tag=p2_sb_hub_baseline] run hub');await this.console('tag @a remove p2_sb_hub_baseline');
    await this.wait(()=>ids.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'clean Overworld client baseline',15000);
    for(const id of ids)this.check(`${id} real server dimension starts in Overworld`,await this.condition(`if entity @a[name=${name(id)},nbt={Dimension:"minecraft:overworld"}]`));
    await sleep(150);
  }
  async startSubset(ids){
    await this.hubBaseline(ids);
    this.rows=new Map();this.round=0;for(const id of ids)this.completed[id]=0;
    this.game='speed';this.voteError=null;const since=Date.now();
    for(const id of ids){this.bots[id].probeStartedAt=0;await this.console(`tag ${name(id)} add p2_sb_join`);}
    this.log('fixtureNote',{description:`A single native execute-as invokes real queue handlers for${ids.length} clients, preserving the actual source player-count branch.`});
    await this.console('execute as @a[tag=p2_sb_join] run pvp join minecraftparty');await this.console('tag @a remove p2_sb_join');
    if(ids.length<4)await this.chat(ids[0],'/pvp start');
    await this.wait(()=>{if(this.voteError)throw this.voteError;return this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮选择：.*原版编号\s+417/.test(row.text));},'417 chosen by actual menu votes',90000);
    const selected=Date.now();for(const id of ids)this.bots[id].probeStartedAt=0;
    await this.wait(()=>ids.every(id=>this.bots[id].probeStartedAt>=selected),'417 enters actual PLAY',60000);
    await this.wait(()=>ids.every(id=>this.bots[id].inventory.items().some(item=>item.name==='golden_pickaxe')),'native full inventory received',2500);
    this.game=null;this.inferSeats(ids);return since;
  }
  async model(id){
    const base=this.preview(id);await this.pinChunk(base);await this.pinChunk(base.offset(5,3,5));
    await this.pinChunk(this.target(id));await this.pinChunk(this.target(id).offset(5,3,5));
    await this.wait(()=>!!this.block(id,base)&&!!this.block(id,base.offset(5,3,5)),'all native model cells loaded',4000);
    // Source completion is after this tick's preview pass; the next model is
    // intentionally restored on the next tick. Completion chat is not a block
    // synchronization barrier. Advance actual ticks, then verify all144 client
    // cells against the authoritative world before building anything.
    if(this.completed[id]>0)await this.sprint(2);
    let cells;const deadline=Date.now()+4000;
    do{
      cells=this.readCells(id,base);
      if((await this.nativeGridDifferences(base,cells)).length===0)break;
      if(Date.now()>deadline)throw Error('Client preview did not synchronize to the actual144-cell native model');
      await sleep(70);
    }while(true);
    this.check('Every sampled preview cell matches the authoritative server',true,{id,build:this.completed[id]+1,cells:144});
    this.check('Original model contains concrete plus exact air only',cells.every(c=>c.name==='air'||COLORS.includes(c.name)),cells);
    const matches=this.templates.filter(t=>{
      const map=new Map(),seen=new Map();
      for(let i=0;i<144;i++){
        if((t.cells[i]==='air')!==(cells[i].name==='air'))return false;
        if(t.cells[i]==='air')continue;
        if(map.has(t.cells[i])&&map.get(t.cells[i])!==cells[i].name)return false;
        if(seen.has(cells[i].name)&&seen.get(cells[i].name)!==t.cells[i])return false;
        map.set(t.cells[i],cells[i].name);seen.set(cells[i].name,t.cells[i]);
      }return true;
    });
    this.check('Native preview is an exact original template with bijective color mapping',matches.length>0,{matches:matches.map(m=>m.id),nonAir:cells.filter(c=>c.name!=='air').length});
    this.log('sourceModel',{id,build:this.completed[id]+1,templateCandidates:matches.map(t=>t.id),cells});
    return {cells,candidates:matches.map(t=>t.id)};
  }
  async reach(id,point){
    const target=point.x>=this.preview(id).x?this.preview(id):this.target(id),localX=point.x-target.x;
    const side=localX<0?point.x-1.5:localX>5?point.x+2:localX<3?target.x-1.1:target.x+7.1;
    await this.teleport(id,new Vec3(side,target.y,point.z+.5));
  }
  async place(id,point,color,reference=null,direction=1){
    const bot=this.bots[id];await this.reach(id,point);bot.setQuickBarSlot(COLORS.indexOf(color)+1);await sleep(30);
    this.log('nativePlace',{id,point,color,reference:reference||point.offset(0,-1,0),direction});
    let observed=false;const placed=(_before,current)=>{if(current?.position?.equals(point)&&current.name===color)observed=true;};bot.on('blockUpdate',placed);
    bot._client.write('block_place',{hand:0,location:reference||point.offset(0,-1,0),direction,cursorX:.5,cursorY:direction===1?1:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence});
    try{await this.wait(()=>observed||this.block(id,point)?.name===color,`${id} native ${color} placement`,2500);}finally{bot.removeListener('blockUpdate',placed);}
  }
  async mine(id,point){
    const bot=this.bots[id];await this.reach(id,point);bot.setQuickBarSlot(0);await sleep(30);
    await this.wait(()=>!!this.block(id,point),'native mined cell loaded',2500);
    const block=this.block(id,point);this.log('nativeMine',{id,point,material:block.name});await bot.dig(block,true);
  }
  async fillNatively(id,model){
    const before=this.completed[id],base=this.target(id),desired=new Map(model.cells.filter(c=>c.name!=='air').map(c=>[`${c.x},${c.y},${c.z}`,c]));
    const temporary=new Map();
    const sentinel=model.cells.find(c=>c.name==='air'&&c.y===0)||model.cells.find(c=>c.name==='air'&&c.y>0&&desired.has(`${c.x},${c.y-1},${c.z}`));
    let sentinelPlaced=false;
    if(sentinel.y===0){const p=base.offset(sentinel.x,sentinel.y,sentinel.z);await this.place(id,p,'red_concrete');temporary.set(`${sentinel.x},${sentinel.y},${sentinel.z}`,p);sentinelPlaced=true;}
    for(const cell of model.cells.filter(c=>c.name!=='air')) {
      for(let y=0;y<cell.y;y++) {
        const key=`${cell.x},${y},${cell.z}`,p=base.offset(cell.x,y,cell.z);
        if(!desired.has(key)&&!temporary.has(key)) {await this.place(id,p,'red_concrete');temporary.set(key,p);}
      }
      await this.place(id,base.offset(cell.x,cell.y,cell.z),cell.name);
      if(!sentinelPlaced&&cell.x===sentinel.x&&cell.y===sentinel.y-1&&cell.z===sentinel.z){const p=base.offset(sentinel.x,sentinel.y,sentinel.z);await this.place(id,p,'red_concrete');temporary.set(`${sentinel.x},${sentinel.y},${sentinel.z}`,p);sentinelPlaced=true;}
    }
    if(temporary.size)this.check('Extra support in original air cells prevents early completion',this.completed[id]===before,{temporary:temporary.size});
    for(const point of temporary.values())await this.mine(id,point);
    try{await this.wait(()=>this.completed[id]===before+1,'real native model completes including required air',5000);}
    catch(error){await this.dumpGrid(id,this.target(id),'target',model.cells);await this.dumpGrid(id,this.preview(id),'preview',model.cells);throw error;}
    this.check('Exactly one model advances after native construction',this.completed[id]===before+1);
  }
  async fixtureClone(id){
    const before=this.completed[id],p=this.preview(id),t=this.target(id);
    if(before>0)await this.sprint(2);
    this.log('fixtureNote',{description:'Controlled threshold/lifecycle fixture clones the already natively validated144-cell preview into the source target, including air.',id});
    await this.console(`execute in pvp:arena run clone ${p.x} ${p.y} ${p.z} ${p.x+5} ${p.y+3} ${p.z+5} ${t.x} ${t.y} ${t.z}`);
    await this.wait(()=>this.completed[id]===before+1,'fixture advances one source model',3000);
  }
  readCells(id,base){const cells=[];for(let y=0;y<4;y++)for(let z=0;z<6;z++)for(let x=0;x<6;x++)cells.push({x,y,z,name:this.block(id,base.offset(x,y,z))?.name||'air'});return cells;}
  async nativeGridDifferences(base,cells){
    const token=`P2_SB_GRID_${++this.sequence}`,since=this.serverLines.length;
    this.log('authoritativeGridCheck',{token,base,cells:cells.length});
    cells.forEach((cell,index)=>{const p=base.offset(cell.x,cell.y,cell.z);this.rawConsole(`execute in pvp:arena unless block ${p.x} ${p.y} ${p.z} minecraft:${cell.name} run say ${token}_${index}`);});
    this.rawConsole(`say ${token}_DONE`);await this.wait(()=>this.serverLines.slice(since).some(row=>row.text.includes(`] ${token}_DONE`)),'all144 authoritative block checks complete',5000);
    const re=new RegExp(`\\] ${token}_(\\d+)$`);return this.serverLines.slice(since).flatMap(row=>{const m=row.text.match(re);return m?[Number(m[1])]:[];});
  }
  async dumpGrid(id,base,kind,expected){
    const actual=this.readCells(id,base),mismatch=await this.nativeGridDifferences(base,actual),token=`P2_SB_DUMP_${++this.sequence}`,since=this.serverLines.length;
    for(const index of mismatch){const cell=actual[index],p=base.offset(cell.x,cell.y,cell.z);for(const block of ['air',...COLORS])this.rawConsole(`execute in pvp:arena if block ${p.x} ${p.y} ${p.z} minecraft:${block} run say ${token}_${index}_${block}`);}
    this.rawConsole(`say ${token}_DONE`);await this.wait(()=>this.serverLines.slice(since).some(row=>row.text.includes(`] ${token}_DONE`)),'actual grid diagnostic completes',5000);
    for(const row of this.serverLines.slice(since)){const match=row.text.match(new RegExp(`\\] ${token}_(\\d+)_([a-z_]+)$`));if(match)actual[Number(match[1])].name=match[2];}
    this.log('nativeGridDump',{id,kind,base,actual,differences:actual.flatMap((cell,index)=>cell.name===expected[index].name?[]:[{index,point:base.offset(cell.x,cell.y,cell.z),expected:expected[index].name,actual:cell.name}])});
  }
  async effect(id,kind,amp){return this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:${kind}"${amp==null?'':`,amplifier:${amp>127?amp-256:amp}b`}}]}]`);}
  async stats(){const p=path.join(this.runDir,'config/pvp/stats.json');return fs.existsSync(p)?JSON.parse(fs.readFileSync(p,'utf8')):{};}

  async nativeFourAndTournament(){
    this.planned=true;const baseline=await this.stats(),since=await this.startSubset(['A','B','C','D']);
    for(const id of ['A','B','C','D']){
      this.check(`${id} has source JumpBoost2 and Weakness255`,await this.effect(id,'jump_boost',2)&&await this.effect(id,'weakness',255));
      this.check(`${id} receives golden pickaxe plus eight replenished64 stacks`,this.bots[id].inventory.items().filter(item=>COLORS.includes(item.name)&&item.count===64).length===8&&this.bots[id].inventory.items().some(item=>item.name==='golden_pickaxe'));
    }
    this.check('Four original source seats create exactly16 FlowerBlocker hitboxes',await this.condition('in pvp:arena if entity @e[tag=party_speed_flower_blocker]'));
    await this.console('scoreboard objectives add p2_sb_count dummy');
    await this.console('execute in pvp:arena store result score blockers p2_sb_count if entity @e[tag=party_speed_flower_blocker]');
    this.check('Source conditional blockers retain the exact4-player count',await this.condition('if score blockers p2_sb_count matches 16'));
    const decorative=this.source(-386,39,-162),button=this.button('A');for(const p of [decorative,button])await this.pinChunk(p);
    this.check('Source banner patterns survive version conversion',await this.condition(`in pvp:arena if data block ${decorative.x} ${decorative.y} ${decorative.z} patterns[0]`));
    const sign=button.offset(1,0,0);
    this.check('Reset button remains native oak wall button',await this.serverBlockIs(button,'oak_button[face=wall,facing=north,powered=false]'));
    const signRead=this.serverLines.length;await this.console(`execute in pvp:arena run data get block ${sign.x} ${sign.y} ${sign.z} front_text.messages`);
    this.check('Source reset sign retains its visible arrow label',this.serverLines.slice(signRead).some(row=>row.text.includes('Reset ➝')));
    const first=await this.model('A'),other=await this.model('B');
    this.check('Every racer begins with the same original randomized model',JSON.stringify(first.cells)===JSON.stringify(other.cells));
    const base=this.target('A'),extra=first.cells.find(c=>c.y===0&&c.name==='air')||first.cells.find(c=>c.y===0);
    const extraPoint=base.offset(extra.x,0,extra.z);
    await this.console('execute in pvp:arena run gamerule doTileDrops true');
    await this.place('A',extraPoint,'red_concrete');await this.mine('A',extraPoint);await sleep(100);
    this.check('Source native golden-pickaxe break suppresses round tile drops only',!await this.condition(`in pvp:arena positioned ${extraPoint.x} ${extraPoint.y} ${extraPoint.z} if entity @e[type=item,distance=..3,nbt={Item:{id:"minecraft:red_concrete"}}]`));
    await this.console('execute in pvp:arena store result score globalDrops p2_sb_count run gamerule doTileDrops');
    this.check('Scoped block loot policy leaves global gamerule true',await this.condition('if score globalDrops p2_sb_count matches 1'));
    await this.outsideLootControl();
    await this.previewEditing(first);
    await this.componentBoundaries(extraPoint);
    await this.buttonChecks(extraPoint);
    const cleanup=await this.createCleanupWitness();
    const modelSets=[];
    for(let build=0;build<4;build++){
      const model=build===0?first:await this.model('A');modelSets.push(model.candidates);
      await this.fillNatively('A',model);
      this.check(`A native build${build+1} preserves unfinished B's first model`,this.completed.B===0);
    }
    const chooseDistinct=(sets,index=0,used=new Set())=>index===sets.length||sets[index].some(id=>!used.has(id)&&chooseDistinct(sets,index+1,new Set([...used,id])));
    this.check('Four source model selections can be matched to four distinct original templates',chooseDistinct(modelSets),modelSets);
    this.check('One of four finishers enters spectator without ending round',this.bots.A.game.gameMode==='spectator'&&!this.rows.has(1));
    this.check('Actual initially-air placement outside target clear-box survives individual finish',await this.serverBlockIs(cleanup,'red_concrete'));
    this.check('Finished builder inventory and JumpBoost are cleared',!this.bots.A.inventory.items().some(item=>COLORS.includes(item.name)||item.name==='golden_pickaxe')&&!await this.effect('A','jump_boost'));
    for(let i=0;i<4;i++)await this.fixtureClone('B');
    this.check('Two finishers still remain below four-player N-1 threshold',!this.rows.has(1));
    for(let i=0;i<4;i++)await this.fixtureClone('C');
    await this.wait(()=>this.rows.get(1)?.length===4,'three finishers produce source result',4000);
    this.check('Native417 first-round placement converts to4/3/2/1 tournament points',[['A',1,4],['B',2,3],['C',3,2],['D',4,1]].every(([id,place,points])=>this.rows.get(1).some(text=>new RegExp(`第 ${place} 名.*${name(id)}.*本轮 \\+${points}`).test(text))),this.rows.get(1));
    this.check('411-style source effects/entities do not leak from417',!await this.effect('D','jump_boost')&&!await this.effect('D','weakness')&&!await this.condition('in pvp:arena if entity @e[tag=party_speed_flower_blocker]'));
    await this.continueTournament(since,baseline,cleanup);
  }
  async previewEditing(model){
    const solid=model.cells.find(c=>c.y===0&&c.name!=='air'),air=model.cells.find(c=>c.y===0&&c.name==='air')||model.cells.find(c=>c.y===1&&c.name==='air'),p=this.preview('A').offset(solid.x,solid.y,solid.z);
    const before=this.completed.A;
    this.log('fixtureNote',{description:'Native mining/placement on the original preview is legal; per-tick restore must repair it without awarding completion.'});
    await this.console(`scoreboard objectives add p2_sb_preview minecraft.mined:minecraft.${solid.name}`);
    await this.console(`scoreboard players set ${name('A')} p2_sb_preview 0`);
    await this.mine('A',p);await sleep(120);
    this.check('Preview remains natively mineable and automatically restored',await this.condition(`if score ${name('A')} p2_sb_preview matches 1..`)&&await this.serverBlockIs(p,solid.name));
    const point=this.preview('A').offset(air.x,air.y,air.z);await this.reach('A',point);this.bots.A.setQuickBarSlot(1);await sleep(30);
    await this.console('scoreboard objectives add p2_sb_use minecraft.used:minecraft.red_concrete');await this.console(`scoreboard players set ${name('A')} p2_sb_use 0`);
    this.bots.A._client.write('block_place',{hand:0,location:point.offset(0,-1,0),direction:1,cursorX:.5,cursorY:1,cursorZ:.5,insideBlock:false,sequence:++this.sequence});
    await sleep(180);
    this.check('Preview air cells accept real block placement then return to source air',await this.condition(`if score ${name('A')} p2_sb_use matches 1..`)&&await this.serverBlockIs(point,'air')&&this.completed.A===before);
  }
  async componentBoundaries(point){
    this.log('fixtureNote',{description:'Explicit in-lane support fixture isolates original Adventure CAN_PLACE_ON exclusion of black concrete and non-concrete support.'});
    const p=this.target('A').offset(-1,0,-1),floor=p.offset(0,-1,0);await this.pinChunk(floor);
    for(const support of ['black_concrete','stone']) {
      await this.console(`execute in pvp:arena run setblock ${floor.x} ${floor.y} ${floor.z} ${support}`);await this.reach('A',p);this.bots.A.setQuickBarSlot(1);await sleep(40);
      this.bots.A._client.write('block_place',{hand:0,location:floor,direction:1,cursorX:.5,cursorY:1,cursorZ:.5,insideBlock:false,sequence:++this.sequence});await sleep(120);
      this.check(`Native Adventure block item refuses source-excluded ${support}`,await this.serverBlockIs(p,'air'));
    }
    await this.console(`execute in pvp:arena run setblock ${floor.x} ${floor.y} ${floor.z} white_concrete_powder`);
    await this.place('A',p,'red_concrete');this.check('Native source white-powder support accepts concrete',await this.serverBlockIs(p,'red_concrete'));
    await this.mine('A',p);await this.console(`execute in pvp:arena run setblock ${floor.x} ${floor.y} ${floor.z} cherry_planks`);
    this.check('Every PLAY tick replenishes all8 stacks to64',this.bots.A.inventory.items().filter(item=>COLORS.includes(item.name)&&item.count===64).length===8);
  }
  async buttonChecks(point){
    const button=this.button('A');await this.place('A',point,'red_concrete');
    this.log('fixtureNote',{description:'Slow this isolated runtime to10TPS while checking native30tick button cooldown; actual native states/sound and one-tick fill remain unchanged.'});
    await this.console('tick rate 10');await this.teleport('A',button.offset(.5,-1,-2));this.bots.A.setQuickBarSlot(0);await sleep(40);
    this.bots.A._client.write('block_place',{hand:1,location:button,direction:2,cursorX:.5,cursorY:.5,cursorZ:0,insideBlock:false,sequence:++this.sequence});await sleep(130);
    this.check('Off-hand use does not falsely trigger the native reset button',await this.serverBlockIs(button,'oak_button[powered=false]')&&await this.serverBlockIs(point,'red_concrete'));
    this.bots.A._client.write('entity_action',{entityId:this.bots.A.entity.id,actionId:0,jumpBoost:0});await sleep(40);await this.click('A',button);
    this.bots.A._client.write('entity_action',{entityId:this.bots.A.entity.id,actionId:1,jumpBoost:0});await sleep(130);
    this.check('Sneaking while holding the pickaxe does not falsely clear builds',await this.serverBlockIs(button,'oak_button[powered=false]')&&await this.serverBlockIs(point,'red_concrete'));
    await this.click('A',button);await sleep(130);
    this.check('Native reset button becomes powered',await this.serverBlockIs(button,'oak_button[powered=true]'));
    this.check('Audited reset clears target and restores preview on following source tick',await this.serverBlockIs(point,'air')&&this.completed.A===0);
    await this.place('A',point,'red_concrete');await this.teleport('A',button.offset(.5,-1,-2));await this.click('A',button);await sleep(120);
    this.check('Repeated powered click does not clear a newly placed target block',await this.serverBlockIs(button,'oak_button[powered=true]')&&await this.serverBlockIs(point,'red_concrete'));
    const deadline=Date.now()+5000;while(Date.now()<deadline&&await this.serverBlockIs(button,'oak_button[powered=true]'))await sleep(100);
    this.check('Native oak button releases after30tick cooldown',await this.serverBlockIs(button,'oak_button[powered=false]'));
    await this.click('A',button);await sleep(160);this.check('Next native button rising edge clears the target again',await this.serverBlockIs(point,'air'));
    await this.console('tick rate 20');
  }
  async outsideLootControl(){
    if(!this.bots.E)this.connect('E');await this.wait(()=>this.bots.E.probeSpawned,'outside control player arrives',25000);
    this.log('fixtureNote',{description:'Outside nonparticipant E mines the same material in Overworld Adventure using a native CAN_BREAK pickaxe. Hub logic forces Adventure every tick, so this control retains the real native item permission while proving unrelated loot is unaffected.'});
    const p=new Vec3(102,80,100),floor=new Vec3(100,79,100);
    await this.console('execute in minecraft:overworld run gamerule doTileDrops true');
    await this.console('item replace entity MyGoCheckE hotbar.0 with golden_pickaxe[can_break={predicates:[{blocks:"minecraft:red_concrete"}]}]');
    this.bots.E.setQuickBarSlot(0);
    await this.console('execute in minecraft:overworld run tp MyGoCheckE 100.5 80.0 100.5');
    await this.wait(()=>!!this.bots.E.blockAt(p),'outside fixture chunk actually arrives before setblock',10000);
    await this.console(`execute in minecraft:overworld run setblock ${p.x} ${p.y} ${p.z} red_concrete`);
    await this.console(`execute in minecraft:overworld run setblock ${floor.x} ${floor.y} ${floor.z} barrier`);
    await this.wait(()=>this.bots.E.blockAt(p)?.name==='red_concrete'&&this.bots.E.heldItem?.name==='golden_pickaxe','outside native block/tool loaded',5000);
    this.log('nativeOutsideMine',{id:'E',point:p});await this.bots.E.dig(this.bots.E.blockAt(p),true);
    await sleep(150);
    this.check('Outside native block break is acknowledged by the server',await this.condition('in minecraft:overworld if block 102 80 100 air'));
    this.check('Outside native block break still creates its concrete item',await this.condition('in minecraft:overworld positioned 102 80 100 if entity @e[type=item,distance=..10,nbt={Item:{id:"minecraft:red_concrete"}}]'));
    await this.console(`execute in minecraft:overworld run setblock ${floor.x} ${floor.y} ${floor.z} air`);
    await this.console('execute in minecraft:overworld positioned 102 80 100 run kill @e[type=item,distance=..5]');await this.chat('E','/hub');
  }
  async createCleanupWitness(){
    const support=this.target('A').offset(6,0,0),p=support.offset(1,0,0),bot=this.bots.A;
    await this.pinChunk(p);this.check('Cleanup witness starts in real source air',await this.serverBlockIs(p,'air'));
    this.log('fixtureNote',{description:'A console concrete support inside the8x8 clear-box allows one actual native side placement into a source-air aisle outside both model boxes. Individual completion removes the support; only onPlaced tracking can later clean the surviving real block.'});
    await this.console(`execute in pvp:arena run setblock ${support.x} ${support.y} ${support.z} red_concrete`);
    await this.teleport('A',p.offset(.5,0,-1.8));bot.setQuickBarSlot(1);await sleep(40);
    bot._client.write('block_place',{hand:0,location:support,direction:5,cursorX:1,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence});await sleep(150);
    this.check('Native side placement records an initially-air cell outside fixed target bounds',await this.serverBlockIs(p,'red_concrete'));
    return p;
  }
  async waitRound(round){await this.wait(()=>this.rows.get(round)?.length===4,`round${round} result rows`,5000);}
  async started(round){
    const source=GAMES[PLAN[round-1]].source;
    const selected=()=>this.chats.find(row=>row.id==='A'&&new RegExp(`第\\s+${round}/5\\s+轮选择：.*原版编号\\s+${source}`).test(row.text));
    await this.wait(()=>{if(this.voteError)throw this.voteError;return selected();},`round${round} selects next distinct game`,90000);
    await this.wait(()=>['A','B','C','D'].every(id=>this.bots[id].probeStartedAt>=selected().at),`round${round} actual PLAY`,60000);
  }
  async tag(ids){await this.console('tag @a remove p2_sb_finish');for(const id of ids)await this.console(`tag ${name(id)} add p2_sb_finish`);}
  async continueTournament(since,baseline,placed){
    // SELECT follows cleanup but precedes the next map's BUILD. Checking after
    // RLGL has spawned can observe its legitimate geometry at the same cell.
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/Party 2 · 第 2\/5 轮：/.test(row.text)),'second-round selection after417 cleanup',40000);
    this.check('A newly placed initially-air cell clears before the next map is built',await this.serverBlockIs(placed,'air'));
    await this.started(2);
    let p=pos(this.bots.A);await this.tag(['A','B','C','D']);await this.console(`execute in pvp:arena as @a[tag=p2_sb_finish] run tp @s ${p.x.toFixed(8)} ${p.y.toFixed(8)} ${(p.z-145.5).toFixed(8)}`);await this.waitRound(2);
    await this.started(3);p=pos(this.bots.A);await this.tag(['B','C','D']);await this.console(`execute in pvp:arena as @a[tag=p2_sb_finish] run tp @s ${p.x.toFixed(8)} ${(p.y-27).toFixed(8)} ${p.z.toFixed(8)}`);await this.waitRound(3);
    await this.started(4);await this.tag(['B','C','D']);await this.console('execute as @a[tag=p2_sb_finish] run damage @s 1000 minecraft:fall');await this.waitRound(4);
    await this.started(5);const starts=['A','B','C','D'].map(id=>pos(this.bots[id])),x=starts.reduce((n,p)=>n+p.x,0)/4,z=starts.reduce((n,p)=>n+p.z,0)/4;
    await this.tag(['A','B','C','D']);await this.console(`execute in pvp:arena as @a[tag=p2_sb_finish] run tp @s ${x.toFixed(8)} -62.75 ${z.toFixed(8)}`);await this.sprint(200);await this.waitRound(5);
    await this.wait(()=>this.finalHeaders===1&&this.finalRows.length===4,'five-round final ranking',35000);
    const totals={A:20,B:17,C:16,D:15};for(const [index,id]of ['A','B','C','D'].entries())this.check(`${id} cumulative total includes417 plus four later modes`,this.finalRows.some(t=>new RegExp(`第 ${index+1} 名.*${name(id)}.*累计 ${totals[id]} 分`).test(t)),this.finalRows);
    await this.wait(()=>['A','B','C','D'].every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'actual final hub return',35000);
    await this.wait(()=>['A','B','C','D'].every(id=>this.bots[id].inventory.items().some(item=>item.name==='compass')),'hub compass restored',12000);
    const deadline=Date.now()+25000;let stats;do{stats=await this.stats();if(['A','B','C','D'].every(id=>(stats[uuid(id)]?.matches||0)===(baseline[uuid(id)]?.matches||0)+1))break;await sleep(100);}while(Date.now()<deadline);
    for(const id of ['A','B','C','D']){
      this.check(`${id} persists exactly one match across five rounds`,stats[uuid(id)]?.matches===(baseline[uuid(id)]?.matches||0)+1);
      this.check(`${id} receives one final feedback link`,this.chats.filter(row=>row.id===id&&row.at>=since&&row.text.includes('qfmaker.com/blackboard/mcserverfb')).length===1);
    }
    this.check('Five-round series produces one final result',this.rows.size===5&&this.finalHeaders===1);
    this.planned=false;await this.unpinChunks();
  }
  async fixtureTournament(){
    this.planned=true;const baseline=await this.stats(),since=await this.startSubset(['A','B','C','D']);
    await this.console('scoreboard objectives add p2_sb_count dummy');
    const cleanup=await this.createCleanupWitness();
    this.log('fixtureNote',{description:'The separate70-check trace already validates all four real native constructions. This rerun uses synchronized native clone fixtures only to finish417 and test its remaining tournament/threshold lifecycle.'});
    for(const id of ['A','B','C']){
      for(let i=0;i<4;i++)await this.fixtureClone(id);
      if(id!=='C')this.check(`${id} finisher remains below source four-player N-1 threshold`,!this.rows.has(1));
    }
    await this.wait(()=>this.rows.get(1)?.length===4,'N-1 synchronized fixtures settle417',4000);
    this.check('Source417 placement converts to4/3/2/1 tournament points',[['A',1,4],['B',2,3],['C',3,2],['D',4,1]].every(([id,place,points])=>this.rows.get(1).some(text=>new RegExp(`第 ${place} 名.*${name(id)}.*本轮 \\+${points}`).test(text))),this.rows.get(1));
    this.check('Source417 clears effects/entities before later rounds',!await this.effect('D','jump_boost')&&!await this.effect('D','weakness')&&!await this.condition('in pvp:arena if entity @e[tag=party_speed_flower_blocker]'));
    await this.continueTournament(since,baseline,cleanup);
  }
  async timeoutTie(){
    const since=await this.startSubset(['A','B']);
    this.log('fixtureNote',{description:'Both two-player builders are positioned at source spawns, then one execute-as clones their own preview/target in the same actual tick; this isolates checkpoint tie order.'});
    await this.console(`tag ${name('A')} add p2_sb_same`);await this.console(`tag ${name('B')} add p2_sb_same`);
    await this.console('execute in pvp:arena as @a[tag=p2_sb_same] at @s run clone ~1 ~ ~-7 ~6 ~3 ~-2 ~-7 ~ ~-7');
    await this.wait(()=>this.completed.A===1&&this.completed.B===1,'same-tick completion of both builders',3000);
    await this.sprint(3400);this.check('Original180second game remains open before3600tick limit',!this.rows.has(1));
    await this.sprint(200);await this.wait(()=>this.rows.get(1)?.length===2,'source3600tick timeout',4000);
    this.check('Same-tick checkpoint completions remain tied at timeout',['A','B'].every(id=>this.rows.get(1).some(text=>new RegExp(`第 1 名.*${name(id)}.*1 / 4.*本轮 \\+2`).test(text))),this.rows.get(1));
    await this.leaveAll();await sleep(5500);
    const second=await this.startSubset(['A','B']);for(let i=0;i<4;i++)await this.fixtureClone('A');
    await this.wait(()=>this.rows.get(1)?.length===2,'one finisher ends actual two-player round',4000);
    this.check('Two-player N-1 threshold ends on exactly one finisher',this.completed.A===4&&this.completed.B===0&&this.chats.some(r=>r.id==='A'&&r.at>=second&&/第 1 名.*MyGoCheckA.*本轮 \+2/.test(r.text)));
    await this.leaveAll();await sleep(5500);
  }
  async crowdThreshold(){
    for(const id of ['E','F','G','H'])if(!this.bots[id])this.connect(id);
    await this.wait(()=>['A','B','C','D','E','F','G','H'].every(id=>this.bots[id].probeSpawned),'eight actual protocol players available',30000);
    await this.startSubset(['A','B','C','D','E','F','G','H']);
    await this.console('execute in pvp:arena store result score blockers p2_sb_count if entity @e[tag=party_speed_flower_blocker]');
    this.check('Eight-player source condition creates exactly32 FlowerBlockers',await this.condition('if score blockers p2_sb_count matches 32'));
    await this.chat('H','/hub');await this.wait(()=>this.bots.H.probeWorld==='minecraft:overworld','one crowd player forfeits to hub',10000);
    this.check('Forfeit restores its effects and original compass',!await this.effect('H','jump_boost')&&!await this.effect('H','weakness')&&this.bots.H.inventory.items().some(item=>item.name==='compass'));
    await this.console('execute in pvp:arena store result score blockers p2_sb_count if entity @e[tag=party_speed_flower_blocker]');
    const removed=this.seats.H%2===0?2:6;
    this.check('Forfeit removes only the blockers assigned to its actual source seat',await this.condition(`if score blockers p2_sb_count matches ${32-removed}`),{seat:this.seats.H,removed});
    for(const id of ['A','B','C','D','E'])for(let i=0;i<4;i++)await this.fixtureClone(id);
    this.check('Five finishers remain below original8-player N-2 threshold',!this.rows.has(1)&&this.completed.F===0&&this.completed.G===0);
    for(let i=0;i<4;i++)await this.fixtureClone('F');
    await this.wait(()=>this.rows.get(1)?.length===7,'six finishers end the original8-player source branch',5000);
    this.check('Initial8-player N-2 threshold survives a scoped forfeit',this.completed.F===4&&this.completed.G===0&&this.rows.get(1).length===7);
    this.check('All owned blockers are removed on crowd result',!await this.condition('in pvp:arena if entity @e[tag=party_speed_flower_blocker]'));
    await this.leaveAll();await this.unpinChunks();
  }
  async run(){await this.nativeFourAndTournament();await sleep(5500);await this.timeoutTie();await this.crowdThreshold();}
  async failurePlayers(){
    for(const id of Object.keys(this.bots))if(this.bots[id].probeSpawned){
      this.log('failurePlayerClient',this.snapshot(id));
      for(const field of ['Dimension','Pos','playerGameType','Health','DeathTime'])await this.console(`data get entity ${name(id)} ${field}`,0);
    }
  }
}

async function main(){const probe=new SpeedBuildersProbe();let error;try{await probe.launch();await probe.connectAll('ABCD');if(process.argv.includes('--tournament-only'))await probe.fixtureTournament();else if(process.argv.includes('--tournament-boundary-only')){await probe.fixtureTournament();await sleep(5500);await probe.timeoutTie();await probe.crowdThreshold();}else if(process.argv.includes('--boundary-only')){await probe.console('scoreboard objectives add p2_sb_count dummy');await probe.timeoutTie();await probe.crowdThreshold();}else await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});await probe.failurePlayers().catch(e=>probe.log('failureDiagnostics',{error:e.stack}));}const report=await probe.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={SpeedBuildersProbe};
