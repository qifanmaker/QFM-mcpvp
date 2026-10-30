'use strict';

// Reason: the original inline games must reset correctly when followed by the
// newer modules. Pre-review: observe real protocol/world/stats results on an
// isolated immutable runtime; label accelerated time and elimination fixtures,
// keep live chunks loaded before proving cleanup, never alter game Java code.
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { Vec3 } = require('vec3');
const IDS = ['A', 'B', 'C', 'D'];
const PLAN = ['spleef', 'tile', 'meat', 'oitq', 'memory'];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const position = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
const name = id => `MyGoCheck${id}`;
function uuid(id) {
  const bytes = crypto.createHash('md5').update(`OfflinePlayer:${name(id)}`).digest();
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

class InternalProbe extends PartyProbe {
  constructor() {
    super({ port: 25584, runDir: path.join(__dirname, '../build/mygo-party2-internal') });
    this.rounds = new Map(); this.finalRows = new Map(); this.finalHeaders = 0;
    this.menus = []; this.totals = new Map(IDS.map(id => [id, 0]));
    // Newly migrated entries may expand the catalogue; count the actual first
    // menu and continue verifying that each played project is removed.
    this.initialChoiceCount = null;
  }

  connect(id) {
    const bot = super.connect(id);
    bot.on('messagestr', message => {
      if (id !== 'A') return;
      const text = String(message).replace(/§./g, '');
      const header = text.match(/第\s+(\d+)\/5\s+轮排名/);
      if (header) {
        this.header = Number(header[1]); this.rounds.set(this.header, new Map());
      }
      if (text.includes('Party 2 五轮总排名')) { this.header = 'final'; this.finalHeaders++; }
      const row = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([A-F]).*本轮\s+\+(\d+)｜累计\s+(\d+)/);
      if (row && typeof this.header === 'number') this.rounds.get(this.header).set(row[2], {
        place: Number(row[1]), awarded: Number(row[3]), total: Number(row[4]), text
      });
      const final = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([A-F])\s+累计\s+(\d+)\s+分/);
      if (final && this.header === 'final') this.finalRows.set(final[2], { place: Number(final[1]), total: Number(final[3]) });
    });
    return bot;
  }

  async vote(id, game) {
    await this.wait(() => this.menuChoices(this.bots[id].currentWindow).length > 0, `${id} menu lore`, 2500);
    const choices = this.menuChoices(this.bots[id].currentWindow).map(item => item.source);
    if (this.initialChoiceCount == null) this.initialChoiceCount = choices.length;
    const index = PLAN.indexOf(game), played = PLAN.slice(0, index).map(key => GAMES[key].source);
    this.check(`Round ${index + 1} ${id} sees only unplayed choices`, choices.length === this.initialChoiceCount - index
      && played.every(source => !choices.includes(source)), { choices, played });
    this.menus.push({ id, game, at: Date.now() });
    return super.vote(id, game);
  }

  async until(predicate, label, timeout = 15000) {
    await this.wait(() => { if (this.voteError) throw this.voteError; return predicate(); }, label, timeout);
  }

  async nextStarted(round, game) {
    const selected = () => this.chats.find(row => row.id === 'A' && row.at >= this.started
      && new RegExp(`第\\s+${round}/5\\s+轮选择：.*原版编号\\s+${GAMES[game].source}`).test(row.text));
    await this.until(() => !!selected(), `${game} selected in round ${round}`, 45000);
    await this.until(() => IDS.every(id => this.bots[id].probeStartedAt >= selected().at), `${game} entered PLAY`, 45000);
    this.game = null;
    this.check(`Round ${round} revives every previous participant`, IDS.every(id => this.bots[id].probeWorld === 'pvp:arena'
      && this.bots[id].game.gameMode === 'adventure'), IDS.map(id => this.snapshot(id)));
  }

  async condition(condition) {
    const token = `P2_INTERNAL_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(`[Server] ${token}`));
  }

  async jumpStrength(id, expected, label) {
    await this.console(`execute store result score ${name(id)} p2_internal_attr run attribute ${name(id)} minecraft:generic.jump_strength base get 1000`);
    this.check(label, await this.condition(`if score ${name(id)} p2_internal_attr matches ${expected}`), { id, expectedScaledValue: expected });
  }

  async sprint(ticks) {
    const start = this.serverLines.length;
    this.log('fixtureNote', { description: `Advance ${ticks} ticks, then wait for the server's Sprint completed acknowledgement.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(start).some(row => /Sprint completed/i.test(row.text)), 'tick sprint completed', 60000);
    await sleep(70); // Let the independently delivered result packets catch up.
  }

  async timeoutRound(round, maximumTicks) {
    for (let advanced = 0; !this.rounds.get(round)?.size && advanced < maximumTicks; advanced += 100) await this.sprint(100);
    await this.until(() => this.rounds.get(round)?.size === 4, `round ${round} time limit produces all rankings`, 3000);
  }

  stats() {
    const file = path.join(this.runDir, 'config/pvp/stats.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
  }
  matches(stats, id) { return stats[uuid(id)]?.matches || 0; }

  async verifyRound(round) {
    await this.until(() => this.rounds.get(round)?.size === 4, `round ${round} ranking rows`);
    for (const id of IDS) {
      const row = this.rounds.get(round).get(id), points = 5 - row.place;
      this.totals.set(id, this.totals.get(id) + points);
      this.check(`Round ${round} ${id} adds placement points exactly once`, row.awarded === points
        && row.total === this.totals.get(id), row, { points, total: this.totals.get(id) });
    }
    this.check(`Round ${round} has not written an individual match`, IDS.every(id => this.matches(this.stats(), id)
      === this.matches(this.baseline, id)), this.stats());
    this.check(`Round ${round} still has no final result`, this.finalHeaders === 0, { finalHeaders: this.finalHeaders });
  }

  async spleef() {
    const p = position(this.bots.A), floor = p.floored().offset(0, -1, 0);
    await this.wait(() => !!this.block('A', floor) && this.block('A', floor).name !== 'air', 'Spleef live floor loaded');
    this.oldFloor = floor;
    await this.pinChunk(floor);
    this.check('Spleef cleanup witness starts as non-air world geometry', await this.serverBlockIs(floor, this.block('A', floor).name),
      { floor, block: this.block('A', floor).name });
    const shovel = this.bots.A.inventory.items().find(item => item.name === 'iron_shovel');
    this.log('realItemDrop', { id: 'A', item: shovel?.name, position: p });
    await this.bots.A.tossStack(shovel);
    await this.console('execute in pvp:arena run tag @e[type=minecraft:item,nbt={Item:{id:"minecraft:iron_shovel"}}] add p2_spleef_drop');
    this.check('Real Spleef equipment drop exists before round end', await this.condition('in pvp:arena if entity @e[type=minecraft:item,tag=p2_spleef_drop]'));
    // Spleef intentionally rejects direct damage, including /kill. Enter its
    // actual below-map threshold instead and observe the survival state change.
    this.log('fixtureNote', { description: 'Teleport B, C, then D below the actual Spleef elimination plane; A survives. This tests lifecycle, not shovel aiming.' });
    for (const id of ['B', 'C']) {
      await this.teleport(id, p.offset(0, -40, 0));
      await this.until(() => this.bots[id].game.gameMode === 'spectator', `${id} really spectates before round end`);
      this.check(`Spleef ${id} is eliminated before later rounds`, this.bots[id].game.gameMode === 'spectator', this.snapshot(id));
    }
    await this.teleport('D', p.offset(0, -40, 0));
    await this.until(() => this.rounds.get(1)?.size === 4, 'Spleef sole survivor ranking');
    this.check('Spleef winner is A after B/C/D elimination', this.rounds.get(1).get('A').place === 1, [...this.rounds.get(1)]);
    this.check('Spleef dropped equipment is removed at round end', !await this.condition('in pvp:arena if entity @e[type=minecraft:item,tag=p2_spleef_drop]'));
  }

  async tournament() {
    this.baseline = this.stats(); this.started = Date.now();
    await this.start('spleef'); this.game = 'tile';
    await this.spleef(); await this.verifyRound(1);
    await this.until(() => this.menus.some(menu => menu.id === 'A' && menu.game === 'tile'), 'second voting phase starts');
    this.check('Spleef original floor cleared before second map build', await this.serverBlockIs(this.oldFloor, 'air'), { floor: this.oldFloor });
    const lobbyFloor = position(this.bots.A).floored().offset(0, -1, 0);
    await this.pinChunk(lobbyFloor);
    this.check('Round cleanup preserves the waiting platform', await this.serverBlockIs(lobbyFloor, 'light_blue_stained_glass'), { lobbyFloor });
    for (let round = 2; round <= 5; round++) {
      const game = PLAN[round - 1];
      await this.nextStarted(round, game); this.game = PLAN[round] || null;
      if (game === 'tile') {
        for (const id of IDS) await this.jumpStrength(id, '0', `Tile sets ${id} jump strength to zero`);
        this.check('Tile gives only its own reset tool after Spleef', IDS.every(id => this.bots[id].inventory.items().every(item => item.name === 'carrot_on_a_stick')),
          IDS.map(id => this.snapshot(id)));
      }
      if (game === 'meat' || game === 'oitq') {
        for (const id of IDS) await this.jumpStrength(id, '419..421', `${game} restores ${id} jump strength after Tile`);
      }
      if (game === 'memory') this.check('Memory starts a new viewing state for all four players', IDS.every(id => !this.bots[id].heldItem), IDS.map(id => this.snapshot(id)));
      await this.timeoutRound(round, game === 'meat' ? 2500 : 2100);
      await this.verifyRound(round);
    }
    await this.until(() => this.finalRows.size === 4, 'single final five-round ranking', 30000);
    for (const id of IDS) {
      const total = this.totals.get(id), place = 1 + [...this.totals.values()].filter(value => value > total).length;
      this.check(`${id} final rank uses the five-round sum`, this.finalRows.get(id)?.total === total && this.finalRows.get(id)?.place === place,
        this.finalRows.get(id), { place, total });
    }
    await this.until(() => IDS.every(id => this.bots[id].probeWorld === 'minecraft:overworld'), 'tournament returns players to main lobby', 30000);
    await this.until(() => IDS.every(id => this.matches(this.stats(), id) === this.matches(this.baseline, id) + 1), 'exactly one saved match per player', 25000);
    this.check('Exactly one final header was published', this.finalHeaders === 1, { finalHeaders: this.finalHeaders });
    for (const id of IDS) {
      const before = this.baseline[uuid(id)] || {}, after = this.stats()[uuid(id)], won = this.finalRows.get(id).place === 1;
      this.check(`${id} receives one match and its actual cumulative win/loss`, after.matches === (before.matches || 0) + 1
        && after.wins === (before.wins || 0) + Number(won) && after.losses === (before.losses || 0) + Number(!won), { before, after, won });
    }
    this.check('Waiting platform is cleared after the whole match', await this.waitServerBlock(lobbyFloor, 'air'), { lobbyFloor });
  }
}

async function main() {
  const probe = new InternalProbe(); let error;
  try {
    await probe.launch(); await probe.connectAll();
    await probe.console('scoreboard objectives add p2_internal_attr dummy');
    await probe.tournament();
  } catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
module.exports = { InternalProbe };
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
