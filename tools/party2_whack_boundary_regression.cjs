'use strict';

// Reason: the original source always creates four WAM lanes, even with only
// two players. Verify the empty-lane case and that other online players remain
// outside this match. This owned-server fixture temporarily slows tick rate.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const path = require('node:path');
class BoundaryProbe extends PartyProbe {
  constructor() { super({port:25610,runDir:path.join(__dirname,'../build/mygo-party2-whack-boundary')});this.healthEvents=[]; }
  connect(id) {
    const bot=super.connect(id);
    bot._client.on('update_health',packet=>this.healthEvents.push({id,at:Date.now(),health:packet.health}));
    return bot;
  }
  async sprint(ticks) {
    const start=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} owned server ticks to check native respawn protection expiration.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(start).some(row=>/Sprint completed/i.test(row.text)),'tick sprint completion',30000);
  }
  async environmentRetry() {
    const a=this.bots.A.probePosition,b=this.bots.B.probePosition;
    const homeA={...a},homeB={...b};
    const fireAt=Date.now();
    this.log('fixtureNote',{description:'Apply two points of native in_fire damage without modifying scene blocks. WAM clears resistance and must retain environmental damage.'});
    await this.console('damage MyGoCheckA 2 minecraft:in_fire');
    await this.wait(()=>this.healthEvents.some(row=>row.id==='A'&&row.at>=fireAt&&row.health<=18),'native fire reduces player health');
    this.check('Source WAM retains non-fall environmental damage',true,this.healthEvents.filter(row=>row.id==='A'&&row.at>=fireAt));

    await this.teleport('B',{x:homeA.x+1.3,y:homeA.y,z:homeA.z});
    await this.wait(()=>Math.abs(this.bots.B.probePosition.x-(homeA.x+1.3))<.05,'B enters real melee range');
    const attackAt=Date.now();
    await this.bots.A.lookAt(this.bots.B.entity.position.offset(0,1,0),true);
    this.log('realPlayerMelee',{attacker:'A',victim:'B'});this.bots.A.attack(this.bots.B.entity);
    await new Promise(resolve=>setTimeout(resolve,250));
    this.check('Source pvp=false still rejects real player melee',this.bots.B.health===20
      && !this.healthEvents.some(row=>row.id==='B'&&row.at>=attackAt&&row.health<20),this.healthEvents.filter(row=>row.id==='B'&&row.at>=attackAt));
    await this.teleport('B',homeB);

    await this.teleport('A',{x:homeA.x+1.2,y:homeA.y,z:homeA.z+1});
    const killedAt=Date.now();
    await this.console('kill MyGoCheckA');
    await this.wait(()=>Math.hypot(this.bots.A.probePosition.x-homeA.x,this.bots.A.probePosition.z-homeA.z)<.05
      && this.bots.A.health===20,'OP kill returns A to its own room at full health');
    this.check('Native lethal kill retries the lane and source loadout without elimination',this.bots.A.probeWorld==='pvp:arena'
      && this.bots.A.game.gameMode==='adventure'&&this.bots.A.inventory.items().some(item=>item.name==='netherite_axe'),this.snapshot('A'));
    this.check('Player retry does not rank or end the round',!this.chats.some(row=>row.id==='A'&&row.at>=killedAt&&row.text.includes('第 1/5 轮排名')));
    const protectionAt=Date.now();
    await this.console('damage MyGoCheckA 2 minecraft:in_fire');
    await new Promise(resolve=>setTimeout(resolve,150));
    this.check('Native new-life protection blocks immediate non-fall damage',this.bots.A.health===20
      && !this.healthEvents.some(row=>row.id==='A'&&row.at>=protectionAt&&row.health<20));
    await this.sprint(70);
    const expiredAt=Date.now();await this.console('damage MyGoCheckA 2 minecraft:in_fire');
    await this.wait(()=>this.healthEvents.some(row=>row.id==='A'&&row.at>=expiredAt&&row.health<=18),'fire damage resumes after protection');
    this.check('Environmental damage resumes after the native sixty-tick protection',true);
  }
  async count() {
    const start=this.serverLines.length;
    await this.console('execute in pvp:arena store result score WamCopies p2_wam_boundary if entity @e[tag=pvp_party_whack_target]');
    await this.console('scoreboard players get WamCopies p2_wam_boundary');
    const match=this.serverLines.slice(start).map(row=>row.text.match(/WamCopies has (-?\d+) \[p2_wam_boundary\]/)).find(Boolean);
    if(!match)throw new Error('No authoritative mob-count result');return Number(match[1]);
  }
  async run() {
    const outside=new Map(['C','D'].map(id=>[id,JSON.stringify(this.snapshot(id).inventory)]));
    await this.console('scoreboard objectives add p2_wam_boundary dummy');
    this.game='whack';const since=Date.now();
    for(const id of ['A','B']) { this.bots[id].probeStartedAt=0;await this.chat(id,'/pvp join minecraftparty'); }
    await this.chat('A','/pvp start');
    await this.wait(()=> {
      if(this.voteError)throw this.voteError;
      return this.chats.some(row=>row.id==='A'&&row.at>=since&&/轮选择：.*原版编号\s+402/.test(row.text));
    },'two-player match selects Whack-A-Mob',60000);
    const selected=this.chats.find(row=>row.id==='A'&&row.at>=since&&/轮选择：.*原版编号\s+402/.test(row.text)).at;
    await this.wait(()=>['A','B'].every(id=>this.bots[id].probeStartedAt>=selected),'two-player PLAY',30000);this.game=null;
    await this.wait(()=>Object.values(this.bots.A.entities).some(entity=>['zombie','wither_skeleton','zombie_villager','enderman','blaze','zombified_piglin'].includes(entity.name)),
      'first two-player wave',10000);
    this.log('fixtureNote',{description:'Temporarily set only this isolated server to1TPS after its first wave, so the next 20-tick spawn cannot race the count assertion.'});
    await this.console('tick rate 1');
    this.check('Two-player WAM retains the original four native mob copies',await this.count()===4);
    for(const id of ['C','D']) this.check(`${id} remains outside the two-player match with unchanged inventory`,
      this.bots[id].probeWorld==='minecraft:overworld'&&JSON.stringify(this.snapshot(id).inventory)===outside.get(id),this.snapshot(id));
    await this.console('tick rate 20');
    await this.environmentRetry();
    await this.chat('A','/hub');
    await this.wait(()=>this.bots.A.probeWorld==='minecraft:overworld','one real quitter leaves two-player game');
    await this.wait(()=>this.bots.B.probeWorld==='minecraft:overworld','remaining player settles the forfeit',25000);
    // The dimension respawn packet can arrive before the separate inventory
    // update that installs the hub compass; wait for that actual update.
    await this.wait(()=>['A','B'].every(id=>this.bots[id].inventory.items().some(item=>item.name==='compass')),'lobby compass inventory packets settle',5000);
    this.check('Both players return with lobby compasses after the forfeit',true,['A','B'].map(id=>this.snapshot(id)));
    this.check('Early forfeit removes all four source mob copies',await this.count()===0);
  }
}
(async()=> {
  const probe=new BoundaryProbe();let error;
  try { await probe.launch();await probe.connectAll();await probe.run(); }
  catch(failure) {error=failure;probe.log('failure',{error:failure.stack});try{await probe.console('tick rate 20');}catch(_){} }
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
