'use strict';

// Reason: 416 needs original multi-level Nether routes and genuine native horse
// controller/saddle/jump inputs. A flat foot-race test cannot validate the port.
// Review: Fabric25632 is isolated; exact decimal console fixtures arrange gold
// markers/fire/time only. Riding/jump packets are real protocol input. This is
// not a rendered vanilla-client horse-steering acceptance test.
const path = require('node:path');
const fs = require('node:fs');
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const mojangson = require('mojangson');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const name = id => `MyGoCheck${id}`;
const decimal = number => Number(number).toFixed(8);
const exact = point => [point.x, point.y, point.z].map(decimal).join(' ');
const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '../src/main/resources/pvp/party/horse_race_rules.json')));
GAMES.horse = { source: 416 };

class HorseProbe extends PartyProbe {
  constructor(timeout, allRoutes) {
    super({ port: 25632, runDir: path.join(__dirname, allRoutes ? '../build/mygo-party2-horse-all-routes' : timeout ? '../build/mygo-party2-horse-timeout' : '../build/mygo-party2-horse') });
    this.timeoutCase = timeout;
    this.metadata = [];
    this.passengers = new Map();
    this.teleports = []; this.confirmations = []; this.vehicleCorrections = [];
    // This fresh isolated server's first allocated region is zero; template
    // size176 means center(88,101,88). Never infer source offset from a rider's
    // seat, which has native height and randomized spawn-marker offsets.
    this.offset = new Vec3(88 - 56, 101 - 45, 88 + 1099);
  }
  async wait(predicate, label, timeout) {
    return super.wait(predicate, label, label === 'horse begins' ? 120000 : timeout);
  }
  connect(id) {
    const bot = super.connect(id);
    const write = bot._client.write.bind(bot._client);
    bot._client.write = (packetName, packet) => {
      if (packetName === 'teleport_confirm') {
        this.confirmations.push({ id, at: Date.now(), packet });
        this.log('nativeTeleportConfirm', { id, packet });
      }
      return write(packetName, packet);
    };
    bot._client.on('position', packet => { this.teleports.push({ id, at: Date.now(), packet }); });
    bot._client.on('vehicle_move', packet => {
      this.vehicleCorrections.push({ id, at: Date.now(), packet }); this.log('nativeVehicleCorrection', { id, packet });
    });
    bot._client.on('entity_metadata', packet => this.metadata.push({ id, at: Date.now(), packet }));
    bot._client.on('set_passengers', packet => this.passengers.set(`${id}:${packet.entityId}`, packet.passengers));
    return bot;
  }
  source(x, y, z) { return this.offset.offset(x, y, z); }
  horse(id) { return `@e[tag=party_horse,tag=p2_hr_${id},limit=1]`; }
  async condition(clause) {
    const token = `P2_HORSE_${++this.sequence}`;
    await this.console(`execute ${clause} run say ${token}`, 15);
    // Command-parser error excerpts can contain the command's nonce. Only an
    // actual terminal say message counts, with any real source entity sender.
    const spoken = new RegExp(`\\]\\s+${token}\\s*$`);
    return this.serverLines.some(row => spoken.test(row.text)) || this.chats.some(row => spoken.test(row.text));
  }
  async sprint(ticks) {
    const start = this.serverLines.length;
    this.log('fixtureNote', { description: `Advance ${ticks} real server ticks and await Sprint completed.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(start).some(row => /Sprint completed/i.test(row.text)), 'tick sprint completed', 60000);
    await sleep(60);
  }
  async mounted(id) { return this.condition(`if data entity ${name(id)} RootVehicle`); }
  async tag(id) { await this.console(`execute as ${name(id)} on vehicle run tag @s add p2_hr_${id}`); }
  async horsePosition(id) {
    const begin = this.serverLines.length;
    await this.console(`execute in pvp:arena run data get entity ${this.horse(id)} Pos`);
    const line = this.serverLines.slice(begin).find(row => row.text.includes('following entity data:'))?.text;
    const match = line?.match(/following entity data: \[([^\]]+)\]/);
    if (!match) throw new Error(`Missing horse Pos for ${id}: ${line}`);
    const value = new Vec3(...match[1].split(',').map(n => Number(n.trim().replace(/[df]$/i, ''))));
    this.log('nativeHorsePosition', { id, value }); return value;
  }
  async at(id, point, radius = .8) {
    return this.condition(`in pvp:arena positioned ${exact(point)} if entity ${this.horse(id).replace('limit=1]', `distance=..${radius},limit=1]`)}`);
  }
  async fixture(id, point) {
    this.log('fixtureNote', { description: `Move ${id}'s source-spawned horse to an original gold/checkpoint witness; this does not claim a natural course playthrough.`, point });
    await this.console(`execute in pvp:arena run tp ${this.horse(id)} ${exact(point)}`);
    await this.teleport(id, point.offset(0, .7, 0));
    await sleep(80);
  }
  async stable(id) {
    this.log('fixtureNote', { description: `Freeze ${id}'s native horse AI/gravity only during source checkpoint fixtures, after controller checks.` });
    await this.console(`execute in pvp:arena run data merge entity ${this.horse(id)} {NoAI:1b,NoGravity:1b,Motion:[0.0d,0.0d,0.0d]}`);
  }
  async flag(id) {
    const item = this.bots[id].inventory.items().find(item => item.name === 'carrot_on_a_stick');
    if (!item) throw new Error(`Missing ${id} reset flag`);
    await this.bots[id].equip(item, 'hand'); return item;
  }
  async navigation() {
    // The source uses one-cell sign clones to change directional arrows. Read
    // every resulting native sign, rather than accepting blank block states.
    const signs = rules.routes[this.routeNumber].signs;
    const owners = [];
    const plain = component => typeof component === 'string' ? component : Array.isArray(component)
      ? component.map(plain).join('') : (component?.text || '') + (component?.extra || []).map(plain).join('');
    for (const source of signs) {
      const point = this.source(...source.position);
      await this.pinChunk(point);
      const since = this.serverLines.length;
      await this.console(`execute in pvp:arena run data get block ${source.position.map((n, axis) => n + [this.offset.x, this.offset.y, this.offset.z][axis]).join(' ')}`);
      const line = this.serverLines.slice(since).find(row => row.text.includes('following block data:'))?.text;
      if (!line) throw new Error(`Missing native sign at ${source.position}: ${line}`);
      const data = mojangson.simplify(mojangson.parse(line.split('following block data: ')[1]));
      for (const face of ['front', 'back']) {
        const encoded = data[`${face}_text`];
        const actual = encoded.messages.map(message => plain(JSON.parse(message)));
        const expected = source[face].messages.slice();
        if (face === 'front' && source.ownerSeat !== undefined) {
          if (actual[0]) owners.push(actual[0]);
          expected[0] = actual[0];
          this.check('Hidden owner sign resolves only a current racer literal', actual[0] === '' || IDS.some(id => actual[0] === `${name(id)}'s Horse`), { source: source.position, actual: actual[0] });
        }
        this.check('Original native navigation face retains all four safe literal lines and glow/color', JSON.stringify(actual) === JSON.stringify(expected)
          && encoded.color === source[face].color && Boolean(encoded.has_glowing_text) === source[face].glowing
          && !/clickEvent|click_event|run_command|selector/.test(JSON.stringify(encoded)), { source: source.position, face, actual, expected });
      }
      this.check('Original sign wax flag is restored', Boolean(data.is_waxed) === source.waxed, { source: source.position });
    }
    this.check('All four native horse-owner signs are present exactly once', owners.length === IDS.length && new Set(owners).size === IDS.length, owners);
    this.log('nativeNavigation', { route: this.routeNumber, signs: signs.length, arrows: signs.filter(sign => sign.front.messages.some(line => /--->|<---/.test(line))).length });
  }
  async resetCheck(id, trigger, expected, label) {
    const prior = this.bots[id].vehicle?.id;
    await trigger();
    await this.wait(() => this.bots[id].vehicle && this.bots[id].vehicle.id !== prior, `${label} remount`, 3000);
    await this.tag(id);
    this.check(label, await this.at(id, expected, 1.2), { expected, actual: await this.horsePosition(id), oldId: prior, newId: this.bots[id].vehicle?.id });
    this.check(`${label} restores the exact native attributes/saddle`, await this.condition(`in pvp:arena if entity ${this.horse(id).replace('limit=1]', 'nbt={Tame:1b,Invulnerable:1b,SaddleItem:{id:"minecraft:saddle"},attributes:[{id:"minecraft:generic.movement_speed",base:0.23d},{id:"minecraft:generic.jump_strength",base:0.85d}]},limit=1]')}`));
  }
  async checkpoint(id, number) {
    const route = rules.routes[String(this.routeNumber)];
    const respawn = route.respawns[String(number)];
    const since = Date.now();
    await this.fixture(id, this.source(...respawn));
    await this.sprint(21);
    this.check(`${id} original checkpoint ${number}/${route.unitsPerLap - 1}`, this.chats.some(row => row.id === id && row.at >= since && row.text.includes(`检查点 ${number}/${route.unitsPerLap - 1}`)),
      this.chats.filter(row => row.id === id && row.at >= since && row.text.includes('检查点')).map(row => row.text));
  }
  async lap(id, number) {
    const route = rules.routes[String(this.routeNumber)];
    for (let checkpoint = 1; checkpoint < route.unitsPerLap; checkpoint++) await this.checkpoint(id, checkpoint);
    const since = Date.now();
    await this.fixture(id, this.source(50.5, 35, -1101.5));
    await this.wait(() => this.chats.some(row => row.id === id && row.at >= since
      && (number === 1 ? row.text.includes('第一圈完成') : row.text.includes(`${name(id)} 已完成赛马`))), `${id} source lap${number} completed`, 3000);
  }
  async run() {
    const since = await this.start('horse');
    await this.wait(() => IDS.every(id => !!this.bots[id].vehicle), 'native horse mounts', 5000);
    const routeChat = this.chats.find(row => row.at >= since && row.id === 'A' && row.text.includes('赛马 · '));
    this.routeNumber = Object.entries(rules.routes).find(([, route]) => routeChat?.text.includes(route.name))?.[0];
    if (!this.routeNumber) throw new Error(`Missing source route: ${routeChat?.text}`);
    this.log('sourceRoute', { number: this.routeNumber, name: rules.routes[this.routeNumber].name });
    const types = [];
    for (const id of IDS) {
      await this.tag(id);
      const horse = this.bots[id].vehicle; types.push(horse.name);
      this.check(`${id} controls a genuine native undead horse`, ['zombie_horse', 'skeleton_horse'].includes(horse.name), { entity: horse.name });
      this.check(`${id} is the native controlling first passenger`, this.passengers.get(`${id}:${horse.id}`)?.[0] === this.bots[id].entity.id);
      this.check(`${id} vanilla controlling-passenger relation selects the owner`, await this.condition(`as ${name(id)} on vehicle on controller if entity @s[name=${name(id)}]`));
      this.log('nativeHorseFlags', { id, horse: horse.id, flags: horse.metadata?.[17] });
      this.check(`${id} native saddle, tame, invulnerability and source movement/jump attributes`, await this.condition(`in pvp:arena if entity ${this.horse(id).replace('limit=1]', 'nbt={Tame:1b,Invulnerable:1b,SaddleItem:{id:"minecraft:saddle"},attributes:[{id:"minecraft:generic.movement_speed",base:0.23d},{id:"minecraft:generic.jump_strength",base:0.85d}]},limit=1]')}`));
      this.check(`${id} retains native collision instead of inventing a no-collision horse team`, await this.condition(`in pvp:arena if entity ${this.horse(id).replace('limit=1]', 'team=,limit=1]')}`));
      this.check(`${id} gets one original reset flag`, this.bots[id].inventory.items().filter(i => i.name === 'carrot_on_a_stick').reduce((n, i) => n + i.count, 0) === 1);
    }
    this.check('Original alternating two zombie/two skeleton horse species', types.filter(x => x === 'zombie_horse').length === 2 && types.filter(x => x === 'skeleton_horse').length === 2, types);
    const witnesses = [[-6,62,-1147],[121,51,-1076],[58,70,-1053],[50,34,-1102]].map(p => this.source(...p));
    for (const p of witnesses) {
      await this.pinChunk(p);
      this.check('Complete high/outer/lap gold geometry is staged', await this.serverBlockIs(p, 'gold_block'), { p });
    }
    const foundation = this.source(58, 14, -1104);
    await this.pinChunk(foundation);
    this.check('Original lower sign gravel retains its native Y14 netherrack foundation', await this.serverBlockIs(foundation, 'netherrack')
      && await this.serverBlockIs(this.source(58, 18, -1104), 'gravel'));
    this.check('Original WRONG WAY text display is scoped', await this.condition('in pvp:arena if entity @e[type=minecraft:text_display,tag=party_horse_wrong_way]'));
    await this.navigation();
    if (this.timeoutCase) {
      for (const id of IDS) await this.stable(id);
      const ticks = rules.routes[this.routeNumber].ticks;
      const ended = () => this.chats.some(row => row.id === 'A' && row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text));
      // Native sign inspections consume real ticks too. Keep the last part in
      // small pulses, so a full-limit sprint cannot run into the next vote.
      await this.sprint(ticks - 600);
      this.check('Original hard deadline has not expired six hundred sprint ticks early', !ended(), { sourceLimit: ticks });
      let advanced = ticks - 600;
      while (!ended() && advanced < ticks + 20) { await this.sprint(20); advanced += 20; }
      await this.wait(ended, 'source timer expires', 5000);
      this.check('Source 4200/3500-tick timeout gives equal untouched raw3 scores', IDS.every(id => this.chats.some(row => row.id === 'A' && row.at >= since && row.text.includes(`第 1 名 ${name(id)}`) && row.text.includes('本轮 +4'))));
    } else {
      const a = this.bots.A;
      this.log('fixtureNote', { description: 'Isolate controller input on the original clear netherrack path. The old northward trace met a real one-block netherrack step at Z=-1108; native collision must remain enabled.' });
      await this.fixture('A', this.source(50.5, 35, -1106));
      await this.wait(() => {
        const last = this.teleports.filter(row => row.id === 'A').at(-1);
        return last && this.confirmations.some(row => row.id === 'A' && row.packet.teleportId === last.packet.teleportId);
      }, 'controller fixture last native teleport confirmed', 3000);
      await sleep(180);
      this.check('Controller fixture preserves the actual server controlling passenger', await this.condition(`as MyGoCheckA on vehicle on controller if entity @s[name=MyGoCheckA]`));
      const original = await this.horsePosition('A');
      this.log('realVehiclePackets', { description: 'Real native vehicle_move messages verify horse controller authority; no rendered physics simulation is claimed.' });
      a._client.write('steer_vehicle', { sideways: 0, forward: 1, jump: 0 });
      for (let step = 1; step <= 10; step++) {
        a._client.write('vehicle_move', { x: original.x + step * .15, y: original.y, z: original.z, yaw: -90, pitch: 0 });
        await sleep(52);
      }
      this.check('Native horse controller movement is accepted on the verified clear source path', await this.at('A', original.offset(1.5, 0, 0), .35),
        { original, actual: await this.horsePosition('A'), corrections: this.vehicleCorrections.filter(row => row.id === 'A').slice(-5) });
      const jumpSince = Date.now(), horseId = a.vehicle.id;
      a._client.write('entity_action', { entityId: a.entity.id, actionId: 5, jumpBoost: 90 });
      await sleep(150);
      this.check('Real START_RIDING_JUMP reaches native horse rearing flag', this.metadata.some(row => row.id === 'A' && row.at >= jumpSince && row.packet.entityId === horseId
        && row.packet.metadata.some(value => value.key === 17 && (value.value & 0x20))), this.metadata.filter(row => row.id === 'A' && row.at >= jumpSince && row.packet.entityId === horseId));
      a._client.write('entity_action', { entityId: a.entity.id, actionId: 6, jumpBoost: 0 });
      a._client.write('steer_vehicle', { sideways: 0, forward: 0, jump: 0 });
      for (const id of IDS) await this.stable(id);
      const nativeA = a.vehicle.id;
      const originalFire = this.source(48, 36, -1110);
      await this.pinChunk(originalFire);
      this.check('Burning fixture uses a genuine original source fire block', await this.serverBlockIs(originalFire, 'fire'));
      this.log('fixtureNote', { description: 'Place the source-spawned horse in the actual original fire block; vanilla collision sets Fire, the module only observes it.' });
      await this.console(`execute in pvp:arena run tp ${this.horse('A')} ${exact(originalFire.offset(.5, 0, .5))}`);
      await sleep(370);
      await this.wait(() => a.vehicle, 'five-tick fire remount', 2000);
      this.check('Burning horse returns to checkpoint zero', a.vehicle.id === nativeA && await this.at('A', this.source(...rules.routes[this.routeNumber].respawns['0']), 1.2));
      this.check('Fire is cleared after source reset', await this.condition(`in pvp:arena if entity ${this.horse('A').replace('limit=1]', 'nbt={Fire:0s},limit=1]')}`));
      const resetPosition = this.source(...rules.routes[this.routeNumber].respawns['0']);
      await this.resetCheck('A', async () => { await this.flag('A'); a.activateItem(); await sleep(120); a.deactivateItem(); }, resetPosition, 'Actual right click rebuilds the owner horse without cooldown');
      await this.resetCheck('A', async () => { const flag = await this.flag('A'); await a.tossStack(flag); }, resetPosition, 'Actual flag drop rebuilds only the thrower-owned horse');
      await this.stable('A');
      const last = rules.routes[this.routeNumber].unitsPerLap - 1;
      const wrongSince = Date.now();
      await this.fixture('A', this.source(...rules.routes[this.routeNumber].respawns[String(last)]));
      await this.sprint(21);
      this.check('Out-of-order final checkpoint does not bypass the course', this.chats.some(row => row.id === 'A' && row.at >= wrongSince && row.text.includes(`检查点 0/${last}`)));
      await this.fixture('A', this.source(50.5, 35, -1101.5));
      this.check('Crossing source gold finish alone does not count a lap', !this.chats.some(row => row.id === 'A' && row.at >= since && row.text.includes('第一圈完成')));
      await this.lap('A', 1); await this.lap('A', 2);
      this.check('One of four finishing does not end the source race early', !this.chats.some(row => row.id === 'A' && row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)));
      await this.lap('B', 1); await this.lap('B', 2);
      await this.lap('C', 1); await this.lap('C', 2);
      await this.wait(() => this.chats.some(row => row.id === 'A' && row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)), 'N-1 source finish threshold', 5000);
      for (const [index, id] of IDS.entries()) this.check(`Source two-lap ordering gives ${id} place${index + 1}`, this.chats.some(row => row.id === 'A' && row.at >= since
        && row.text.includes(`第 ${index + 1} 名 ${name(id)}`) && row.text.includes(`本轮 +${4 - index}`)));
    }
    this.check('All owned horses are discarded on round close', !await this.condition('in pvp:arena if entity @e[tag=party_horse]'));
    this.check('Native horse statuses are restored before the next game', !await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:night_vision"}]}]')
      && !await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:weakness"}]}]'));
    await this.wait(() => IDS.every(id => this.bots[id].currentWindow && this.menuChoices(this.bots[id].currentWindow).length), 'next round after full native scene cleanup', 90000);
    this.check('416 cannot repeat within the five-game series', IDS.every(id => !this.menuChoices(this.bots[id].currentWindow).some(choice => choice.source === 416)));
    for (const p of witnesses) this.check('Pinned original scene witness is actually cleaned', await this.serverBlockIs(p, 'air'), { p });
    const cleanupAt = this.serverLines.length;
    await this.leaveAll(); await this.unpinChunks();
    await this.wait(() => this.serverLines.slice(cleanupAt).some(row => row.text.includes('已结束并清理')), 'owned match delayed cleanup releases region zero', 90000);
    this.log('coverageLimit', { note: 'Native horse entity/saddle/attributes/controller and jump command are checked. Checkpoint travel, burning and time are explicitly controlled fixtures; no rendered full horse course playthrough is claimed.' });
  }
  async skipUnusedMatchId() {
    this.game = null;
    for (const id of IDS) await this.chat(id, '/pvp join minecraftparty');
    await this.wait(() => IDS.every(id => this.bots[id].currentWindow && this.menuChoices(this.bots[id].currentWindow).length), 'dummy match reaches vote menu', 60000);
    const cleanupAt = this.serverLines.length;
    this.log('fixtureNote', { description: 'Cancel this real voting match before any scene is chosen, advancing the normal match ID without altering RNG or production rules.' });
    await this.leaveAll();
    await this.wait(() => this.serverLines.slice(cleanupAt).some(row => row.text.includes('已结束并清理')), 'dummy match region is actually released', 30000);
  }
}

async function main() {
  const allRoutes = process.argv.includes('--all-routes');
  const probe = new HorseProbe(process.argv.includes('--timeout'), allRoutes); let error;
  try {
    await probe.launch(); await probe.connectAll();
    if (!allRoutes) await probe.run();
    else {
      // The real Match IDs begin at zero: paths 3,3,3,1,2 at IDs 0..4.
      // Cancel IDs 1 and 2 while voting, awaiting actual delayed cleanup.
      const completed = [];
      for (let caseNumber = 0; caseNumber < 3; caseNumber++) {
        if (caseNumber === 1) { await probe.skipUnusedMatchId(); await probe.skipUnusedMatchId(); }
        probe.timeoutCase = false; await probe.run(); completed.push(Number(probe.routeNumber));
      }
      probe.check('Every original native route has a full two-lap finish case', new Set(completed).size === 3, completed);
      const durations = [];
      for (let caseNumber = 0; caseNumber < 2; caseNumber++) {
        probe.timeoutCase = true; await probe.run(); durations.push(rules.routes[probe.routeNumber].ticks);
      }
      probe.check('Both original multiplayer hard deadlines are exercised', new Set(durations).size === 2 && durations.includes(3500) && durations.includes(4200), durations);
    }
  }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) void main();
