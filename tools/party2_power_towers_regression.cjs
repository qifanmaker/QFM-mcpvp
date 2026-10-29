'use strict';

// Reason: 419 must sample native tune_noteblock statistics and count actual
// falling powder; a virtual click counter would pass a superficial GUI test.
// Review: isolated Fabric25644, real main-hand block packets, explicit timing
// fixtures for the >7 same-tick batch, no source/global datapack execution.
// This headless protocol test does not claim a rendered-client playthrough.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A','B','C','D'];
const name = id => `MyGoCheck${id}`;
const rules = JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/pvp/party/power_towers_rules.json')));
const geometry = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(__dirname,'../src/main/resources/pvp/party/geometry/power_towers.json.gz'))));
GAMES.power = { source: 419 };

class PowerProbe extends PartyProbe {
  constructor() {
    super({ port: 25644, runDir: path.join(__dirname,'../build/mygo-party2-power-towers') });
    this.offset = new Vec3(88+498,101-52,88+5);
    this.nativeFalling = [];
    this.rotations = [];
    this.releaseEvents = [];
    this.seats = {};
  }
  connect(id) {
    const bot = super.connect(id);
    bot.on('entitySpawn', entity => {
      if (entity.name === 'falling_block') {
        const event = { id, at: Date.now(), entityId: entity.id, position: entity.position.clone(), metadata: entity.metadata };
        this.nativeFalling.push(event); this.log('nativeFallingEntity',event);
      }
    });
    bot._client.on('position', packet => this.rotations.push({ id, at: Date.now(), yaw: packet.yaw, pitch: packet.pitch }));
    return bot;
  }
  source(x,y,z) { return this.offset.offset(x,y,z); }
  seat(id) { return this.seats[id]; }
  note(id) { return this.source(...this.seat(id).note); }
  tower(id,y) { const point=this.seat(id).tower;return this.source(point[0],y,point[2]); }
  powder(id) { return `${rules.powderColors[this.seat(id).platform-1]}_concrete_powder`; }
  async condition(clause) {
    const token = `P2_POWER_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`,10);
    const spoken = new RegExp(`\\]\\s+${token}\\s*$`);
    return this.serverLines.some(row => spoken.test(row.text)) || this.chats.some(row => spoken.test(row.text));
  }
  async clickNote(id, count, delay = 80, note = this.note(id)) {
    for (let index = 0; index < count; index++) {
      this.bots[id]._client.write('block_place',{ hand:0,location:note,direction:this.seat(id).yaw===90?5:4,
        cursorX:.5,cursorY:.5,cursorZ:.5,insideBlock:false,sequence:++this.sequence });
      if (delay) await sleep(delay);
    }
    this.log('nativeNotePackets',{ id,count,delay,note });
  }
  async stat(id, count) { return this.condition(`if score ${name(id)} p2_power_tunes matches ${count}`); }
  async jump(id, scaled) {
    await this.console(`execute store result score ${name(id)} p2_power_jump run attribute ${name(id)} minecraft:generic.jump_strength base get 1000`);
    return this.condition(`if score ${name(id)} p2_power_jump matches ${scaled}`);
  }
  async sprint(ticks) {
    const since = this.serverLines.length;
    this.log('fixtureNote',{description:`Advance ${ticks} actual server ticks with native tick sprint.`});
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(since).some(row => /Sprint completed/i.test(row.text)),'native sprint completes',30000);
    await sleep(60);
  }
  async begin(label) {
    await this.console('scoreboard players set @a p2_power_tunes 0');
    const since = await this.start('power');
    this.log('powerCase',{label,since});
    this.seats = {};
    // Session participants are intentionally not identified by join order.
    // Resolve the original source seat from the actual native spawn first.
    for (const id of IDS) {
      const actual=this.bots[id].probePosition;
      this.seats[id]=rules.seats.find(seat=>actual.distanceTo(this.source(...seat.spawn))<.2);
      this.check(`${label}: ${id} uses an original source seat`,!!this.seats[id],{actual});
    }
    this.check(`${label}: all four occupied source seats are distinct`,new Set(Object.values(this.seats).map(seat=>seat.platform)).size===4);
    for (const id of IDS) {
      const spawn = this.source(...this.seat(id).spawn);
      this.check(`${label}: original independent seat spawn for ${id}`,this.bots[id].probePosition.distanceTo(spawn)<.2,{ actual:this.bots[id].probePosition,expected:spawn });
      this.check(`${label}: ${id} starts with no invented building items`,this.bots[id].inventory.items().length===0);
      this.check(`${label}: native source jump suppression for ${id}`,await this.jump(id,0));
      // 1.21.1 StatusEffectInstance uses Codecs.UNSIGNED_BYTE;255 is stored
      // as signed NBT -1b, rather than an integer255 predicate.
      this.check(`${label}: source weakness255 exists for ${id}`,await this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:weakness",amplifier:-1b}]}]`));
      await this.pinChunk(this.note(id));
      this.check(`${label}: clone contains actual native note block for ${id}`,await this.serverBlockIs(this.note(id),'note_block'));
    }
    return since;
  }
  async scenery() {
    const selected = [geometry.blocks.find(cell=>cell[0]===0), geometry.blocks.find(cell=>cell[0]===257),
      geometry.blocks.find(cell=>cell[2]===0), geometry.blocks.find(cell=>cell[2]===228),
      geometry.blocks.find(cell=>cell[1]===242)];
    for (const cell of selected) {
      const point = this.source(cell[0]-632,cell[1],cell[2]-107);
      const block = geometry.palette[cell[3]].Name.slice('minecraft:'.length);
      await this.pinChunk(point);
      this.check('Full distant/tall source scenery is present at unchanged coordinates',await this.serverBlockIs(point,block),{point,block});
    }
  }
  results(since) { return this.chats.find(row=>row.id==='A'&&row.at>=since&&/第\s+1\/5\s+轮排名/.test(row.text)); }
  release(since) { return this.chats.find(row=>row.id==='A'&&row.at>=since&&row.text.includes('停止调音')); }
  async finish(since, expected, label) {
    await this.wait(()=>this.results(since),`${label} actual native collapse is ranked`,30000);
    const result = this.results(since), release = this.release(since);
    this.check(`${label}: the source release phase occurs before ranking`,!!release&&release.at<=result.at);
    for (const [id,height] of Object.entries(expected)) {
      this.check(`${label}: ${id} is scored by actual TowerHeight=${height}`,this.chats.some(row=>row.id==='A'&&row.at>=since
        &&row.text.includes(name(id))&&row.text.includes(`${height} 格塔高`)),{id,height});
      this.check(`${label}: native jump strength restored for ${id}`,await this.jump(id,'419..421'));
      this.check(`${label}: source weakness removed for ${id}`,!await this.condition(`if entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:weakness"}]}]`));
    }
    this.check(`${label}: owned falling blocks and ghost bases are discarded`,!await this.condition('in pvp:arena if entity @e[tag=party_power_towers_falling]')
      &&!await this.condition('in pvp:arena if entity @e[tag=party_power_towers_base]'));
    this.check(`${label}: no powder drop items leak from native landing`,!await this.condition('in pvp:arena if entity @e[type=minecraft:item]'));
    await this.wait(()=>IDS.every(id=>this.bots[id].currentWindow&&this.menuChoices(this.bots[id].currentWindow).length),'next distinct-game vote',60000);
    this.check(`${label}: 419 is absent from the next vote`,IDS.every(id=>!this.menuChoices(this.bots[id].currentWindow).some(choice=>choice.source===419)));
    const cleanupAt=this.serverLines.length;
    await this.leaveAll(); await this.unpinChunks();
    await this.wait(()=>this.serverLines.slice(cleanupAt).some(row=>row.text.includes('已结束并清理')),'actual cleanup releases region zero',60000);
    return { release,result };
  }
  async ordinary() {
    const since=await this.begin('real tuning and collapse');
    // Actual tune events must be counted; left-click attacks are not tunes.
    this.bots.D._client.write('block_dig',{status:0,location:this.note('D'),face:1,sequence:++this.sequence});
    this.bots.D._client.write('block_dig',{status:1,location:this.note('D'),face:1,sequence:++this.sequence});
    await Promise.all([this.clickNote('A',20),this.clickNote('B',2),this.clickNote('C',1)]);
    for (const [id,count] of Object.entries({A:20,B:2,C:1,D:0})) this.check('Real native tune_noteblock criterion reflects only right-click tuning',await this.stat(id,count),{id,count});
    this.check('Twenty valid native tunes grow the original powder column toY71',await this.serverBlockIs(this.tower('A',71),this.powder('A')));
    this.check('No click-total score is awarded before the physical collapse',!this.results(since));
    await this.scenery();
    const {release}=await this.finish(since,{A:20,B:2,C:1,D:0},'0/1/2 compensation plus native falling');
    this.check('Real native falling_block entities were transmitted during collapse',this.nativeFalling.some(row=>row.id==='A'&&row.at>=release.at));
    const turnYaw=this.seat('A').yaw+180;
    this.check('Source turn-around is a real native player rotation',this.rotations.some(row=>row.id==='A'&&row.at>=release.at-100&&Math.abs(((row.yaw-turnYaw)%360+360)%360)<.1));
    for (const [index,id] of IDS.entries()) this.check('Final tower ordering produces the five-game round points',this.chats.some(row=>row.id==='A'&&row.at>=since
      &&row.text.includes(`第 ${index+1} 名 ${name(id)}`)&&row.text.includes(`本轮 +${4-index}`)));
  }
  async batch() {
    const since=await this.begin('source >7 same-tick batch');
    this.log('fixtureNote',{description:'Temporarily lower this isolated server tick rate to1 so eight real native tuning packets occupy one game tick. No production CPS branch or synthetic stat value is used.'});
    await this.console('tick rate 1');
    try {
      await this.clickNote('A',8,0);
      await sleep(1250);
      this.check('Eight real native tunes are accepted before applying the original batch rule',await this.stat('A',8));
      this.check('The >7 tick batch does not extend the source tower above its baseline',await this.serverBlockIs(this.tower('A',54),'air'));
    } finally { await this.console('tick rate 20'); }
    await this.clickNote('A',2,100);
    this.check('Two later accepted tunes still only reach the baseline after the ignored batch',await this.stat('A',10)
      &&await this.serverBlockIs(this.tower('A',54),'air'));
    await this.clickNote('A',1,100);
    this.check('The third later tune creates the first actually scored falling powder cell',await this.stat('A',11)
      &&await this.serverBlockIs(this.tower('A',54),this.powder('A')));
    await this.finish(since,{A:3,B:0,C:0,D:0},'ignored8 then3 valid tunes');
  }
  async zero() {
    const since=await this.begin('fifteen empty falling ticks');
    const {release,result}=await this.finish(since,{A:0,B:0,C:0,D:0},'all zero native tunes');
    // All four baseline powder cells are removed before falling. Source adds
    // its first empty tick on release, then finishes fourteen ticks later.
    const elapsed=result.at-release.at;
    this.check('Zero towers still wait the source15 empty ticks instead of ending immediately',elapsed>=600&&elapsed<2500,{wallMilliseconds:elapsed,sourceEmptyTicks:15});
    this.check('Four equal original zero heights share first place and +4 points',IDS.every(id=>this.chats.some(row=>row.id==='A'&&row.at>=since
      &&row.text.includes(`第 1 名 ${name(id)}`)&&row.text.includes('本轮 +4'))));
  }
}

async function main() {
  const probe=new PowerProbe();let error;
  try {
    await probe.launch();await probe.connectAll();
    await probe.console('scoreboard objectives add p2_power_tunes minecraft.custom:minecraft.tune_noteblock');
    await probe.console('scoreboard objectives add p2_power_jump dummy');
    await probe.ordinary();await probe.batch();await probe.zero();
    probe.log('coverageLimit',{note:'Native block tuning packets/stat criteria, actual powder blocks/falling entities/tripwire scoring and source results are tested. Tick-rate1 is an explicitly isolated timing fixture for the original same-tick>7 rule. No rendered natural-client acceptance claim.'});
  } catch(failure) {error=failure;probe.log('failure',{error:failure.stack});}
  const report=await probe.close(error);if(!report.ok)process.exitCode=1;
}
if(require.main===module)void main();
