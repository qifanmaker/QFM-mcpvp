'use strict';

// Reason: 406 must use actual enchanted-stick PvP and three native-style lives,
// not a generic knockback/score approximation. Review: own server25622 only;
// real attacks/drop packets are separated from console lava/death/time fixtures.
const path = require('node:path');
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve,ms));
const pos = bot => new Vec3(bot.probePosition.x,bot.probePosition.y,bot.probePosition.z);
const name = id => `MyGoCheck${id}`;
const IDS = ['A','B','C','D'];
GAMES.knockout = { source: 406 };

class KnockoutProbe extends PartyProbe {
  constructor() {
    super({port:25622,runDir:path.join(__dirname,'../build/mygo-party2-knockout')});
    this.velocities = [];
  }
  connect(id) {
    const bot = super.connect(id);
    bot._client.on('entity_velocity', packet => {
      if (packet.entityId === bot.entity?.id) {
        const row = {id,at:Date.now(),packet}; this.velocities.push(row);
        this.log('nativePlayerVelocity',row);
      }
    });
    return bot;
  }
  async condition(condition) {
    const token = `P2_KNOCKOUT_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(token));
  }
  async sprint(ticks) {
    const since = this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} native ticks; wait for Sprint completed before next command.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(since).some(row=>/Sprint completed/i.test(row.text)),'tick sprint complete',60000);
  }
  async effect(id,effect,amplifier) {
    return this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:${effect}"${amplifier==null?'':`,amplifier:${amplifier}b`}}]}]`);
  }
  async knockback(id,level) {
    return this.condition(`if entity @a[name=${name(id)},nbt={Inventory:[{Slot:0b,id:"minecraft:stick",components:{"minecraft:enchantments":{levels:{"minecraft:knockback":${level}}}}}]}]`);
  }
  async attack(attacker,victim) {
    const bot = this.bots[attacker];
    await this.wait(()=>!!bot.players[name(victim)]?.entity,`${attacker} sees ${victim}`);
    const entity = bot.players[name(victim)].entity;
    await bot.lookAt(entity.position.offset(0,1.1,0),true);
    const at = Date.now();
    this.log('realStickAttack',{attacker,victim,item:bot.heldItem?.name,target:entity.position});
    bot.attack(entity);
    return at;
  }
  async lose(id,kind='fall') {
    const at = Date.now();
    this.log('fixtureNote',{description:`Console ${kind} lethal damage consumes exactly one ${id} life; it does not simulate an ordinary stick attack.`});
    await this.console(`damage ${name(id)} 100 minecraft:${kind}`);
    // A random marker can repeat; teleport displacement is not evidence of a new life.
    await this.wait(()=>this.chats.some(row=>row.id===id&&row.at>=at&&/剩余生命：|三条命已用完/.test(row.text)),`${id} respawn or third-life elimination`,2500);
  }
  async run() {
    const since = await this.start('knockout');
    const homes = Object.fromEntries(IDS.map(id=>[id,pos(this.bots[id])]));
    const mean = IDS.reduce((sum,id)=>sum.plus(homes[id]),new Vec3(0,0,0)).scaled(1/4);
    const offset = mean.minus(new Vec3(-427.75,43,-109));
    const source = (x,y,z) => offset.offset(x,y,z);
    const upper = source(-433,57,-112), lava = source(-434,39,-129);
    await this.pinChunk(upper); await this.pinChunk(lava);
    this.check('Original upper-clone nether bricks are staged',await this.serverBlockIs(upper,'nether_bricks'),{upper});
    this.check('Original lava floor is preserved',await this.serverBlockIs(lava,'lava'),{lava});
    for (const id of IDS) {
      this.check(`${id} starts with native Knockback I in hotbar0`,await this.knockback(id,1),this.snapshot(id));
      this.check(`${id} receives source Regeneration44`,await this.effect(id,'regeneration',44));
    }
    const origin = source(-420.5,58,-95.5);
    const low = origin.floored().offset(-2,-1,-2),high = low.offset(17,4,4);
    this.log('fixtureNote',{description:'A small temporary aerial corridor holds physics-disabled protocol clients; native attack packets and native velocity packets validate combat. Source geometry witnesses remain untouched.'});
    await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} air`);
    await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${low.y} ${high.z} barrier`);
    await this.teleport('A',origin); await this.teleport('B',origin.offset(2,0,0));
    await this.teleport('C',origin.offset(6,0,0)); await this.teleport('D',origin.offset(10,0,0));
    await this.sprint(310);
    for (const id of IDS) this.check(`${id} glows after300 inactive ticks`,await this.effect(id,'glowing'));
    await this.teleport('C',origin.offset(7,0,0));
    this.check('Movement alone does not clear damage-based inactivity',await this.effect('C','glowing'));
    // The fifth connection is an unqueued outsider; console placement only supplies reach.
    this.connect('E');
    await this.wait(()=>this.bots.E.probeSpawned,'outsider E spawned',20000);
    await this.console(`gamemode adventure ${name('E')}`);
    await this.teleport('E',origin.offset(4,0,0));
    await this.wait(()=>this.bots.E.probeWorld!=='pvp:arena','shared arena guard returns unqueued outsider');
    this.check('Unqueued outsider cannot remain inside an active Party arena',this.bots.E.probeWorld==='minecraft:overworld',this.snapshot('E'));
    await this.sprint(65); // native initial join invulnerability must not mask this ownership check
    this.log('fixtureNote',{description:'Shared arena guard immediately removes outsider E, so console player_attack fixtures isolate cross-session damage authorization independently of native reach.'});
    const outsiderHit=Date.now();
    await this.console(`damage ${name('B')} 1 minecraft:player_attack by ${name('E')}`);
    this.check('Outsider damage fixture cannot activate a participant',await this.effect('B','glowing')
      &&!this.velocities.some(row=>row.id==='B'&&row.at>=outsiderHit));
    const attackOutsider=Date.now();
    await this.console(`damage ${name('E')} 1 minecraft:player_attack by ${name('A')}`);
    this.check('Participant damage fixture cannot affect an unqueued player',this.bots.E.health===20
      &&!this.velocities.some(row=>row.id==='E'&&row.at>=attackOutsider),this.snapshot('E'));
    await sleep(800);
    const hitAt = await this.attack('A','B');
    await this.wait(()=>this.velocities.some(row=>row.id==='B'&&row.at>=hitAt),'real native stick hit sends victim velocity',1500);
    this.check('Native PvP stick hit produces knockback',this.velocities.some(row=>row.id==='B'&&row.at>=hitAt),this.velocities.filter(row=>row.at>=hitAt));
    this.check('Actual damage clears glow for attacker and victim',!await this.effect('A','glowing')&&!await this.effect('B','glowing'));
    this.check('Untouched participant remains glowing',await this.effect('C','glowing'));
    await this.wait(()=>this.bots.B.health===20,'source high regeneration restores full health',1500);
    this.check('Native damage is healed by Regeneration44',this.bots.B.health===20,this.snapshot('B'));

    // A real recent stick hit followed by the original lava-feet rule must upgrade A.
    await this.teleport('B',lava.offset(.5,.2,.5));
    await this.wait(()=>pos(this.bots.B).distanceTo(lava)>3&&this.bots.B.game.gameMode==='adventure','lava consumes first B life and returns at source marker',2500);
    const returns = [[-414,43,-103],[-414,42,-113],[-427,46,-124],[-438,44,-119],[-442,43,-108],[-438,43,-100],[-425,43,-96],[-427,45,-109],[-431,45,-110]];
    this.check('Lava death returns to one of nine centered source markers',returns.some(p=>pos(this.bots.B).distanceTo(source(p[0]+.5,p[1],p[2]+.5))<.2),this.snapshot('B'));
    this.check('Recent knockoff earns native-style Knockback II upgrade',await this.knockback('A',2),this.snapshot('A'));
    this.check('Death does not upgrade the victim',await this.knockback('B',1));
    await this.teleport('B',origin.offset(2,0,0));
    const protectedAt = pos(this.bots.B);
    const protectedHit = await this.attack('A','B');
    await sleep(200);
    this.check('New-life60tick protection rejects an immediate player hit',!this.velocities.some(row=>row.id==='B'&&row.at>=protectedHit)&&pos(this.bots.B).distanceTo(protectedAt)<.1);
    await this.lose('B'); // native fall exception consumes life2 even in join protection
    this.check('Fall exception leaves B in its third life',this.bots.B.game.gameMode==='adventure',this.snapshot('B'));
    await this.sprint(65);
    await this.teleport('A',origin); await this.teleport('B',origin.offset(2,0,0));
    await sleep(800);
    const upgradedHit = await this.attack('A','B');
    await this.wait(()=>this.velocities.some(row=>row.id==='B'&&row.at>=upgradedHit),'upgraded native stick velocity',1500);
    const level1 = this.velocities.find(row=>row.id==='B'&&row.at>=hitAt&&row.at<protectedHit);
    const level2 = this.velocities.find(row=>row.id==='B'&&row.at>=upgradedHit);
    const horizontal = row => Math.hypot(row.packet.velocity.x,row.packet.velocity.z);
    this.check('Native Knockback II has greater horizontal velocity than I',horizontal(level2)>horizontal(level1),{level1,level2});

    const pairAt=Date.now();
    for(const id of ['C','D'])await this.console(`tag ${name(id)} add p2_knockout_pair`);
    this.log('fixtureNote',{description:'One console execution applies two native lethal falls in the same server tick, verifying the original one-random-respawner-per-tick serialization.'});
    await this.console('execute as @a[tag=p2_knockout_pair] run damage @s 100 minecraft:fall');
    await this.wait(()=>['C','D'].every(id=>this.chats.some(row=>row.id===id&&row.at>=pairAt&&row.text==='剩余生命：2')),'both same-tick victims return');
    const pair=['C','D'].map(id=>this.chats.find(row=>row.id===id&&row.at>=pairAt&&row.text==='剩余生命：2'));
    this.check('Same-tick deaths respawn one random player per tick',Math.abs(pair[0].at-pair[1].at)>=30,pair);
    await this.console('tag @a remove p2_knockout_pair');
    await this.lose('C');
    await this.lose('C');
    this.check('Three deaths eliminate C and preserve two rivals',this.bots.C.game.gameMode==='spectator'&&this.bots.A.game.gameMode==='adventure'&&this.bots.D.game.gameMode==='adventure');
    await this.lose('B');
    this.check('B is eliminated only on its third death',this.bots.B.game.gameMode==='spectator');

    // Native drop packets: token-tagged own stick is removed, unrelated item survives.
    await this.console(`execute in pvp:arena run summon item ${origin.x} ${origin.y} ${origin.z} {Tags:["p2_knockout_foreign"],PickupDelay:32767s,Item:{id:"minecraft:stick",count:1}}`);
    const ownStick = this.bots.A.inventory.items().find(item=>item.name==='stick');
    this.log('realDrop',{id:'A',item:ownStick}); await this.bots.A.tossStack(ownStick); await sleep(200);
    this.check('Dropped participant stick is replenished',await this.knockback('A',2));
    this.check('Unrelated native stick is not removed by global scan',await this.condition('in pvp:arena if entity @e[type=minecraft:item,tag=p2_knockout_foreign]'));
    await this.console('execute in pvp:arena run kill @e[tag=p2_knockout_foreign]');
    // Restore the explicitly-owned console fixture before testing production cleanup.
    await this.console(`execute in pvp:arena run fill ${low.x} ${low.y} ${low.z} ${high.x} ${high.y} ${high.z} air`);
    await this.lose('D'); await this.lose('D');
    await this.wait(()=>this.chats.some(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)),'round1 result',4000);
    const rows=this.chats.filter(row=>row.id==='A'&&row.at>=since&&/第\s+\d+\s+名/.test(row.text));
    this.check('Source elimination order ranks A,D,B,C with4/3/2/1 points',[['A',1,4],['D',2,3],['B',3,2],['C',4,1]].every(([id,place,points])=>rows.some(row=>new RegExp(`第\\s+${place}\\s+名\\s+${name(id)}.*本轮\\s+\\+${points}`).test(row.text))),rows);
    for (const id of IDS) this.check(`${id} regeneration/glow cleared after round`,!await this.effect(id,'regeneration')&&!await this.effect(id,'glowing'));
    await this.wait(()=>IDS.every(id=>!!this.bots[id].currentWindow),'five-round next selection resumes',30000);
    this.check('Knockout result continues tournament with source406 excluded',IDS.every(id=>!this.menuChoices(this.bots[id].currentWindow).some(choice=>choice.source===406)),IDS.map(id=>this.menuChoices(this.bots[id].currentWindow)));
    this.check('Upper structure cleanup is confirmed in its pinned live chunk',await this.waitServerBlock(upper,'air'),{upper});
    this.check('Lava cleanup is confirmed in its pinned live chunk',await this.waitServerBlock(lava,'air'),{lava});
    await this.leaveAll(); await this.unpinChunks();
  }
}

async function main() {
  const probe=new KnockoutProbe(); let error;
  try {await probe.launch();await probe.connectAll('ABCD');await probe.run();}
  catch(failure){error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
}
if(require.main===module)void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={KnockoutProbe};
