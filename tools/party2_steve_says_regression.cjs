'use strict';

// Reason:418 requires real movement, armor inventory, player damage, note and
// sign clicks and native drop statistics across22 source actions. Review: own
// Fabric25642 only; console position fixtures are labeled and never award
// points or edit the module. No original function/command is executed.
const path=require('node:path');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const name=id=>`MyGoCheck${id}`;
GAMES.steve={source:418,players:4};
const TYPES=[['攻击另一名玩家',1],['潜行',2],['穿上',3],['跳跃',4],['看向天花板',5],['点击标牌一次',6],['把音符盒调音3次',7],['丢出石头',8],['丢出圆石',8],['丢出滴水石块',8],['站到桌子上',9],['混凝土',10],['羊毛',10],['保持不动',11]];
const BOOTS={'钻石靴':'diamond_boots','金靴子':'golden_boots','皮革靴':'leather_boots'};
const STONES={'石头':'stone','圆石':'cobblestone','滴水石块':'dripstone_block'};
const COLORS={'橙色':'orange_concrete','黄色':'yellow_concrete','红色':'red_concrete'};
function javaHash(uuid){const h=uuid.replaceAll('-','');let value=0;for(let i=0;i<4;i++)value^=parseInt(h.slice(i*8,(i+1)*8),16);return value|0;}

class SteveProbe extends PartyProbe {
  constructor(options={}){super({port:25642,runDir:options.runDir||path.join(__dirname,'../build/mygo-party2-steve-says')});this.actions=[];this.results={};this.infos=[];this.spawns=[];this.equipment=[];this.removals=[];this.destroyed=[];this.native=new Set();this.boundaries=new Set();this.ties=0;}
  connect(id){
    const bot=super.connect(id);this.results[id]=[];
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');
      const action=text.match(/第 (\d+)\/22 条指令\s*｜\s*(.*)/);
      if(id==='A'&&action){const instruction=action[2];const task=TYPES.find(([fragment])=>instruction.includes(fragment))?.[1];this.actions.push({number:Number(action[1]),task,instruction,at:Date.now()});this.log('sourceInstruction',this.actions.at(-1));}
      const result=text.match(/第 (\d+)\/22 条结果：\+(\d+)\s*｜累计 (\d+)(.*)/);
      if(result){const row={number:Number(result[1]),points:Number(result[2]),total:Number(result[3]),out:result[4].includes('出局'),at:Date.now()};this.results[id].push(row);this.log('sourceResult',{id,...row});}
    });
    bot._client.on('player_info',packet=>{for(const row of packet.data||[])if(row.player?.name==='Steve'){this.infos.push({id,at:Date.now(),...row});this.log('nativeHostInfo',{id,...row});}});
    bot._client.on('spawn_entity',packet=>this.spawns.push({id,at:Date.now(),...packet}));
    bot._client.on('entity_equipment',packet=>this.equipment.push({id,at:Date.now(),...packet}));
    bot._client.on('player_remove',packet=>this.removals.push({id,at:Date.now(),...packet}));
    bot._client.on('entity_destroy',packet=>this.destroyed.push({id,at:Date.now(),...packet}));
    return bot;
  }
  async condition(condition){const token=`P2_SS_${++this.sequence}`;await this.console(`execute ${condition} run say ${token}`,25);return this.serverLines.some(row=>row.text.includes(`[Server] ${token}`));}
  async nativeStat(id,objective,criterion){await this.console(`scoreboard objectives add ${objective} ${criterion}`);return this.condition(`if score ${name(id)} ${objective} matches 1..`);}
  async clickNative(id,point,count,hand=0){for(let i=0;i<count;i++){this.bots[id]._client.write('block_place',{hand,location:point,direction:4,cursorX:0,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence});await sleep(60);}}
  async movement(id){const b=this.bots[id];b.probeAllowMovement=true;b.physicsEnabled=true;await b.look(0,0,true);b.setControlState('forward',true);await sleep(400);b.setControlState('forward',false);await sleep(100);b.physicsEnabled=false;b.probeAllowMovement=false;}
  async ground(id,point){const b=this.bots[id];await this.teleport(id,point);b.probeAllowMovement=true;b.physicsEnabled=true;await sleep(300);b.physicsEnabled=false;b.probeAllowMovement=false;}
  lookNative(id,pitch){const b=this.bots[id];b._client.write('look',{yaw:0,pitch,onGround:true});b.entity.pitch=-pitch*Math.PI/180;this.log('nativePitchInput',{id,pitch});}
  async baseline(since){
    const anchor=this.bots.A.probePosition;const offset=new Vec3(anchor.x+658,anchor.y-33,anchor.z+22.5);this.source=(x,y,z)=>offset.offset(x,y,z);
    const host=this.infos.find(row=>row.id==='A'&&row.at>=since);this.check('Native PLAYER host profile is sent to the current arena viewer',!!host,host);
    this.check('Host fallback UUID selects1.21.1 wide Steve skin',((javaHash(host.uuid)%18)+18)%18===15,{uuid:host.uuid,hash:javaHash(host.uuid)});
    this.check('Host is omitted from the real online-player tab list',host.listed===0||host.listed===false,host);
    const spawn=this.spawns.find(row=>row.id==='A'&&row.objectUUID===host.uuid);const expected=this.source(-674.5,33.5,-22);
    this.check('Full native player model retains original position and facing',!!spawn&&new Vec3(spawn.x,spawn.y,spawn.z).distanceTo(expected)<.01&&spawn.yaw===-64,spawn,expected);
    this.host=spawn;this.hostProfile=host;
    const gear=this.equipment.find(row=>row.id==='A'&&row.entityId===spawn.entityId);
    this.check('Visible host holds native book and apple',!!gear&&gear.equipments.some(e=>e.item.itemId===this.bots.A.registry.itemsByName.book.id)&&gear.equipments.some(e=>e.item.itemId===this.bots.A.registry.itemsByName.apple.id),gear);
    this.check('Display NPC never registers an extra live server player',await this.condition('if entity @a[name=MyGoCheckA] unless entity @a[name=Steve]'));
    const banner=this.source(-663,40,-34);await this.pinChunk(banner);
    this.check('Original patterned banner keeps its actual native pattern NBT',await this.condition(`in pvp:arena if data block ${banner.x} ${banner.y} ${banner.z} patterns[{color:"black",pattern:"minecraft:stripe_top"}]`));
    this.marker=this.source(-679,32,-35);await this.pinChunk(this.marker);
    this.check('Complete original classroom foundation exists',await this.serverBlockIs(this.marker,'oak_planks'));
  }
  requestedItem(action,mapping){return Object.entries(mapping).find(([word])=>action.instruction.includes(word))?.[1];}
  async perform(action){
    this.check('Source action has one of eleven audited task types',Number.isInteger(action.task),action);
    const says=action.instruction.includes('史蒂夫说');
    const boots=action.task===3?this.requestedItem(action,BOOTS):null;
    const stone=action.task===8?this.requestedItem(action,STONES):null;
    const trap=(action.task===3&&says&&!this.bots.A.inventory.items().some(s=>s.name===boots))
      ||(action.task===8&&says&&!this.bots.A.inventory.items().some(s=>s.name===stone))
      ||(action.task===10&&action.instruction.includes('羊毛'));
    const obey=says&&!trap;
    this.log('nativeActionPlan',{...action,says,trap,obey,inventory:this.snapshot('A').inventory});
    const invalid=[];
    if(action.task===11&&!says)await Promise.all(['B','C'].filter(id=>this.bots[id].game.gameMode==='adventure').map(id=>this.movement(id)));
    if(action.task===6&&obey&&this.bots.B.game.gameMode==='adventure'){
      const point=this.source(-661,33,-29);await this.teleport('B',point.offset(-1,0,.5));await this.clickNative('B',point,2);invalid.push(['B','sign-overclick']);
    }
    if(action.task===7&&obey&&this.bots.C.game.gameMode==='adventure'){
      const point=this.source(-661,33,-29);await this.teleport('C',point.offset(-1,0,.5));await this.clickNative('C',point,4);invalid.push(['C','note-overtune']);
    }
    if(action.task===1&&obey){
      const a=this.source(-658,33,-22.5),d=a.offset(0,0,1.5);await this.teleport('A',a);await this.teleport('D',d);
      for(const id of ['A','D']){const b=this.bots[id],other=id==='A'?'D':'A';b.setQuickBarSlot(8);await this.wait(()=>!!b.players[name(other)]?.entity,'native player combat target',800);await b.lookAt(b.players[name(other)].entity.position.offset(0,1,0),true);b.attack(b.players[name(other)].entity);}
      this.native.add(1);
    }else if(action.task===2&&obey){
      for(const id of ['A','D'])this.bots[id]._client.write('entity_action',{entityId:this.bots[id].entity.id,actionId:0,jumpBoost:0});
      await sleep(220);for(const id of ['A','D'])this.bots[id]._client.write('entity_action',{entityId:this.bots[id].entity.id,actionId:1,jumpBoost:0});this.native.add(2);
    }else if(action.task===3&&obey){
      for(const id of ['A','D']){const b=this.bots[id];await b.equip(b.inventory.items().find(s=>s.name===boots),'feet');}this.native.add(3);
    }else if(action.task===4&&obey){
      await Promise.all(['A','D'].map(id=>this.jump(id)));this.native.add(4);
    }else if(action.task===5&&obey){
      // Explicit native look packets remain observable with passive fixture physics disabled.
      // Source floors -89.5degrees to-90; no console rotation or score is used.
      for(const id of ['A','D'])this.lookNative(id,-89.5);this.native.add(5);
    }else if((action.task===6||action.task===7)&&obey){
      const point=this.source(-666,33,-29);for(const id of ['A','D']){await this.teleport(id,point.offset(-1,0,.5));await this.clickNative(id,point,action.task===6?1:3,action.task===6&&id==='D'?1:0);}this.native.add(action.task);
    }else if(action.task===8&&obey){
      for(const id of ['A','D']){const b=this.bots[id];await b.tossStack(b.inventory.items().find(s=>s.name===stone));}this.native.add(8);
    }else if(action.task===9&&obey){
      await this.ground('A',this.source(-664.5,34.3,-31.5));await this.ground('D',this.source(-659.5,34.3,-31.5));this.native.add(9);
    }else if(action.task===10&&obey){
      const target=this.requestedItem(action,COLORS);for(const id of ['A','D']){const b=this.bots[id];await b.tossStack(b.inventory.items().find(s=>s.name===target));}this.native.add(10);
    }else if(action.task===11){if(!says)await Promise.all(['A','D'].map(id=>this.movement(id)));this.native.add(11);}
    await this.wait(()=>['A','D'].every(id=>this.results[id].some(r=>r.number===action.number)),`deadline scores source instruction${action.number}`,12000);
    for(const id of ['A','D']){const result=this.results[id].find(r=>r.number===action.number);this.check(`Instruction${action.number} ${id} native action/refraining earns source3or4 without elimination`,!result.out&&result.points>=3&&result.points<=4,{action,result});}
    if(['A','D'].every(id=>this.results[id].find(r=>r.number===action.number).points===4))this.ties++;
    for(const [id,boundary]of invalid){await this.wait(()=>this.results[id].some(r=>r.number===action.number),'wrong-count native result',1500);const result=this.results[id].find(r=>r.number===action.number);this.check(`${boundary} uses actual native input and eliminates at its deadline without points`,result.out&&result.points===0,result);this.boundaries.add(boundary);}
    for(const id of ['A','D'])this.lookNative(id,0);
    await this.teleport('A',this.source(-658,33,-22.5));await this.teleport('D',this.source(-658,33,-20.5));
  }
  async round(){
    this.actions=[];for(const id of ['A','B','C','D'])this.results[id]=[];
    const since=await this.start('steve');await this.baseline(since);
    for(let i=0;i<22;i++){await this.wait(()=>this.actions.length>i,`source instruction${i+1}`,12000);await this.perform(this.actions[i]);}
    this.check('Twenty-two instructions contain each native task exactly once per eleven-action cycle',[0,11].every(start=>new Set(this.actions.slice(start,start+11).map(a=>a.task)).size===11),this.actions.map(a=>a.task));
    await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'source22-action ranking',5000);
    await this.wait(()=>!!this.bots.A.currentWindow,'five-round series continues',20000);
    this.check('Five-round selection excludes the completed418 module',!this.menuChoices(this.bots.A.currentWindow).some(c=>c.source===418));
    this.check('Classroom is actually removed in its pinned loaded chunk',await this.waitServerBlock(this.marker,'air'));
    this.check('Host native player entity is removed at settlement',this.destroyed.some(r=>r.id==='A'&&r.entityIds.includes(this.host.entityId)));
    this.check('Host player profile is removed without ghost tab entries',this.removals.some(r=>r.id==='A'&&r.players.includes(this.hostProfile.uuid)));
    this.check('Temporary native desk interactions are removed',await this.condition('in pvp:arena unless entity @e[type=minecraft:interaction]'));
    await this.leaveAll();await this.unpinChunks();
  }
  async run(){
    for(let pass=0;pass<3;pass++){
      await this.round();this.log('nativeCoverage',{pass:pass+1,tasks:[...this.native].sort((a,b)=>a-b)});
      if(this.native.size===11&&this.boundaries.size===2)break;await sleep(5500);
    }
    this.check('All eleven task families exercised actual native controls across audited22-action rounds',this.native.size===11,{tasks:[...this.native]});
    this.check('Native excessive sign and note counts were both rejected',this.boundaries.size===2,{boundaries:[...this.boundaries]});
    this.check('Multiple valid players observed in one tick share the first-completion4-point reward',this.ties>0,{ties:this.ties});
  }
}
async function main(){const probe=new SteveProbe();let error;try{await probe.launch();await probe.connectAll('ABCD');await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});}const report=await probe.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={SteveProbe};
