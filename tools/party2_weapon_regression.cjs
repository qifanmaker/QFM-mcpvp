'use strict';
// Reason: source410 requires actual native laser/trident/snowball damage and
// death-based progression, not synthetic raycasting or an obsolete sumo arena.
// Review: own Fabric25628 runtime; ordinary attack/item packets are separated
// from explicit console positioning, damage, health and tick fixtures.
const path=require('node:path');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const {Vec3}=require('vec3');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const IDS=['A','B','C','D'];
const name=id=>`MyGoCheck${id}`;
const pos=bot=>new Vec3(bot.probePosition.x,bot.probePosition.y,bot.probePosition.z);
GAMES.weapon={source:410};
class WeaponProbe extends PartyProbe {
 constructor(options={}){super({port:options.port||25628,runDir:options.runDir||path.join(__dirname,'../build/mygo-party2-weapon')});this.velocities=[];this.spawns=[];this.teamPackets=[];}
 connect(id){const bot=super.connect(id);bot._client.on('teams',packet=>{if(packet.team.startsWith('p2w_'))this.teamPackets.push({id,...packet});});bot._client.on('entity_velocity',packet=>this.velocities.push({id,at:Date.now(),packet}));bot.on('entitySpawn',entity=>this.spawns.push({id,at:Date.now(),entity}));return bot;}
 async condition(value){const token=`P2_WD_${++this.sequence}`;await this.console(`execute ${value} run say ${token}`);return this.serverLines.some(r=>r.text.includes(`] ${token}`));}
 async sprint(ticks){const since=this.serverLines.length;this.log('fixtureNote',{description:`Advance ${ticks} native ticks and await completion.`});this.rawConsole(`tick sprint ${ticks}`);await this.wait(()=>this.serverLines.slice(since).some(r=>/Sprint completed/i.test(r.text)),'tick sprint complete',60000);}
 async effect(id,kind,amp){return this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:${kind}"${amp==null||amp===0?'':`,amplifier:${amp}b`}}]}]${amp===0?` unless data entity ${name(id)} active_effects[{id:"minecraft:${kind}"}].amplifier`:''}`);}
 async equip(id,item){await this.wait(()=>this.bots[id].inventory.items().some(x=>x.name===item),`${id} receives ${item}`,2500);await this.bots[id].equip(this.bots[id].inventory.items().find(x=>x.name===item),'hand');}
 async aim(id,target){const bot=this.bots[id];await this.wait(()=>!!bot.players[name(target)]?.entity,`${id} sees ${target}`);const point=pos(this.bots[target]).offset(0,1.2,0);await bot.lookAt(point,true);await sleep(80);this.log('realAim',{id,target,point,yaw:bot.entity.yaw,pitch:bot.entity.pitch});}
 async hit(id,target){await this.aim(id,target);const bot=this.bots[id];this.log('realAttack',{id,target,item:bot.heldItem?.name});bot.attack(bot.players[name(target)].entity);await sleep(150);}
 async fixtureDamage(target,amount,attacker,type='player_attack'){this.log('fixtureNote',{description:`Console native ${type} ${amount} damage by ${attacker}; this isolates attribution/progression rather than weapon output.`});await this.console(`damage ${name(target)} ${amount} minecraft:${type}${attacker?` by ${name(attacker)}`:''}`);}
 async gun(id){await this.equip(id,'carrot_on_a_stick');this.log('realLaserClick',{id});this.bots[id].activateItem();await sleep(100);this.bots[id].deactivateItem();}
 async run(){
  const started=await this.start('weapon');
  await this.wait(()=>IDS.every(id=>this.bots[id].inventory.items().some(x=>x.name==='carrot_on_a_stick')),'first native inventory update after PLAY',2500);
  const source=(x,y,z)=>new Vec3(x+341,y+79,z+464); // Independent isolated region0 center(88,101,88), source anchor(-253,22,-376).
  const initial=IDS.map(id=>pos(this.bots[id]));
  const expected=[[-253,25,-345],[-222,25,-376],[-253,25,-407],[-284,25,-376]].map(p=>source(...p));
  this.check('Four players start at exact decimal source gates',expected.every(p=>initial.some(q=>p.distanceTo(q)<.2)),{initial,expected});
  for(const id of IDS){this.check(`${id} receives initial laser`,this.bots[id].inventory.items().some(x=>x.name==='carrot_on_a_stick'),this.snapshot(id));this.check(`${id} receives initial Regeneration I`,await this.effect(id,'regeneration',0));this.check(`${id} has source Weakness99`,await this.effect(id,'weakness',99));}
  const arena=source(-287,19,-408),tower=source(-283,82,-406),gate=source(-250,25,-406),frame=source(-253,25,-406);
  for(const point of [arena,tower,gate])await this.pinChunk(point);
  this.check('Original arena stone-brick witness staged',await this.serverBlockIs(arena,'stone_bricks'));
  this.check('Original upper tower clone witness staged',await this.serverBlockIs(tower,'dark_oak_wood'));
  await this.sprint(52);
  this.check('Gate opens to original invisible light0',await this.serverBlockIs(gate,'light[level=0]'));
  this.check('Native opening preserves original deepslate gate frame',await this.serverBlockIs(frame,'deepslate_bricks'));
  // Keep physics-disabled clients on a labelled temporary aerial fixture, away from source blocks.
  this.origin=source(-253.5,95,-376.5);const low=this.origin.floored().offset(-8,-1,-32),high=low.offset(28,4,64);
  this.log('fixtureNote',{description:'Temporary aerial corridor supplies stable client positions; only native damage and item actions provide weapon evidence.'});
  await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} air`);
  await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${low.y} ${high.z} barrier`);
  for(const [i,id]of IDS.entries())await this.teleport(id,this.origin.offset(i*6,0,0));
  await this.console('scoreboard objectives add p2_wd_data dummy');
  // Fire into empty space and query native arrow coefficients/motion before it hits anything.
  const a=this.bots.A;await a.lookAt(this.origin.offset(0,1.62,-20),true);await this.gun('A');
  this.check('Laser is a real native damage1.25 Arrow',await this.condition('in pvp:arena if entity @e[type=minecraft:arrow,tag=party_wd_laser,nbt={damage:1.25d}]'));
  await this.console('execute in pvp:arena as @e[type=minecraft:arrow,tag=party_wd_laser,limit=1] store result score wd_speed p2_wd_data run data get entity @s Motion[2] 1000');
  this.check('Native laser motion has source speed near10',await this.condition('if score wd_speed p2_wd_data matches -10000..-8500'));
  this.check('Cooling gun uses old-format model11',await this.condition(`if entity @a[name=${name('A')},nbt={Inventory:[{id:"minecraft:carrot_on_a_stick",components:{"minecraft:custom_model_data":11}}]}]`));
  await this.sprint(20);this.check('Laser model returns to10 after19ticks',await this.condition(`if entity @a[name=${name('A')},nbt={Inventory:[{id:"minecraft:carrot_on_a_stick",components:{"minecraft:custom_model_data":10}}]}]`));
  await this.console(`effect clear ${name('B')} minecraft:regeneration`);
  await this.teleport('A',this.origin);await this.teleport('B',this.origin.offset(0,0,6));
  await this.aim('A','B');await this.gun('A');
  await this.wait(()=>this.bots.B.health<20,'first real laser damages B',2500);
  this.check('Real laser gives native speed-scaled13damage',this.bots.B.health===7,this.snapshot('B'));
  await this.sprint(20);await this.aim('A','B');await this.gun('A');await this.equip('A','netherite_axe');
  this.check('Second laser kill advances A to axe',a.inventory.items().some(x=>x.name==='netherite_axe'));
  await this.wait(()=>this.bots.B.game.gameMode==='adventure','B instant native-style respawn',2500);
  this.check('Death clears initial Regeneration without global rule changes',!await this.effect('B','regeneration'));
  await this.teleport('B',this.origin.offset(2,0,0));await this.teleport('A',this.origin);
  const health=this.bots.B.health;await this.hit('A','B');this.check('Fresh life rejects immediate nonfall attack',this.bots.B.health===health,this.snapshot('B'));
  await this.sprint(65);await sleep(800);await this.hit('A','B');
  this.check('Native netherite axe damages unarmored player',this.bots.B.health<=10&&this.bots.B.health>0,this.snapshot('B'));
  const damaged=this.bots.B.health;await this.sprint(160);this.check('Natural regeneration stays disabled after death',this.bots.B.health===damaged,this.snapshot('B'));
  await sleep(800);await this.hit('A','B');await this.equip('A','trident');
  this.check('Second native axe hit advances to trident',await this.effect('A','weakness',1));
  // Three real thrown tridents deal8 each, retaining source WeaknessII for melee only.
  this.connect('E');await this.wait(()=>this.bots.E.probeSpawned,'unmatched external operator spawns',25000);
  await this.sprint(65);
  const outsideHealth=this.bots.E.health,participantHealth=this.bots.B.health;
  this.log('fixtureNote',{description:'Explicit console player_attack damage isolates cross-match authorization; it does not claim physical melee reach across dimensions.'});
  await this.console('damage MyGoCheckB 1 minecraft:player_attack by MyGoCheckE');
  await this.console('damage MyGoCheckE 1 minecraft:player_attack by MyGoCheckA');
  this.check('Unmatched outsider and participant cannot damage each other',this.bots.E.health===outsideHealth&&this.bots.B.health===participantHealth);
  this.check('Unmatched player receives none of this round weapon/effect state',!this.bots.E.inventory.items().some(x=>x.name==='carrot_on_a_stick')&&!await this.effect('E','weakness')&&!await this.effect('E','regeneration'));
  await this.console(`execute in pvp:arena run summon item ${this.origin.x.toFixed(8)} ${this.origin.y.toFixed(8)} ${this.origin.z.toFixed(8)} {Tags:["p2_wd_foreign"],PickupDelay:32767s,Item:{id:"minecraft:stick",count:1}}`);
  const ownTrident=a.inventory.items().find(x=>x.name==='trident');await a.tossStack(ownTrident);await this.equip('A','trident');
  this.check('Native dropped owned weapon is replenished',a.inventory.items().some(x=>x.name==='trident'));
  this.check('Foreign untagged item survives scoped cleanup',await this.condition('in pvp:arena if entity @e[type=minecraft:item,tag=p2_wd_foreign]'));
  await this.console('execute in pvp:arena run kill @e[tag=p2_wd_foreign]');
  for(let i=0;i<3;i++){
   await this.teleport('A',this.origin);await this.teleport('B',this.origin.offset(0,0,6));await this.equip('A','trident');await this.aim('A','B');
   this.log('realTridentThrow',{id:'A',number:i+1});a.activateItem();await sleep(650);a.deactivateItem();await sleep(350);
   if(i<2)this.check(`Native trident throw${i+1} deals8`,this.bots.B.health===20-8*(i+1),this.snapshot('B'));
  }
  await this.equip('A','wooden_sword');this.check('Third real trident kill advances to sword and clears Weakness',!await this.effect('A','weakness'));
  await this.sprint(65);await this.teleport('A',this.origin);await this.teleport('B',this.origin.offset(2,0,0));
  for(let i=0;i<5;i++){await sleep(700);await this.hit('A','B');}
  await this.equip('A','snowball');this.check('Five native wooden-sword hits reach final weapon',await this.effect('A','weakness',99));
  await this.sprint(65);
  this.check('Snowball supply caps at two',a.inventory.items().filter(x=>x.name==='snowball').reduce((n,x)=>n+x.count,0)===2,this.snapshot('A'));
  await this.teleport('A',this.origin);await a.lookAt(this.origin.offset(0,1.62,-20),true);await this.equip('A','snowball');
  a.activateItem();await sleep(90);a.deactivateItem();await sleep(100);
  this.check('Real snowball creates an actual native Pierce100 arrow',await this.condition('in pvp:arena if entity @e[type=minecraft:arrow,tag=party_wd_snow_arrow,nbt={PierceLevel:100b}]'));
  this.check('Native snow-arrow origin is its actual throwing player',await this.condition('in pvp:arena as @e[type=minecraft:arrow,tag=party_wd_snow_arrow] on origin if entity @s[name=MyGoCheckA]'));
  await this.sprint(35);
  for(let i=0;i<7;i++){
   await this.teleport('A',this.origin);await this.teleport('B',this.origin.offset(0,0,5));await this.equip('A','snowball');await this.aim('A','B');
   this.log('realSharpSnowball',{id:'A',number:i+1});a.activateItem();await sleep(90);a.deactivateItem();await sleep(230);
   if(i<6){this.check(`Sharp snowball${i+1} applies native damage`,this.bots.B.health<20,this.snapshot('B'));await this.sprint(30);}
  }
  await this.wait(()=>a.game.gameMode==='spectator','five real-weapon kills finish A',3000);
  this.check('One finisher does not end four-player source round',!this.chats.some(r=>r.id==='A'&&r.at>=started&&/第\s+1\/5\s+轮排名/.test(r.text)));
  // Explicit damage fixtures isolate source stat coalescing and N-1 termination from native weapon tests above.
  await this.fixtureDamage('C',200,'D');await this.equip('D','netherite_axe');
  await this.sprint(65);
  const twoAt=Date.now();
  for(const id of ['B','C']){await this.console(`tag ${name(id)} add p2_wd_pair`);}
  this.log('fixtureNote',{description:'Same-tick native player_attack damage to two victims; source advances the killer only once.'});
  await this.console(`execute as @a[tag=p2_wd_pair] run damage @s 200 minecraft:player_attack by ${name('D')}`);await this.console('tag @a remove p2_wd_pair');
  await this.equip('D','trident');this.check('Same-tick double kill advances just one stage',!this.bots.D.inventory.items().some(x=>x.name==='wooden_sword'));
  await this.sprint(65);
  await this.console(`damage ${name('D')} 100 minecraft:fall`);await this.wait(()=>this.bots.D.game.gameMode==='adventure','D fallback respawn',2500);
  this.check('Fall bypasses new-life protection without granting a phantom upgrade',this.bots.D.inventory.items().some(x=>x.name==='trident'));
  this.check('Native snow arrow uses Pierce100 when present',!await this.condition('in pvp:arena if entity @e[type=minecraft:arrow,tag=party_wd_snow_arrow,nbt={PierceLevel:0b}]'));
  await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} air`);
  for(const [killer,count] of [['D',3],['C',5]])for(let i=0;i<count;i++) {await this.sprint(65);await this.fixtureDamage('B',200,killer);await sleep(100);}
  await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=started&&/第\s+1\/5\s+轮排名/.test(r.text)),'third finisher triggers N-1 source end',3000);
  this.check('Three finishers rank by source recent-kill order',[['A',1,4],['D',2,3],['C',3,2],['B',4,1]].every(([id,place,points])=>this.chats.some(r=>r.id==='A'&&r.at>=started&&new RegExp(`第\\s+${place}\\s+名\\s+${name(id)}.*本轮\\s+\\+${points}`).test(r.text))));
  this.check('Completed A outranks unfinished weapon stages',this.chats.some(r=>r.id==='A'&&r.at>=started&&/第\s+1\s+名\s+MyGoCheckA/.test(r.text)));
  this.check('All owned native projectiles removed on round close',!await this.condition('in pvp:arena if entity @e[tag=party_wd_projectile]'));
  this.check('All private laser teams removed on close',this.teamPackets.some(x=>x.mode===0)&&this.teamPackets.filter(x=>x.mode===0).every(x=>this.teamPackets.some(y=>y.team===x.team&&y.mode===1)));
  for(const id of IDS)this.check(`${id} source effects cleared`,!await this.effect(id,'weakness')&&!await this.effect(id,'regeneration'));
  await this.wait(()=>IDS.every(id=>!!this.bots[id].currentWindow),'next five-round selection resumes',30000);
  this.check('Source410 cannot repeat in same tournament',IDS.every(id=>!this.menuChoices(this.bots[id].currentWindow).some(x=>x.source===410)));
  this.check('Pinned arena actually clears',await this.waitServerBlock(arena,'air'));
  this.check('Pinned original tower clone actually clears',await this.waitServerBlock(tower,'air'));
  await this.leaveAll();await this.unpinChunks();
  await this.crowdAndDeadline();
  await this.twoPlayerBranch();
 }
 async joinSubset(ids){
  this.game='weapon';this.voteError=null;const since=Date.now();
  for(const id of ids){this.bots[id].probeStartedAt=0;await this.console(`tag ${name(id)} add p2_wd_join`);}
  this.log('fixtureNote',{description:`One execute-as invokes real queue join handlers for ${ids.length} players in one tick; no player-count source branch is bypassed.`});
  await this.console('execute as @a[tag=p2_wd_join] run pvp join minecraftparty');await this.console('tag @a remove p2_wd_join');
  if(ids.length<4){this.log('fixtureNote',{description:'Actual prepared OP uses /pvp start to launch the valid two-player queue immediately.'});await this.chat(ids[0],'/pvp start');}
  await this.wait(()=>{if(this.voteError)throw this.voteError;return this.chats.some(r=>r.id===ids[0]&&r.at>=since&&/轮选择：.*原版编号\s+410/.test(r.text));},'source410 selected by subset votes',90000);
  const selected=Date.now();for(const id of ids)this.bots[id].probeStartedAt=0;
  await this.wait(()=>ids.every(id=>this.bots[id].probeStartedAt>=selected),'subset enters actual PLAY',45000);
  await this.wait(()=>ids.every(id=>this.bots[id].inventory.items().some(x=>x.name==='carrot_on_a_stick')),'subset receives native PLAY inventory',2500);
  this.game=null;return since;
 }
 async crowdAndDeadline(){
  if(!this.bots.E){this.connect('E');await this.wait(()=>this.bots.E.probeSpawned,'fifth operator joins',25000);}
  const ids=['A','B','C','D','E'];const since=await this.joinSubset(ids);
  const points=ids.map(id=>pos(this.bots[id])),meanX=points.reduce((n,p)=>n+p.x,0)/5;
  const center=new Vec3(Math.round((meanX-88)/512)*512+88,101,88);
  const expected=ids.map((_,seat)=>{const a=seat*72*Math.PI/180;return center.offset(-24*Math.sin(a),0,24*Math.cos(a));});
  this.check('Five-player source branch uses exact radius24 circle',expected.every(p=>points.some(q=>p.distanceTo(q)<.006)),{points,center,expected});
  for(const id of ids)this.check(`${id} source circle has native laser`,this.bots[id].inventory.items().some(x=>x.name==='carrot_on_a_stick'),this.snapshot(id));
  await this.sprint(2300);
  this.check('Source410 does not finish before120seconds',!this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)));
  await this.sprint(101);await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'five-player deadline settles',3000);
  this.check('Equal unplayed stages retain tied first-place scores',ids.every(id=>this.chats.some(r=>r.id==='A'&&r.at>=since&&new RegExp(`第\\s+1\\s+名\\s+${name(id)}.*本轮\\s+\\+5`).test(r.text))));
  await this.leaveAll();
 }
 async twoPlayerBranch(){
  const since=await this.joinSubset(['A','B']);const points=['A','B'].map(id=>pos(this.bots[id]));
  this.check('Two-player branch selects two cardinal source gates',Math.abs(points[0].x-points[1].x)>30&&Math.abs(points[0].z-points[1].z)>30&&points.every(p=>p.y===104),points);
  for(let i=0;i<5;i++){await this.sprint(65);await this.fixtureDamage('B',200,'A');await sleep(120);}
  await this.wait(()=>this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\/5\s+轮排名/.test(r.text)),'one finisher ends two-player source branch',3000);
  this.check('N-1 threshold is one in the actual two-player match',this.chats.some(r=>r.id==='A'&&r.at>=since&&/第\s+1\s+名\s+MyGoCheckA.*本轮\s+\+2/.test(r.text)));
  await this.leaveAll();
 }
}
async function main(){const p=new WeaponProbe();let error;try{await p.launch();await p.connectAll('ABCD');await p.run();}catch(e){error=e;p.log('failure',{error:e.stack});}const r=await p.close(error);if(!r.ok)process.exitCode=1;}
if(require.main===module)void main().catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={WeaponProbe};
