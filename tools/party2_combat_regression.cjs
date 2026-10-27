'use strict';

// Reason: the native Party combat ports need packet-level regression evidence
// for lethal first arrows, per-life respawn state and ownership after leaving.
// Pre-review: only use the owned isolated server on 25596, preserve every failed
// run, and label console damage/teleport fixtures separately from real attacks.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const clean = value => String(value).replace(/§./g, '');
const position = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
const name = id => `MyGoCheck${id}`;
const count = (bot, item) => bot.inventory.items().filter(stack => stack.name === item).reduce((sum, stack) => sum + stack.count, 0);

class CombatProbe extends PartyProbe {
  constructor() {
    super({ port: 25596, runDir: require('node:path').join(__dirname, '../build/mygo-party2-combat') });
    this.killScores = new Map();
  }

  connect(id) {
    const bot = super.connect(id);
    bot._client.on('scoreboard_score', packet => {
      const text = clean(packet.itemName);
      const match = text.match(/MyGoCheck([A-F])\s+(\d+)\s*次击杀/);
      if (match) {
        this.killScores.set(match[1], Number(match[2]));
        this.log('combatScore', { observer: id, player: match[1], kills: Number(match[2]), packet });
      }
    });
    bot.on('health', () => this.log('health', { id, health: bot.health }));
    bot.on('entitySpawn', entity => {
      if (entity.name === 'arrow') this.log('arrowSpawn', { observer: id, uuid: entity.uuid, position: entity.position });
    });
    return bot;
  }

  async condition(dimension, condition) {
    const token = `P2_COMBAT_CONDITION_${++this.sequence}`;
    await this.console(`execute in ${dimension} ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(token)) || this.chats.some(row => row.text.includes(token));
  }

  async attack(attacker, victim) {
    const bot = this.bots[attacker];
    await this.wait(() => !!bot.players[name(victim)]?.entity, `${attacker} sees ${victim}`);
    const entity = bot.players[name(victim)].entity;
    await bot.lookAt(entity.position.offset(0, 1.2, 0), true);
    this.log('realAttack', { attacker, victim, item: bot.heldItem?.name, attackerPosition: position(bot), targetPosition: entity.position });
    bot.attack(entity);
  }

  async shoot(attacker, target, aim) {
    const bot = this.bots[attacker];
    const bow = bot.inventory.items().find(item => item.name === 'bow');
    if (!bow) throw new Error(`${attacker} has no bow`);
    await bot.equip(bow, 'hand');
    const point = aim || position(this.bots[target]).offset(0, 1.2, 0);
    await bot.lookAt(point, true);
    this.log('realBowDraw', { attacker, target, aim: point, beforeArrows: count(bot, 'arrow') });
    bot.activateItem();
    await sleep(1100);
    // Read-only console sampling catches the actual in-flight first projectile,
    // before its target collision removes it. It never injects arrow entities.
    const damageToken = `P2_REAL_ARROW_99_${++this.sequence}`;
    const sampler = target ? setInterval(() => this.rawConsole(`execute in pvp:arena if entity @e[type=minecraft:arrow,nbt={damage:99.0d}] run say ${damageToken}`), 25) : null;
    bot.deactivateItem();
    this.log('realBowRelease', { attacker, target });
    if (sampler) {
      await sleep(300);
      clearInterval(sampler);
      this.lastArrow99Observed = this.serverLines.some(row => row.text.includes(damageToken));
      this.log('firstArrowNbtProbe', { exactDamage99Observed: this.lastArrow99Observed });
    }
  }

  async fixtureCorridor(origin, game) {
    this.log('fixtureNote', { game, description: 'Console clears a small aerial corridor inside this match and supplies a barrier floor. Physics-disabled clients hold their commanded positions. Attacks and bow releases use real client packets; this fixture isolates combat from terrain and bot pathfinding.' });
    await this.console(`execute in pvp:arena run fill ${Math.floor(origin.x)-2} ${Math.floor(origin.y)} ${Math.floor(origin.z)-2} ${Math.floor(origin.x)+14} ${Math.floor(origin.y)+3} ${Math.floor(origin.z)+2} air`);
    await this.console(`execute in pvp:arena run fill ${Math.floor(origin.x)-2} ${Math.floor(origin.y)-1} ${Math.floor(origin.z)-2} ${Math.floor(origin.x)+14} ${Math.floor(origin.y)-1} ${Math.floor(origin.z)+2} barrier`);
  }

  async oitq() {
    await this.start('oitq');
    this.killScores.clear();
    const spawns = Object.values(this.bots).map(position);
    // Match shuffles player slots: A is not necessarily source spawn 1.
    // The four original starts are symmetric around (125.5,21,-40.5).
    const origin = new Vec3(spawns.reduce((sum,p) => sum+p.x,0)/4-5,
      Math.min(...spawns.map(p => p.y))+9, spawns.reduce((sum,p) => sum+p.z,0)/4);
    await this.fixtureCorridor(origin, 'oitq');
    await this.teleport('A', origin);
    await this.teleport('B', origin.offset(10, 0, 0));
    await this.teleport('C', origin.offset(-1, 0, -2));
    await this.teleport('D', origin.offset(1, 0, 2));
    await sleep(500);
    this.check('OITQ starts with exactly one arrow each', ['A','B','C','D'].every(id => count(this.bots[id], 'arrow') === 1),
      Object.fromEntries(['A','B','C','D'].map(id => [id,count(this.bots[id],'arrow')])));
    const beforeDeath = position(this.bots.B);
    await this.shoot('A', 'B');
    await this.wait(() => position(this.bots.B).distanceTo(beforeDeath) > 2 && this.bots.B.game.gameMode === 'adventure',
      'real first bow shot kills and next-tick respawns B', 3500);
    const respawnedAt = Date.now();
    await this.wait(() => count(this.bots.A, 'arrow') === 1 && count(this.bots.B, 'arrow') === 1,
      'killer replenished one arrow; victim respawns with one');
    await this.wait(() => this.killScores.get('A') === 1, 'first lethal bow credits its shooter', 2500);
    this.check('First real bow has damage99, kills full-health target and grants one kill/arrow', this.lastArrow99Observed && this.killScores.get('A') === 1
      && count(this.bots.A,'arrow') === 1 && count(this.bots.B,'arrow') === 1,
      { exactDamage99Observed: this.lastArrow99Observed, scores: Object.fromEntries(this.killScores), a: this.snapshot('A'), b: this.snapshot('B') },
      'A has one kill and one arrow; B has full health and one arrow');

    this.log('fixtureNote', { description: 'Console damage fixtures distinguish respawn protection, fall exception and cross-life kill attribution; they are not counted as real weapon tests.' });
    const protectedPosition = position(this.bots.B);
    await this.console(`damage ${name('B')} 100 minecraft:player_attack by ${name('A')}`);
    this.check('Respawn blocks non-fall damage before 60 ticks', this.bots.B.health === 20
      && position(this.bots.B).distanceTo(protectedPosition) < 0.1,
      { msSinceRespawn: Date.now()-respawnedAt, b: this.snapshot('B') });
    await this.console(`damage ${name('B')} 1 minecraft:fall`);
    await this.wait(() => this.bots.B.health === 19, 'fall damage bypasses respawn protection', 1000);
    await sleep(Math.max(0, 2200 - (Date.now()-respawnedAt)));
    await this.console(`damage ${name('B')} 100 minecraft:player_attack by ${name('A')}`);
    this.check('Protection persists well beyond ordinary hurt cooldown', this.bots.B.health === 19
      && position(this.bots.B).distanceTo(protectedPosition) < 0.1,
      { msSinceRespawn: Date.now()-respawnedAt, health: this.bots.B.health }, 'still protected after more than two seconds');
    await sleep(Math.max(0, 3400 - (Date.now()-respawnedAt)));
    await this.console(`damage ${name('B')} 1 minecraft:player_attack by ${name('A')}`);
    await this.wait(() => this.bots.B.health === 18, 'non-fall damage accepted after 60 ticks', 1000);
    this.check('Respawn protection expires after 60 ticks', this.bots.B.health === 18,
      { msSinceRespawn: Date.now()-respawnedAt, health: this.bots.B.health });

    // Clear the deliberate nonlethal hit by starting another life, then kill
    // only through fall damage. The last life must not inherit A's adversary.
    await this.console(`damage ${name('B')} 100 minecraft:player_attack by ${name('A')}`);
    await this.wait(() => this.killScores.get('A') === 2, 'second lethal fixture credits second life', 2500);
    await this.wait(() => this.bots.B.game.gameMode === 'adventure' && this.bots.B.health === 20, 'B second life reset');
    const arrowsBeforeFall = count(this.bots.A, 'arrow');
    await this.console(`damage ${name('B')} 100 minecraft:fall`);
    await this.wait(() => this.bots.B.game.gameMode === 'adventure' && this.bots.B.health === 20, 'fall-only death respawns B');
    await sleep(1200);
    this.check('New-life fall does not credit previous-life killer', this.killScores.get('A') === 2
      && count(this.bots.A,'arrow') === arrowsBeforeFall,
      { scores: Object.fromEntries(this.killScores), arrowsBeforeFall, arrowsAfterFall: count(this.bots.A,'arrow') });

    // Fresh victims C/D avoid both respawn protection and the previous target's
    // ordinary hurt cooldown. Their 4-block separation also excludes sweeping.
    await this.teleport('A', origin.offset(4,0,0));
    await this.teleport('C', origin.offset(6,0,0));
    await this.teleport('D', origin.offset(2,0,0));
    await this.bots.A.equip(this.bots.A.inventory.items().find(item => item.name === 'iron_sword'), 'hand');
    await sleep(800);
    await this.attack('A','C');
    await this.wait(() => this.bots.C.health < 20, 'fully charged sword hit', 1000);
    await this.attack('A','D');
    await this.wait(() => this.bots.D.health < 20, 'immediate second sword hit', 1000);
    this.check('Modern cooldown weakens rapid second real attack', this.bots.C.health <= 14.1 && this.bots.D.health > 17,
      { fullHitDamage: 20-this.bots.C.health, rapidHitDamage: 20-this.bots.D.health }, 'full iron sword >=5.9; rapid hit <3');

    await this.chat('A','/hub');
    await this.wait(() => this.bots.A.probeWorld === 'minecraft:overworld', 'A leaves active four-player OITQ');
    await this.console('execute in minecraft:overworld run kill @e[type=minecraft:arrow]');
    await this.console(`give ${name('A')} minecraft:bow`);
    await this.console(`give ${name('A')} minecraft:arrow 2`);
    await this.console(`execute in minecraft:overworld run tp ${name('A')} 0.5 200.0 0.5`);
    await this.shoot('A', null, new Vec3(0.5,250,0.5));
    await this.wait(() => Object.values(this.bots.A.entities).some(entity => entity.name === 'arrow'), 'post-leave real arrow exists', 1200);
    const nativeArrow = await this.condition('minecraft:overworld', 'if entity @e[type=minecraft:arrow,nbt={damage:2.0d}]');
    const poisonedArrow = await this.condition('minecraft:overworld', 'if entity @e[type=minecraft:arrow,nbt={damage:99.0d}]');
    this.check('Departed player new overworld arrow retains native damage', nativeArrow && !poisonedArrow,
      { nativeDamage2: nativeArrow, incorrectlyClaimedDamage99: poisonedArrow });
    await this.leaveAll();
  }

  holder() { return Object.keys(this.bots).find(id => count(this.bots[id], 'porkchop') > 0); }

  async meat() {
    await this.start('meat');
    await this.wait(() => !!this.holder(), 'initial meat holder');
    // Mean of the four source starts is (-113.75,38.5,-283.25).
    // Derive translation from all four, independent of randomized slot order.
    const spawns = Object.values(this.bots).map(position);
    const origin = new Vec3(spawns.reduce((sum,p) => sum+p.x,0)/4+1.25,
      Math.min(...spawns.map(p => p.y))+12, spawns.reduce((sum,p) => sum+p.z,0)/4-0.25);
    await this.fixtureCorridor(origin, 'meat');
    let holder = this.holder();
    const victim = Object.keys(this.bots).find(id => id !== holder);
    await this.teleport(holder, origin);
    await this.teleport(victim, origin.offset(2,0,0));
    for (const id of Object.keys(this.bots).filter(id => id !== holder && id !== victim)) await this.teleport(id, origin.offset(10,0,2));
    const weakAt = Date.now();
    this.bots[holder].setQuickBarSlot(1);
    await sleep(100);
    this.bots[holder].setQuickBarSlot(0);
    await sleep(60);
    await this.attack(holder,victim);
    await this.wait(() => this.chats.some(row => row.id === holder && row.at >= weakAt && row.text.includes('必须蓄满力')), 'real weak-hit rejection', 1500);
    this.check('Weak real meat hit keeps original holder', this.holder() === holder, { holder, actualHolder: this.holder() });
    await sleep(800);
    const targetPosition = position(this.bots[victim]);
    await this.attack(holder,victim);
    await this.wait(() => this.holder() === victim, 'fully charged real meat hit transfers holder', 1500);
    const distance = position(this.bots[holder]).distanceTo(targetPosition);
    this.check('Full real meat hit transfers and relocates old holder >=24 blocks', distance >= 24,
      { oldHolder: holder, newHolder: this.holder(), relocated: position(this.bots[holder]), targetPosition, distance });

    holder = victim;
    const next = Object.keys(this.bots).find(id => id !== holder);
    await this.teleport(holder,origin);
    await this.teleport(next,origin.offset(2,0,0));
    this.bots[holder].setQuickBarSlot(0);
    await sleep(650);
    this.log('fixtureNote', { description: 'Exact /damage 6.0 then 6.5 isolates the source statistics thresholds 60 and 65, independently of cooldown rounding.' });
    await this.console(`damage ${name(next)} 6.0 minecraft:player_attack by ${name(holder)}`);
    await this.wait(() => position(this.bots[holder]).distanceTo(origin) > 5, '6.0 damage relocates old holder');
    this.check('6.0 damage relocates >=24 blocks without transferring meat', this.holder() === holder
      && position(this.bots[holder]).distanceTo(position(this.bots[next])) >= 24,
      { holder, actualHolder: this.holder(), distance: position(this.bots[holder]).distanceTo(position(this.bots[next])) });
    await this.teleport(holder,origin);
    await this.teleport(next,origin.offset(2,0,0));
    await sleep(650);
    await this.console(`damage ${name(next)} 6.5 minecraft:player_attack by ${name(holder)}`);
    await this.wait(() => this.holder() === next, '6.5 damage transfers meat');
    this.check('6.5 damage transfers meat and relocates previous holder', this.holder() === next
      && position(this.bots[holder]).distanceTo(position(this.bots[next])) >= 24,
      { previousHolder: holder, holder: this.holder(), distance: position(this.bots[holder]).distanceTo(position(this.bots[next])) });
    await this.leaveAll();
    for (const id of Object.keys(this.bots)) {
      const noSpeed = await this.condition('minecraft:overworld', `unless entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:speed"}]}]`);
      const noRegen = await this.condition('minecraft:overworld', `unless entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:regeneration"}]}]`);
      this.check(`${id} meat speed/regeneration removed after leaving`, noSpeed && noRegen, { noSpeed, noRegen });
    }
  }
}

async function main() {
  const probe = new CombatProbe();
  let error;
  try {
    await probe.launch();
    await probe.connectAll('ABCD');
    await probe.oitq();
    await probe.meat();
  } catch (failure) {
    error = failure;
    probe.log('failure', { error: failure.stack });
  }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}

if (require.main === module) void main().catch(error => { console.error(error.stack); process.exitCode = 1; });
module.exports = { CombatProbe };
