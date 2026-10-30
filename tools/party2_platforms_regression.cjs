'use strict';

// Reason: Platforms must retain six simultaneous diamond choices, real native
// marker trajectories/pickups and the two invisible sign rounds inside one
// outer tournament round. Review: own isolated Fabric runtime, real drop/click
// packets, observe inventories before reading scores, pin live map before clear.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const path = require('node:path');
const IDS = ['A', 'B', 'C', 'D'];
const STARTS = [[-11, -47], [-7, -43], [-3, -47], [-7, -51]];
const SIGNS = [
  [[-11, -48], [-10, -47], [-11, -46]],
  [[-8, -43], [-7, -44], [-6, -43]],
  [[-3, -46], [-4, -47], [-3, -48]],
  [[-6, -51], [-7, -50], [-8, -51]]
];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const position = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
const count = (bot, item) => bot.inventory.items().filter(stack => stack.name === item).reduce((n, stack) => n + stack.count, 0);

class PlatformsProbe extends PartyProbe {
  constructor() {
    super({ port: 25604, runDir: path.join(__dirname, '../build/mygo-party2-platforms') });
    this.internalRound = 0;
    this.tallies = new Map(); this.landed = new Map(); this.selected = new Map();
    this.positions = new Map(); this.inventoryMaxima = new Map();
  }

  connect(id) {
    const bot = super.connect(id);
    bot._client.on('packet', (packet, meta) => {
      if (!meta.name.includes('title')) return;
      const text = JSON.stringify(packet);
      const match = text.match(/第\s*(\d+)\/6\s*轮/);
      if (match && id === 'A') {
        this.internalRound = Number(match[1]);
        this.log('platformInternalRound', { round: this.internalRound, packet });
      }
    });
    bot.on('messagestr', message => {
      const text = String(message).replace(/§./g, '');
      const tally = text.match(/本轮拾取\s*(\d+)\s*颗钻石\s*·\s*累计\s*(\d+)/);
      if (tally) {
        const rows = this.tallies.get(this.internalRound) || new Map();
        rows.set(id, { amount: Number(tally[1]), total: Number(tally[2]), at: Date.now() });
        this.tallies.set(this.internalRound, rows);
      }
      if (/同一平台|赢得了这个平台/.test(text)) {
        const rows = this.landed.get(this.internalRound) || new Map();
        rows.set(id, { knocked: text.includes('同一平台'), text, at: Date.now() });
        this.landed.set(this.internalRound, rows);
      }
      if (/已选择/.test(text)) {
        const rows = this.selected.get(this.internalRound) || new Map();
        rows.set(id, text); this.selected.set(this.internalRound, rows);
      }
    });
    bot.once('spawn', () => bot.inventory.on('updateSlot', () => {
      if (!this.internalRound) return;
      const rows = this.inventoryMaxima.get(this.internalRound) || new Map();
      rows.set(id, Math.max(rows.get(id) || 0, count(bot, 'diamond')));
      this.inventoryMaxima.set(this.internalRound, rows);
    }));
    return bot;
  }

  async condition(condition) {
    const token = `P2_PLATFORMS_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(`[Server] ${token}`));
  }

  async ready(round) {
    await this.wait(() => this.internalRound === round && (round >= 5
      || IDS.every(id => count(this.bots[id], 'paper') === 3)), `internal round ${round} ready`, 18000);
    await sleep(200);
    const positions = Object.fromEntries(IDS.map(id => [id, position(this.bots[id])]));
    this.positions.set(round, positions);
    const xs = Object.values(positions).map(p => p.x), zs = Object.values(positions).map(p => p.z);
    this.offset = new Vec3(Math.min(...xs) + 10.5, positions.A.y - 45, Math.min(...zs) + 50.5);
    this.seats = Object.fromEntries(IDS.map(id => [id, STARTS.findIndex(([x, z]) =>
      positions[id].distanceTo(this.source(x + .5, 45, z + .5)) < .1)]));
    this.check(`Round ${round} randomly assigns four distinct source spots`, new Set(Object.values(this.seats)).size === 4
      && Object.values(this.seats).every(spot => spot >= 0), { positions, seats: this.seats });
    this.check(`Round ${round} uses correct selector type`, IDS.every(id => count(this.bots[id], 'paper') === (round <= 4 ? 3 : 0)),
      IDS.map(id => this.snapshot(id)));
    if (round === 3 && this.dropTag) this.check('Post-tally player-dropped diamonds cannot survive into the next internal round',
      !await this.condition(`in pvp:arena if entity @e[type=minecraft:item,tag=${this.dropTag}]`));
    if (round === 1) {
      const floor = this.source(-11, 44, -47).floored();
      await this.pinChunk(floor); this.floor = floor;
      this.check('Original Platforms floor exists before cleanup', await this.serverBlockIs(floor, 'chiseled_quartz_block'), { floor });
    }
    if (round >= 5) {
      for (const id of IDS) this.check(`Round ${round} ${id} is invisible while choosing`, await this.condition(
        `if entity @a[name=MyGoCheck${id},nbt={active_effects:[{id:"minecraft:invisibility"}]}]`));
    }
  }

  source(x, y, z) { return this.offset.offset(x, y, z); }

  async select(round, plan) {
    for (const id of IDS) {
      const choice = plan[this.seats[id]], bot = this.bots[id];
      if (round <= 4) {
        const paper = bot.inventory.slots[36 + choice];
        this.check(`Round ${round} ${id} has its chosen paper`, paper?.name === 'paper', { choice, paper: paper?.name });
        this.log('realSelectionDrop', { id, round, choice, sourceSpot: this.seats[id], position: position(bot) });
        await bot.tossStack(paper);
      } else {
        const [x, z] = SIGNS[this.seats[id]][choice], target = this.source(x, 46, z).floored();
        await this.wait(() => this.block(id, target)?.name?.includes('sign'), `${id} source selection sign loaded`, 2500);
        this.log('realSelectionSign', { id, round, choice, sourceSpot: this.seats[id], target });
        await this.click(id, target);
      }
    }
    await this.wait(() => this.selected.get(round)?.size === 4, `all four round ${round} selections accepted`, 2000);
    this.check(`Round ${round} consumes every player's selector once`, IDS.every(id => count(this.bots[id], 'paper') === 0),
      IDS.map(id => this.snapshot(id)));
  }

  async verifyRound(round, plan, knockedSeats) {
    await this.select(round, plan);
    const started = Date.now();
    await this.wait(() => this.landed.get(round)?.size === 4, `round ${round} native launch reaches collision/pickup stage`, 10000);
    const rows = this.landed.get(round);
    this.check(`Round ${round} uses the 104 tick launch interval`, Date.now() - started >= 5500,
      { durationMs: Date.now() - started, expected: '20-tick launch delay +104-tick flight' });
    for (const id of IDS) this.check(`Round ${round} ${id} collision result follows chosen platforms`, rows.get(id).knocked === knockedSeats.includes(this.seats[id]),
      { seat: this.seats[id], choice: plan[this.seats[id]], ...rows.get(id), position: position(this.bots[id]) });
    if (round === 2) {
      const bot = this.bots.A;
      await this.wait(() => bot.inventory.items().some(item => item.name === 'diamond'), 'A actually picks up a round2 diamond before F swap', 500);
      const diamond = bot.inventory.items().find(item => item.name === 'diamond');
      await bot.equip(diamond, 'hand');
      this.log('realOffhandSwap', { id: 'A', before: this.snapshot('A'), description: 'Native F swap during the actual 20 tick pickup window; no console inventory injection.' });
      bot._client.write('block_dig', { status: 6, location: new Vec3(0, 0, 0), face: 0, sequence: ++this.sequence });
      await this.wait(() => bot.inventory.slots[45]?.name === 'diamond', 'F swap places the real diamonds in offhand before tally', 500);
      this.check('Real F swap retains diamonds in offhand during the collection interval', bot.inventory.slots[45]?.count === diamond.count,
        { offhand: bot.inventory.slots[45]?.count, mainHand: bot.heldItem?.name, amount: diamond.count });
    }
    await this.wait(() => this.tallies.get(round)?.size === 4, `round ${round} pickup results tallied after 20 ticks`, 2500);
    for (const id of IDS) {
      const tally = this.tallies.get(round).get(id), observed = this.inventoryMaxima.get(round)?.get(id) || 0;
      const previous = this.tallies.get(round - 1)?.get(id)?.total || 0;
      this.check(`Round ${round} ${id} score uses actual native inventory pickup`, tally.amount === observed
        && tally.total === previous + tally.amount && (!rows.get(id).knocked || tally.amount === 0), { tally, observed, previous });
      if (!rows.get(id).knocked) this.check(`Round ${round} ${id} really picks up a diamond from a unique platform`, tally.amount > 0, tally);
    }
  }

  async runPlatforms() {
    const since = await this.start('platforms');
    for (let round = 1; round <= 6; round++) {
      await this.ready(round);
      if (round === 1 || round === 4 || round === 6) await this.verifyRound(round, [1, 1, 1, 1], [0, 1, 2, 3]);
      else if (round === 3) await this.verifyRound(round, [0, 0, 0, 2], [0, 3]);
      else await this.verifyRound(round, [0, 0, 0, 0], []);
      this.check(`Internal round ${round} has no premature outer ranking`, !this.chats.some(row => row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)));
      if (round === 2) {
        const diamond = this.bots.B.inventory.items().find(item => item.name === 'diamond');
        this.log('realPostTallyDiamondDrop', { id: 'B', count: diamond?.count, position: position(this.bots.B) });
        await this.bots.B.tossStack(diamond);
        // Keep the witness from being picked up again before source jump4's
        // cleanup. The drop is a real client action; relocation is a fixture.
        const [x, z] = STARTS[this.seats.B];
        this.log('fixtureNote', { description: 'Move B back to its now-empty starting platform immediately after the real post-tally drop so pickup cannot erase the cleanup witness.' });
        await this.teleport('B', this.source(x + .5, 45, z + .5));
        this.dropTag = `p2_platforms_drop_${++this.sequence}`;
        await this.console(`execute in pvp:arena as @e[type=minecraft:item,nbt={Item:{id:"minecraft:diamond"}}] if data entity @s Thrower run tag @s add ${this.dropTag}`);
        this.check('A real player-dropped diamond witness exists before source cleanup',
          await this.condition(`in pvp:arena if entity @e[type=minecraft:item,tag=${this.dropTag}]`));
      }
    }
    await this.wait(() => this.chats.some(row => row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)), 'sixth internal round ends one outer round', 5000);
    this.check('All six source rounds completed exactly once', this.tallies.size === 6 && [...this.tallies.values()].every(rows => rows.size === 4));
    for (const id of IDS) this.check(`${id} invisibility restored at minigame end`, !await this.condition(
      `if entity @a[name=MyGoCheck${id},nbt={active_effects:[{id:"minecraft:invisibility"}]}]`));
    this.check('Native armor stands and diamonds are cleaned when the round closes', !await this.condition(
      'in pvp:arena if entity @e[type=minecraft:armor_stand]') && !await this.condition('in pvp:arena if entity @e[type=minecraft:item]'));
    this.check('Original Platforms floor is cleared before the next vote', await this.waitServerBlock(this.floor, 'air'));
    await this.leaveAll();
  }

  async forfeitDoesNotStall() {
    this.internalRound = 0; this.tallies.clear(); this.landed.clear(); this.selected.clear(); this.inventoryMaxima.clear();
    await this.start('platforms'); await this.ready(1);
    this.log('fixtureNote', { description: 'C invokes the ordinary /hub forfeit while all four paper selectors exist. The original waits for four forever; this server adaptation waits for remaining eligible participants.' });
    await this.chat('C', '/hub');
    await this.wait(() => this.bots.C.probeWorld === 'minecraft:overworld', 'quitter C really leaves arena');
    this.log('fixtureNote', { description: 'Console arranges two separate diamond stacks of count2 in A hotbar slots3/4. This isolates the source Inventory count-existence queries; it is not counted as native diamond pickup. All remaining players choose middle and collide, so no new native gems affect the fixture.' });
    await this.console('item replace entity MyGoCheckA hotbar.3 with minecraft:diamond 2');
    await this.console('item replace entity MyGoCheckA hotbar.4 with minecraft:diamond 2');
    this.check('Equal-stack fixture contains two separate count2 stacks before launch', this.bots.A.inventory.slots[39]?.count === 2
      && this.bots.A.inventory.slots[40]?.count === 2, this.snapshot('A'));
    for (const id of ['A', 'B', 'D']) await this.bots[id].tossStack(this.bots[id].inventory.slots[37]);
    await this.wait(() => this.landed.get(1)?.size === 3, 'three remaining choices launch without waiting for quitter', 10000);
    await this.wait(() => this.tallies.get(1)?.size === 3, 'only three remaining players receive a new tally', 2500);
    this.check('Forfeit does not fabricate a fourth choice or a quitter tally', !this.tallies.get(1).has('C')
      && this.tallies.get(1).size === 3 && this.bots.C.probeWorld === 'minecraft:overworld', [...this.tallies.get(1)]);
    this.check('Source count2 existence query awards two equal stacks only once', this.tallies.get(1).get('A').amount === 2
      && this.tallies.get(1).get('A').total === 2 && count(this.bots.A, 'diamond') === 4, {
        row: this.tallies.get(1).get('A'), actualDiamondCount: count(this.bots.A, 'diamond') });
    this.check('Forfeit restores the ordinary lobby compass', count(this.bots.C, 'compass') === 1, this.snapshot('C'));
    await this.leaveAll();
  }
}

async function main() {
  const probe = new PlatformsProbe(); let error;
  try { await probe.launch(); await probe.connectAll(); await probe.runPlatforms(); await probe.forfeitDoesNotStall(); }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
module.exports = { PlatformsProbe };
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
