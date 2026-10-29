'use strict';

// Reason: fixed 5v5 slots, real-client resource gating and live actor replacement
// need a real Fabric server. Keep protocol dependencies and reports outside the
// shipped mod, and never point this runner at the ordinary server port/world.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const modules = process.env.MYGO_PRACTICE_NODE_MODULES || '/tmp/pvp-practice-protocol/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const port = Number(process.env.MYGO_PRACTICE_PORT || 25657);
const runDir = path.resolve(process.env.MYGO_PRACTICE_RUN_DIR || path.join(__dirname, '../build/mygo-practice-5v5'));
const buildRoot = path.resolve(__dirname, '../build') + path.sep;
if (port === 25565 || !Number.isInteger(port) || port < 1024 || port > 65535
    || !runDir.startsWith(buildRoot) || !path.basename(runDir).includes('mygo')) {
  throw new Error('Use an isolated mygo-named build/ directory and a test port other than25565.');
}
fs.mkdirSync(runDir, { recursive: true });
const checks = [], chats = [], observations = [], shots = [], clients = [], packLoads = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const emit = (event, data = {}) => process.stdout.write(JSON.stringify({ event, ...data }) + '\n');
function check(name, pass, actual) {
  const result = { name, pass: !!pass, actual };
  checks.push(result); emit('check', result);
  if (!pass) throw new Error(`Assertion failed: ${name}`);
}
async function waitFor(predicate, label, timeout = 30000) {
  const end = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > end) throw new Error(`Timeout: ${label}`);
    await sleep(100);
  }
}
async function pack(bot, packet) {
  try {
    const url = new URL(packet.url);
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
      throw new Error('This test only accepts the isolated server local HTTP resource pack.');
    }
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 3 });
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Resource pack HTTP${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const sha1 = crypto.createHash('sha1').update(bytes).digest('hex');
    check(`${bot.username} resource pack hash matches`, sha1 === packet.hash, { sha1, expected: packet.hash });
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 4 });
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 0 });
    bot.packReady = true;
    packLoads.push({ name: bot.username, sha1, bytes: bytes.length, rendered: false });
  } catch (error) {
    bot.packFailure = error.message;
    bot._client.write('resource_pack_receive', { uuid: packet.uuid, result: 2 });
    emit('packFailure', { name: bot.username, error: error.stack });
  }
}
function connect(letter) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, version: '1.21.1', auth: 'offline', username: `MyGoCheck${letter}` });
  clients.push(bot);
  let authoritative;
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (authoritative && (name === 'position' || name === 'position_look')) {
      if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
      return name === 'position_look'
        ? write('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true })
        : write('flying', { onGround: true });
    }
    return write(name, packet);
  };
  bot._client.prependListener('position', packet => {
    // Recoil packets contain only relative camera deltas. Preserve the latest
    // real teleport so the stationary probe cannot replay an old world position.
    const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object'
      ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  const world = packet => { const state = packet.worldState || packet; bot.testWorld = state.name ?? state.worldName ?? packet.worldName; };
  bot._client.on('login', world);
  bot._client.on('respawn', world);
  bot._client.on('add_resource_pack', packet => { void pack(bot, packet); });
  bot._client.on('remove_resource_pack', () => { bot.packReady = false; });
  bot.on('spawn', () => { bot.physicsEnabled = false; bot.testSpawned = true; });
  bot.on('messagestr', (message, position) => {
    chats.push({ name: bot.username, message, position, at: Date.now() });
    if (position !== 'game_info') emit('chat', { name: bot.username, message });
  });
  bot._client.on('sound_effect', packet => {
    const name = packet.sound?.data?.soundName || bot.registry.sounds?.[packet.sound?.soundId]?.name || '';
    if (name.startsWith('pvp:mygo.')) shots.push({ name, at: Date.now(), player: bot.username });
  });
  bot.on('error', error => emit('clientError', { name: bot.username, error: error.message }));
  bot.on('kicked', reason => emit('kicked', { name: bot.username, reason }));
  return bot;
}
async function command(bot, text, delay = 180) { bot.chat(text); await sleep(delay); }
async function message(bot, text, regex, timeout = 30000) {
  const index = chats.length;
  await command(bot, text);
  await waitFor(() => chats.slice(index).some(row => row.name === bot.username && regex.test(row.message)), text, timeout);
  return chats.slice(index).find(row => row.name === bot.username && regex.test(row.message)).message;
}
async function info(bot, actor) {
  const line = await message(bot, (actor ? `/execute as ${actor} run ` : '/') + 'pvp mygo practiceinfo', /MyGOPractice\s+\{/);
  const value = JSON.parse(line.slice(line.indexOf('{')));
  observations.push({ ...value, at: Date.now() });
  return value;
}
function verifyRoom(state, humans, label) {
  const roster = state.roster;
  check(`${label}: fixed ten actor seats`, state.total === 10 && roster.length === 10, state.total);
  check(`${label}: exactly five per current side`, roster.filter(row => row.team === 'T').length === 5
    && roster.filter(row => row.team === 'CT').length === 5, roster.map(row => row.team));
  check(`${label}: human replaces one bot`, state.humans === humans && state.bots === 10 - humans
    && roster.filter(row => row.bot).length === 10 - humans, { humans: state.humans, bots: state.bots });
  check(`${label}: all actor UUIDs unique`, new Set(roster.map(row => row.uuid)).size === 10);
}
async function ready(bot) {
  await waitFor(() => bot.testSpawned, `${bot.username}login`);
  if (!bot.packReady) {
    await command(bot, '/pvp mygo pack');
    await waitFor(() => bot.packReady || bot.packFailure, 'local resource pack download');
    if (bot.packFailure) throw new Error(bot.packFailure);
  }
}
async function join(bot, team, difficulty = 'normal', map = 'dust2') {
  await ready(bot);
  const before = chats.length;
  await command(bot, `/pvp practice mygo ${difficulty} ${team} ${map}`);
  await waitFor(() => bot.testWorld === 'pvp:arena', 'practice world teleport', 120000);
  // A correctly inherited dead seat has no inventory until the next round.
  await waitFor(() => chats.slice(before).some(row => row.name === bot.username && /已进入 MyGO 5v5 人机练习/.test(row.message)), 'practice seat admission', 30000);
}
async function leave(bot, text = '/pvp practice leave') {
  if (!bot || bot._client.state === 'disconnected') return;
  await command(bot, text);
  await waitFor(() => bot.testWorld === 'minecraft:overworld', `${text}returns lobby`);
}
async function click(bot, slot) {
  const previous = bot.currentWindow?.id;
  await bot.clickWindow(slot, 0, 0);
  await waitFor(() => bot.currentWindow && bot.currentWindow.id !== previous, `GUI slot${slot}`);
}
async function gui(a) {
  await command(a, '/pvp');
  await waitFor(() => a.currentWindow, 'main GUI');
  await click(a, 22); await click(a, 16); await click(a, 17);
  check('GUI shows all five gun bot difficulties', [11, 12, 13, 14, 15]
    .every(slot => a.currentWindow.slots[slot]?.name.endsWith('_sword')));
  await click(a, 13);
  check('GUI exposes all three MyGO maps', [10, 13, 16]
    .every(slot => !!a.currentWindow.slots[slot]), [10, 13, 16].map(slot => a.currentWindow.slots[slot]?.name));
  for (const slot of [10, 13, 16]) {
    await click(a, slot);
    check(`map slot${slot} offers T CT auto`, a.currentWindow.slots[10]?.name === 'red_wool'
      && a.currentWindow.slots[13]?.name === 'blue_wool' && a.currentWindow.slots[16]?.name === 'white_wool');
    await click(a, 26);
  }
  await click(a, 10);
  await a.clickWindow(10, 0, 0);
  await waitFor(() => a.testWorld === 'pvp:arena', 'GUI starts selected MyGO practice', 120000);
}
async function core() {
  const a = connect('A');
  await waitFor(() => a.testSpawned, 'A login');
  await command(a, '/give @s minecraft:diamond 3');
  await command(a, '/item replace entity @s armor.head with minecraft:iron_helmet');
  const statsBefore = await message(a, '/pvp mygo stats', /MyGO 战绩/);
  await message(a, '/pvp practice mygo impossible t dust2', /未知 MyGO 人机难度/);
  await message(a, '/pvp practice mygo normal invalid dust2', /未知练习队伍/);
  await message(a, '/pvp practice mygo normal t invalid', /未知练习地图/);
  check('invalid practice settings leave player in lobby', a.testWorld === 'minecraft:overworld');
  await command(a, '/pvp practice mygo normal t dust2');
  check('first join waits for resource pack', a.testWorld === 'minecraft:overworld');
  await waitFor(() => a.packReady || a.packFailure, 'first practice resource gate');
  if (a.packFailure) throw new Error(a.packFailure);
  await gui(a);
  let state = await info(a);
  verifyRoom(state, 1, 'GUI join');
  check('GUI difficulty and map selection retained', state.difficulty === 'normal' && state.map === 'dust2', state);
  check('selected T side honored', state.roster.find(row => row.uuid === a.player.uuid)?.team === 'T');
  check('no exit item occupies combat inventory', !a.inventory.items().some(item => item.name === 'barrier'));
  const list = await message(a, '/list', /players online/);
  check('nine bot actors do not count as online players', !/Bot|bot|人机/.test(list), list);

  const b = connect('B');
  await ready(b);
  await command(b, '/give @s minecraft:emerald 7');
  await command(b, '/item replace entity @s armor.feet with minecraft:iron_boots');
  const before = await info(a);
  await join(b, 'ct');
  state = await info(a);
  verifyRoom(state, 2, 'second human');
  const replacement = state.roster.find(row => row.uuid === b.player.uuid);
  const retired = before.roster.find(row => row.bot && !state.roster.some(next => next.uuid === row.uuid));
  check('CT join retires exactly one selected-side bot', replacement?.team === 'CT' && retired?.team === 'CT');
  if (before.phase === 'BUY' && state.phase === 'BUY') {
    check('buy-phase takeover keeps seat HP and ammunition', replacement.hp === retired.hp
      && replacement.ammo === retired.ammo, { replacement, retired });
    await message(b, '/pvp practice mygo team t', /已进入 MyGO 5v5 人机练习/);
    state = await info(a);
    check('buy-phase team selection changes CT to T atomically', state.roster.find(row => row.uuid === b.player.uuid)?.team === 'T'
      && state.humans === 2 && state.total === 10, state.tHumans);
    await message(b, '/pvp practice mygo team ct', /已进入 MyGO 5v5 人机练习/);
    state = await info(a);
    check('buy-phase team selection changes T back to CT atomically', state.roster.find(row => row.uuid === b.player.uuid)?.team === 'CT'
      && state.humans === 2 && state.total === 10, state.ctHumans);
  }
  await message(b, '/pvp practice', /MyGO 人机 5v5/);
  await leave(b, '/hub');
  verifyRoom(await info(a), 1, 'human leave backfill');
  await join(b, 'ct');
  b.quit();
  await sleep(400);
  verifyRoom(await info(a), 1, 'disconnect backfill');

  // Keep A as the OP observer while the remaining real clients need no OP
  // permissions to exercise selection, fullness rejection or resource gating.
  const teamMembers = [];
  for (const letter of ['C', 'D', 'E', 'F']) {
    const bot = connect(letter); await join(bot, 't'); teamMembers.push(bot);
  }
  state = await info(a);
  check('T accepts five humans without changing five slots', state.tHumans === 5 && state.humans === 5, state);
  const rejected = connect('G');
  await ready(rejected);
  await message(rejected, '/pvp practice mygo normal t dust2', /已满|满员|没有.*人机|无.*空位/);
  check('sixth T human cannot overflow selected team', rejected.testWorld === 'minecraft:overworld');
  verifyRoom(await info(a), 5, 'full T unchanged');
  await join(rejected, 'ct'); teamMembers.push(rejected);
  for (const letter of ['H', 'I', 'J', 'K']) {
    const bot = connect(letter); await join(bot, 'ct'); teamMembers.push(bot);
  }
  state = await info(a);
  verifyRoom(state, 10, 'ten real humans');
  const overflow = connect('L');
  await ready(overflow);
  await message(overflow, '/pvp practice mygo normal auto dust2', /已满|满员|没有.*人机|无.*空位/);
  check('eleventh human cannot overflow whole room', overflow.testWorld === 'minecraft:overworld');
  overflow.quit();
  for (const member of teamMembers) await leave(member);
  state = await info(a);
  verifyRoom(state, 1, 'all departing humans backfilled');
  const observingBot = state.roster.find(row => row.bot).uuid;
  await leave(a, '/pvp tpout');
  check('explicit leave restores original inventory and helmet', a.inventory.items().some(item => item.name === 'diamond' && item.count === 3)
    && a.inventory.slots[5]?.name === 'iron_helmet');
  verifyRoom(await info(a, observingBot), 0, 'last human leaves ten bots');
  await message(a, '/pvp practice', /你不在练习模式中/);
  const statsAfter = await message(a, '/pvp mygo stats', /MyGO 战绩/);
  check('practice admission and departure do not write formal gun stats', statsBefore === statsAfter, { statsBefore, statsAfter });

  // Ordinary MyGO must still use the original queue/session path rather than
  // being redirected into a persistent practice room by the shared gun hooks.
  const standardB = connect('B');
  await ready(a); await ready(standardB);
  check('disconnect reconnect restores original inventory and boots', standardB.inventory.items().some(item => item.name === 'emerald' && item.count === 7)
    && standardB.inventory.slots[8]?.name === 'iron_boots');
  await command(a, '/pvp join mygo');
  if (!a.packReady) { await ready(a); await command(a, '/pvp join mygo'); }
  await command(standardB, '/pvp join mygo');
  if (!standardB.packReady) { await ready(standardB); await command(standardB, '/pvp join mygo'); }
  await command(a, '/pvp start dust2');
  await waitFor(() => a.testWorld === 'pvp:arena' && standardB.testWorld === 'pvp:arena', 'ordinary MyGO match starts', 120000);
  let ordinary = await message(a, '/pvp mygo status', /MyGO round=/);
  const activeUntil = Date.now() + 30000;
  while (!/alive=true/.test(ordinary) && Date.now() < activeUntil) {
    await sleep(300);
    ordinary = await message(a, '/pvp mygo status', /MyGO round=/);
  }
  check('ordinary MyGO queue still reaches original round runtime', /alive=true/.test(ordinary) && /hp=100/.test(ordinary), ordinary);
  await command(a, '/pvp mygo shop');
  await waitFor(() => a.currentWindow, 'ordinary MyGO shop');
  check('ordinary MyGO shop remains available', a.currentWindow.slots.slice(9, 43).some(item => item?.name === 'iron_pickaxe'));
  a.closeWindow(a.currentWindow);
  await leave(a, '/hub');
  await waitFor(() => standardB.testWorld === 'minecraft:overworld', 'ordinary match cleanup', 30000);
}

async function observe() {
  const a = connect('A');
  await join(a, 'ct', process.env.MYGO_PRACTICE_DIFFICULTY || 'expert', process.env.MYGO_PRACTICE_MAP || 'dust2');
  let state = await info(a);
  const firstRound = state.round;
  const initial = new Map(state.roster.filter(row => row.bot).map(row => [row.uuid, { ...row, round: state.round }]));
  const moved = new Set(), shotsAtStart = shots.length;
  let ammoDrops = 0, healthDrops = 0, deaths = 0, highestRound = firstRound, plantSeen = false;
  let previous = state;
  const start = Date.now(), timeout = Number(process.env.MYGO_PRACTICE_OBSERVE_MS || 180000);
  while (Date.now() - start < timeout) {
    await sleep(1000);
    state = await info(a);
    highestRound = Math.max(highestRound, state.round);
    for (const actor of state.roster.filter(row => row.bot)) {
      const before = previous.roster.find(row => row.uuid === actor.uuid);
      let beginning = initial.get(actor.uuid);
      if (!beginning || beginning.round !== state.round) {
        beginning = { ...actor, round: state.round };
        initial.set(actor.uuid, beginning);
      }
      // Dead actors teleport to a separate waiting room. Neither that camera
      // transition nor the following round respawn proves physical navigation.
      const stepDistance = before ? Math.hypot(actor.x - before.x, actor.z - before.z) : Infinity;
      if (actor.alive && before?.alive && state.round === previous.round
          && state.phase === 'LIVE' && previous.phase === 'LIVE' && stepDistance < 12
          && Math.hypot(actor.x - beginning.x, actor.z - beginning.z) > 10) moved.add(actor.uuid);
      if (!before || previous.round !== state.round) continue;
      if (before.alive && !actor.alive) deaths++;
      if (before.alive && actor.alive && actor.hp < before.hp) healthDrops++;
      if (actor.gun && actor.gun === before.gun && actor.ammo < before.ammo) ammoDrops++;
    }
    const status = await message(a, '/pvp mygo status', /MyGO round=/);
    plantSeen ||= /bombPlanted=true/.test(status);
    previous = state;
    if (highestRound > firstRound && moved.size >= 6 && deaths > 0 && healthDrops > 0 && ammoDrops > 0) break;
  }
  const audibleShots = shots.slice(shotsAtStart).filter(event => /\.(glock|usp_s|p250|ak47|m4a4|m4a1_s|awp|galil|famas|deagle)/.test(event.name));
  check('at least six alive bots physically navigate over ten blocks', moved.size >= 6, moved.size);
  check('bots spend finite ammunition while firing', ammoDrops > 0, ammoDrops);
  check('bot firefights produce virtual health damage', healthDrops > 0, healthDrops);
  check('bots can eliminate opponents', deaths > 0, deaths);
  check('bot combat advances to a new round', highestRound > firstRound, { firstRound, highestRound });
  // World sounds have normal16-block attenuation; a stationary CT-spawn
  // observer cannot be required to hear firefights at a remote bomb site.
  emit('observation', { elapsedMillis: Date.now() - start, moved: moved.size, ammoDrops, healthDrops, deaths,
    firstRound, highestRound, plantSeen, audibleShots: audibleShots.length });
  await leave(a);
}

async function objective() {
  // Five stationary real defenders keep the round alive without introducing
  // CT bot interception. T bots must walk their real route and plant normally.
  const defenders = ['A', 'B', 'C', 'D', 'E'].map(connect);
  for (const actor of defenders) await ready(actor);
  const [a, b] = defenders;
  const map = process.env.MYGO_PRACTICE_OBJECTIVE_MAP || 'dust2';
  await join(a, 'ct', 'expert', map);
  await command(a, '/pvp mygo buy defuser');
  check('objective defender purchases real defuse kit', a.inventory.slots[41]?.name === 'shears', a.inventory.slots[41]?.name);
  for (const actor of defenders.slice(1)) await join(actor, 'ct', 'expert', map);
  let state = await info(a);
  verifyRoom(state, 5, 'controlled bomb scenario');
  check('all five attackers remain bot actors', state.roster.filter(row => row.team === 'T').every(row => row.bot));
  let status = '', planted = false;
  const until = Date.now() + 150000;
  while (Date.now() < until && !planted) {
    status = await message(a, '/pvp mygo status', /MyGO round=/);
    planted = /bombPlanted=true/.test(status);
    if (!planted) await sleep(1000);
  }
  check('T bot navigates to a real site and completes plant channel', planted, status);
  state = await info(a);
  const match = status.match(/bomb=BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}/);
  if (!match) throw new Error(`Cannot read planted C4 position: ${status}`);
  // Blind the post-plant guards through an ordinary status effect to isolate
  // defuse logic. It must limit AI perception exactly as it limits a player.
  for (const attacker of state.roster.filter(row => row.team === 'T')) {
    await command(a, `/effect give ${attacker.uuid} minecraft:blindness 60 0 true`, 80);
  }
  await command(a, `/tp @s ${Number(match[1]) + 0.5} ${match[2]} ${Number(match[3]) + 0.5}`, 100);
  const departedSeat = state.roster.find(row => row.uuid === a.player.uuid).seat;
  await leave(a);
  state = await info(b);
  const replacement = state.roster.find(row => row.seat === departedSeat);
  check('departed objective defender is replaced by alive bot', replacement.bot && replacement.alive, replacement);
  const kit = await message(b, `/data get entity ${replacement.uuid} Inventory[{Slot:5b}].id`, /following entity data:/);
  check('replacement bot inherits purchased defuse kit', kit.includes('minecraft:shears'), kit);
  const defuseUntil = Date.now() + 22000;
  while (Date.now() < defuseUntil && !chats.some(row => row.name === b.username && /C4 已拆除/.test(row.message))) await sleep(100);
  check('CT replacement bot completes actual defuse channel', chats.some(row => row.name === b.username && /C4 已拆除/.test(row.message)));
  for (const actor of defenders.slice(1)) await leave(actor);
}

async function handover() {
  const players = ['A', 'B', 'C', 'D', 'E', 'F'].map(connect);
  for (const player of players) await ready(player);
  const map = process.env.MYGO_PRACTICE_TRANSFER_MAP || 'mirage';
  for (const player of players.slice(0, 5)) await join(player, 't', 'beginner', map);
  const initial = await info(players[0]);
  verifyRoom(initial, 5, 'handover setup');
  const carrier = initial.roster.find(row => row.bomb);
  check('five T human takeovers retain one real C4 carrier', !!carrier && !carrier.bot && carrier.team === 'T', carrier);
  const departing = players.find(player => player.player.uuid === carrier.uuid);
  const inspector = players.find(player => player !== departing);
  const replacementPlayer = players[5];
  const statsBefore = await message(inspector, '/pvp mygo stats', /MyGO 战绩/);
  await leave(departing);
  let state = await info(inspector);
  let source = state.roster.find(row => row.seat === carrier.seat);
  check('human C4 carrier departure transfers ownership to replacement bot', source.bot && source.bomb, source);
  const liveUntil = Date.now() + 30000;
  while (state.phase !== 'LIVE' && Date.now() < liveUntil) {
    await sleep(300);
    state = await info(inspector);
    source = state.roster.find(row => row.seat === carrier.seat);
  }
  const status = await message(inspector, '/pvp mygo status', /MyGO round=/);
  const anchor = status.match(/range=(-?\d+),(-?\d+),(-?\d+)/);
  if (!anchor) throw new Error(`No map range anchor: ${status}`);
  const enemy = state.roster.find(row => row.bot && row.team === 'CT' && row.alive);
  // Reuse each map's established clear test range. This is only controlled
  // placement: damage must still come from the real bot's finite firearm.
  await command(inspector, `/tp ${source.uuid} ${Number(anchor[1]) + 0.5} ${anchor[2]} ${Number(anchor[3]) + 0.5}`, 60);
  await command(inspector, `/tp ${enemy.uuid} ${Number(anchor[1]) + 6.5} ${anchor[2]} ${Number(anchor[3]) + 0.5}`, 60);
  const injuredUntil = Date.now() + 45000;
  while (Date.now() < injuredUntil && !(source.alive && source.hp > 0 && source.hp < 100)) {
    await sleep(100);
    state = await info(inspector);
    source = state.roster.find(row => row.seat === carrier.seat);
  }
  check('real bot firefight supplies injured source seat', source.alive && source.hp > 0 && source.hp < 100, source);
  const injuredHp = source.hp;
  const injuredRound = state.round;
  await join(replacementPlayer, 't', 'beginner', map);
  state = await info(inspector);
  let incoming = state.roster.find(row => row.seat === carrier.seat);
  check('live takeover keeps same seat and cannot heal injured actor', incoming.uuid === replacementPlayer.player.uuid
    && incoming.hp <= injuredHp && incoming.hp < 100 && state.round === injuredRound, { injuredHp, incoming, round: state.round });
  await leave(replacementPlayer);
  const deadUntil = Date.now() + 90000;
  while (Date.now() < deadUntil) {
    state = await info(inspector);
    source = state.roster.find(row => row.seat === carrier.seat);
    if (!source.alive && source.hp === 0) break;
    await sleep(300);
  }
  check('bot combat produces a dead source seat', !source.alive && source.hp === 0, source);
  const deadRound = state.round;
  await join(replacementPlayer, 't', 'beginner', map);
  state = await info(inspector);
  incoming = state.roster.find(row => row.seat === carrier.seat);
  check('joining dead seat never revives or resets the round', incoming.uuid === replacementPlayer.player.uuid
    && !incoming.alive && incoming.hp === 0 && state.round === deadRound, incoming);
  check('dead-seat human stays in spectator state', replacementPlayer.game.gameMode === 'spectator', replacementPlayer.game.gameMode);
  await leave(replacementPlayer);
  state = await info(inspector);
  source = state.roster.find(row => row.seat === carrier.seat);
  check('dead human departure backfills another dead actor', source.bot && !source.alive && source.hp === 0, source);
  const statsAfter = await message(inspector, '/pvp mygo stats', /MyGO 战绩/);
  check('live/dead/C4 handovers do not write formal stats', statsBefore === statsAfter, { statsBefore, statsAfter });
  for (const player of players.slice(0, 5)) if (player !== departing) await leave(player);
}

const mode = process.env.MYGO_PRACTICE_MODE || 'core';
(mode === 'observe' ? observe() : mode === 'objective' ? objective() : mode === 'handover' ? handover() : core()).then(() => emit('complete', { mode, checks: checks.length })).catch(error => {
  emit('failure', { error: error.stack }); process.exitCode = 1;
}).finally(async () => {
  fs.writeFileSync(path.join(runDir, `mygo-practice-${mode}-results.json`), JSON.stringify({ checks, chats, observations, packLoads, shots }, null, 2));
  for (const bot of clients) if (bot._client.state !== 'disconnected') bot.quit();
  await sleep(300);
  process.exit(process.exitCode || 0);
});
