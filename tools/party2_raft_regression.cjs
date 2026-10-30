'use strict';

// Reason: 405 needs native boat ownership/control and original two-lap rules,
// not a generic foot race. Review: isolate Fabric25620, preserve 383-block
// course witnesses, and label console positioning/time fixtures explicitly.
// Protocol clients do not render ice steering; vehicle packets below validate
// vanilla boat/controller acceptance rather than claiming a rendered playthrough.
const path = require('node:path');
const fs = require('node:fs');
const { PartyProbe, GAMES } = require('./party2_protocol_regression.cjs');
const { Vec3 } = require('vec3');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const IDS = ['A', 'B', 'C', 'D'];
const name = id => `MyGoCheck${id}`;
const position = bot => new Vec3(bot.probePosition.x, bot.probePosition.y, bot.probePosition.z);
GAMES.raft = { source: 405 };

class RaftProbe extends PartyProbe {
  constructor() {
    super({ port: 25620, runDir: path.join(__dirname, '../build/mygo-party2-raft') });
    this.passengers = new Map(); this.velocities = []; this.metadata = []; this.rounds = [];
  }
  connect(id) {
    const bot = super.connect(id);
    bot._client.on('set_passengers', packet => {
      this.passengers.set(`${id}:${packet.entityId}`, packet.passengers);
      this.log('nativePassengers', { id, packet });
    });
    bot._client.on('entity_metadata', packet => this.metadata.push({ id, at: Date.now(), packet }));
    bot._client.on('entity_velocity', packet => {
      const row = { id, at: Date.now(), packet }; this.velocities.push(row);
    });
    bot._client.on('vehicle_move', packet => this.log('nativeVehicleCorrection', { id, packet }));
    return bot;
  }
  async condition(condition) {
    const token = `P2_RAFT_${++this.sequence}`;
    await this.console(`execute ${condition} run say ${token}`, 25);
    return this.serverLines.some(row => row.text.includes(`[Server] ${token}`));
  }
  async sprint(ticks) {
    const since = this.serverLines.length;
    this.log('fixtureNote', { description: `Advance ${ticks} native ticks; await Sprint completed.` });
    this.rawConsole(`tick sprint ${ticks}`);
    await this.wait(() => this.serverLines.slice(since).some(row => /Sprint completed/i.test(row.text)), 'tick sprint complete', 60000);
    await sleep(70);
  }
  async boatTag(id) {
    await this.console(`execute as ${name(id)} on vehicle run tag @s add p2_raft_${id}`);
  }
  async mounted(id) { return this.condition(`if data entity ${name(id)} RootVehicle`); }
  async noMount(id) {
    this.bots[id]._client.write('steer_vehicle', { sideways: 0, forward: 0, jump: 2 });
    await sleep(120);
    this.check(`${id} native dismount accepted`, !await this.mounted(id));
  }
  async boatPosition(id) {
    const before = this.serverLines.length;
    await this.console(`execute in pvp:arena run data get entity @e[type=minecraft:boat,tag=p2_raft_${id},limit=1] Pos`);
    const line = this.serverLines.slice(before).find(row => row.text.includes('following entity data:'))?.text;
    const match = line?.match(/following entity data: \[([^\]]+)\]/);
    if (!match) throw new Error(`Missing native boat Pos: ${line}`);
    const values = match[1].split(',').map(value => Number(value.trim().replace(/[df]$/i, '')));
    const result = new Vec3(...values); this.log('nativeBoatPosition', { id, result }); return result;
  }
  async boatAt(id, target, radius = .8) {
    return this.condition(`in pvp:arena positioned ${target.x.toFixed(8)} ${target.y.toFixed(8)} ${target.z.toFixed(8)} if entity @e[type=minecraft:boat,tag=p2_raft_${id},distance=..${radius}]`);
  }
  async fixtureBoat(id, target, mount = false) {
    this.log('fixtureNote', { description: `Console positions ${id}'s owned boat/player at an original source rule witness.`, target });
    await this.console(`ride ${name(id)} dismount`);
    await this.console(`execute in pvp:arena run tp @e[type=minecraft:boat,tag=p2_raft_${id},limit=1] ${target.x.toFixed(8)} ${target.y.toFixed(8)} ${target.z.toFixed(8)}`);
    await this.teleport(id, target);
    if (mount) {
      await this.wait(() => !!this.bots[id].entities[this.rafts[id].id], `${id} tracks owned raft at relocated witness`, 5000);
      await this.board(id);
    }
  }
  async board(id) {
    const bot = this.bots[id];
    // The bot's current boat entity is retained through dismount; actual server
    // interaction is still sent and ownership/controller verified via packets/NBT.
    const boat = this.rafts[id];
    if (!boat || !bot.entities[boat.id]) throw new Error(`Unloaded owned boat for ${id}`);
    bot.mount(bot.entities[boat.id]);
    await sleep(160);
    this.check(`${id} can board its own native boat`, await this.mounted(id));
    const passengers = this.passengers.get(`${id}:${boat.id}`);
    this.check(`${id} is first passenger and vanilla controller`, passengers?.[0] === bot.entity.id, passengers);
    this.rafts[id] = bot.vehicle;
  }
  async selectRecall(id) {
    const bot = this.bots[id];
    const stack = bot.inventory.items().find(item => item.name === 'carrot_on_a_stick');
    if (!stack) throw new Error(`Missing recall item for ${id}`);
    await bot.equip(stack, 'hand');
    return stack;
  }
  async waypoint(id, level) {
    const sourcePoints = {
      11: [[849913.5, 63.2, -118.5], [849925.5, 74.2, 70.5], [850001.5, 74.2, 34.5]],
      12: [[849913.5, 63.2, -118.5], [850013.5, 55.2, 115.5], [850113.5, 70.2, 39.5]],
      13: [[850120.5, 63.2, -78.5], [850156.5, 56.2, 11.5], [850113.5, 70.2, 39.5]],
      14: [[849878.5, 70.2, -166.5], [849925.5, 74.2, 70.5], [850001.5, 74.2, 34.5]]
    };
    this.markers = sourcePoints[this.route];
    const p = this.source(...this.markers[level - 1]);
    await this.fixtureBoat(id, p);
    const since = Date.now(); await this.sprint(21);
    this.check(`${id} source light waypoint ${level}/3`, this.chats.some(row => row.id === id && row.at >= since
      && row.text.includes(`路标 ${level}/3`)), this.chats.filter(row => row.id === id && row.at >= since).map(row => row.text));
  }
  async lap(id, number) {
    for (const level of [1, 2, 3]) await this.waypoint(id, level);
    await this.fixtureBoat(id, this.source(850000.5, 64.2, .5));
    if (number === 1) {
      await this.wait(() => this.chats.some(row => row.id === id && row.text.includes('已完成第 1 圈')), `${id} lap1 counted`, 2000);
    } else await this.wait(() => this.chats.some(row => row.id === 'A' && row.text.includes(`${name(id)} 已完成竹筏竞速`)), `${id} two laps completed`, 2000);
  }
  async run() {
    const since = await this.start('raft');
    const homes = IDS.map(id => position(this.bots[id]));
    // A fresh isolated server has one Party2 match in region0, whose template
    // center is (88,101,88). Verify native boat witnesses below; passenger feet
    // have a seat offset and compact seat order can differ from bot names.
    const origin = new Vec3(88 - 850008, 101 - 64, 88 + 56);
    this.source = (x, y, z) => origin.offset(x, y, z);
    this.rafts = {};
    await this.console('scoreboard objectives add p2_raft_data dummy');
    await this.wait(() => IDS.every(id => !!this.bots[id].vehicle), 'four native rafts mounted', 3000);
    for (const id of IDS) {
      this.rafts[id] = this.bots[id].vehicle;
      await this.boatTag(id);
      this.check(`${id} starts on an actual bamboo BoatEntity`, await this.condition(
        `if entity @a[name=${name(id)},nbt={RootVehicle:{Entity:{id:"minecraft:boat",Type:"bamboo",Invulnerable:1b,Passengers:[{id:"minecraft:rabbit",NoAI:1b,NoGravity:1b}]}}}]`));
      const passengers = this.passengers.get(`${id}:${this.rafts[id].id}`);
      this.check(`${id} starts as first controlling passenger`, passengers?.[0] === this.bots[id].entity.id, passengers);
      this.check(`${id} gets exactly one recall stick`, this.bots[id].inventory.items().filter(item => item.name === 'carrot_on_a_stick').reduce((sum, item) => sum + item.count, 0) === 1);
    }
    const witnesses = [[849955, 61, -247], [849932, 53, 135], [849852, 53, 17], [850164, 54, -13]].map(p => this.source(...p));
    for (const p of witnesses) {
      await this.pinChunk(p);
      this.check('Full original track extremity is staged', await this.serverBlockIs(p, 'end_stone_bricks'), { p });
    }
    const gate = this.source(850000, 66, -74); await this.pinChunk(gate);
    const low = this.source(849904, 58, 70); await this.pinChunk(low);
    const high = this.source(850100, 66, -121); await this.pinChunk(high);
    if (await this.serverBlockIs(gate, 'end_stone_bricks')) this.route = await this.serverBlockIs(low, 'air') ? 11 : 12;
    else this.route = await this.serverBlockIs(high, 'end_stone_bricks') ? 13 : 14;
    this.log('sourceRoute', { route: this.route });
    const finish = this.source(850000, 62, 1); await this.pinChunk(finish);
    this.check('Source two-lap finish coral is retained', await this.serverBlockIs(finish, 'dead_horn_coral_block'));
    const startGate = this.source(850000, 68, 1); await this.pinChunk(startGate);
    this.check('Pregame barrier gate opens at PLAY', await this.serverBlockIs(startGate, 'air'));

    const bot = this.bots.A;
    const vehicleStart = await this.boatPosition('A');
    this.log('realVehiclePackets', { description: 'Vanilla steer_boat and vehicle_move packets move the first-passenger controlled raft; protocol-only client does not render native ice physics.' });
    bot._client.write('steer_boat', { leftPaddle: true, rightPaddle: true });
    for (let step = 1; step <= 20; step++) {
      bot._client.write('vehicle_move', { x: vehicleStart.x, y: vehicleStart.y, z: vehicleStart.z - step * .2, yaw: 180, pitch: 0 });
      await sleep(52);
    }
    bot._client.write('steer_boat', { leftPaddle: false, rightPaddle: false });
    await this.boatPosition('A');
    this.check('Native controller vehicle movement is accepted on source blue ice', await this.boatAt('A', vehicleStart.offset(0, 0, -4), .3));
    await this.noMount('A');
    bot.mount(this.rafts.B); await sleep(150);
    this.check('Another racer cannot board a foreign raft', !await this.mounted('A'));
    await this.board('A');
    bot.attack(this.rafts.A); await sleep(100);
    this.check('Invulnerable owned raft survives real attack packet', await this.condition('in pvp:arena if entity @e[type=minecraft:boat,tag=p2_raft_A]'));

    await this.noMount('A');
    const recallTarget = this.source(849997.5, 64, -10.5);
    await this.teleport('A', recallTarget); await sleep(100);
    await this.selectRecall('A'); bot.activateItem(); await sleep(180); bot.deactivateItem();
    this.check('Actual right click recalls owned raft to player', await this.boatAt('A', recallTarget));
    this.check('Recall stick is consumed during cooldown', !bot.inventory.items().some(item => item.name === 'carrot_on_a_stick'));
    await this.sprint(175);
    this.check('Recall does not return before200ticks', !bot.inventory.items().some(item => item.name === 'carrot_on_a_stick'));
    await this.sprint(25);
    await this.wait(() => bot.inventory.items().some(item => item.name === 'carrot_on_a_stick'), 'recall replenished at200ticks', 1500);
    const dropped = await this.selectRecall('A');
    const dropTarget = recallTarget.offset(2, 0, 0); await this.teleport('A', dropTarget);
    this.log('realDrop', { id: 'A', item: dropped.name }); await bot.tossStack(dropped); await sleep(180);
    this.check('Actual drop recalls only its thrower-owned raft', await this.boatAt('A', dropTarget));
    this.check('Dropped reset item is consumed', !await this.condition('in pvp:arena if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:carrot_on_a_stick"}}]'));

    // Native boat replacement below original Y44 uses the last packed-ice marker.
    await this.teleport('A', recallTarget); await sleep(100);
    const oldRaft = this.rafts.A.id;
    await this.console(`execute in pvp:arena run tp @e[type=minecraft:boat,tag=p2_raft_A] ${recallTarget.x} ${this.source(0, 42, 0).y} ${recallTarget.z}`);
    await this.wait(() => bot.vehicle && bot.vehicle.id !== oldRaft, 'below44 spawns and mounts replacement raft', 2000);
    this.rafts.A = bot.vehicle; await this.boatTag('A');
    this.check('Replacement bamboo raft returns to last packed ice', await this.boatAt('A', recallTarget.offset(0, 1, 0), 1));
    this.check('Old raft was discarded', !bot.entities[oldRaft]);

    // Native bubble-column surface collision, observed through boat tracker metadata/velocity.
    const bubble = this.source(849914.5, 71.9, -198.5); await this.pinChunk(bubble);
    await this.fixtureBoat('A', bubble, true);
    const bubbleSince = Date.now(), bubbleId = this.rafts.A.id;
    // Observe native Motion each server tick. This runtime-only datapack never
    // sets velocity and is not a production resource; it catches the single
    // tick where a client-controlled boat's server Motion is2.7 before reset.
    const pack = path.join(this.runDir, 'mygo-validation-world/datapacks/p2probe');
    fs.mkdirSync(path.join(pack, 'data/p2probe/function'), { recursive: true });
    fs.writeFileSync(path.join(pack, 'pack.mcmeta'), JSON.stringify({ pack: { pack_format: 48, description: 'Isolated native raft motion observer' } }));
    fs.writeFileSync(path.join(pack, 'data/p2probe/function/bubble.mcfunction'), [
      'execute in pvp:arena as @e[type=minecraft:boat,tag=p2_raft_A] store result score @s p2_raft_motion run data get entity @s Motion[1] 1000',
      'execute in pvp:arena as @e[type=minecraft:boat,tag=p2_raft_A] if score @s p2_raft_motion matches 2600.. run say P2_NATIVE_RAFT_BUBBLE_2700',
      'schedule function p2probe:bubble 1t replace'
    ].join('\n') + '\n');
    this.log('fixtureNote', { description: 'Runtime-only datapack samples native raft Motion[1] each tick; it never sets velocity, blocks, scores used by game, or movement. Its own p2_raft_motion objective is observational.' });
    await this.console('scoreboard objectives add p2_raft_motion dummy');
    await this.console('reload', 150);
    await this.console('function p2probe:bubble');
    await this.console(`execute in pvp:arena run data get entity @e[type=minecraft:boat,tag=p2_raft_A,limit=1] Pos`);
    await this.console('data get entity MyGoCheckA RootVehicle');
    try {
      await sleep(3350);
      const motion = this.serverLines.some(row => row.text.includes('P2_NATIVE_RAFT_BUBBLE_2700'));
      this.check('Native bubble column applies upward2.7 Motion to actual player raft', motion, {
        velocities: this.velocities.filter(row => row.at >= bubbleSince && row.packet.entityId === bubbleId),
        wobble: this.metadata.filter(row => row.at >= bubbleSince && row.packet.entityId === bubbleId)
          .flatMap(row => row.packet.metadata.filter(entry => entry.key === 14).map(entry => entry.value))
      });
    } catch (failure) {
      this.bubbleFailure = failure;
      this.log('deferredBubbleFailure', { error: failure.stack, note: 'Keep the required failed bubble check and continue independent lap/cleanup checks; final report remains failed.' });
    } finally {
      await this.console('schedule clear p2probe:bubble');
    }

    // Finish alone and out-of-order checkpoints cannot increment laps.
    await this.fixtureBoat('A', this.source(850000.5, 64.2, .5)); await this.sprint(21);
    this.check('Crossing finish without route markers does not count', !this.chats.some(row => row.id === 'A' && row.text.includes('已完成第 1 圈')));
    const third = this.route === 11 || this.route === 14 ? [850001.5, 74.2, 34.5] : [850113.5, 70.2, 39.5];
    await this.fixtureBoat('A', this.source(...third)); const wrongAt = Date.now(); await this.sprint(21);
    this.check('Out-of-order light3 keeps source waypoint0', this.chats.some(row => row.id === 'A' && row.at >= wrongAt && row.text.includes('路标 0/3')));
    await this.lap('A', 1); await this.lap('A', 2);
    this.check('One finisher does not prematurely end four-player race', this.bots.B.game.gameMode === 'adventure'
      && !this.chats.some(row => row.id === 'A' && row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)));
    await this.lap('B', 1); await this.lap('B', 2);
    await this.lap('C', 1); await this.lap('C', 2);
    await this.wait(() => this.chats.some(row => row.id === 'A' && row.at >= since && /第\s+1\/5\s+轮排名/.test(row.text)), 'third finisher leaves one racer and closes source round', 3000);
    for (const [index, id] of IDS.entries()) {
      this.check(`Source two-lap order ranks ${id} at${index + 1}`, this.chats.some(row => row.id === 'A' && row.at >= since
        && new RegExp(`第 ${index + 1} 名.*${name(id)}`).test(row.text)), this.chats.filter(row => row.id === 'A' && row.at >= since && /第 \d+ 名/.test(row.text)).map(row => row.text));
    }
    this.check('All owned rafts are removed on round close', !await this.condition('in pvp:arena if entity @e[type=minecraft:boat,tag=party_raft]'));
    this.check('Round weakness is restored before next round', !await this.condition('if entity @a[name=MyGoCheckA,nbt={active_effects:[{id:"minecraft:weakness"}]}]'));
    await this.wait(() => IDS.every(id => this.bots[id].currentWindow && this.menuChoices(this.bots[id].currentWindow).length > 0), 'next round menu after source geometry cleanup', 30000);
    this.check('405 result remains in five-round tournament and cannot repeat', IDS.every(id => !this.menuChoices(this.bots[id].currentWindow).some(choice => choice.source === 405)));
    for (const p of witnesses) this.check('Live-loaded complete-course extremity is cleaned', await this.serverBlockIs(p, 'air'), { p });
    await this.leaveAll(); await this.unpinChunks();
    if (this.bubbleFailure) throw this.bubbleFailure;
    this.log('coverageLimit', { note: 'Native boat mount/packet authority, bubble-column server launch and source rule fixtures validated; no rendered vanilla-client ice steering playthrough is claimed.' });
  }
}

async function main() {
  const probe = new RaftProbe(); let error;
  try { await probe.launch(); await probe.connectAll(); await probe.run(); }
  catch (failure) { error = failure; probe.log('failure', { error: failure.stack }); }
  const report = await probe.close(error);
  if (!report.ok) process.exitCode = 1;
}
if (require.main === module) void main();
