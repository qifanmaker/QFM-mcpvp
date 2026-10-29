'use strict';

// Reason: fake players, vanilla combat and cross-world GUI transitions require
// a real Fabric 1.21.1 server; unit tests alone cannot validate their lifecycle.
// Run against tools/mygo_test_server.py's isolated runtime, never the live server.
const fs = require('node:fs');
const path = require('node:path');
const modules = process.env.PRACTICE_TEST_NODE_MODULES || '/tmp/pvp-practice-protocol/node_modules';
const mineflayer = require(path.join(modules, 'mineflayer'));
const { Vec3 } = require(path.join(modules, 'vec3'));
const port = Number(process.env.PRACTICE_TEST_PORT || 25575);
const runDir = path.resolve(process.env.PRACTICE_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-practice-test'));
if (port === 25565 || !runDir.includes(`${path.sep}build${path.sep}`)) throw new Error('Use an isolated build/ test server.');
fs.mkdirSync(runDir, { recursive: true });
const checks = [], clients = [], chats = [], health = [], actions = [], arrows = [], hooks = [], fluids = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const log = (event, data = {}) => process.stdout.write(`${JSON.stringify({ event, ...data })}\n`);
function check(name, pass, detail) {
  const entry = { name, pass: !!pass, detail };
  checks.push(entry); log('check', entry);
  if (!pass) throw new Error(name);
}
async function waitFor(predicate, label, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timeout: ${label}`);
    await sleep(80);
  }
}
function connect(letter) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, version: '1.21.1', auth: 'offline', username: `MyGoCheck${letter}` });
  clients.push(bot);
  let authoritative;
  const write = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    // Stationary probes acknowledge teleports but never replay stale client
    // physics coordinates across practice's repeated round/world teleports.
    if (authoritative && (name === 'position' || name === 'position_look')) {
      if (bot.entity) bot.entity.position.set(authoritative.x, authoritative.y, authoritative.z);
      return name === 'position_look'
        ? write('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true })
        : write('flying', { onGround: true });
    }
    return write(name, packet);
  };
  bot._client.prependListener('position', packet => {
    const prior = authoritative || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object' ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritative = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  const world = packet => { const state = packet.worldState || packet; bot.testWorld = state.name ?? state.worldName ?? packet.worldName; };
  bot._client.on('login', world);
  bot._client.on('respawn', world);
  bot.on('spawn', () => { bot.physicsEnabled = false; bot.testSpawned = true; });
  bot.on('messagestr', (message, position) => {
    chats.push({ name: bot.username, message, position, time: Date.now() });
    if (position !== 'game_info') log('chat', { name: bot.username, message });
    else actions.push(message);
  });
  bot.on('health', () => health.push({ name: bot.username, value: bot.health, time: Date.now() }));
  bot.on('entitySpawn', entity => {
    if (entity.name === 'arrow') {
      arrows.push({ id: entity.id, time: Date.now() });
      if (bot.testInspectArrowTags && !bot.testArrowTagsRequested) {
        bot.testArrowTagsRequested = true;
        bot.chat(`/data get entity ${entity.uuid} Tags`);
      }
    }
    if (entity.name === 'fishing_bobber') hooks.push({ id: entity.id, time: Date.now() });
  });
  bot.on('blockUpdate', (oldBlock, newBlock) => {
    if (oldBlock?.name === 'water' || newBlock?.name === 'water') fluids.push({ before: oldBlock?.name, after: newBlock?.name, pos: newBlock?.position, time: Date.now() });
  });
  bot.on('error', error => log('clientError', { name: bot.username, error: error.stack }));
  bot.on('kicked', reason => log('kicked', { name: bot.username, reason }));
  return bot;
}
async function command(bot, text, delay = 300) { bot.chat(text); await sleep(delay); }
async function messageAfter(bot, text, expected) {
  const start = chats.length;
  await command(bot, text);
  await waitFor(() => chats.slice(start).some(row => row.name === bot.username && expected.test(row.message)), `${text}: ${expected}`);
  return chats.slice(start).find(row => row.name === bot.username && expected.test(row.message)).message;
}
function npc(bot) {
  return Object.values(bot.entities).find(entity => entity.id !== bot.entity.id
    && (entity.username?.startsWith('PvP_Bot_L') || Object.values(bot.players).some(player => player.username.startsWith('PvP_Bot_L') && player.entity?.id === entity.id)));
}
async function start(bot, difficulty = 'expert', kit = 'sword') {
  await command(bot, `/pvp practice pvp ${difficulty} ${kit}`);
  await waitFor(() => bot.testWorld === 'pvp:arena' && npc(bot), 'practice arena and humanoid spawn');
  return npc(bot);
}
async function leave(bot, text = '/pvp practice leave') {
  await command(bot, text);
  await waitFor(() => bot.testWorld === 'minecraft:overworld', `${text} returns to lobby`);
  await waitFor(() => !npc(bot), 'practice bot removed');
}
async function click(bot, slot) {
  const previous = bot.currentWindow;
  await bot.clickWindow(slot, 0, 0);
  await waitFor(() => bot.currentWindow && bot.currentWindow.id !== previous.id, `menu transition slot ${slot}`);
}
async function botHealth(bot) {
  const entity = npc(bot);
  const line = await messageAfter(bot, `/data get entity ${entity.uuid} Health`, /following entity data:/);
  const value = Number(line.match(/following entity data: ([\d.]+)f/)?.[1]);
  if (!Number.isFinite(value)) throw new Error(`Unparseable bot health: ${line}`);
  return value;
}
async function botItemCount(bot, item, slot) {
  const slotFilter = slot === undefined ? '' : `,Slot:${slot}b`;
  const line = await messageAfter(bot, `/data get entity ${npc(bot).uuid} Inventory[{id:"minecraft:${item}"${slotFilter}}].count`, /following entity data:|Found no elements/);
  if (line.includes('Found no elements')) return 0;
  const value = Number(line.match(/following entity data: (\d+)/)?.[1]);
  if (!Number.isFinite(value)) throw new Error(`Unparseable bot inventory count: ${line}`);
  return value;
}
async function supplemental() {
  const a = connect('C');
  await waitFor(() => a.testSpawned, 'supplemental player login');
  if (process.env.PRACTICE_TEST_TOOLS_ONLY !== '1') {
    const kits = ['sword', 'bow', 'full_gear', 'iron_pvp', 'no_debuff', 'gapple', 'axe', 'legacy_1_8'];
    for (const kit of kits) {
      const opponent = await start(a, 'expert', kit);
      check(`${kit} starts with humanoid and full kit hotbar`, opponent.username === 'PvP_Bot_L5'
        && a.inventory.items().length > 0 && !a.inventory.items().some(item => item.name === 'barrier'));
      await leave(a);
    }

    await start(a, 'expert', 'gapple');
    const apples = await botItemCount(a, 'golden_apple');
    check('gapple starts with finite eight apples', apples === 8, apples);
    await sleep(5200);
    await command(a, `/damage ${npc(a).uuid} 14 minecraft:generic`);
    await waitFor(() => actions.some(message => /进食恢复/.test(message)), 'gapple eating action', 10000);
    await sleep(2200);
    const remaining = await botItemCount(a, 'golden_apple');
    const recovered = await botHealth(a);
    check('gapple healing consumes real inventory', remaining < apples && remaining >= 0, { before: apples, after: remaining });
    check('gapple healing recovers normal health', recovered > 6, recovered);
    await leave(a);

    await start(a, 'expert', 'no_debuff');
    const splash = await botItemCount(a, 'splash_potion');
    const speed = await botItemCount(a, 'potion', 3);
    check('NoDebuff starts with finite healing potions', splash === 16, splash);
    const firstAction = actions.length;
    await sleep(5200);
    await waitFor(() => actions.slice(firstAction).some(message => /喝药补给/.test(message)), 'NoDebuff drinks buff potion', 10000);
    await sleep(2200);
    const drinksRemaining = await botItemCount(a, 'potion', 3);
    check('NoDebuff buff drinking consumes real potion', drinksRemaining < speed, { before: speed, after: drinksRemaining });
    await command(a, `/damage ${npc(a).uuid} 14 minecraft:generic`);
    await sleep(2000);
    const healingRemaining = await botItemCount(a, 'splash_potion');
    const healingHealth = await botHealth(a);
    check('NoDebuff splash healing consumes real stack', healingRemaining < splash && healingRemaining >= 0, { before: splash, after: healingRemaining });
    check('NoDebuff splash healing applies vanilla healing', healingHealth > 6, healingHealth);
    await leave(a);
  }

  await start(a, 'expert', 'iron_pvp');
  await sleep(5200);
  await command(a, `/effect give ${npc(a).uuid} minecraft:slowness 8 255 true`, 100);
  const waterStart = fluids.length;
  // Lava supplies a definite ordinary burning/fluid emergency; a one-block
  // fire contact fixture is avoidable by correct combat footwork.
  await command(a, `/execute at ${npc(a).uuid} run setblock ~ ~ ~ minecraft:lava`, 100);
  await waitFor(() => fluids.slice(waterStart).some(event => event.after === 'water'), 'iron bot places real water to extinguish fire', 10000);
  check('iron bot uses real water bucket to extinguish', fluids.slice(waterStart).some(event => event.after === 'water'), fluids.slice(waterStart));
  await sleep(2000);
  const fire = await messageAfter(a, `/data get entity ${npc(a).uuid} Fire`, /following entity data:/);
  check('water extinguishes burning opponent', /following entity data: -?\d+s/.test(fire)
    && Number(fire.match(/following entity data: (-?\d+)s/)?.[1]) <= 0, fire);
  const restoredWater = await botItemCount(a, 'water_bucket', 7);
  check('iron bot recovers original water source into bucket', restoredWater === 1, restoredWater);
  await leave(a);

  await start(a, 'expert', 'iron_pvp');
  await sleep(5200);
  // A short slowness fixture holds the opponent in place while controlled
  // owner teleports establish an approaching target inside real rod range.
  await command(a, `/effect give ${npc(a).uuid} minecraft:slowness 8 255 true`, 100);
  const rodStart = hooks.length;
  const position = npc(a).position.clone();
  for (const distance of [7, 6.5, 6, 5.5, 5, 4.5]) {
    await command(a, `/tp @s ${position.x - distance} 101 ${position.z}`, 150);
  }
  await waitFor(() => hooks.length > rodStart, 'iron bot casts real fishing hook at approaching player', 10000);
  check('iron bot casts physical fishing hook', hooks.length > rodStart, hooks.slice(rodStart));
  await leave(a);
}
async function main() {
  const a = connect('A');
  await waitFor(() => a.testSpawned, 'test player login');
  await messageAfter(a, '/pvp practice pvp impossible sword', /未知人机难度/);
  await messageAfter(a, '/pvp practice pvp normal sumo', /不支持该套件/);
  check('invalid settings do not enter arena', a.testWorld === 'minecraft:overworld', a.testWorld);

  await command(a, '/pvp');
  await waitFor(() => a.currentWindow, 'main menu');
  await click(a, 22); await click(a, 16); await click(a, 15);
  check('GUI exposes all five difficulties', [11, 12, 13, 14, 15].every(slot => a.currentWindow.slots[slot]?.name.endsWith('_sword')),
    [11, 12, 13, 14, 15].map(slot => a.currentWindow.slots[slot]?.name));
  await click(a, 15);
  const kitCount = a.currentWindow.slots.slice(9, 26).filter(Boolean).length;
  check('GUI exposes the eight supported PvP kits', kitCount === 8, kitCount);
  await click(a, 27); await click(a, 26);
  check('GUI back navigation reaches practice category', a.currentWindow.slots[15]?.name === 'diamond_sword');
  await click(a, 15); await click(a, 11);
  await a.clickWindow(9, 0, 0);
  await waitFor(() => a.testWorld === 'pvp:arena' && npc(a), 'GUI kit selection starts practice');
  check('GUI difficulty and kit selection enters practice', npc(a).username === 'PvP_Bot_L1');
  const countHealth = a.health;
  await command(a, '/damage @s 8 minecraft:generic');
  check('countdown blocks player damage', a.health === countHealth, { before: countHealth, after: a.health });
  const ownerBytes = Buffer.from(npc(a).uuid.replaceAll('-', ''), 'hex');
  const ownerInts = [0, 4, 8, 12].map(offset => ownerBytes.readInt32BE(offset));
  await command(a, '/summon minecraft:arrow ~ ~3 ~ '
    + `{Tags:["pvp_practice_round_retired","practice_stale_probe"],Owner:[I;${ownerInts.join(',')}],NoGravity:1b,Motion:[0.0d,0.0d,0.0d]}`);
  const stale = await messageAfter(a, '/execute unless entity @e[tag=practice_stale_probe] run say practice_stale_removed', /practice_stale_removed/);
  check('saved arrow from retired practice round is discarded on entity load', stale.includes('practice_stale_removed'), stale);
  const list = await messageAfter(a, '/list', /players online/);
  check('fake opponent does not consume online player slot', !list.includes('PvP_Bot_'), list);
  await leave(a);

  const difficultyNames = ['新手', '简单', '普通', '困难', '专家'];
  for (let level = 1; level <= 5; level++) {
    const opponent = await start(a, String(level));
    const status = await messageAfter(a, '/pvp practice', /当前练习:/);
    check(`difficulty ${level} creates humanoid and correct profile`, opponent.username === `PvP_Bot_L${level}` && status.includes(difficultyNames[level - 1]), { name: opponent.username, status });
    check(`difficulty ${level} does not issue exit button`, !a.inventory.items().some(item => item.name === 'barrier'));
    await leave(a);
  }

  const melee = await start(a);
  const original = melee.position.clone();
  const healthStart = health.length;
  await waitFor(() => health.slice(healthStart).some(row => row.name === a.username && row.value < 20), 'expert approaches and lands vanilla melee', 25000);
  check('expert bot moves using player physics', npc(a)?.position.distanceTo(original) > 3, { original, current: npc(a)?.position });
  check('expert deals actual combat damage', health.slice(healthStart).some(row => row.value < 20), health.slice(healthStart));

  const beforeHit = await botHealth(a);
  let target = npc(a);
  await command(a, `/tp @s ${target.position.x - 1.5} ${target.position.y} ${target.position.z}`, 100);
  target = npc(a);
  await a.lookAt(target.position.offset(0, 1.5, 0), true);
  const hitPosition = target.position.clone();
  a.setControlState('sprint', true);
  a.attack(target);
  await sleep(400);
  const afterHit = await botHealth(a);
  check('owner melee attack damages fake player', afterHit < beforeHit, { beforeHit, afterHit });
  check('owner attack moves opponent with normal knockback', npc(a).position.distanceTo(hitPosition) > 0.1,
    { before: hitPosition, after: npc(a).position });
  a.setControlState('sprint', false);

  const outsider = connect('B');
  await waitFor(() => outsider.testSpawned, 'outside player login');
  const beforeOutside = await botHealth(a);
  await command(a, `/damage ${npc(a).uuid} 8 minecraft:player_attack by MyGoCheckB`);
  const afterOutside = await botHealth(a);
  check('outsider cannot damage practice opponent', afterOutside >= beforeOutside, { beforeOutside, afterOutside });
  outsider.quit();

  // Vanilla damage commands deliberately pass through the same ALLOW_DAMAGE /
  // ALLOW_DEATH path as normal melee and projectiles; lethal rounds must reset
  // without a vanilla death screen or advancing formal matchmaking statistics.
  const roundStart = chats.length;
  await command(a, `/damage ${npc(a).uuid} 100 minecraft:player_attack by @s`);
  await waitFor(() => chats.slice(roundStart).some(row => /第 \d+ 回合：胜利/.test(row.message)), 'player can defeat bot');
  check('bot death awards practice win without player respawn', a.health > 0 && a.testWorld === 'pvp:arena');
  await waitFor(() => npc(a) && npc(a).id !== melee.id, 'next round spawns fresh opponent');
  await sleep(5700);
  const lossStart = chats.length;
  await command(a, '/damage @s 100 minecraft:generic');
  await waitFor(() => chats.slice(lossStart).some(row => /第 \d+ 回合：落败/.test(row.message)), 'player lethal damage becomes practice loss');
  check('player loss avoids vanilla death state', a.health > 0 && a.testWorld === 'pvp:arena', a.health);
  await leave(a, '/hub');

  await start(a, 'hard', 'iron_pvp');
  check('iron PvP preserves ninth loadout slot', a.inventory.slots[44]?.name === 'lava_bucket', a.inventory.slots[44]?.name);
  await leave(a, '/pvp tpout');

  const bowStart = arrows.length, bowHealth = health.length, bowChats = chats.length;
  a.testInspectArrowTags = true;
  await start(a, 'expert', 'bow');
  await waitFor(() => arrows.length > bowStart, 'bot releases a real arrow', 25000);
  await waitFor(() => chats.slice(bowChats).some(row => /following entity data:.*pvp_practice_round_/.test(row.message)), 'live practice arrow retains current round tag');
  check('legitimate bow projectile receives current round tag', chats.slice(bowChats).some(row => /following entity data:.*pvp_practice_round_/.test(row.message)));
  await waitFor(() => health.slice(bowHealth).some(row => row.name === a.username && row.value < 20), 'bow projectile damages player', 20000);
  check('bow bot fires physical projectiles', arrows.length > bowStart, arrows.slice(bowStart));
  check('bow projectile inflicts damage', health.slice(bowHealth).some(row => row.value < 20));
  await leave(a);

  await command(a, '/pvp join 1v1 sword');
  await messageAfter(a, '/pvp practice pvp', /先离开匹配队列/);
  check('queue membership blocks practice entry', a.testWorld === 'minecraft:overworld');
  await command(a, '/pvp leave');
  await start(a, 'normal');
  a.quit();
  await sleep(500);
  const reconnect = connect('A');
  await waitFor(() => reconnect.testSpawned, 'reconnect after practice disconnect');
  await messageAfter(reconnect, '/pvp practice', /你不在练习模式中/);
  check('disconnect releases practice session', reconnect.testWorld === 'minecraft:overworld');
  await start(reconnect, 'normal');
  await leave(reconnect);

  await command(reconnect, '/pvp practice bridge');
  await waitFor(() => reconnect.testWorld === 'pvp:arena', 'existing bridge practice');
  check('bridge retains original supply and exit item', reconnect.inventory.items().some(item => item.name === 'white_wool')
    && reconnect.inventory.items().some(item => item.name === 'barrier'));
  await leave(reconnect);
  await command(reconnect, '/pvp practice pearl');
  await waitFor(() => reconnect.testWorld === 'pvp:arena', 'existing pearl practice');
  check('pearl retains original supply and exit item', reconnect.inventory.items().some(item => item.name === 'ender_pearl')
    && reconnect.inventory.items().some(item => item.name === 'barrier'));
  await leave(reconnect);
}
const run = process.env.PRACTICE_TEST_SUPPLEMENTAL === '1' ? supplemental : main;
run().then(() => log('complete', { checks: checks.length })).catch(error => {
  log('failure', { error: error.stack }); process.exitCode = 1;
}).finally(async () => {
  const report = process.env.PRACTICE_TEST_TOOLS_ONLY === '1' ? 'practice-tools-results.json'
    : run === supplemental ? 'practice-supplemental-results.json' : 'practice-protocol-results.json';
  fs.writeFileSync(path.join(runDir, report), JSON.stringify({ checks, chats, health, actions, arrows, hooks, fluids }, null, 2));
  for (const client of clients) if (client._client.state !== 'disconnected') client.quit();
  await sleep(300);
  // Mineflayer keeps physics timers for clients that reconnected under the same
  // username. Reports are flushed synchronously and all owned sockets closed.
  process.exit(process.exitCode || 0);
});
