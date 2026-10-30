'use strict';

// Reason: Monster Shoot needs real native bow/mob evidence, not simulated hit
// counters. Review: own25612 runtime, preserve failed evidence, label all entity
// freeze/teleport/fuse/time fixtures, never create fake production-owned mobs.
const { PartyProbe } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const path = require('node:path');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const name = id => `MyGoCheck${id}`;
const count = (bot, item) => bot.inventory.items().filter(stack => stack.name === item).reduce((sum, stack) => sum + stack.count, 0);

class MonsterProbe extends PartyProbe {
  constructor() {
    super({ port: 25612, runDir: path.join(__dirname, '../build/mygo-party2-monster') });
    this.points = new Map(); this.rows = new Map(); this.shooting = false;
  }
  connect(id) {
    const bot = super.connect(id);
    bot.on('messagestr', message => {
      const text = String(message).replace(/§./g, '');
      const points = text.match(/\+(\d+)\s*分\s*·\s*累计\s*(\d+)/);
      if (points) { this.points.set(id, Number(points[2])); this.log('monsterPoints', { id, points: Number(points[1]), total: Number(points[2]) }); }
      if (id === 'B') {
        const row = text.match(/第\s+(\d+)\s+名\s+MyGoCheck([A-F])\s+(\d+)\s*分\s+本轮/);
        if (row) this.rows.set(row[2], { place: Number(row[1]), score: Number(row[3]) });
      }
    });
    return bot;
  }
  source(x, y, z) { return this.offset.offset(x, y, z); }
  position(id) { const p = this.bots[id].probePosition; return new Vec3(p.x, p.y, p.z); }
  async condition(condition) {
    const token = `P2_MONSTER_CONDITION_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`);
    return this.serverLines.some(row => row.text.includes(`[Server] ${token}`));
  }
  async sprint(ticks) {
    const since = this.serverLines.length;
    this.log('fixtureNote', { description: `Advance ${ticks} server ticks and wait for the actual sprint completion barrier.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(since).some(row => /Sprint completed/i.test(row.text)), 'tick sprint completed', 90000);
  }
  async freeze() {
    this.log('fixtureNote', { description: 'Freeze actual source-spawned targets and park them away from the shot corridor. Spawn ownership, health and species remain production values; only NoAI/NoGravity/Motion/position are arranged.' });
    const holdingY = this.source(0, 45, 0).y;
    // /tp sets LivingEntity.OnGround=true, which the real game correctly
    // removes. Arrange Pos+OnGround atomically in one NBT fixture instead.
    await this.console('execute in pvp:arena as @e[tag=pvp_party_monster_shoot] run data merge entity @s {NoAI:1b,NoGravity:1b,OnGround:0b,Motion:[0.0d,0.0d,0.0d]}');
    // Retain the original spread of X/Z points, avoiding artificial cramming
    // and body pushing when many genuine targets are held during later checks.
    await this.console(`execute in pvp:arena as @e[tag=pvp_party_monster_shoot] run data modify entity @s Pos[1] set value ${holdingY}d`);
  }
  async target(type, tag, position, excluded = '') {
    await this.console(`execute in pvp:arena run tag @e[type=minecraft:${type},tag=pvp_party_monster_shoot${excluded},limit=1] add ${tag}`);
    this.check(`Fixture selects a real owned ${type}`, await this.condition(`in pvp:arena if entity @e[tag=${tag}]`));
    await this.console(`execute in pvp:arena run data merge entity @e[tag=${tag},limit=1] {NoAI:1b,NoGravity:1b,OnGround:0b,Motion:[0.0d,0.0d,0.0d],Pos:[${position.x}d,${position.y}d,${position.z}d]}`);
    return tag;
  }
  async nativePosition(selector) {
    const start = this.serverLines.length;
    await this.console(`execute in pvp:arena run data get entity ${selector} Pos`);
    for (const row of this.serverLines.slice(start)) {
      const match = row.text.match(/\[([-+\d.Ee]+)d,\s*([-+\d.Ee]+)d,\s*([-+\d.Ee]+)d\]/);
      if (match) return new Vec3(Number(match[1]), Number(match[2]), Number(match[3]));
    }
    throw new Error(`No native position returned for ${selector}`);
  }
  async realShot(id, aim, target, expectedGain) {
    const before = this.points.get(id) || 0, bot = this.bots[id];
    await bot.equip(bot.inventory.items().find(item => item.name === 'bow'), 'hand');
    await bot.lookAt(aim, true);
    this.log('realBowDraw', { id, target, aim, before });
    bot.activateItem(); await sleep(1100);
    const token = `P2_MONSTER_PIERCE_${++this.sequence}`;
    const sampler = setInterval(() => this.rawConsole(`execute in pvp:arena if entity @e[type=minecraft:arrow,nbt={PierceLevel:1b}] run say ${token}`), 10);
    bot.deactivateItem();
    try { await this.wait(() => this.serverLines.some(row => row.text.includes(`[Server] ${token}`)), 'real projectile has source PierceLevel1', 1000); }
    finally { clearInterval(sampler); }
    this.check('Real native bow projectile uses PierceLevel1', true);
    await sleep(300);
    if (expectedGain !== null) {
      await this.wait(() => (this.points.get(id) || 0) === before + expectedGain, `${id} gets ${expectedGain} native kill points`, 2200);
      this.check(`Native ${target} shot credits source point value`, (this.points.get(id) || 0) === before + expectedGain,
        { before, after: this.points.get(id), expectedGain });
    }
    // The final draining kill immediately returns players to the tournament
    // lobby and correctly clears the game inventory in the same tick.
    if (expectedGain !== null) this.check('Infinity bow retains its single arrow after a real release', count(bot, 'arrow') === 1, this.snapshot(id));
  }

  async naturalTargets() {
    for (let attempt = 0; attempt < 8; attempt++) {
      const complete = await this.condition('in pvp:arena if entity @e[type=minecraft:zombie,tag=pvp_party_ms_monster] if entity @e[type=minecraft:creeper,tag=pvp_party_ms_monster] if entity @e[type=minecraft:skeleton,tag=pvp_party_ms_monster] if entity @e[type=minecraft:silverfish,tag=pvp_party_ms_monster]');
      if (complete) break;
      await this.sprint(60);
    }
    for (const type of ['zombie', 'creeper', 'skeleton', 'silverfish']) {
      this.check(`Natural ${type} targets survive Peaceful difficulty with native AI and5HP`, await this.condition(
        `in pvp:arena if entity @e[type=minecraft:${type},tag=pvp_party_ms_monster,nbt={Health:5.0f}] unless entity @e[type=minecraft:${type},tag=pvp_party_ms_monster,nbt={NoAI:1b}]`));
    }
    this.check('Native zombies ride invisible5HP chickens', await this.condition('in pvp:arena if entity @e[type=minecraft:chicken,tag=pvp_party_ms_chicken,nbt={Health:5.0f,Passengers:[{id:"minecraft:zombie"}],active_effects:[{id:"minecraft:invisibility"}]}]'));
    for (const type of ['creeper', 'skeleton', 'silverfish']) this.check(`${type} has native Slow Falling`, await this.condition(
      `in pvp:arena if entity @e[type=minecraft:${type},tag=pvp_party_ms_monster,nbt={active_effects:[{id:"minecraft:slow_falling"}]}]`));
    for (const type of ['skeleton', 'zombie']) this.check(`${type} has the original sunlight-protecting button helmet`, await this.condition(
      `in pvp:arena if entity @e[type=minecraft:${type},tag=pvp_party_ms_monster,nbt={ArmorItems:[{id:"minecraft:stone_button"}]}]`));
  }

  async reservePool() {
    await this.console('scoreboard objectives add p2_ms_count dummy');
    for (let attempt = 0; attempt < 20; attempt++) {
      await this.freeze();
      await this.console('execute in pvp:arena store result score creepers p2_ms_count if entity @e[type=minecraft:creeper,tag=pvp_party_ms_monster]');
      await this.console('execute in pvp:arena store result score zombies p2_ms_count if entity @e[type=minecraft:zombie,tag=pvp_party_ms_monster]');
      if (await this.condition('if score creepers p2_ms_count matches 4.. if score zombies p2_ms_count matches 2..')) {
        this.check('Natural weighted pool provides four creepers and two jockeys for the real-shot/cloud fixtures', true);
        return;
      }
      await this.sprint(60);
    }
    throw new Error('Natural source intake did not provide four creepers for this test fixture; no production random state was changed.');
  }

  async scoring() {
    const actor = this.source(-78.5, 28, -260.5), target = this.source(-72.5, 28, -260.5);
    await this.teleport('A', actor);
    const originalB = this.position('B');
    for (const [type, points] of [['creeper', 2], ['skeleton', 3], ['silverfish', 5]]) {
      await this.freeze();
      const tag = `p2_ms_shot_${type}_${this.sequence}`;
      await this.target(type, tag, target);
      if (type === 'creeper') {
        // Keep an explicit fractional X; Minecraft centers bare integer X/Z
        // arguments, so actor+.5 would serialize82 and actually become82.5.
        await this.teleport('B', actor.offset(.6, 0, 0));
        this.check('A real participant stands between shooter and target with over5blocks remaining', this.position('B').distanceTo(target) > 5,
          { a: actor, blocker: this.position('B'), target });
      }
      await this.realShot('A', target.offset(0, type === 'silverfish' ? .15 : .9, 0), type, points);
      this.check(`Real bow kills the tagged5HP ${type}`, !await this.condition(`in pvp:arena if entity @e[tag=${tag}]`));
      if (type === 'creeper') {
        this.check('Native no-PvP arrow passes through the participant without damage or losing its target kill', this.bots.B.health === 20);
        await this.teleport('B', originalB);
      }
    }
    await this.freeze();
    const chickenTag = `p2_ms_chicken_${++this.sequence}`;
    const zombieTag = `p2_ms_rider_${this.sequence}`;
    const pairTag = `p2_ms_jockey_pair_${this.sequence}`;
    await this.console(`execute in pvp:arena run tag @e[type=minecraft:chicken,tag=pvp_party_ms_chicken,limit=1] add ${chickenTag}`);
    await this.console(`execute in pvp:arena as @e[tag=${chickenTag},limit=1] on passengers run tag @s add ${zombieTag}`);
    await this.console(`execute in pvp:arena run tag @e[tag=${chickenTag}] add ${pairTag}`);
    await this.console(`execute in pvp:arena run tag @e[tag=${zombieTag}] add ${pairTag}`);
    // Move both live members atomically. Moving only the vehicle between mod
    // ticks correctly triggers the original distance-based orphan rule.
    await this.console(`execute in pvp:arena as @e[tag=${pairTag}] run data merge entity @s {NoAI:1b,NoGravity:1b,OnGround:0b,Motion:[0.0d,0.0d,0.0d],Pos:[${target.x}d,${target.y}d,${target.z}d]}`);
    this.check('Arranged chicken retains its actual live source rider', await this.condition(`in pvp:arena if entity @e[tag=${zombieTag}]`));
    const rider = await this.nativePosition(`@e[tag=${zombieTag},limit=1]`);
    this.log('nativeRiderPosition', { rider, chicken: target });
    await this.realShot('A', rider.offset(0, 1.5, 0), 'zombie', 1);
    this.check('Orphan chicken cleanup does not fabricate a second specific kill credit', !await this.condition(`in pvp:arena if entity @e[tag=${chickenTag}]`));

    await this.freeze();
    const bonusChicken = `p2_ms_bonus_chicken_${++this.sequence}`, bonusZombie = `p2_ms_bonus_zombie_${this.sequence}`;
    const bonusPair = `p2_ms_bonus_pair_${this.sequence}`;
    await this.console(`execute in pvp:arena run tag @e[type=minecraft:chicken,tag=pvp_party_ms_chicken,limit=1] add ${bonusChicken}`);
    await this.console(`execute in pvp:arena as @e[tag=${bonusChicken},limit=1] on passengers run tag @s add ${bonusZombie}`);
    await this.console(`execute in pvp:arena run tag @e[tag=${bonusChicken}] add ${bonusPair}`);
    await this.console(`execute in pvp:arena run tag @e[tag=${bonusZombie}] add ${bonusPair}`);
    await this.console(`execute in pvp:arena as @e[tag=${bonusPair}] run data merge entity @s {NoAI:1b,NoGravity:1b,OnGround:0b,Motion:[0.0d,0.0d,0.0d],Pos:[${target.x}d,${target.y}d,${target.z}d]}`);
    const lowRider = await this.nativePosition(`@e[tag=${bonusZombie},limit=1]`);
    await this.realShot('A', lowRider.offset(0, .3, 0), 'native zombie plus invisible chicken', 2);
    this.check('Piercing a jockey and its invisible mount awards the source extra chicken point', !await this.condition(
      `in pvp:arena if entity @e[tag=${bonusPair}]`));

    // Two nearby native creepers fit in one piercing projectile's same-tick
    // collision traversal. Source >=1 counters award the species once/tick.
    await this.freeze();
    const first = `p2_ms_pair_a_${++this.sequence}`, second = `p2_ms_pair_b_${this.sequence}`;
    const pairFirst = target.offset(.8, 0, 0), pairSecond = target.offset(1.5, 0, 0);
    await this.target('creeper', first, pairFirst);
    await this.target('creeper', second, pairSecond, `,tag=!${first}`);
    this.log('fixtureNote', { description: 'Two non-overlapping creepers stand6.8/7.5blocks ahead, so both lie inside the same third-tick native arrow segment rather than straddling its ~6block second-tick boundary.',
      first: await this.nativePosition(`@e[tag=${first},limit=1]`), second: await this.nativePosition(`@e[tag=${second},limit=1]`) });
    await this.realShot('A', pairFirst.offset(0, .9, 0), 'two same-tick creepers', 2);
    this.check('One native PierceLevel1 arrow kills both aligned creepers', !await this.condition(
      `in pvp:arena if entity @e[tag=${first}]`) && !await this.condition(`in pvp:arena if entity @e[tag=${second}]`));
  }

  async foreignAndCreeper() {
    await this.freeze();
    const floor = this.source(-72, -3, -262).floored(), explosion = this.source(-71.5, -2, -261.5);
    await this.pinChunk(floor); this.floor = floor;
    this.check('Original pit deepslate floor exists before explosion/cleanup', await this.serverBlockIs(floor, 'deepslate'), { floor });
    const tag = `p2_ms_fuse_${++this.sequence}`;
    await this.target('creeper', tag, explosion);
    await this.console(`execute in pvp:arena run summon minecraft:cow ${explosion.x + 1} ${explosion.y} ${explosion.z} {Tags:["p2_ms_foreign_cow"],NoAI:1b,NoGravity:1b,Silent:1b}`);
    this.restoreB = this.position('B');
    await this.teleport('B', explosion.offset(-1, 0, 0));
    this.log('fixtureNote', { description: 'Ignite one actual owned source creeper beside the retained original pit floor and a foreign passive cow; verify native explosion policy and cloud ownership.' });
    await this.console(`execute in pvp:arena run data merge entity @e[tag=${tag},limit=1] {ignited:1b,Fuse:1s}`);
    await this.wait(() => this.serverLines.some(row => /Explosion/.test(row.text)) || !Object.values(this.bots.B.entities).some(entity => entity.name === 'creeper'
      && entity.position.distanceTo(explosion) < .1), 'native creeper explosion removes target', 2500);
    await sleep(1000);
    this.check('Source creeper explosion preserves original floor blocks', await this.serverBlockIs(floor, 'deepslate'), { floor });
    this.check('Native source creeper cloud is owned by this round', await this.condition('in pvp:arena if entity @e[type=minecraft:area_effect_cloud,tag=pvp_party_ms_cloud]'));
    this.check('Participant receives the native owned Slow Falling cloud effect', await this.condition(
      `if entity @a[name=${name('B')},nbt={active_effects:[{id:"minecraft:slow_falling"}]}]`));
    this.check('Foreign cow is excluded from Party-owned cloud effects', !await this.condition('in pvp:arena if entity @e[tag=p2_ms_foreign_cow,nbt={active_effects:[{id:"minecraft:slow_falling"}]}]'));
    await this.teleport('B', this.restoreB);
    const point = this.source(-72.5, 28, -260.5);
    await this.console(`execute in pvp:arena run tp @e[tag=p2_ms_foreign_cow,limit=1] ${point.x} ${point.y} ${point.z}`);
    const before = this.points.get('A') || 0;
    await this.realShot('A', point.offset(0, .8, 0), 'foreign cow', 0);
    this.check('Participant arrow cannot damage an unowned native animal', await this.condition('in pvp:arena if entity @e[tag=p2_ms_foreign_cow,nbt={Health:10.0f}]'), { expectedNativeCowHealth: 10 });
    this.check('Foreign animal does not generate Party points', (this.points.get('A') || 0) === before);
    await this.console('execute in pvp:arena run kill @e[tag=p2_ms_foreign_cow]');
  }

  async quitter() {
    await this.chat('D', '/hub');
    await this.wait(() => this.bots.D.probeWorld === 'minecraft:overworld', 'D forfeits during the shooting round');
    this.check('Quitter receives lobby compass and loses round effects', count(this.bots.D, 'compass') === 1
      && await this.condition(`unless entity @a[name=${name('D')},nbt={active_effects:[{id:"minecraft:weakness"}]}] unless entity @a[name=${name('D')},nbt={active_effects:[{id:"minecraft:resistance"}]}]`), this.snapshot('D'));
    await this.console(`give ${name('D')} minecraft:bow`); await this.console(`give ${name('D')} minecraft:arrow 1`);
    const bot = this.bots.D;
    await bot.equip(bot.inventory.items().find(item => item.name === 'bow'), 'hand');
    await bot.look(0, .6, true); bot.activateItem(); await sleep(1100);
    const token = `P2_MS_OUTSIDE_ARROW_${++this.sequence}`;
    const timer = setInterval(() => this.rawConsole(`execute in minecraft:overworld if entity @e[type=minecraft:arrow,nbt={PierceLevel:0b}] run say ${token}`), 15);
    bot.deactivateItem();
    try { await this.wait(() => this.serverLines.some(row => row.text.includes(`[Server] ${token}`)), 'departed player ordinary overworld arrow remains PierceLevel0', 1200); }
    finally { clearInterval(timer); }
    this.check('New overworld arrows of a departed player retain ordinary native piercing', true);
  }

  async drain() {
    await this.freeze();
    const target = this.source(-72.5, 28, -260.5), sentinel = `p2_ms_sentinel_${++this.sequence}`;
    await this.target('silverfish', sentinel, target);
    await this.sprint(1300);
    this.check('A live frozen final target prevents hard60-second settlement', this.rows.size === 0
      && this.bots.A.probeWorld === 'pvp:arena' && await this.condition(`in pvp:arena if entity @e[tag=${sentinel}]`));
    await this.console(`execute in pvp:arena run kill @e[tag=pvp_party_monster_shoot,tag=!${sentinel}]`);
    this.check('Only the real held target remains alive after the source timer', await this.condition(
      `in pvp:arena if entity @e[tag=${sentinel}] unless entity @e[tag=pvp_party_ms_monster,tag=!${sentinel}]`));
    const before = this.points.get('A') || 0;
    await this.teleport('A', this.source(-80.5, 28, -263.5));
    await this.teleport('B', this.source(-78.5, 28, -260.5));
    await this.realShot('B', target.offset(0, .15, 0), 'last draining silverfish', null);
    await this.wait(() => this.rows.size === 3, 'last live target settles one complete outer-round table', 3500);
    this.check('Original end-before-point-flush omits the final draining-tick kill', this.rows.get('B')?.score === 0, Object.fromEntries(this.rows));
    this.check('Prior genuine kills remain in final raw score and rank', this.rows.get('A')?.score === before && this.rows.get('A')?.place === 1,
      { before, rows: Object.fromEntries(this.rows) });
    this.check('Forfeited player is absent from pending round rankings', !this.rows.has('D'));
    this.check('First minigame does not prematurely produce whole-match feedback', !this.chats.some(row => row.at >= this.caseStart
      && (row.text.includes('五轮总排名') || row.text.includes('qfmaker.com/blackboard/mcserverfb'))));
    this.check('Owned mobs, arrows and native explosion clouds are discarded after the round', !await this.condition(
      'in pvp:arena if entity @e[tag=pvp_party_monster_shoot]') && !await this.condition('in pvp:arena if entity @e[tag=pvp_party_ms_cloud]'));
    this.check('Pinned original pit floor is removed before next game', await this.waitServerBlock(this.floor, 'air', 35000), { floor: this.floor });
    for (const id of ['A', 'B', 'C']) this.check(`${id} round resistance/weakness are restored`, await this.condition(
      `unless entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:weakness"}]}] unless entity @a[name=${name(id)},nbt={active_effects:[{id:"minecraft:resistance"}]}]`));
    await this.leaveAll();
  }
  async run() {
    this.caseStart = await this.start('monster');
    const starts = IDS.map(id => this.position(id));
    this.offset = new Vec3(starts.reduce((sum, p) => sum + p.x, 0) / 4 + 71.5,
      starts[0].y - 19, starts.reduce((sum, p) => sum + p.z, 0) / 4 + 261.5);
    this.check('Four participants use the four original cardinal source starts', new Set(starts.map(p => `${p.x},${p.z}`)).size === 4
      && starts.every(p => p.y === starts[0].y), { starts, offset: this.offset });
    for (const id of IDS) {
      this.check(`${id} receives original bow and one arrow`, count(this.bots[id], 'bow') === 1 && count(this.bots[id], 'arrow') === 1, this.snapshot(id));
      this.check(`${id} receives the source binding head item`, await this.condition(
        `if entity @a[name=${name(id)},nbt={Inventory:[{Slot:103b,id:"minecraft:shaper_armor_trim_smithing_template"}]}]`));
    }
    await this.naturalTargets(); await this.reservePool(); await this.scoring(); await this.foreignAndCreeper(); await this.quitter(); await this.drain();
  }
}

(async () => {
  const probe = new MonsterProbe(); let error;
  try { await probe.launch(); await probe.connectAll(); await probe.run(); }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error); if (!report.ok) process.exitCode = 1;
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
