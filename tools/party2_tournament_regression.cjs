'use strict';

// Reason: five-round Party matches must accumulate placement points without
// settling five separate matches, repeating games, or carrying player state.
// Pre-review: use only the owned 25598 server; never modify the shared probe or
// game implementation. Menu clicks and bow shots are real protocol actions;
// finish-zone teleports, fall damage and tick sprint are explicit fixtures.
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const clean = value => String(value).replace(/§./g, '');
const json = value => JSON.stringify(value, (_, item) => typeof item === 'bigint' ? item.toString() : item);
const name = id => `MyGoCheck${id}`;
const pos = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
const PLAN = [401, 428, 424, 415, 413];
const IDS = ['A', 'B', 'C', 'D'];

function offlineUuid(username) {
  const bytes = crypto.createHash('md5').update(`OfflinePlayer:${username}`).digest();
  bytes[6] = (bytes[6] & 15) | 48; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

class TournamentProbe extends PartyProbe {
  constructor() {
    super({ port: 25598, runDir: path.join(__dirname, '../build/mygo-party2-tournament') });
    this.caseNumber = 0;
    this.game = null; // The base probe's fixed-slot voter is intentionally unused.
    this.voteRequests = new Set();
    this.resetCase();
  }

  resetCase() {
    this.caseNumber++;
    this.caseStart = Date.now();
    this.nextRound = 1;
    this.targetSource = PLAN[0];
    this.activeIds = [...IDS];
    this.played = new Set();
    this.selected = [];
    this.menuHistory = [];
    this.roundRows = new Map();
    this.finalRows = new Map();
    this.totals = new Map(IDS.map(id => [id, 0]));
    this.header = null;
    this.voteError = null;
    this.initialChoiceCount = null;
    this.finalHeaders = 0;
  }

  connect(id) {
    const bot = super.connect(id);
    bot.on('windowOpen', window => {
      if (json(window.title).includes('Party')) {
        void this.voteDynamic(id, window).catch(error => {
          this.voteError = error; this.log('voteError', { id, error: error.stack });
        });
      }
    });
    bot.on('messagestr', message => {
      if (id !== 'A') return;
      const text = clean(message);
      const round = text.match(/第\s+(\d+)\/5\s+轮排名/);
      if (round) {
        this.header = Number(round[1]);
        if (!this.roundRows.has(this.header)) this.roundRows.set(this.header, new Map());
      }
      if (text.includes('Party 2 五轮总排名')) {
        this.header = 'final'; this.finalHeaders++;
      }
      const row = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([A-F]).*本轮\s+\+(\d+)｜累计\s+(\d+)/);
      if (row && typeof this.header === 'number') {
        this.roundRows.get(this.header).set(row[2], { place: Number(row[1]), points: Number(row[3]), total: Number(row[4]), text });
        this.log('tournamentRoundRow', { round: this.header, id: row[2], ...this.roundRows.get(this.header).get(row[2]) });
      }
      const final = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([A-F])\s+累计\s+(\d+)\s+分/);
      if (final && this.header === 'final') {
        this.finalRows.set(final[2], { place: Number(final[1]), total: Number(final[3]) });
      }
    });
    return bot;
  }

  async until(predicate, label, timeout = 15000) {
    await this.wait(() => { if (this.voteError) throw this.voteError; return predicate(); }, label, timeout);
  }

  menuChoices(window) {
    return window.slots.slice(0, 45).flatMap((item, slot) => {
      if (!item) return [];
      const lore = clean(json(item.customLore ?? item.components ?? item.nbt));
      const source = lore.match(/原版编号\s+(\d+)/);
      return source ? [{ slot, source: Number(source[1]), item: item.name, lore }] : [];
    });
  }

  async voteDynamic(id, window) {
    const round = this.nextRound, source = this.targetSource;
    const key = `${this.caseNumber}:${round}:${id}:${window.id}`;
    if (this.voteRequests.has(key)) return;
    this.voteRequests.add(key);
    this.check(`${id} receives round ${round} menu only while participating`, this.activeIds.includes(id), { id, activeIds: this.activeIds });
    await this.wait(() => this.menuChoices(window).length > 0, 'menu lore source IDs arrived', 2500);
    const choices = this.menuChoices(window);
    if (this.initialChoiceCount == null) this.initialChoiceCount = choices.length;
    const sourceIds = choices.map(choice => choice.source);
    // A forfeit can also remove a game requiring exactly four players. Verify
    // the complete eligible catalogue, not only the previous menu's length.
    const expected = Object.values(GAMES).filter(game => (this.activeIds.length >= (game.minimumPlayers ?? game.players ?? 2)
      && this.activeIds.length <= (game.maximumPlayers ?? game.players ?? 10))
      && !this.played.has(game.source)).map(game => game.source);
    this.check(`Round ${round} ${id} menu excludes previously played games`, [...this.played].every(played => !sourceIds.includes(played))
      && sourceIds.length === expected.length && expected.every(source => sourceIds.includes(source)),
      { sourceIds, played: [...this.played], count: sourceIds.length, expected });
    const selected = choices.find(choice => choice.source === source);
    this.check(`Round ${round} ${id} planned source remains selectable`, !!selected, { source, sourceIds });
    this.menuHistory.push({ id, round, at: Date.now(), sourceIds });
    this.log('dynamicVote', { id, round, source, slot: selected.slot });
    await this.bots[id].clickWindow(selected.slot, 0, 0);
  }

  async joinTournament() {
    for (const id of this.activeIds) {
      this.bots[id].probeStartedAt = 0;
      await this.chat(id, '/pvp join minecraftparty');
    }
  }

  async roundStarted(round, source) {
    const selection = () => this.chats.find(row => row.id === 'A' && row.at >= this.caseStart
      && new RegExp(`第\\s+${round}/5\\s+轮选择：.*原版编号\\s+${source}`).test(row.text));
    await this.until(() => !!selection(), `round ${round} selects source ${source}`, 90000);
    const at = selection().at;
    await this.until(() => this.activeIds.every(id => this.bots[id].probeStartedAt >= at), `round ${round} enters PLAY`, 50000);
    this.check(`Round ${round} plays a distinct game`, !this.played.has(source), { played: [...this.played], source });
    this.played.add(source); this.selected.push(source);
    this.check(`Round ${round} restores every remaining player from prior elimination`, this.activeIds.every(id => this.bots[id].probeWorld === 'pvp:arena'
      && this.bots[id].game.gameMode === 'adventure'), this.activeIds.map(id => this.snapshot(id)));
    this.log('tournamentRoundStarted', { round, source, participants: this.activeIds });
  }

  async sprint(ticks) {
    const start = this.serverLines.length;
    this.log('fixtureNote', { description: `Advance ${ticks} server ticks; wait for Sprint completed before issuing another command.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(start).some(row => /Sprint completed/i.test(row.text)), 'tick sprint completed acknowledgement', 60000);
    this.log('tickSprintCompleted', { ticks });
  }

  async condition(condition) {
    const token = `P2_TOURNEY_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(token));
  }

  async jumpStrength(id, range, label) {
    await this.console(`execute store result score ${name(id)} p2_tourney_attr run attribute ${name(id)} minecraft:generic.jump_strength base get 1000`);
    this.check(label, await this.condition(`if score ${name(id)} p2_tourney_attr matches ${range}`), { id, expectedScaledRange: range });
  }

  async tag(ids) {
    await this.console('tag @a remove p2_tourney_finish');
    for (const id of ids) await this.console(`tag ${name(id)} add p2_tourney_finish`);
  }

  async dropper() {
    const spawns = this.activeIds.map(id => pos(this.bots[id]));
    const x = spawns.reduce((sum,p) => sum+p.x,0)/spawns.length;
    const z = spawns.reduce((sum,p) => sum+p.z,0)/spawns.length;
    this.oldWall = new Vec3(Math.floor(x)+8,250,Math.floor(z));
    await this.pinChunk(this.oldWall);
    await this.jumpStrength('A', '0', 'Dropper sets jump strength to zero');
    this.check('Dropper tower exists before first cleanup', await this.serverBlockIs(this.oldWall,'smooth_quartz'), { block: this.oldWall });
    this.log('fixtureNote', { description: 'Teleport A/B/C into the real original Dropper water pool in one command, making a tied first-attempt finish; D remains unfinished.' });
    await this.tag(['A','B','C']);
    await this.console(`execute in pvp:arena as @a[tag=p2_tourney_finish] run tp @s ${x} -62.75 ${z}`);
    await this.sprint(180);
  }

  async oitq() {
    await this.jumpStrength('A', '419..421', 'Dropper jump suppression is restored before OITQ');
    this.check('Previous Dropper tower cleared before next gameplay', await this.serverBlockIs(this.oldWall,'air'), { block: this.oldWall });
    const spawns = this.activeIds.map(id => pos(this.bots[id]));
    // Four shuffled original starts are symmetric. This source Y=35 corridor
    // is already air in the original map, so the test never modifies geometry.
    const origin = new Vec3(spawns.reduce((sum,p) => sum+p.x,0)/4-5,
      Math.min(...spawns.map(p=>p.y))+14, spawns.reduce((sum,p)=>sum+p.z,0)/4);
    await this.teleport('A', origin); await this.teleport('B', origin.offset(10,0,0));
    const before = pos(this.bots.B);
    const bot = this.bots.A;
    await bot.equip(bot.inventory.items().find(item => item.name==='bow'),'hand');
    await bot.lookAt(pos(this.bots.B).offset(0,1.2,0),true);
    this.log('realBowDraw', { attacker:'A', victim:'B', round:2 });
    bot.activateItem(); await sleep(1100); bot.deactivateItem();
    await this.wait(() => pos(this.bots.B).distanceTo(before)>2 && this.bots.B.game.gameMode==='adventure', 'OITQ actual bow kill and respawn', 3000);
    this.check('OITQ real first kill replenishes A arrow', bot.inventory.items().filter(item=>item.name==='arrow').reduce((n,item)=>n+item.count,0)===1,
      this.snapshot('A'));
    await this.sprint(1800);
  }

  async trafficLights() {
    const p = pos(this.bots.A);
    this.log('fixtureNote', { description:'During initial green light, send A/B/C together to original white-concrete finish. This tests tied round scoring, not normal race movement.' });
    await this.tag(['A','B','C']);
    await this.console(`execute in pvp:arena as @a[tag=p2_tourney_finish] run tp @s ${p.x} ${p.y} ${p.z-145.5}`);
  }

  async runner() {
    this.log('fixtureNote', { description:'Apply one batched fall-damage command to B/C/D; A is the sole survivor. Runner movement was separately tested by the protocol regression.' });
    await this.tag(['B','C','D']);
    await this.console('execute as @a[tag=p2_tourney_finish] run damage @s 1000 minecraft:fall');
  }

  async rainbow() {
    const p = pos(this.bots.A);
    this.log('fixtureNote', { description:'Send B/C/D below the original Rainbow void threshold together, preserving a tied survival loss; A survives.' });
    await this.tag(['B','C','D']);
    await this.console(`execute in pvp:arena as @a[tag=p2_tourney_finish] run tp @s ${p.x} ${p.y-27} ${p.z}`);
  }

  stats() {
    const file = path.join(this.runDir,'config/pvp/stats.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file,'utf8')) : {};
  }

  matches(stats, id) { return stats[offlineUuid(name(id))]?.matches || 0; }

  async verifyRound(round, expectedPlaces, baseline) {
    await this.until(() => this.roundRows.get(round)?.size===this.activeIds.length, `round ${round} complete result rows`);
    const rows = this.roundRows.get(round);
    for (const id of this.activeIds) {
      const row = rows.get(id), points = 4-expectedPlaces[id]+1;
      const cumulative = this.totals.get(id)+points;
      this.check(`Round ${round} ${id} placement conversion and accumulation`, row.place===expectedPlaces[id]
        && row.points===points && row.total===cumulative, row, { place:expectedPlaces[id], points, total:cumulative });
      this.totals.set(id,cumulative);
    }
    if (round<5) {
      this.check(`Round ${round} result keeps players inside tournament`, this.activeIds.every(id=>this.bots[id].probeWorld==='pvp:arena'), this.activeIds.map(id=>this.snapshot(id)));
      this.check(`Round ${round} does not persist a separate match`, IDS.every(id=>this.matches(this.stats(),id)===this.matches(baseline,id)), this.stats());
      this.check(`Round ${round} has no final standings or feedback`, this.finalHeaders===0
        && !this.chats.some(row=>row.at>=this.caseStart && row.text.includes('qfmaker.com/blackboard/mcserverfb')),
      { finalHeaders:this.finalHeaders });
    }
    this.nextRound = round+1; this.targetSource = PLAN[round];
  }

  async tournament() {
    this.resetCase();
    const baseline = this.stats();
    await this.joinTournament();
    for (let round=1; round<=5; round++) {
      await this.roundStarted(round,PLAN[round-1]);
      await [null,()=>this.dropper(),()=>this.oitq(),()=>this.trafficLights(),()=>this.runner(),()=>this.rainbow()][round]();
      const places = round===1 || round===3 ? {A:1,B:1,C:1,D:4} : {A:1,B:2,C:2,D:2};
      await this.verifyRound(round,places,baseline);
    }
    await this.until(()=>this.finalRows.size===4,'one complete final cumulative table',30000);
    const ordered = [...this.totals.entries()].sort((a,b)=>b[1]-a[1]);
    for (const [id,total] of ordered) {
      const place = 1+ordered.filter(([,other])=>other>total).length;
      this.check(`${id} final place follows total points including ties`, this.finalRows.get(id)?.place===place
        && this.finalRows.get(id)?.total===total, this.finalRows.get(id), {place,total});
    }
    this.check('Five selected games are distinct and match actual votes', this.selected.length===5 && new Set(this.selected).size===5
      && this.selected.every((source,index)=>source===PLAN[index]),this.selected,PLAN);
    await this.until(()=>IDS.every(id=>this.bots[id].probeWorld==='minecraft:overworld'),'five-round tournament returns everyone to lobby',30000);
    await this.until(()=>IDS.every(id=>this.matches(this.stats(),id)===this.matches(baseline,id)+1),'exactly one persisted match each',25000);
    await sleep(800);
    this.check('Final tournament header appears exactly once',this.finalHeaders===1,{count:this.finalHeaders});
    for (const id of IDS) {
      const hints = this.chats.filter(row=>row.id===id && row.at>=this.caseStart && row.text.includes('qfmaker.com/blackboard/mcserverfb'));
      this.check(`${id} receives exactly one final feedback hint`,hints.length===1,{count:hints.length});
      this.check(`${id} receives one match and correct win/loss`, this.matches(this.stats(),id)===this.matches(baseline,id)+1
        && (this.stats()[offlineUuid(name(id))]?.wins||0)===(baseline[offlineUuid(name(id))]?.wins||0)+(id==='A'?1:0)
        && (this.stats()[offlineUuid(name(id))]?.losses||0)===(baseline[offlineUuid(name(id))]?.losses||0)+(id==='A'?0:1),
      {before:baseline[offlineUuid(name(id))],after:this.stats()[offlineUuid(name(id))]});
    }
  }

  async forfeitBetweenRounds() {
    this.resetCase();
    await this.joinTournament();
    await this.roundStarted(1,401);
    await this.dropper();
    await this.until(()=>this.roundRows.get(1)?.size===4,'forfeit case reaches round-one result page');
    const leftAt = Date.now();
    this.activeIds=['A','B','C'];
    this.nextRound=2; this.targetSource=428;
    await this.chat('D','/hub');
    await this.until(()=>this.bots.D.probeWorld==='minecraft:overworld','D leaves between rounds');
    await this.roundStarted(2,428);
    this.check('Round-interval forfeiter never reenters the arena or gets another vote',this.bots.D.probeWorld==='minecraft:overworld'
      && !this.menuHistory.some(menu=>menu.id==='D' && menu.at>=leftAt),this.snapshot('D'));
    this.check('Other players continue the second round after a forfeit',this.activeIds.every(id=>this.bots[id].probeWorld==='pvp:arena'
      && this.bots[id].game.gameMode==='adventure'),this.activeIds.map(id=>this.snapshot(id)));
    // This second case deliberately ends here; it only audits round-boundary
    // membership, while the first case verifies the complete five-round path.
    await this.leaveAll();
  }
}

async function main() {
  const probe = new TournamentProbe();
  let error;
  try {
    await probe.launch(); await probe.connectAll('ABCD');
    await probe.console('scoreboard objectives add p2_tourney_attr dummy');
    await probe.tournament();
    await probe.forfeitBetweenRounds();
  } catch (failure) { error=failure; probe.log('failure',{error:failure.stack}); }
  const report=await probe.close(error);
  if (!report.ok) process.exitCode=1;
}

module.exports={TournamentProbe};
if (require.main===module) void main().catch(error=>{console.error(error.stack);process.exitCode=1;});
