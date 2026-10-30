'use strict';

// Reason: source423 accepts finishes before the countdown ends. Shared PLAY
// preparation must preserve those spectators, scores and absolute finish order.
// Review: own25680 only; terminal TP is explicitly a boundary fixture, not a
// claim of rendered full-course flight. Every cleanup witness is pinned live.
const path = require('node:path');
const { Vec3 } = require('vec3');
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

class RocketIntroProbe extends PartyProbe {
  constructor() {
    super({ port: 25680, runDir: path.join(__dirname, '../build/mygo-party2-rocket-intro') });
    this.rows = new Map(); this.roundEnded = false; this.voting = false;
  }
  connect(id) {
    const bot = super.connect(id);
    bot.on('messagestr', message => {
      if (id !== 'A') return;
      const text = String(message).replace(/§./g, '');
      if (/第\s+1\/5\s+轮排名/.test(text)) this.roundEnded = true;
      if (/第\s+2\/5\s+轮.*投票/.test(text)) this.voting = true;
      const row = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([AB])\s+(\d+) 格.*本轮\s+\+(\d+)｜累计\s+(\d+)/);
      if (row) this.rows.set(row[2], { place: +row[1], distance: +row[3], points: +row[4], total: +row[5] });
    });
    return bot;
  }
  async condition(clause) {
    const token = `P2_ROCKET_INTRO_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`, 20);
    return this.serverLines.some(row => row.text.endsWith(`] ${token}`))
      || this.chats.some(row => row.text === token || row.text.endsWith(`] ${token}`));
  }
  async startIntro() {
    this.game = null;
    await this.console('execute as @a run hub');
    await this.wait(() => Object.values(this.bots).every(bot => bot.probeWorld === 'minecraft:overworld'), 'clean hub baseline', 15000);
    this.rows.clear(); this.roundEnded = false; this.voting = false;
    this.game = 'rocket'; this.voteError = null;
    const since = Date.now();
    for (const id of ['A', 'B']) await this.chat(id, '/pvp join minecraftparty');
    await this.chat('A', '/pvp start');
    await this.wait(() => {
      if (this.voteError) throw this.voteError;
      return this.chats.some(row => row.id === 'A' && row.at >= since && /轮选择：.*编号 423/.test(row.text));
    }, 'real vote selects423', 90000);
    this.selectedAt = Date.now();
    for (const bot of Object.values(this.bots)) bot.probeStartedAt = 0;
    await this.wait(() => Object.values(this.bots).every(bot => bot.probeWorld === 'pvp:arena'
      && Math.abs(bot.probePosition.y - 138) < 0.01 && bot.inventory.slots[6]?.name === 'elytra'), 'complete13M scene enters original intro', 300000);
    this.game = null;
    const position = this.bots.A.probePosition;
    this.offset = new Vec3(position.x + 62, position.y - 138, position.z - 120);
    this.source = (x, y, z) => this.offset.offset(x, y, z);
    this.bedrock = this.source(-62, -64, -2043);
    this.platform = this.source(-62, 137, 120);
    for (const witness of [this.bedrock, this.platform]) await this.pinChunk(witness);
    this.check('Full distant source bedrock exists before any boundary fixture', await this.serverBlockIs(this.bedrock, 'bedrock'));
    this.check('Original launch platform retains red sandstone', await this.serverBlockIs(this.platform, 'red_sandstone'));
    this.check('No PLAY title has occurred before the terminal intro fixture', Object.values(this.bots).every(bot => !bot.probeStartedAt));
    this.log('introReady', { offset: this.offset, buildMillis: Date.now() - this.selectedAt });
  }
  async finishOneDuringIntro() {
    await this.startIntro();
    this.log('fixtureNote', { description: 'Teleport A to source(-62,203,-2049) during INTRO. Expected117-floorZ=2166 plus203-196=7 gives2173, not a2160 cap.' });
    await this.teleport('A', this.source(-62, 203, -2049));
    await this.wait(() => this.bots.A.game.gameMode === 'spectator', 'source intro acceptsA terminal finish', 2500);
    this.check('One precompleted player does not end an all-finish game', !this.roundEnded && this.bots.B.game.gameMode === 'adventure');
    await this.wait(() => this.bots.B.probeStartedAt >= this.selectedAt, 'remainingB reaches PLAY', 15000);
    this.check('Shared PLAY preparation preserves precompletedA spectator', await this.condition('if entity @a[name=MyGoCheckA,gamemode=spectator]'));
    this.check('Source start removes high-finishA wings without reequipping', await this.condition('unless entity @a[name=MyGoCheckA,nbt={Inventory:[{id:"minecraft:elytra"}]}]'));
    this.check('UnfinishedB receives its native Elytra and remains playable', await this.condition('if entity @a[name=MyGoCheckB,gamemode=adventure,nbt={Inventory:[{id:"minecraft:elytra"}]}]'));
    this.log('fixtureNote', { description: 'B reaches the identical terminal position later; equal distance must retain original earlier-completion tiebreak.' });
    await this.teleport('B', this.source(-62, 203, -2049));
    await this.wait(() => this.rows.size === 2, 'both exact distance rankings arrive', 3000);
    this.check('Overflow distance and final height are preserved for both', [...this.rows.values()].every(row => row.distance === 2173), Object.fromEntries(this.rows));
    this.check('Absolute intro finish order survives the PLAY clock reset', this.rows.get('A').place === 1 && this.rows.get('A').points === 2
      && this.rows.get('B').place === 2 && this.rows.get('B').points === 1, Object.fromEntries(this.rows));
    await this.verifyCleanup();
    await this.leaveAll(); await this.unpinChunks();
  }
  async finishEveryoneDuringIntro() {
    await this.startIntro();
    const target = this.source(-62, 203, -2049);
    this.log('fixtureNote', { description: 'One native command moves A and B to the exact same terminal position before the next source tick, proving same-tick intro finish ties.' });
    await this.console('tag MyGoCheckA add p2_rocket_intro_terminal');
    await this.console('tag MyGoCheckB add p2_rocket_intro_terminal');
    await this.console(`execute in pvp:arena as @a[tag=p2_rocket_intro_terminal] run tp @s ${target.x.toFixed(8)} ${target.y.toFixed(8)} ${target.z.toFixed(8)}`);
    await this.console('tag @a remove p2_rocket_intro_terminal');
    await this.wait(() => this.rows.size === 2, 'whole round settles during INTRO', 3000);
    this.check('All intro finishes settle once without starting PLAY', Object.values(this.bots).every(bot => !bot.probeStartedAt));
    this.check('Same-tick source finishes share first place and full rank points', [...this.rows.values()].every(row => row.place === 1
      && row.distance === 2173 && row.points === 2 && row.total === 2), Object.fromEntries(this.rows));
    await this.verifyCleanup();
    await this.leaveAll(); await this.unpinChunks();
  }
  async verifyCleanup() {
    const since = Date.now();
    await this.wait(() => this.voting, 'full13M scene cleans before next SELECT', 240000);
    this.check('Far original underground body is removed before next selection', await this.serverBlockIs(this.bedrock, 'air'));
    this.check('Launch structure is also removed before next selection', await this.serverBlockIs(this.platform, 'air'));
    this.check('No original finish statue survives cleanup', await this.condition('in pvp:arena unless entity @e[tag=party_rocket_launch_stand]'));
    this.log('fullSceneCleanup', { millis: Date.now() - since });
  }
}

async function main() {
  const probe = new RocketIntroProbe(); let error;
  try {
    await probe.launch(); await probe.connectAll('AB');
    await probe.finishOneDuringIntro(); await probe.finishEveryoneDuringIntro();
  } catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
