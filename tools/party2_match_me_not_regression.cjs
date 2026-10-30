'use strict';
// Reason:422 requires real Q/Ctrl-Q items, hidden native selection and physical
// piston motion over eight internal rounds. Review: owned25658 runtime only;
// tick-rate/sprint/queue fixtures are explicit, no source global function runs.
const path=require('node:path');
const mineflayer=require('mineflayer');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const name=id=>`MyGoCheck${id}`;
const ITEMS=['brick','iron_ingot','gold_ingot','netherite_ingot','nether_brick','copper_ingot','diamond'];
const LABELS=['Bricks','Iron','Gold','Netherite','Nether Bricks','Copper','Diamond'];
const COLORS=['blue','red','lime','yellow','cyan','magenta','brown','green','purple','gray'];
const BUILD=['bricks','iron_block','gold_block','netherite_block','nether_bricks','copper_block','diamond_block'];
GAMES.matchnot={source:422,minimumPlayers:2,maximumPlayers:10};

class MatchMeNotProbe extends PartyProbe {
  constructor(options={}){
    super({port:options.port||25658,runDir:options.runDir||path.join(__dirname,'../build/mygo-party2-match-me-not')});
    this.internal={};this.chosen={};this.points={};this.settled=0;this.particles=[];this.blockActions=[];
  }
  connect(id){
    const bot=/^[A-F]$/.test(id)?super.connect(id):this.connectExtra(id);
    this.internal[id]=0;this.chosen[id]=0;this.points[id]=0;
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');const r=text.match(/选材 (\d+)\/8：/);
      if(r){this.internal[id]=Number(r[1]);this.chosen[id]=0;this.log('internalRound',{id,round:this.internal[id]});}
      const c=text.match(/已选择：(.+)$/);if(c){this.chosen[id]=LABELS.indexOf(c[1])+1;this.log('nativeChoice',{id,choice:this.chosen[id]});}
      const score=text.match(/累计 (\d+)\/8 分/);if(score){this.points[id]=Number(score[1]);this.log('uniqueScore',{id,points:this.points[id]});}
      const end=text.match(/第 (\d+)\/8 次选材已结算/);if(end&&id==='A')this.settled=Number(end[1]);
    });
    bot._client.on('position',packet=>{if(Number.isFinite(packet.yaw))bot.mmnYaw=packet.yaw;});
    bot._client.on('world_particles',packet=>this.particles.push({id,at:Date.now(),packet}));
    bot._client.on('block_action',packet=>this.blockActions.push({id,at:Date.now(),packet}));
    return bot;
  }
  connectExtra(id){
    if(!/^[G-J]$/.test(id))throw Error('Crowd clients are G..J');
    const bot=mineflayer.createBot({host:'127.0.0.1',port:this.port,username:name(id),version:'1.21.1',auth:'offline'});
    this.bots[id]=bot;bot.probeId=id;bot.probeBlockUpdates=new Map();let authoritative;
    const write=bot._client.write.bind(bot._client);bot._client.write=(kind,packet)=>{if(authoritative&&(kind==='position'||kind==='position_look')){bot.entity?.position.set(authoritative.x,authoritative.y,authoritative.z);return;}return write(kind,packet);};
    bot._client.prependListener('position',packet=>{const p=authoritative||bot.entity?.position||new Vec3(0,0,0),r=a=>typeof packet.flags==='object'?packet.flags[a]:!!(packet.flags&{x:1,y:2,z:4}[a]);authoritative=new Vec3(packet.x+(r('x')?p.x:0),packet.y+(r('y')?p.y:0),packet.z+(r('z')?p.z:0));bot.probePosition=authoritative;setTimeout(()=>{if(!this.closing)write('position',{x:authoritative.x,y:authoritative.y,z:authoritative.z,onGround:true});},100);});
    const world=packet=>{const state=packet.worldState||packet;bot.probeWorld=state.name??state.worldName??packet.worldName;};bot._client.on('login',world);bot._client.on('respawn',world);
    bot._client.on('set_title_text',packet=>{if(JSON.stringify(packet).includes('开始')){bot.probeStartedAt=Date.now();if(this.slowPlay)this.rawConsole('tick rate 2');}});
    bot.on('spawn',()=>{bot.physicsEnabled=false;bot.probeSpawned=true;});bot.on('messagestr',message=>this.chats.push({id,text:String(message).replace(/§./g,''),at:Date.now()}));
    bot.on('windowOpen',window=>{if(this.game&&JSON.stringify(window.title).includes('Party'))void this.vote(id,this.game).catch(e=>{this.voteError=e;});});
    bot.on('error',error=>this.log('clientError',{id,error:error.stack}));bot.on('end',()=>{bot.probeSpawned=false;});return bot;
  }
  async condition(value){const token=`P2_MMN_${++this.sequence}`;await this.console(`execute ${value} run say ${token}`,15);return this.serverLines.some(row=>row.text.includes(`] ${token}`));}
  async sprint(ticks){const since=this.serverLines.length;this.log('fixtureNote',{description:`Advance${ticks} actual native ticks.`});this.rawConsole(`tick sprint ${ticks}`);await this.wait(()=>this.serverLines.slice(since).some(row=>/Sprint completed/i.test(row.text)),'native tick sprint completes',60000);await sleep(25);}
  async hub(ids){for(const id of ids)await this.console(`tag ${name(id)} add p2_mmn_hub`);await this.console('execute as @a[tag=p2_mmn_hub] run hub');await this.console('tag @a remove p2_mmn_hub');await this.wait(()=>ids.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'clean hub baseline',15000);}
  async startSubset(ids,slow=false){
    await this.hub(ids);this.game='matchnot';this.voteError=null;this.settled=0;this.activeIds=[...ids];this.slowPlay=slow;const since=Date.now();
    for(const id of ids){this.internal[id]=0;this.chosen[id]=0;this.points[id]=0;this.bots[id].probeStartedAt=0;await this.console(`tag ${name(id)} add p2_mmn_join`);}
    await this.console('execute as @a[tag=p2_mmn_join] run pvp join minecraftparty');await this.console('tag @a remove p2_mmn_join');if(ids.length<4)await this.chat(ids[0],'/pvp start');
    await this.wait(()=>{if(this.voteError)throw this.voteError;return this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮选择：.*原版编号\s+422/.test(r.text));},'422 chosen by native menu',90000);
    const selected=Date.now();for(const id of ids)this.bots[id].probeStartedAt=0;
    if(slow)await this.auditIntro(ids,since);
    if(slow){for(const id of ids)this.bots[id]._client.once('set_title_text',()=>{});this.startSlowListener=packet=>{if(JSON.stringify(packet).includes('开始'))this.rawConsole('tick rate 2');};this.bots.A._client.on('set_title_text',this.startSlowListener);}
    await this.wait(()=>ids.every(id=>this.bots[id].probeStartedAt>=selected),'422 enters actual PLAY',60000);this.game=null;
    await this.inventory(ids,1);
    if(this.startSlowListener){this.bots.A._client.removeListener('set_title_text',this.startSlowListener);this.startSlowListener=null;}
    this.slowPlay=false;this.spawns={};
    for(const id of ids)this.spawns[id]=new Vec3(...['x','y','z'].map(k=>this.bots[id].probePosition[k]));
    const mean=k=>ids.reduce((n,id)=>n+this.spawns[id][k],0)/ids.length;
    this.center=new Vec3(88+Math.round((mean('x')-88)/512)*512,101,88+Math.round((mean('z')-88)/512)*512);
    this.source=(x,y,z)=>this.center.offset(x-26,y-2,z-533);
    this.seats={};const radius=ids.length>=9?12:10;
    for(const id of ids)this.seats[id]=Array.from({length:ids.length},(_,seat)=>seat).find(seat=>{const a=seat*Math.floor(360/ids.length)*Math.PI/180;return this.spawns[id].distanceTo(this.center.offset(-radius*Math.sin(a),0,radius*Math.cos(a)))<.025;});
    this.check('Native integer-angle circle preserves source seat starts',ids.every(id=>Number.isInteger(this.seats[id]))&&new Set(ids.map(id=>this.seats[id])).size===ids.length,{seats:this.seats,spawns:this.spawns});
    this.check('Circle teleports face each native seat toward the center',ids.every(id=>{const expected=(this.seats[id]*Math.floor(360/ids.length)+180)%360;return Math.abs(((this.bots[id].mmnYaw-expected+540)%360)-180)<.02;}),Object.fromEntries(ids.map(id=>[id,{seat:this.seats[id],yaw:this.bots[id].mmnYaw}])));
    if(!slow)for(const id of ids)await this.pinChunk(this.spawns[id]);
    return since;
  }
  async auditIntro(ids,since){
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/规则：.*Match Me Not/.test(row.text)),'original levitation entry starts',15000);
    const entry=new Vec3(...['x','y','z'].map(k=>this.bots.A.probePosition[k]));
    this.check('Entry uses original native Levitation80 before PLAY',await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:levitation"}]}]')&&!this.bots.A.probeStartedAt);
    this.check('Original arena has not appeared before rules tick60',await this.condition(`in pvp:arena if block ${Math.floor(entry.x)} 99 ${Math.floor(entry.z)} air`));
    // Exercise native client levitation physics instead of only inspecting its effect tag.
    const floating=this.bots.A;floating.probeAllowMovement=true;floating.physicsEnabled=true;
    await this.wait(()=>floating.entity.position.y>entry.y+.35,'actual native levitation raises the client',1600);
    await this.console('execute store result score floating p2_mmn_count run data get entity MyGoCheckA Pos[1] 1000');
    this.check('Unlocked INTRO accepts real native levitation movement',await this.condition(`if score floating p2_mmn_count matches ${Math.ceil((entry.y+.3)*1000)}..`),{entry,raised:floating.entity.position.y});
    floating.physicsEnabled=false;floating.probeAllowMovement=false;
    await this.wait(()=>new Vec3(...['x','y','z'].map(k=>this.bots.A.probePosition[k])).distanceTo(entry)>4,'source circle appears after3seconds',6500);
    this.check('Circle creation preserves remaining native levitation',await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:levitation"}]}]'));
    await this.console('execute in pvp:arena store result score frames p2_mmn_count if entity @e[tag=party_mmn_frame]');
    this.check('Original reveal anchors appear with the delayed circle',await this.condition(`if score frames p2_mmn_count matches ${ids.length}`));
    await sleep(1200);this.check('Levitation naturally expires before PLAY and is not restarted',!await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:levitation"}]}]')&&!this.bots.A.probeStartedAt);
  }
  async inventory(ids,round){await this.wait(()=>ids.every(id=>this.internal[id]===round&&ITEMS.slice(0,this.materialCount(ids.length)).every(item=>this.bots[id].inventory.items().some(stack=>stack.name===item&&stack.count===1))),`round${round} native items`,6000);}
  materialCount(n){return n<=4?4:n<=6?5:n<=8?6:7;}
  async drop(id,material,all=false){
    const bot=this.bots[id],item=bot.inventory.items().find(x=>x.name===ITEMS[material-1]);if(!item)throw Error(`${id} lacks material${material}`);
    if(item.slot<36||item.slot>44)await bot.equip(item,'hand');else bot.setQuickBarSlot(item.slot-36);
    await sleep(25);this.log('realDrop',{id,material,all});this.dropHeld(bot,all);
  }
  // Vanilla predicts Q removal locally. Raw protocol has no Mineflayer key
  // helper, so mirror that client prediction; server NBT is checked separately.
  dropHeld(bot,all=false){bot._client.write('block_dig',{status:all?3:4,location:new Vec3(0,0,0),face:0,sequence:++this.sequence});const slot=36+bot.quickBarSlot,item=bot.inventory.slots[slot];if(item&&(all||item.count===1))bot.inventory.updateSlot(slot,null);}
  async allChoose(choices){for(const [id,material]of Object.entries(choices))await this.drop(id,material);await this.wait(()=>Object.entries(choices).every(([id,m])=>this.chosen[id]===m),'all native choices observed',5000);}
  floor(id,points){return this.spawns[id].floored().offset(0,points-1,0);}
  async verifyFloor(id,points){const p=this.floor(id,points),color=`${COLORS[this.seats[id]]}_concrete`;this.check(`${id} original colored floor moves by exactly${points} native blocks`,await this.serverBlockIs(p,color),{p,color});}
  async nativeTwo(){
    const since=await this.startSubset(['A','B'],true);
    this.check('Two players receive exactly the original four1-item choices',['A','B'].every(id=>this.bots[id].inventory.items().filter(x=>ITEMS.includes(x.name)).length===4));
    this.check('Selection uses actual hotbar inventory without a custom GUI',!this.bots.A.currentWindow&&!this.bots.B.currentWindow);
    await this.drop('A',1);await sleep(50);
    this.check('A real Q drop exists during preparation before selection is accepted',this.chosen.A===0&&await this.condition('in pvp:arena if entity @e[type=item,nbt={Item:{components:{"minecraft:custom_data":{mmn:1b,bricks:1b}}}}]'));
    await this.sprint(4);await this.wait(()=>this.chosen.A===1,'preparation Q drop accepted at active boundary',2500);
    await this.wait(()=>!this.bots.A.inventory.items().some(x=>ITEMS.includes(x.name)),'accepted material inventory clears',2500);
    this.check('Accepted selection clears remaining native material items',!this.bots.A.inventory.items().some(x=>ITEMS.includes(x.name))&&await this.condition('unless entity @a[name=MyGoCheckA,nbt={Inventory:[{components:{"minecraft:custom_data":{mmn:1b}}}]}]'));
    await this.console('tick rate 20');for(const id of ['A','B'])await this.pinChunk(this.spawns[id]);
    await this.drop('B',2,true);await this.wait(()=>this.chosen.B===2,'native Ctrl-Q choice',2500);
    await this.sprint(60);
    await this.console('execute in pvp:arena store result score displays p2_mmn_count if entity @e[type=item,tag=party_mmn_display]');
    this.check('Reveal uses two actual gravity-enabled native item entities',await this.condition('if score displays p2_mmn_count matches 2')&&!await this.condition('in pvp:arena if entity @e[type=item,tag=party_mmn_display,nbt={NoGravity:1b}]'));
    await this.console('execute in pvp:arena store result score frames p2_mmn_count if entity @e[type=item_frame,tag=party_mmn_frame,nbt={Invisible:1b,Fixed:1b,Invulnerable:1b,Silent:1b}]');
    this.check('Original hidden fixed itemframe anchors remain native entities',await this.condition('if score frames p2_mmn_count matches 2'));
    this.check('Points are not granted at the reveal60 boundary',this.points.A===0&&this.points.B===0);
    await this.sprint(75);await this.inventory(['A','B'],2);
    this.check('Different native dropped materials each earn exactly one point',this.points.A===1&&this.points.B===1,{points:{...this.points}});
    await this.verifyFloor('A',1);await this.verifyFloor('B',1);
    for(const id of ['A','B']){
      await this.console(`execute store result score height p2_mmn_count run data get entity ${name(id)} Pos[1] 1000`);
      this.check(`${id} actual server player is physically pushed upward`,await this.condition(`if score height p2_mmn_count matches ${Math.floor(this.spawns[id].y*1000)+850}..${Math.floor(this.spawns[id].y*1000)+1200}`));
    }
    this.check('Source concealment emits native dust packets to opponents',this.particles.some(row=>row.packet.amount===10&&row.packet.particle?.type==='dust'&&row.packet.particle.data.scale===4),this.particles.slice(0,2));
    // One-second native tick is an explicit batching fixture for two genuine Q packets.
    await this.console('tick rate 1');
    const a=this.bots.A;a.setQuickBarSlot(0);this.dropHeld(a);a.setQuickBarSlot(3);this.dropHeld(a);
    await this.wait(()=>this.chosen.A===4,'same-tick native drops resolve to higher materialID',3500);this.check('Multiple same-tick Q items preserve original material-order overwrite',this.chosen.A===4);await this.console('tick rate 20');await this.drop('B',4);
    await this.sprint(140);await this.inventory(['A','B'],3);
    this.check('Repeated material choices do not score or raise either platform',this.points.A===1&&this.points.B===1);await this.verifyFloor('A',1);await this.verifyFloor('B',1);
    await this.drop('B',1);await this.sprint(210);
    this.check('One nonselector does not receive a random default choice',this.chosen.A===0);
    await this.sprint(140);await this.inventory(['A','B'],4);
    this.check('Full200tick choice timeout still awards the unique selected player only',this.points.A===1&&this.points.B===2);
    for(let round=4;round<=8;round++){
      await this.inventory(['A','B'],round);await this.allChoose({A:3,B:4});await this.sprint(140);
    }
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'eight internal rounds settle one outer round',5000);
    this.check('Eight source rounds produce6 and7 unique choices',this.points.A===6&&this.points.B===7,{points:{...this.points}});
    this.check('Source raw scores become correct tournament placements',this.chats.some(row=>row.id==='A'&&row.at>=since&&/第 1 名 MyGoCheckB 7 \/ 8 次独选 本轮 \+2/.test(row.text))&&this.chats.some(row=>row.id==='A'&&row.at>=since&&/第 2 名 MyGoCheckA 6 \/ 8 次独选 本轮 \+1/.test(row.text)));
    await this.wait(()=>!!this.bots.A.currentWindow,'next outer-round menu after cleanup',30000);
    this.check('Eight internal rounds consume only one five-round tournament entry',!this.menuChoices(this.bots.A.currentWindow).some(c=>c.source===422)&&this.chats.filter(row=>row.id==='A'&&row.at>=since&&/第\s+\d+\/5\s+轮排名/.test(row.text)).length===1);
    this.check('Native moved floor and piston region actually clear',await this.serverBlockIs(this.floor('A',6),'air')&&await this.serverBlockIs(this.spawns.A.floored().offset(0,-3,0),'air'));
    this.check('All reveal items and fixed frames are removed',!await this.condition('in pvp:arena if entity @e[tag=party_mmn_display]')&&!await this.condition('in pvp:arena if entity @e[tag=party_mmn_frame]'));
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
  }
  async crowd(n){
    const ids='ABCDEFGHIJ'.slice(0,n).split('');for(const id of ids)if(!this.bots[id])this.connect(id);
    await this.wait(()=>ids.every(id=>this.bots[id].probeSpawned),`${n} actual clients`,30000);await this.startSubset(ids);
    const count=this.materialCount(n);this.check(`${n} players receive exactly${count} original choices`,ids.every(id=>this.bots[id].inventory.items().filter(item=>ITEMS.includes(item.name)).length===count));
    this.check(`${n} players use original radius${n>=9?12:10}`,ids.every(id=>Math.abs(Math.hypot(this.spawns[id].x-this.center.x,this.spawns[id].z-this.center.z)-(n>=9?12:10))<.025));
    const picks={};for(let i=0;i<n;i++)picks[ids[i]]=i<count?i+1:1;
    await this.allChoose(picks);await this.sprint((n<=4?130:130+20*(n-4))+8);await this.inventory(ids,2);
    for(const id of ids){const freq=Object.values(picks).filter(x=>x===picks[id]).length,expected=freq===1?1:0;this.check(`${n}-player ${id} uses source uniqueness count`,this.points[id]===expected);await this.verifyFloor(id,expected);}
    if(n===9){await this.chat('I','/hub');await this.wait(()=>this.bots.I.probeWorld==='minecraft:overworld','forfeiter returns hub');await this.console('execute in pvp:arena store result score frames p2_mmn_count if entity @e[tag=party_mmn_frame]');this.check('Forfeit removes only its own fixed reveal anchor',await this.condition('if score frames p2_mmn_count matches 8'));const remain=ids.slice(0,8);await this.allChoose(Object.fromEntries(remain.map(id=>[id,1])));await this.sprint(240);await this.inventory(remain,3);this.check('Forfeit retains original9-player seven-material palette',remain.every(id=>this.bots[id].inventory.items().some(item=>item.name==='diamond')));}
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
  }
  async clearBoundary(){
    await this.startSubset(['A','B']);await this.drop('A',1);
    await this.wait(()=>this.chosen.A===1,'A real material selection before crafting-hide boundary',2500);
    const bot=this.bots.B,recipe=bot.recipesFor(bot.registry.itemsByName.gold_nugget.id,null,1,null)[0];
    if(!recipe)throw Error('No native one-ingot to nine-nugget inventory recipe');
    this.log('nativeCraft',{id:'B',recipe:'gold_nugget',source:'supplied gold ingot',note:'Native crafted output has no round custom_data.'});
    await bot.craft(recipe,1);await bot._syncWindow(bot.inventory);
    await this.wait(()=>bot.inventory.items().some(item=>item.name==='gold_nugget'&&item.count===9),'native unmarked nine-nugget craft output',2500);
    const nuggets=bot.inventory.items().find(item=>item.name==='gold_nugget');
    this.check('Native crafting really transforms the supplied token material into plain nuggets',await this.condition('if entity @a[name=MyGoCheckB,nbt={Inventory:[{id:"minecraft:gold_nugget",count:9}]}]')&&await this.condition('unless entity @a[name=MyGoCheckB,nbt={Inventory:[{id:"minecraft:gold_nugget",components:{"minecraft:custom_data":{mmn:1b}}}]}]'));
    await bot.clickWindow(nuggets.slot,0,0);await bot.clickWindow(1,1,0);
    this.check('Real player GUI stores unmarked output in both2x2 input and cursor',bot.inventory.slots[1]?.name==='gold_nugget'&&bot.inventory.selectedItem?.name==='gold_nugget'&&bot.inventory.selectedItem.count===8,{craftInput:bot.inventory.slots[1],cursor:bot.inventory.selectedItem});
    await this.sprint(210);await bot._syncWindow(bot.inventory);
    this.check('Phase3 source clear removes the real2x2 crafting input',!bot.inventory.slots.slice(1,5).some(Boolean));
    this.check('Phase3 source clear removes unmarked cursor and transformed inventory',!bot.inventory.selectedItem&&!bot.inventory.items().some(item=>['gold_nugget',...ITEMS].includes(item.name))&&await this.condition('unless entity @a[name=MyGoCheckB,nbt={Inventory:[{id:"minecraft:gold_nugget"}]}]'));
    this.check('Clearing hidden items never invents a choice',this.chosen.B===0);
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
  }
  async run(){await this.console('scoreboard objectives add p2_mmn_count dummy');if(process.argv.includes('--clear-only')){await this.clearBoundary();return;}if(!process.argv.includes('--crowd-only'))await this.nativeTwo();if(!process.argv.includes('--native-only')){await this.crowd(5);await this.crowd(7);await this.crowd(9);}}
  async diagnose(){for(const id of Object.keys(this.bots))if(this.bots[id].probeSpawned){this.log('failureClient',this.snapshot(id));for(const field of ['Dimension','Pos','Health','Inventory'])await this.console(`data get entity ${name(id)} ${field}`,0);}}
}
async function main(){const p=new MatchMeNotProbe();let error;try{await p.launch();await p.connectAll('AB');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});await p.diagnose().catch(()=>{});}const report=await p.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={MatchMeNotProbe};
