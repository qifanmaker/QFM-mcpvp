'use strict';

// Reason/pre-review: 407 must use native Elytra/start-flight packets and the
// source floor-zone scoring, not a generic checkpoint race. Owned 25624 server
// only; flight launch/landing and later game completion are declared fixtures.
// This checks protocol/server behavior, not rendered client/resource-pack art.
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const path = require('node:path');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const PLAN = ['skyfly', 'rlgl', 'rainbow', 'runner', 'dropper'];

class SkyFlyProbe extends PartyProbe {
  constructor() {
    super({port:25624,runDir:path.join(__dirname,'../build/mygo-party2-skyfly')});
    this.round = 0; this.rows = new Map(); this.finalCount = 0; this.votes = new Set();
  }
  connect(id) {
    const bot = super.connect(id);
    bot.on('windowOpen', window => {
      if (IDS.includes(id) && JSON.stringify(window.title).includes('Party')) {
        void this.castVote(id,window).catch(error => {this.voteError=error;this.log('voteError',{error:error.stack});});
      }
    });
    bot.on('messagestr', message => {
      if (id !== 'A') return;
      const text=String(message).replace(/§./g,'');
      const header=text.match(/第\s+(\d+)\/5\s+轮排名/);
      if(header) {this.round=Number(header[1]);this.rows.set(this.round,[]);}
      if(this.rows.has(this.round)&&/第 \d+ 名.*MyGoCheck.*本轮/.test(text))this.rows.get(this.round).push(text);
      if(text.includes('Party 2 五轮总排名'))this.finalCount++;
    });
    return bot;
  }
  async castVote(id,window) {
    const key=`${this.round}:${id}:${window.id}`;if(this.votes.has(key))return;this.votes.add(key);
    await this.wait(()=>this.menuChoices(window).length>0,'source IDs arrive');
    const choices=this.menuChoices(window),target=GAMES[PLAN[this.round]].source;
    const choice=choices.find(item=>item.source===target);
    this.check(`${id} round ${this.round+1} source ${target} selectable`,!!choice,{choices,target});
    for(let i=0;i<this.round;i++)this.check(`Round ${this.round+1} omits played ${PLAN[i]}`,
      !choices.some(item=>item.source===GAMES[PLAN[i]].source));
    await this.bots[id].clickWindow(choice.slot,0,0);
  }
  async until(predicate,label,timeout=15000) {
    await this.wait(()=>{if(this.voteError)throw this.voteError;return predicate();},label,timeout);
  }
  async started(round) {
    const source=GAMES[PLAN[round-1]].source;
    const selected=()=>this.chats.find(row=>row.id==='A'&&new RegExp(`第\\s+${round}/5\\s+轮选择：.*原版编号\\s+${source}`).test(row.text));
    await this.until(()=>selected(),`round ${round} selected`,90000);
    await this.until(()=>IDS.every(id=>this.bots[id].probeStartedAt>=selected().at),`round ${round} PLAY`,60000);
    this.log('sourceRoundStarted',{round,source,players:IDS.map(id=>this.snapshot(id))});
  }
  async condition(condition) {
    const token=`P2_SF_${++this.sequence}`;await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row=>row.text.includes(token));
  }
  async sprint(ticks) {
    const begin=this.serverLines.length;this.log('fixtureNote',{description:`Advance ${ticks} owned server ticks; wait for Sprint completed.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.until(()=>this.serverLines.slice(begin).some(row=>/Sprint completed/i.test(row.text)),'sprint complete',60000);
  }
  async jumpStrength(id,value,label) {
    await this.console(`execute store result score MyGoCheck${id} p2_skyfly_attr run attribute MyGoCheck${id} minecraft:generic.jump_strength base get 1000`);
    this.check(label,await this.condition(`if score MyGoCheck${id} p2_skyfly_attr matches ${value}`));
  }
  source(x,y,z) {return this.offset.offset(x,y,z);}
  async stopFlight(id) {
    const bot=this.bots[id];bot.physicsEnabled=false;bot.probeAllowMovement=false;bot.clearControlStates();
    await sleep(150);
  }
  async fly(id,position,target,stop) {
    const bot=this.bots[id];
    this.log('fixtureNote',{description:`${id} launch position is console-arranged; subsequent start_elytra_flying and movement packets use native client physics.`});
    await this.teleport(id,position);
    await this.until(()=>Math.abs(bot.probePosition.x-position.x)<.02,`${id} fixture launch arrived`);
    await bot.lookAt(target.offset(0,bot.entity.eyeHeight,0),true);
    bot.entity.onGround=false;bot.probeAllowMovement=true;bot.physicsEnabled=true;
    await sleep(110);
    await bot.elytraFly();
    await this.until(()=>bot.entity.elytraFlying,`${id} native gliding flag`,3000);
    // prismarine-physics otherwise retains its standing 1.8-block collider
    // while gliding. A real 1.21.1 client/server uses the native 0.6-block pose.
    bot.physics.playerHeight=.6;
    this.check(`${id} real start-flight packet activates native FallFlying`,await this.condition(`if entity MyGoCheck${id}[nbt={FallFlying:1b}]`));
    try{await this.until(stop,`${id} native flight displacement`,6500);}
    finally{this.log('nativeFlightEnd',{id,clientPosition:bot.entity.position,serverPosition:bot.probePosition,
      velocity:bot.entity.velocity,onGround:bot.entity.onGround,gliding:bot.entity.elytraFlying,
      collidedHorizontally:bot.entity.isCollidedHorizontally});
      await this.stopFlight(id);bot.physics.playerHeight=1.8;}
  }
  async skyFly() {
    const home=new Vec3(this.bots.A.probePosition.x,this.bots.A.probePosition.y,this.bots.A.probePosition.z);
    this.offset=home.minus(new Vec3(-23.5,118,4.5));
    this.check('All four receive native chest Elytra',IDS.every(id=>this.bots[id].inventory.slots[6]?.name==='elytra'),IDS.map(id=>this.snapshot(id)));
    const external=this.snapshot('E');
    this.check('Outside player stays in hub with no source equipment/effects',external.world==='minecraft:overworld'
      && !external.inventory.some(item=>item.name==='elytra') && !Object.values(this.bots.E.entity.effects).some(effect=>effect.amplifier===99),external);
    const wall=this.source(-39,115,0).floored();await this.pinChunk(wall);
    await this.until(()=>this.block('A',wall),'first ring chunk loaded');
    const holes=[];
    for(let z=-4;z<=12;z++)if(this.block('A',this.source(-39,115,z).floored())?.name==='air')holes.push(z);
    this.check('Random first source ring retains five-wide open flight row',holes.length===5,{holes});
    const z=holes[2]+.5;
    await this.fly('A',this.source(-34.5,119,z),this.source(-50,119,z),()=>this.bots.A.entity.position.x<this.source(-39.7,0,0).x);
    this.check('Native flight passes first original gold aperture into zone1',await this.condition(`as MyGoCheckA at @s if block ~ ${this.offset.y-42} ~ minecraft:orange_concrete`));
    this.check('Native flight does not consume or replace source Elytra',this.bots.A.inventory.slots[6]?.name==='elytra'&&this.bots.A.health===20,this.snapshot('A'));

    // Make the unchanged 158-block-high exterior quartz wall observable under
    // native movement; no runtime teleport/rubber-band is used as a barrier.
    const exterior=this.source(-183,113,70).floored();await this.pinChunk(exterior);
    this.check('Original exterior remains quartz at flight altitude',await this.serverBlockIs(exterior,'quartz_block'));
    await this.fly('B',this.source(-180.5,114,70.5),this.source(-190,114,70.5),
      ()=>this.bots.B.entity.isCollidedHorizontally&&this.bots.B.entity.position.x<this.source(-181.6,0,0).x);
    await this.console('execute store result score MyGoCheckB p2_skyfly_attr run data get entity MyGoCheckB Pos[0] 1000');
    const min=Math.floor(this.source(-181.71,0,0).x*1000);
    this.check('Native collision does not fly through original outside wall',await this.condition(`if score MyGoCheckB p2_skyfly_attr matches ${min}..`));

    // Original iron-bar miss rule is deliberately a source-state fixture:
    // feet in iron_bars plus OnGround, rather than claiming a rendered landing.
    const miss=this.source(-38.15,113,z);
    this.log('fixtureNote',{description:'Arrange the exact original iron_bars+OnGround miss condition; this fixture is not a natural landing test.'});
    await this.teleport('A',miss);
    await this.until(()=>!this.bots.A.inventory.items().some(item=>item.name==='elytra'),'iron-bar miss clears all Elytra',4000);
    this.check('Missed source hoop removes Elytra permanently for this attempt',true,this.snapshot('A'));
    await this.sprint(2400);
    this.check('Original no-timer rule remains PLAY beyond two minutes',!this.rows.has(1)
      &&IDS.every(id=>this.bots[id].game.gameMode==='adventure'));

    this.log('fixtureNote',{description:'Landing teleports arrange score36/36/16/33 under the actual sourceY−42 materials. No ring checkpoint is injected.'});
    for(const [id,p]of [['A',[-100.5,-39,75.5]],['B',[-98.5,-39,75.5]],['C',[-150.5,-39,127.5]]])await this.teleport(id,this.source(...p));
    await sleep(350);
    this.check('Three landed players do not end while fourth remains above',!this.rows.has(1)
      &&IDS.every(id=>this.bots[id].game.gameMode==='adventure'));
    await this.jumpStrength('A','0','Source cached bottom Y disables jump');
    this.check('Bottom threshold clears Elytra from chest and inventory',!this.bots.B.inventory.items().some(item=>item.name==='elytra'),this.snapshot('B'));
    this.cleanupWitness=this.source(-100,-42,75).floored();await this.pinChunk(this.cleanupWitness);
    this.check('Scoring-floor witness is original yellow terracotta before cleanup',await this.serverBlockIs(this.cleanupWitness,'yellow_terracotta'));
    await this.teleport('D',this.source(-140.5,-39,92.5));
    await this.until(()=>this.rows.get(1)?.length===4,'all-bottom native round end');
    const rows=this.rows.get(1);
    for(const[id,place,raw,points]of [['A',1,36,4],['B',1,36,4],['C',4,16,1],['D',3,33,2]]) {
      this.check(`${id} raw zone score freezes before lobby teleport with tie placement`,rows.some(row=>new RegExp(`第 ${place} 名.*MyGoCheck${id}.*${raw} 环区域.*本轮 \\+${points}`).test(row)),rows);
    }
    await this.jumpStrength('A','419..421','Bottom jump suppression restored for results/next round');
    this.check('Source Elytra removed from all players on close',IDS.every(id=>!this.bots[id].inventory.items().some(item=>item.name==='elytra')));
    this.check('Outside player remains unaffected through source finish',this.bots.E.probeWorld==='minecraft:overworld'
      &&JSON.stringify(this.snapshot('E').inventory)===JSON.stringify(external.inventory));
  }
  async tag(ids) {
    await this.console('tag @a remove p2_skyfly_finish');
    for(const id of ids)await this.console(`tag MyGoCheck${id} add p2_skyfly_finish`);
  }
  async run() {
    await this.console('scoreboard objectives add p2_skyfly_attr dummy');
    for(const id of IDS)await this.chat(id,'/pvp join minecraftparty');
    await this.started(1);await this.skyFly();
    await this.started(2);
    this.check('Old score-floor source geometry cleans before next game',await this.waitServerBlock(this.cleanupWitness,'air'));
    let p=this.bots.A.probePosition;
    await this.tag(IDS);await this.console(`execute in pvp:arena as @a[tag=p2_skyfly_finish] run tp @s ${p.x} ${p.y} ${p.z-145.5}`);
    await this.until(()=>this.rows.get(2)?.length===4,'traffic-light continuation completes');
    await this.started(3);p=this.bots.A.probePosition;
    await this.tag(['B','C','D']);await this.console(`execute in pvp:arena as @a[tag=p2_skyfly_finish] run tp @s ${p.x} ${p.y-27} ${p.z}`);
    await this.until(()=>this.rows.get(3)?.length===4,'rainbow continuation completes');
    await this.started(4);await this.tag(['B','C','D']);
    await this.console('execute as @a[tag=p2_skyfly_finish] run damage @s 1000 minecraft:fall');
    await this.until(()=>this.rows.get(4)?.length===4,'runner continuation completes');
    await this.started(5);
    const starts=IDS.map(id=>this.bots[id].probePosition);
    const x=starts.reduce((sum,p)=>sum+p.x,0)/4,z=starts.reduce((sum,p)=>sum+p.z,0)/4;
    await this.tag(IDS);await this.console(`execute in pvp:arena as @a[tag=p2_skyfly_finish] run tp @s ${x} -62.75 ${z}`);
    await this.sprint(200);
    await this.until(()=>this.rows.get(5)?.length===4&&this.finalCount===1,'exact five-round cumulative final',35000);
    await this.until(()=>IDS.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'five-round return to hub',35000);
    this.check('Exactly five distinct round results and one final table follow 407',this.rows.size===5&&this.finalCount===1,{rounds:[...this.rows.keys()],finalCount:this.finalCount});
    await this.until(()=>IDS.every(id=>this.bots[id].inventory.items().some(item=>item.name==='compass')),'hub compasses');
    this.check('All four restored to hub with compasses and no Elytra',IDS.every(id=>!this.bots[id].inventory.items().some(item=>item.name==='elytra')));
    this.check('Exactly one feedback hint per completed tournament player',IDS.every(id=>this.chats.filter(row=>row.id===id&&row.text.includes('qfmaker.com/blackboard/mcserverfb')).length===1));
  }
}

(async()=>{
  const probe=new SkyFlyProbe();let error;
  try{await probe.launch();await probe.connectAll('ABCDE');await probe.run();}
  catch(failure){error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
