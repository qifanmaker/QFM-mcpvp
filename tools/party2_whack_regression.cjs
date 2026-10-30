'use strict';

// Reason: Whack-A-Mob uses real axe cooldown, armor and kill statistics; a
// click-counter test cannot validate the port. Use owned Fabric25610 and real
// attack packets; identify every teleport/time fixture, retain failed evidence.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const path = require('node:path');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const POINTS = { zombie: 1, wither_skeleton: 3, zombie_villager: 3, enderman: 5, blaze: -1, zombified_piglin: 2 };

class WhackProbe extends PartyProbe {
  constructor() {
    super({ port: 25610, runDir: path.join(__dirname, '../build/mygo-party2-whack') });
    this.total = 0; this.lastAttack = 0;
    this.teamPackets=[];
  }
  connect(id) {
    const bot=super.connect(id);
    bot._client.on('teams',packet=> {
      if (packet.team.startsWith('p2w_')) { this.teamPackets.push({id,...packet});this.log('sourceCollisionTeam',{id,...packet}); }
    });
    return bot;
  }
  position(id) { const p = this.bots[id].probePosition; return new Vec3(p.x, p.y, p.z); }
  source(x, y, z) { return new Vec3(x+this.offset.x, y+this.offset.y, z+this.offset.z); }
  async condition(condition) {
    const token = `P2_WAM_IF_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(`[Server] ${token}`));
  }
  async health(entity) {
    const start = this.serverLines.length;
    await this.console(`execute store result score WamHp p2_wam_stats run data get entity ${entity.uuid} Health 1000`);
    await this.console('scoreboard players get WamHp p2_wam_stats');
    const result = this.serverLines.slice(start).map(row => row.text.match(/WamHp has (-?\d+) \[p2_wam_stats\]/)).find(Boolean);
    if (!result) throw new Error(`No server health value for ${entity.uuid}`);
    return Number(result[1])/1000;
  }
  nearOwnRoom(entity) {
    return Object.hasOwn(POINTS, entity.name) && entity.uuid
      && Math.abs(entity.position.x-this.home.x) < 6 && Math.abs(entity.position.z-this.home.z) < 6;
  }
  targets() { return Object.values(this.bots.A.entities).filter(entity => this.nearOwnRoom(entity)); }
  scoreSince(since) {
    return this.chats.filter(row => row.id==='A' && row.at>=since).flatMap(row => {
      const match=row.text.match(/当前\s+(-?\d+)/); return match ? [Number(match[1])] : [];
    });
  }
  async readyTarget(kind, timeout=80000) {
    let found;
    await this.wait(() => {
      found=this.targets().find(entity => entity.name===kind
        && entity.position.y >= this.home.y-(kind==='enderman'?2.05:1.1)
        && entity.position.y <= this.home.y+.25 && !this.used.has(entity.uuid));
      return !!found;
    }, `naturally generated visible ${kind}`, timeout);
    return found;
  }
  async attack(entity) {
    const bot=this.bots.A;
    this.log('fixtureNote',{description:'Teleport A to a visible target in its original room to isolate attack/armor timing; the test does not claim rendered normal movement.'});
    await this.teleport('A',new Vec3(entity.position.x,this.home.y,entity.position.z+1.0));
    if (entity.name==='enderman') await this.wait(()=>entity.position.y>=this.home.y-2.05
      && entity.position.y<=this.home.y+.25,'Enderman emerges after its source hide cycle',3000);
    await bot.lookAt(entity.position.offset(0,1.0,0),true);
    this.log('realAxeAttack',{entity:entity.uuid,kind:entity.name,position:entity.position});
    bot.attack(entity); this.lastAttack=Date.now();
    await sleep(100);
  }
  async kill(kind, attempt=0) {
    const target=await this.readyTarget(kind);
    this.used.add(target.uuid);
    if (Date.now()-this.lastAttack<1150) await sleep(1150-(Date.now()-this.lastAttack));
    const start=Date.now(), before=await this.health(target);
    this.check(`${kind} uses the original small target health`,before>0 && before<=({enderman:9,blaze:4}[kind]||10),before);
    this.check(`${kind} preserves source no-gravity and persistence`,await this.condition(`if entity @e[nbt={UUID:${this.uuidNbt(target.uuid)},NoGravity:1b,PersistenceRequired:1b,Silent:1b}]`));
    if (kind==='enderman') this.check('Enderman alone is explicitly NoAI',await this.condition(`if entity @e[nbt={UUID:${this.uuidNbt(target.uuid)},NoAI:1b}]`));
    await this.attack(target);
    if (kind==='zombie_villager') {
      const first=await this.health(target);
      this.check('Iron-armored zombie survives one fully charged native axe hit',first>0 && first<before,{before,first});
      await this.attack(target);
      const weak=await this.health(target);
      this.check('Immediate repeated axe input does not bypass native cooldown/armor',weak>0 && weak>=first-1,{first,weak});
      if (Date.now()-this.lastAttack<1150) await sleep(1150-(Date.now()-this.lastAttack));
      await this.attack(target);
    }
    const expected=this.total+POINTS[kind];
    try { await this.wait(()=>this.scoreSince(start).includes(expected),`real ${kind} kill applies original ${POINTS[kind]} points`,1200); }
    catch(error) {
      if (kind==='enderman' && attempt<3) {
        this.log('transientTargetRetry',{kind,attempt,reason:'Original Enderman hide/expiry can race the client attack packet; retry a naturally generated target.',position:target.position});
        await this.teleport('A',this.home);return this.kill(kind,attempt+1);
      }
      throw error;
    }
    this.total=expected;
    this.check(`${kind} kill updates the scoped score by ${POINTS[kind]}`,true,{total:this.total});
    await this.teleport('A',this.home);
  }
  uuidNbt(uuid) {
    const text=uuid.replace(/-/g,'');
    return '[I;'+[0,8,16,24].map(i=>Number.parseInt(text.slice(i,i+8),16)|0).join(',')+']';
  }
  async sprint(ticks) {
    const index=this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} server ticks; wait for actual Sprint completed before inspecting state.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(()=>this.serverLines.slice(index).some(row=>/Sprint completed/i.test(row.text)),'tick sprint completed',90000);
  }
  async run() {
    await this.console('scoreboard objectives add p2_wam_stats dummy');
    this.started=await this.start('whack');
    this.used=new Set(); this.home=this.position('A');
    // Root compacts participant lanes for every round. Derive the translation
    // from all four original starts; their Z mean is -456, X remains -231.5.
    const starts=IDS.map(id=>this.position(id));
    const meanZ=starts.reduce((sum,p)=>sum+p.z,0)/4;
    this.offset=new Vec3(this.home.x+231.5,this.home.y-39,meanZ+456);
    this.check('Four participants start in distinct original rooms',new Set(starts.map(p=>p.z)).size===4
      && starts.every(p=>p.y===this.home.y && p.x===this.home.x),starts);
    this.check('Every player receives the source netherite axe',IDS.every(id=>this.bots[id].inventory.items().some(item=>item.name==='netherite_axe')),
      IDS.map(id=>this.snapshot(id)));
    const floor=new Vec3(Math.floor(this.home.x),this.home.y-1,Math.floor(this.home.z));
    await this.pinChunk(floor);
    this.check('Original center soul-sand floor is present',await this.serverBlockIs(floor,'soul_sand'),floor);
    await this.wait(()=>this.targets().length>0,'first native wave appears',10000);
    const first=this.targets()[0];
    const nativeTeam=this.teamPackets.find(packet=>packet.id==='A' && packet.collisionRule==='never')?.team;
    this.check('Original no-collision rule reaches every vanilla client',!!nativeTeam
      && IDS.every(id=>this.teamPackets.some(packet=>packet.id===id && packet.team===nativeTeam && packet.collisionRule==='never')),
    this.teamPackets);
    this.check('The scoped collision team contains mob UUIDs only',this.teamPackets.some(packet=>packet.team===nativeTeam
      && packet.players?.includes(first.uuid)) && this.teamPackets.filter(packet=>packet.team===nativeTeam)
      .every(packet=>(packet.players||[]).every(player=>/^[0-9a-f-]{36}$/.test(player))),this.teamPackets);
    const relatives=IDS.map(id=>Object.values(this.bots[id].entities).filter(entity=>entity.name===first.name
      && entity.uuid && Math.abs(entity.position.x-this.position(id).x)<6
      && Math.abs(entity.position.z-this.position(id).z)<6));
    this.check('First mob wave uses the same kind and hole in all player lanes',relatives.every(list=>list.length>=1)
      && relatives.every((list,index)=>Math.abs((list[0].position.z-starts[index].z)-(relatives[0][0].position.z-starts[0].z))<.01),relatives.map(list=>list.map(e=>({kind:e.name,position:e.position}))));
    const unseen=new Set(Object.keys(POINTS));
    while (unseen.size) {
      let kind;
      await this.wait(()=> {
        const target=this.targets().find(entity=>unseen.has(entity.name)
          && entity.position.y >= this.home.y-(entity.name==='enderman'?2.05:1.1)
          && entity.position.y <= this.home.y+.25 && !this.used.has(entity.uuid));
        kind=target?.name;return !!kind;
      },'an untested native species emerges',80000);
      await this.kill(kind);unseen.delete(kind);
    }
    this.check('All six real target species use the original point table',this.total===13,{total:this.total});

    // Source /kill retirement does not invoke the specific killed:type stat.
    // Apply one nonlethal player-attributed native damage, then let the regular
    // lifecycle retract it; do not call the scoring callback directly.
    const partial=await this.readyTarget('zombie'); this.used.add(partial.uuid);
    const partialAt=Date.now();
    await this.console(`damage ${partial.uuid} 1 minecraft:player_attack by MyGoCheckA`);
    this.log('fixtureNote',{description:'One labeled nonlethal /damage player_attack prepares an expiry-credit regression; the module retires the target normally.'});
    await this.sprint(180);
    this.check('Partially damaged target retirement does not add species-kill points',!this.scoreSince(partialAt).some(value=>value!==13),this.scoreSince(partialAt));
    this.check('Owned source mobs do not create item or XP drops',await this.condition(`in pvp:arena unless entity @e[type=minecraft:item,x=${this.home.x-7},y=${this.home.y-7},z=${this.home.z-7},dx=14,dy=18,dz=14] unless entity @e[type=minecraft:experience_orb,x=${this.home.x-7},y=${this.home.y-7},z=${this.home.z-7},dx=14,dy=18,dz=14]`));

    const voidTarget=await this.readyTarget('blaze');this.used.add(voidTarget.uuid);
    const voidAt=Date.now(), voidPosition=this.source(-233.5,-128.1,-423.5);
    this.log('fixtureNote',{description:'Move one naturally spawned owned blaze below the original source-world void threshold. No player damage is injected; this isolates vertical-translation lifecycle parity.'});
    await this.console(`execute in pvp:arena run tp ${voidTarget.uuid} ${voidPosition.x} ${voidPosition.y} ${voidPosition.z}`);
    await sleep(200);
    this.check('Translated source void kills a blaze at source Y below -128',await this.condition(`in pvp:arena unless entity @e[nbt={UUID:${this.uuidNbt(voidTarget.uuid)}}]`));
    this.check('Source-void retirement does not award a player kill',!this.scoreSince(voidAt).some(value=>value!==13),this.scoreSince(voidAt));
    const atLimit=Date.now();
    const hasResult=()=>this.chats.some(row=>row.id==='A' && row.at>=this.started && row.text.includes('第 1/5 轮排名'));
    this.log('fixtureNote',{description:'Advance in 40-tick batches until all source targets retire. This permits the original drain without overshooting into another game.'});
    for(let advanced=0;!hasResult() && advanced<6000;advanced+=40) await this.sprint(40);
    await this.wait(()=>this.chats.some(row=>row.id==='A' && row.at>=this.started && row.text.includes('第 1/5 轮排名')),'source last-mob drain produces round results',20000);
    const rows=this.chats.filter(row=>row.id==='A' && row.at>=this.started && /第\s+\d+\s+名\s+MyGoCheck/.test(row.text));
    this.check('Real scoped score ranks the active attacker first',rows.some(row=>/第\s+1\s+名\s+MyGoCheckA/.test(row.text)),rows);
    this.check('Non-attacking players remain tied on zero points', ['B','C','D'].every(id=>rows.some(row=>new RegExp(`第\\s+2\\s+名\\s+MyGoCheck${id}`).test(row.text))),rows);
    this.check('Finishing Whack-A-Mob does not settle the entire five-round match',!this.chats.some(row=>row.at>=this.started
      && (row.text.includes('Party 2 五轮总排名') || row.text.includes('qfmaker.com/blackboard/mcserverfb'))));
    this.check('Every source target has been removed at round close',await this.condition('in pvp:arena unless entity @e[tag=pvp_party_whack_target]'));
    this.check('Round close removes only its owned collision team from clients',IDS.every(id=>this.teamPackets.some(packet=>packet.id===id
      && packet.team===nativeTeam && packet.mode===1)));
    await this.waitServerBlock(floor,'air',30000);
    this.check('Original pinned room floor is cleared before the next round',true,{floor,atLimit});
    await this.leaveAll();
  }
}
(async()=> {
  const probe=new WhackProbe();let error;
  try { await probe.launch(); await probe.connectAll(); await probe.run(); }
  catch(failure) {error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error); if(!report.ok)process.exitCode=1;
})().catch(error=>{console.error(error.stack);process.exitCode=1;});
