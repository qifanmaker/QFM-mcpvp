'use strict';

// Reason: 427 must retain real Elytra/flight0 fireworks, material-plane laps,
// native half-heart recovery and integer slab boosts in the original cave.
// Review: this owns only25672/build/mygo-party2-take-flight. Launch, waypoint
// and damage fixtures are labeled; no scores or private round fields are set.
// Headless native protocol evidence, not rendered full-course flight acceptance.
const path=require('node:path');
const {Vec3}=require('vec3');
const {PartyProbe,GAMES}=require('./party2_protocol_regression.cjs');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
GAMES.takeflight={source:427,minimumPlayers:2,maximumPlayers:10};
const name=id=>`MyGoCheck${id}`;
const WAYPOINTS=[[-310.5,-12,-593.5],[-297.5,-40,-647.5],[-355.5,-10,-661.5],[-236.5,19,-650.5]];

class TakeFlightProbe extends PartyProbe {
  constructor(){super({port:25672,runDir:path.join(__dirname,'../build/mygo-party2-take-flight')});this.laps={};this.round=0;this.rows=[];}
  connect(id){
    const bot=super.connect(id);this.laps[id]=0;
    bot.on('messagestr',message=>{
      const text=String(message).replace(/§./g,'');
      const lap=text.match(/已完成 (\d)\/3 圈/);if(lap)this.laps[id]=Number(lap[1]);
      if(id==='A'&&/第\s+1\/5\s+轮排名/.test(text))this.round=1;
      if(id==='A'&&/第 \d+ 名.*本轮/.test(text))this.rows.push(text);
    });return bot;
  }
  async condition(clause){const token=`P2_TF_${++this.sequence}`;await this.console(`execute ${clause} run say ${token}`,10);return this.serverLines.some(row=>row.text.includes(`] ${token}`));}
  async sprint(ticks){const begin=this.serverLines.length;this.log('fixtureNote',{description:`Advance ${ticks} actual native ticks.`});this.rawConsole(`tick sprint ${ticks}`);await this.wait(()=>this.serverLines.slice(begin).some(row=>/Sprint completed/i.test(row.text)),'native tick sprint',60000);await sleep(35);}
  async startSubset(ids){
    this.activeIds=[...ids];this.round=0;this.rows=[];this.game='takeflight';this.voteError=null;
    for(const id of ids){this.laps[id]=0;await this.console(`tag ${name(id)} add p2_tf_hub`);}
    await this.console('execute as @a[tag=p2_tf_hub] run hub');await this.console('tag @a remove p2_tf_hub');
    await this.wait(()=>ids.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'clean hub baseline',15000);
    const since=Date.now();for(const id of ids){this.bots[id].probeStartedAt=0;await this.console(`tag ${name(id)} add p2_tf_join`);}
    await this.console('execute as @a[tag=p2_tf_join] run pvp join minecraftparty');await this.console('tag @a remove p2_tf_join');if(ids.length<4)await this.chat(ids[0],'/pvp start');
    await this.wait(()=>{if(this.voteError)throw this.voteError;return this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮选择：.*原版编号\s+427/.test(row.text));},'427 selected through real votes',90000);
    const selected=Date.now();for(const id of ids)this.bots[id].probeStartedAt=0;
    await this.wait(()=>ids.every(id=>this.bots[id].probeStartedAt>=selected),'six-million-cell source cave and native intro enter PLAY',240000);
    this.game=null;
    const first=this.bots[ids[0]].probePosition,minX=Math.min(...ids.map(id=>this.bots[id].probePosition.x));
    const rawMin=ids.length<=4?-232.5-2*(ids.length-1):-235.5;
    this.offset=new Vec3(minX-rawMin,first.y-22,first.z+644.5);this.source=(x,y,z)=>this.offset.offset(x,y,z);
    this.check('Source Y coordinates retain the real world bottom',Math.abs(this.offset.y)<.01,this.offset);
    this.check(`${ids.length} players receive actual binding native Elytra`,ids.every(id=>this.bots[id].inventory.slots[6]?.name==='elytra'));
    this.check(`${ids.length} players use original compact/shared launch positions`,ids.length>=5?ids.every(id=>this.bots[id].probePosition.distanceTo(first)<.01):new Set(ids.map(id=>this.bots[id].probePosition.x)).size===ids.length);
    await this.console('scoreboard objectives add p2_tf_stats dummy');
    await this.console(`execute store result score health p2_tf_stats run attribute ${name('A')} minecraft:generic.max_health base get 1000`);
    this.check('The source maximum health is exactly one half-heart',await this.condition('if score health p2_tf_stats matches 1000'));
    for(const raw of [[-410,-64,-770],[-200,64,-510],[-311,-20,-594],[-298,-48,-648],[-356,-25,-662],[-237,10,-651],[-257,26,-692],[-240,22,-646]])await this.pinChunk(this.source(...raw));
    for(const [raw,block]of [[[-311,-20,-594],'emerald_block'],[[-298,-48,-648],'diamond_block'],[[-356,-25,-662],'netherite_block'],[[-237,10,-651],'sponge']])this.check(`Original ${block} waypoint pad exists at its fixed native Y`,await this.serverBlockIs(this.source(...raw),block));
    this.check('The true source bedrock bottom remains present',await this.serverBlockIs(this.source(-410,-64,-770),'bedrock'));
    this.check('The race removes only the lower glass cage',await this.serverBlockIs(this.source(-240,22,-646),'air'));
    this.check('Four native glass boost displays are present',await this.condition('in pvp:arena if entity @e[type=block_display,tag=party_take_flight_boost,tag=waypoint1] if entity @e[type=block_display,tag=party_take_flight_boost,tag=waypoint2] if entity @e[type=block_display,tag=party_take_flight_boost,tag=waypoint3] if entity @e[type=block_display,tag=party_take_flight_boost,tag=waypoint4]'));
    return since;
  }
  async stopFlight(id){const bot=this.bots[id];bot.physicsEnabled=false;bot.probeAllowMovement=false;bot.clearControlStates();bot.physics.playerHeight=1.8;await sleep(100);}
  async rocketFlight(){
    const bot=this.bots.A;
    await this.console('scoreboard objectives add p2_tf_rocket minecraft.used:minecraft.firework_rocket');
    await this.console(`scoreboard players set ${name('A')} p2_tf_rocket 0`);
    await this.wait(()=>bot.inventory.items().some(item=>item.name==='firework_rocket'),'native10tick rocket refill',4000);
    await this.teleport('A',this.source(-256.5,28,-691.5));await bot.lookAt(this.source(-271,28,-686).offset(0,bot.entity.eyeHeight,0),true);
    this.log('fixtureNote',{description:'Arrange a source-air launch point; real start_elytra_flying and client movement packets then activate native flight0 firework use.'});
    bot.entity.onGround=false;bot.probeAllowMovement=true;bot.physicsEnabled=true;await sleep(110);await bot.elytraFly();bot.physics.playerHeight=.6;
    await this.wait(()=>bot.entity.elytraFlying,'real native Elytra flag',4000);
    this.check('Actual start-flight packets set native FallFlying',await this.condition(`if entity @a[name=${name('A')},nbt={FallFlying:1b}]`));
    const rocket=bot.inventory.items().find(item=>item.name==='firework_rocket');await bot.equip(rocket,'hand');bot.activateItem();
    this.check('Native use creates a real owned attached rocket entity',await this.condition('in pvp:arena if entity @e[type=firework_rocket,tag=party_take_flight_rocket]'));
    await sleep(100);
    await this.console(`execute store result score vx p2_tf_stats run data get entity ${name('A')} Motion[0] 1000`);
    this.check('Flight0 rockets apply actual native Elytra acceleration',await this.condition('if score vx p2_tf_stats matches ..-300'));
    this.check('The genuine native firework item-use statistic increments once',await this.condition(`if score ${name('A')} p2_tf_rocket matches 1`));
    await this.stopFlight('A');
    await this.console('tick rate 2');
    await this.console(`execute in pvp:arena store result score used p2_tf_stats if entity @e[type=firework_rocket,tag=party_take_flight_rocket]`);
    await this.sprint(25);
    this.check('Native flight0 rocket entities expire without custom projectile substitutes',!await this.condition('in pvp:arena if entity @e[type=firework_rocket,tag=party_take_flight_rocket]'));
    this.check('Rocket refill holds one item rather than accumulating a stack',bot.inventory.items().filter(item=>item.name==='firework_rocket').reduce((sum,item)=>sum+item.count,0)===1);
    const item=bot.inventory.items().find(stack=>stack.name==='firework_rocket');await bot.equip(item,'hand');
    bot._client.write('block_dig',{status:4,location:new Vec3(0,0,0),face:0,sequence:++this.sequence});await this.sprint(2);
    this.check('A real Q-dropped rocket is removed by scoped source cleanup',!await this.condition('in pvp:arena if entity @e[type=item,nbt={Item:{id:"minecraft:firework_rocket"}}]'));
    await this.sprint(12);
    this.check('Dropping still uses the source native inventory recharge',bot.inventory.items().some(stack=>stack.name==='firework_rocket'));
  }
  async waypoint(id,index){
    const position=this.source(...WAYPOINTS[index]);
    this.log('fixtureNote',{description:'This labeled position fixture enters the actual source material XZ plane at a legal cave-air height; no checkpoint/score fields are assigned.',id,index,position});
    await this.teleport(id,position);await this.sprint(2);
  }
  async lap(id,total){for(let index=0;index<4;index++)await this.waypoint(id,index);await this.wait(()=>this.laps[id]===total,`${id} source lap${total}`,4000);}
  async recover(){
    await this.teleport('A',this.source(-256.5,28,-691.5));await this.sprint(1);
    const before=this.bots.A.probePosition,since=Date.now();
    this.log('fixtureNote',{description:'Native fly_into_wall damage is injected to isolate half-heart recovery; this does not claim a physically rendered wall impact.'});
    await this.console(`damage ${name('A')} 3 minecraft:fly_into_wall`);
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&row.text.includes('已在原位恢复')),'native non-eliminating collision recovery',4000);
    this.check('Collision recovery preserves source position and active participation',this.bots.A.probePosition.distanceTo(before)<.05&&this.bots.A.game.gameMode==='adventure');
    this.check('Recovery clears native gliding pose and retains binding Elytra',!await this.condition(`if entity @a[name=${name('A')},nbt={FallFlying:1b}]`)&&this.bots.A.inventory.slots[6]?.name==='elytra');
    this.check('Recovery grants the original two-second Resistance99',await this.condition(`if entity @a[name=${name('A')},nbt={active_effects:[{id:"minecraft:resistance",amplifier:99b}]}]`));
  }
  async nativeTwo(){
    const since=await this.startSubset(['A','B']);await this.rocketFlight();await this.recover();
    await this.waypoint('A',3);this.check('Sponge crossed out of order does not count a lap',this.laps.A===0);
    await this.lap('A',1);
    this.check('One real source lap does not finish a three-lap race',this.round===0&&this.bots.A.game.gameMode==='adventure');
    await this.lap('A',2);await this.lap('A',3);
    await this.wait(()=>this.rows.length>=2,'N-1 native source finish ranking',7000);
    this.check('Three laps convert to six raw points and first tournament place',this.rows.some(row=>/第 1 名 MyGoCheckA 3 \/ 3 圈（6分） 本轮 \+2/.test(row)),this.rows);
    this.check('The idle pilot keeps zero laps and its checkpoint recency point',this.rows.some(row=>/MyGoCheckB 0 \/ 3 圈（1分） 本轮 \+1/.test(row)),this.rows);
    await this.console('tick rate 20');
    await this.wait(()=>!!this.bots.A.currentWindow,'exact original cave cleanup and next menu',180000);
    this.check('The catalog removes only the played427 entry',!this.menuChoices(this.bots.A.currentWindow).some(choice=>choice.source===427));
    this.check('The true underground source terrain clears after the round',await this.serverBlockIs(this.source(-311,-20,-594),'air'));
    this.check('Every native boost display and attached rocket clears',!await this.condition('in pvp:arena if entity @e[tag=party_take_flight_boost]')&&!await this.condition('in pvp:arena if entity @e[tag=party_take_flight_lightning]')&&!await this.condition('in pvp:arena if entity @e[tag=party_take_flight_rocket]'));
    for(const id of ['A','B']){
      await this.console(`execute store result score restored p2_tf_stats run attribute ${name(id)} minecraft:generic.max_health base get 1000`);
      this.check(`${id} original20HP health cap restores`,await this.condition('if score restored p2_tf_stats matches 20000'));
      this.check(`${id} binding Elytra and source rockets are removed`,!this.bots[id].inventory.items().some(item=>['elytra','firework_rocket'].includes(item.name)));
    }
    await this.leaveAll();await this.unpinChunks();await sleep(5500);
  }
  async fivePlayers(){
    for(const id of ['C','D','E'])this.connect(id);await this.wait(()=>['A','B','C','D','E'].every(id=>this.bots[id].probeSpawned),'five real clients',30000);
    await this.startSubset(['A','B','C','D','E']);
    this.check('Five-player branch uses the exact source shared cage start',this.activeIds.every(id=>this.bots[id].probePosition.distanceTo(this.bots.A.probePosition)<.01));
    await this.console('execute as MyGoCheckE run hub');await this.wait(()=>this.bots.E.probeWorld==='minecraft:overworld','one pilot forfeits to hub',20000);
    this.check('Forfeit restores only the departed pilot health/equipment',this.bots.E.health===20&&!this.bots.E.inventory.items().some(item=>item.name==='elytra')&&this.bots.A.inventory.slots[6]?.name==='elytra');
    this.check('A single forfeit leaves remaining pilots racing in Adventure',['A','B','C','D'].every(id=>this.bots[id].probeWorld==='pvp:arena'&&this.bots[id].game.gameMode==='adventure'));
    await this.leaveAll();await sleep(5500);await this.unpinChunks();
    this.check('Cancellation returns all five clients to their native hub',Object.values(this.bots).every(bot=>bot.probeWorld==='minecraft:overworld'));
  }
  async run(){await this.launch();await this.connectAll('AB');await this.nativeTwo();await this.fivePlayers();}
}

async function main(){const probe=new TakeFlightProbe();let error;try{await probe.run();}catch(failure){error=failure;probe.log('failure',{error:failure.stack});for(const id of Object.keys(probe.bots))if(probe.bots[id].probeSpawned)probe.log('failureClient',probe.snapshot(id));}const report=await probe.close(error);if(!report.ok)process.exitCode=1;}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={TakeFlightProbe};
