'use strict';

// Reason: exercise MyGO through genuine 1.21.1 client packets on the isolated
// server, retaining reproducible evidence while keeping npm out of this repo.
// A protocol bot can validate downloaded assets and server state; it cannot
// validate rendered gun models, the scope texture, animation, or audio playback.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');
const { execFileSync } = require('node:child_process');
const moduleRoot = process.env.MYGO_TEST_NODE_MODULES || '/tmp/pvp-protocol-check/node_modules';
const mineflayer = require(path.join(moduleRoot, 'mineflayer'));
const { Vec3 } = require(path.join(moduleRoot, 'vec3'));
const runDir = path.resolve(process.env.MYGO_TEST_RUN_DIR || path.join(__dirname, '../build/mygo-server-test'));
const port = Number(process.env.MYGO_TEST_PORT || 25575);
if (port === 25565 || !path.basename(runDir).includes('mygo')) throw new Error('Use an isolated MyGO test directory and port.');
fs.mkdirSync(runDir, { recursive: true });
const logfile = path.join(runDir, 'protocol-results.jsonl');
const bots = {}, packMode = {}, packs = {}, packRequests = {}, assertions = [], chats = [], healthEvents = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const stringify = value => JSON.stringify(value, (_, entry) => typeof entry === 'bigint' ? entry.toString() : entry);
function emit(event, detail = {}) {
  const record = stringify({ time: new Date().toISOString(), event, ...detail });
  fs.appendFileSync(logfile, `${record}\n`);
  process.stdout.write(`${record}\n`);
}
function check(name, ok, actual, expected) {
  const result = { name, pass: !!ok, actual, expected };
  assertions.push(result); emit('assertion', result);
  if (!ok) throw new Error(`Assertion failed: ${name}`);
}
function itemInfo(item) {
  return item && { name: item.name, count: item.count, slot: item.slot, components: item.components, nbt: item.nbt };
}
function snapshot(id) {
  const bot = bots[id];
  return { id, name: bot.username, position: bot.entity?.position, health: bot.health,
    food: bot.food, dimension: bot.game?.dimension, mode: bot.game?.gameMode,
    selected: bot.quickBarSlot, held: itemInfo(bot.heldItem),
    items: bot.inventory?.slots.map(itemInfo).filter(Boolean),
    window: bot.currentWindow && { title: bot.currentWindow.title, id: bot.currentWindow.id,
      items: bot.currentWindow.slots.map(itemInfo).filter(Boolean) }, packs: packs[id] };
}
function packStatus(bot, packet, result) {
  bot._client.write('resource_pack_receive', { uuid: packet.uuid, result });
  emit('packStatus', { id: bot.testId, uuid: packet.uuid, result, headless: true });
}
async function handlePack(bot, packet) {
  const id = bot.testId;
  packRequests[id] = packet;
  emit('packRequested', { id, ...packet });
  if (packMode[id] === 'hold') return;
  if (packMode[id] === 'decline') { packStatus(bot, packet, 1); return; }
  packStatus(bot, packet, 3);
  try {
    const url = new URL(packet.url);
    const local = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
    if (!local && url.protocol !== 'https:') throw new Error('Non-local resource packs must use HTTPS.');
    const response = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error(`Download returned HTTP ${response.status}`);
    const body = Buffer.from(await response.arrayBuffer());
    if (body.length > 30 * 1024 * 1024) throw new Error('Unexpected resource pack size over 30 MiB.');
    const hash = crypto.createHash('sha1').update(body).digest('hex');
    check(`${id} resource ZIP SHA-1`, hash === packet.hash, hash, packet.hash);
    const destination = path.join(runDir, `download-${id}-${hash}.zip`);
    fs.writeFileSync(destination, body);
    const validation = JSON.parse(execFileSync('python3', ['-c',
      'import json,sys,zipfile\nwith zipfile.ZipFile(sys.argv[1]) as z:\n assert z.testzip() is None\n names=z.namelist()\n parsed={n:json.loads(z.read(n)) for n in names if n.endswith((".json", ".mcmeta"))}\n assert parsed["pack.mcmeta"]["pack"]["pack_format"] == 34\n print(json.dumps({"entries":len(names),"jsonFiles":len(parsed),"models":sum(n.endswith(".json") and "/models/" in n for n in names),"scope":any(n.endswith("pumpkinblur.png") for n in names),"sounds":sum(n.endswith(".ogg") for n in names),"format":34}))', destination], { encoding: 'utf8' }));
    check(`${id} resource ZIP contains scope`, validation.scope, validation, 'pumpkinblur.png');
    packs[id] = { uuid: packet.uuid, sha1: hash, size: body.length, destination, validation,
      rendered: false, note: 'Downloaded and checked; headless bot reports loaded to exercise protocol gating.' };
    packStatus(bot, packet, 4);
    packStatus(bot, packet, 0);
    emit('packValidated', { id, ...packs[id] });
  } catch (error) {
    packStatus(bot, packet, 2);
    emit('packError', { id, message: error.stack });
  }
}
function connect(id) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: `MyGoCheck${id}`,
    version: '1.21.1', auth: 'offline', hideErrors: false });
  bot.testId = id;
  bots[id] = bot;
  packMode[id] ??= 'accept';
  // These probes stand still and use server /tp for setup. Mineflayer's delayed
  // respawn reply can replay a stale location after a second teleport; retain
  // the latest authoritative server coordinates in all stationary replies.
  let authoritativePosition;
  const originalWrite = bot._client.write.bind(bot._client);
  bot._client.write = (name, packet) => {
    if (authoritativePosition && (name === 'position' || name === 'position_look')) {
      if (bot.entity) { bot.entity.position.set(authoritativePosition.x, authoritativePosition.y, authoritativePosition.z); bot.entity.onGround = true; }
      // No walking is part of these scenarios. Acknowledge teleports through
      // teleport_confirm, and send standing/look packets instead of replaying
      // the physics plugin's delayed coordinates across dimensions or rounds.
      if (name === 'position_look') return originalWrite('look', { yaw: packet.yaw, pitch: packet.pitch, onGround: true });
      return originalWrite('flying', { onGround: true });
    }
    return originalWrite(name, packet);
  };
  bot._client.prependListener('position', packet => {
    const prior = authoritativePosition || bot.entity?.position || new Vec3(0, 0, 0);
    const relative = axis => typeof packet.flags === 'object' ? packet.flags[axis] : !!(packet.flags & { x: 1, y: 2, z: 4 }[axis]);
    authoritativePosition = new Vec3((relative('x') ? prior.x : 0) + packet.x,
      (relative('y') ? prior.y : 0) + packet.y, (relative('z') ? prior.z : 0) + packet.z);
  });
  bot.on('spawn', () => { bot.physicsEnabled = false; emit('spawn', snapshot(id)); });
  bot.on('messagestr', (message, position) => {
    chats.push({ id, message, position, at: Date.now() });
    if (position !== 'game_info') emit('chat', { id, message });
  });
  bot.on('windowOpen', window => emit('windowOpen', { id, title: window.title,
    items: window.slots.map(itemInfo).filter(Boolean) }));
  bot.on('health', () => { healthEvents.push({ id, health: bot.health, at: Date.now() }); emit('health', { id, health: bot.health, food: bot.food }); });
  bot.on('title', (title, type) => emit('title', { id, title, type }));
  bot.on('error', error => emit('error', { id, message: error.stack }));
  bot.on('kicked', reason => emit('kicked', { id, reason }));
  bot.on('end', reason => emit('end', { id, reason }));
  bot._client.on('add_resource_pack', packet => { void handlePack(bot, packet); });
  bot._client.on('remove_resource_pack', packet => { delete packs[id]; emit('packRemoved', { id, ...packet }); });
  bot._client.on('entity_equipment', packet => {
    if (packet.entityId === bot.entity?.id) emit('selfEquipment', { id, ...packet });
  });
}
async function chat(id, text, delay = 200) {
  bots[id].chat(text);
  await sleep(delay);
}
async function waitUntil(predicate, timeout = 15000, label = 'condition') {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Timeout waiting for ${label}`);
    await sleep(100);
  }
}
async function status(id) {
  const start = chats.length;
  await chat(id, '/pvp mygo status', 180);
  const message = chats.slice(start).find(entry => entry.id === id && entry.message.includes('MyGO round='))?.message;
  if (!message) throw new Error(`No MyGO status for bot ${id}`);
  const state = Object.fromEntries([...message.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(match => [match[1], match[2]]));
  for (const key of ['round', 'remainingTicks', 'team', 'hp', 'cash', 'ammo', 'reserve']) state[key] = Number(state[key]);
  for (const key of ['alive', 'vest', 'helmet', 'bombPlanted']) state[key] = state[key] === 'true';
  state.sites = [...message.matchAll(/BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}/g)].map(match => ({ x: +match[1], y: +match[2], z: +match[3] }));
  state.raw = message;
  return state;
}
async function waitPhase(id, phase, timeout = 25000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const state = await status(id);
    if (state.phase === phase) return state;
    await sleep(800);
  }
  throw new Error(`Timeout waiting for MyGO phase ${phase}`);
}
async function teleport(id, position) {
  await chat('A', `/execute in pvp:arena run tp MyGoCheck${id} ${position.x + .5} ${position.y} ${position.z + .5}`, 200);
}
async function select(id, slot) {
  bots[id].setQuickBarSlot(slot);
  // Explicit packet also covers an earlier server slot change before the next
  // server-to-client held-slot synchronization reaches the protocol bot.
  bots[id]._client.write('held_item_slot', { slotId: slot });
  await sleep(120);
}
async function trigger(id, count = 1, interval = 250) {
  for (let i = 0; i < count; i++) { bots[id].activateItem(); await sleep(interval); }
}
async function gate() {
  await waitUntil(() => Object.values(bots).every(bot => bot.entity), 30000, 'all bots spawned');
  const probe = 'D';
  packMode[probe] = 'decline';
  const declinedAt = chats.length;
  await chat(probe, '/pvp join mygo', 400);
  await waitUntil(() => chats.slice(declinedAt).some(entry => entry.id === probe && entry.message.includes('DECLINED')), 5000, 'declined pack feedback');
  await chat(probe, '/pvp mygo status', 200);
  check('declining optional pack keeps player in lobby and unready', chats.slice(declinedAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=false')) && bots[probe].game.dimension !== 'pvp:arena', chats.slice(declinedAt).filter(entry => entry.id === probe), 'unready; in lobby');
  packMode[probe] = 'hold';
  delete packRequests[probe];
  await chat(probe, '/pvp join mygo', 200);
  await waitUntil(() => packRequests[probe], 5000, 'held pack request');
  packStatus(bots[probe], packRequests[probe], 3);
  packStatus(bots[probe], packRequests[probe], 4);
  const heldAt = chats.length;
  await chat(probe, '/pvp join mygo', 180);
  await chat(probe, '/pvp mygo status', 180);
  check('ACCEPTED and DOWNLOADED do not unlock matchmaking', chats.slice(heldAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=false')), chats.slice(heldAt).filter(entry => entry.id === probe), 'resourcePackReady=false');
  const timeoutAt = chats.length;
  await waitUntil(() => chats.slice(timeoutAt).some(entry => entry.id === probe && entry.message.includes('加载超时')), 14000, '10 second pack loading timeout');
  check('pack loading timeout removes pending pack', !packs[probe] && chats.slice(timeoutAt).some(entry => entry.id === probe && entry.message.includes('加载超时')), chats.slice(timeoutAt).filter(entry => entry.id === probe), 'timeout; unready');
  packMode[probe] = 'accept';
  await chat(probe, '/pvp join mygo', 200);
  await waitUntil(() => packs[probe], 10000, 'valid pack download after timeout');
  const readyAt = chats.length;
  await chat(probe, '/pvp mygo status', 200);
  check('real ZIP download plus reported LOADED unlocks ready state', chats.slice(readyAt).some(entry => entry.id === probe && entry.message.includes('resourcePackReady=true')), chats.slice(readyAt).filter(entry => entry.id === probe), 'resourcePackReady=true');
  await chat(probe, '/pvp join mygo', 200);
  await chat(probe, '/pvp leave', 250);
  check('queue exit removes only MyGO resource pack', !packs[probe], packs[probe], 'removed');
  return { passed: assertions.filter(result => result.pass).length, failed: assertions.filter(result => !result.pass).length };
}
async function smoke() {
  const ids = Object.keys(bots);
  await waitUntil(() => ids.every(id => bots[id].entity), 30000, 'all bots spawned');
  check('four clients connect to Fabric 1.21.1', ids.length === 4 && ids.every(id => bots[id].version === '1.21.1'), ids, 'ABCD');
  // Save a recognizable lobby item and helmet before joining to verify restoration.
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 20000, 'every resource pack downloaded and checked');
  for (const id of ids) await chat(id, '/pvp join mygo');
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'round loadouts');
  const initial = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  check('balanced 2v2 sides', Object.values(initial).filter(state => state.side === 'T').length === 2 && Object.values(initial).filter(state => state.side === 'CT').length === 2, initial, 'two T and two CT');
  check('quick match begins with $1600 and 100 HP', Object.values(initial).every(state => state.cash === 1600 && state.hp === 100), initial, '1600 cash, 100 virtual HP');
  const attackers = ids.filter(id => initial[id].side === 'T'), defenders = ids.filter(id => initial[id].side === 'CT');
  const carrier = attackers.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  check('exactly one attacker receives C4', !!carrier && attackers.filter(id => bots[id].inventory.items().some(item => item.name === 'redstone')).length === 1, carrier, 'one carrier');
  const gunner = attackers.find(id => id !== carrier), defender = defenders[0], otherDefender = defenders[1];
  await select(gunner, 8); bots[gunner].activateItem(); await sleep(150);
  check('purchase device opens real shop GUI', !!bots[gunner].currentWindow, bots[gunner].currentWindow?.title, 'MyGO shop');
  await bots[gunner].clickWindow(0, 0, 0); await sleep(100); bots[gunner].closeWindow(bots[gunner].currentWindow);
  const bought = await status(gunner);
  check('GUI purchase charges MP5 price', bought.cash === 350 && bots[gunner].inventory.items().filter(item => item.name === 'iron_pickaxe').length === 2, bought, 'MP5 plus P9; cash=350');
  await chat(defender, '/pvp mygo buy vest');
  await chat(defender, '/pvp mygo buy helmet');
  await chat(defender, '/pvp mygo buy kit');
  const armor = await status(defender);
  check('vest helmet and defuse kit charge correct prices', armor.vest && armor.helmet && armor.cash === 200, armor, '1600 - 650 - 350 - 400 = 200');
  await waitPhase(gunner, 'LIVE');
  // Use the unobstructed corridor, away from cover and the bomb site.
  const site = initial[carrier].sites.at(-1);
  if (!site) throw new Error('Status did not include a bomb site');
  const range = { x: site.x - 50, y: site.y, z: site.z - 12 };
  await teleport(gunner, range);
  await teleport(defender, { ...range, x: range.x + 6 });
  await teleport(carrier, { ...range, x: range.x + 3 });
  await select(gunner, 0); bots[gunner].setControlState('sneak', true); await sleep(600);
  check('sneaking with gun equips scope pumpkin', bots[gunner].inventory.slots[5]?.name === 'carved_pumpkin', itemInfo(bots[gunner].inventory.slots[5]), 'carved_pumpkin');
  const friendBefore = await status(carrier), enemyBefore = await status(defender);
  await bots[gunner].lookAt(bots[defender].entity.position.offset(0, .9, 0), true);
  await trigger(gunner, 1, 300);
  const shot = await status(gunner), friendAfter = await status(carrier), enemyAfter = await status(defender);
  check('MP5 emits exactly two bullets per trigger', shot.ammo === 28, shot, '30 -> 28');
  check('friendly target does not take damage or block bullets', friendAfter.hp === friendBefore.hp && enemyAfter.hp < enemyBefore.hp, { friendBefore, friendAfter, enemyBefore, enemyAfter }, 'friend unchanged, enemy damaged');
  bots[gunner]._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
  await sleep(2300);
  const reloaded = await status(gunner);
  check('F swap packet reloads magazine and consumes reserve', reloaded.ammo === 30 && reloaded.reserve === 88, reloaded, '30/88');
  bots[gunner].setControlState('sneak', false); await sleep(150);
  check('releasing sneak clears scope helmet', !bots[gunner].inventory.slots[5], itemInfo(bots[gunner].inventory.slots[5]), 'empty head');
  await teleport(carrier, site); await select(carrier, 5);
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(1000);
  bots[carrier].setControlState('sneak', false); await sleep(2400);
  check('releasing sneak cancels installation', !(await status(carrier)).bombPlanted, await status(carrier), 'unplanted');
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(3300);
  const planted = await status(carrier);
  check('three second plant starts 40 second fuse and rewards $300', planted.bombPlanted && planted.remainingTicks <= 800 && planted.remainingTicks > 700 && planted.cash === 1900, planted, 'planted; approximately 40s; 1900 cash');
  bots[carrier].setControlState('sneak', false);
  await teleport(defender, { ...site, z: site.z + 1 }); await select(defender, 5);
  bots[defender].setControlState('sneak', true); await sleep(150); bots[defender].activateItem(); await sleep(1800);
  await select(defender, 1); await sleep(2500);
  check('switching slots cancels defuse', (await status(defender)).phase === 'LIVE' && (await status(defender)).bombPlanted, await status(defender), 'LIVE and planted');
  await select(defender, 5); bots[defender].activateItem(); await sleep(4300);
  const defused = await status(defender);
  check('four second kit defuse wins round and grants economy rewards', defused.phase === 'POST' && defused.score === '0:1' && defused.cash === 3750, defused, 'POST, defenders lead, 200+300+3250=3750');
  bots[defender].setControlState('sneak', false);
  const next = await waitPhase(defender, 'BUY', 10000);
  check('new round resets HP and unplants C4', next.round === 2 && next.hp === 100 && !next.bombPlanted, next, 'round=2, hp=100, no planted bomb');
  const gunnerNext = await status(gunner);
  check('survivor retains purchased gun and refills ammo', gunnerNext.gun === 'MP5' && gunnerNext.ammo === 30 && gunnerNext.reserve === 90, gunnerNext, 'MP5 30/90');
  for (const id of [carrier, gunner, defender, otherDefender]) await chat(id, '/hub', 250);
  await sleep(500);
  check('leaving restores lobby helmet', bots.A.inventory.slots[5]?.name === 'golden_helmet', itemInfo(bots.A.inventory.slots[5]), 'golden_helmet');
  check('leaving restores lobby inventory', bots.A.inventory.items().some(item => item.name === 'apple' && item.count === 3), snapshot('A'), 'three apples');
  emit('smokeComplete', { passed: assertions.filter(result => result.pass).length,
    failed: assertions.filter(result => !result.pass).length, renderingVerified: false });
  return { initial, gunner, carrier, defender, otherDefender, range, site };
}
async function shutdownSetup() {
  await sleep(6500);
  const ids = Object.keys(bots);
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  await chat('A', '/pvp mygo status', 200);
  await chat('A', '/data get entity MyGoCheckA Inventory', 200);
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 15000, 'shutdown test packs loaded');
  for (const id of ids) await chat(id, '/pvp join mygo', 200);
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'shutdown test loadouts');
  await select('A', 1);
  await waitPhase('A', 'LIVE');
  bots.A.setControlState('sneak', false);
  await sleep(150);
  bots.A.setControlState('sneak', true);
  await sleep(300);
  check('shutdown test begins while scope is equipped', bots.A.inventory.slots[5]?.name === 'carved_pumpkin', itemInfo(bots.A.inventory.slots[5]), 'temporary carved pumpkin');
  emit('shutdownReady', { instruction: 'Send stop on the isolated server console, then run tools/mygo_verify_saved_player.cjs.', snapshot: snapshot('A') });
  return status('A');
}
async function fullMatch() {
  await sleep(6500);
  const ids = Object.keys(bots);
  await chat('A', '/item replace entity MyGoCheckA armor.head with minecraft:golden_helmet');
  await chat('A', '/item replace entity MyGoCheckA hotbar.7 with minecraft:apple 3');
  const statsStart = chats.length;
  for (const id of ids) await chat(id, '/pvp mygo stats', 150);
  for (const id of ids) await chat(id, '/pvp join mygo', 300);
  await waitUntil(() => ids.every(id => packs[id]), 15000, 'full match resource packs');
  for (const id of ids) await chat(id, '/pvp join mygo', 180);
  await chat('A', '/pvp start');
  await waitUntil(() => ids.every(id => bots[id].inventory.items().some(item => item.name === 'iron_pickaxe')), 25000, 'full match loadouts');
  const opening = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const winners = ids.filter(id => opening[id].team === 0), losers = ids.filter(id => opening[id].team === 1), gunner = winners[0];
  const site = opening[gunner].sites.at(-1), range = { x: site.x - 50, y: site.y, z: site.z - 12 };
  for (let round = 1; round <= 7; round++) {
    const buy = await waitPhase(gunner, 'BUY', 15000);
    check(`round ${round} starts alive with restored HP`, buy.round === round && buy.hp === 100 && buy.alive, buy, `round=${round}, hp=100, alive=true`);
    if (round === 2) await chat(gunner, '/pvp mygo buy ak', 150);
    if (round === 7) {
      check('halftime swaps attacker side and resets cash', buy.side === 'CT' && buy.cash === 1600 && buy.score === '6:0', buy, 'CT; cash=1600; score=6:0');
      check('halftime clears previously purchased AK', bots[gunner].inventory.items().filter(item => item.name === 'iron_pickaxe').length === 1,
        snapshot(gunner), 'P9 only');
    }
    await waitPhase(gunner, 'LIVE', 20000);
    await teleport(gunner, range); await select(gunner, 1);
    bots[gunner].setControlState('sneak', false); await sleep(100);
    bots[gunner].setControlState('sneak', true); await sleep(200);
    for (const [index, victim] of losers.entries()) {
      await teleport(victim, { ...range, x: range.x + 6, z: range.z + index * 3 });
      await bots[gunner].lookAt(bots[victim].entity.position.offset(0, 1.6, 0), true);
      await trigger(gunner, 1, 300);
      const dead = await status(victim);
      check(`round ${round} actual P9 headshot eliminates ${victim}`, !dead.alive && dead.hp === 0, dead, 'alive=false; hp=0');
      check(`round ${round} ${victim} stays in adventure death room`, bots[victim].game.gameMode === 'adventure' && bots[victim].entity.position.x < range.x - 10,
        { mode: bots[victim].game.gameMode, position: bots[victim].entity.position }, 'adventure; sealed death room');
    }
    bots[gunner].setControlState('sneak', false);
    if (round < 7) {
      const post = await status(gunner);
      check(`round ${round} elimination awards a single team point`, post.phase === 'POST' && post.score === `${round}:0`, post, `POST; ${round}:0`);
      await waitPhase(gunner, 'BUY', 10000);
    }
  }
  await sleep(6000);
  check('seven victories restore original lobby helmet', bots.A.inventory.slots[5]?.name === 'golden_helmet', itemInfo(bots.A.inventory.slots[5]), 'golden_helmet');
  check('seven victories restore original lobby inventory', bots.A.inventory.items().some(item => item.name === 'apple' && item.count === 3), snapshot('A'), 'three apples');
  const statsFinish = chats.length;
  for (const id of ids) await chat(id, '/pvp mygo stats', 150);
  emit('fullMatchComplete', { winners, losers,
    before: chats.slice(statsStart).filter(entry => entry.message.includes('MyGO 战绩')).slice(0, 4),
    after: chats.slice(statsFinish), finalScore: '7:0' });
  return { winners, losers, finalScore: '7:0' };
}
async function explosion() {
  const ids = Object.keys(bots);
  const states = Object.fromEntries(await Promise.all(ids.map(async id => [id, await status(id)])));
  const attackers = ids.filter(id => states[id].side === 'T'), defenders = ids.filter(id => states[id].side === 'CT');
  const carrier = attackers.find(id => bots[id].inventory.items().some(item => item.name === 'redstone'));
  const site = states[carrier].sites.at(-1), gunner = defenders[0];
  for (const id of ids) bots[id].setControlState('sneak', false);
  await teleport(carrier, site); await select(carrier, 5);
  bots[carrier].setControlState('sneak', true); await sleep(150); bots[carrier].activateItem(); await sleep(3300);
  check('explosion scenario installs live C4', (await status(carrier)).bombPlanted, await status(carrier), 'planted');
  bots[carrier].setControlState('sneak', false);
  const position = { x: site.x - 5, y: site.y, z: site.z - 5 };
  await teleport(gunner, position); await select(gunner, 1);
  bots[gunner].setControlState('sneak', true); await sleep(200);
  for (const [index, victim] of attackers.entries()) {
    await teleport(victim, { ...position, x: position.x + 5, z: position.z + index * 2 });
    await bots[gunner].lookAt(bots[victim].entity.position.offset(0, 1.6, 0), true);
    await trigger(gunner, 1, 300);
    check(`planted round eliminates attacker ${victim}`, !(await status(victim)).alive, await status(victim), 'dead');
  }
  const continued = await status(gunner);
  check('eliminating every attacker does not end planted round', continued.phase === 'LIVE' && continued.bombPlanted,
    continued, 'LIVE; bombPlanted=true');
  bots[gunner].setControlState('sneak', false);
  await sleep(Math.max(0, continued.remainingTicks * 50 + 250));
  const result = await status(gunner);
  check('real 40 second C4 explosion wins for eliminated attackers', result.phase === 'POST' && result.score === '1:0', result, 'POST; attackers lead 1:0');
  return result;
}
async function execute(command) {
  const id = command.who || 'A', bot = bots[id];
  switch (command.op) {
    case 'chat': await chat(id, command.text, command.delay ?? 250); return;
    case 'commands': for (const text of command.commands) await chat(id, text, command.delay ?? 200); return;
    case 'wait': await sleep(Math.min(command.ms ?? 500, 30000)); return;
    case 'ready': await waitUntil(() => Object.values(bots).every(b => b.entity), 30000, 'all bots spawned'); return;
    case 'snapshot': return command.who ? snapshot(id) : Object.fromEntries(Object.keys(bots).map(key => [key, snapshot(key)]));
    case 'status': {
      return status(id);
    }
    case 'smoke': return smoke();
    case 'gate': return gate();
    case 'shutdownSetup': return shutdownSetup();
    case 'fullMatch': return fullMatch();
    case 'explosion': return explosion();
    case 'packMode': packMode[id] = command.value; return packMode[id];
    case 'packProcess': if (!packRequests[id]) throw new Error('No pack requested'); await handlePack(bot, packRequests[id]); return;
    case 'packReply': packStatus(bot, { uuid: command.uuid || packRequests[id]?.uuid }, command.result); return;
    case 'slot': bot.setQuickBarSlot(command.slot); await sleep(150); return itemInfo(bot.heldItem);
    case 'use': bot.activateItem(command.offhand || false); await sleep(command.delay ?? 250); return itemInfo(bot.heldItem);
    case 'fire':
      for (let i = 0; i < (command.count ?? 1); i++) {
        bot.activateItem(); await sleep(command.interval ?? 250);
      }
      return snapshot(id);
    case 'reload':
      bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
      await sleep(command.delay ?? 250); return;
    case 'drop':
      bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
      await sleep(command.delay ?? 250); return;
    case 'release': bot.deactivateItem(); return;
    case 'move': bot.setControlState(command.control || 'forward', command.value); await sleep(command.delay ?? 150); return;
    case 'physics': bot.physicsEnabled = command.value; return;
    case 'look': await bot.look(command.yaw, command.pitch, true); return;
    case 'lookAt': await bot.lookAt(new Vec3(command.x, command.y, command.z), true); return;
    case 'aim': {
      const target = bots[command.target || 'B']?.entity;
      if (!target) throw new Error('Target is not present');
      await bot.lookAt(target.position.offset(0, command.height ?? 1.1, 0), true); return;
    }
    case 'click': await bot.clickWindow(command.slot, command.button ?? 0, command.mode ?? 0); await sleep(200); return;
    case 'close': if (bot.currentWindow) bot.closeWindow(bot.currentWindow); return;
    case 'block': {
      const position = new Vec3(command.x, command.y, command.z), block = bot.blockAt(position);
      return { position, name: block?.name, properties: block?.getProperties() };
    }
    case 'activateBlock': {
      const block = bot.blockAt(new Vec3(command.x, command.y, command.z));
      if (!block) throw new Error('Block is not loaded');
      await bot.activateBlock(block); return;
    }
    case 'assertItem': {
      const items = bot.inventory.slots.filter(Boolean).filter(item => item.name === command.item);
      const count = items.reduce((sum, item) => sum + item.count, 0);
      const pass = (command.count === undefined || count === command.count) &&
        (command.slot === undefined || items.some(item => item.slot === command.slot)) &&
        (command.contains === undefined || stringify(items).includes(command.contains));
      check(command.name || `${id} ${command.item}`, pass, items.map(itemInfo), command); return;
    }
    case 'assertChat': {
      const found = chats.filter(entry => entry.id === id && entry.message.includes(command.contains));
      check(command.name || `${id} chat ${command.contains}`, found.length > 0, found.slice(-3), command.contains); return;
    }
    case 'assertMode': check(command.name || `${id} mode`, bot.game.gameMode === command.mode, bot.game.gameMode, command.mode); return;
    case 'assertState': {
      const actual = await status(id);
      check(command.name || `${id} state`, Object.entries(command.expected).every(([key, value]) => actual[key] === value), actual, command.expected); return;
    }
    case 'assertHealth': check(command.name || `${id} health`, Math.abs(bot.health - command.value) < 0.05, bot.health, command.value); return;
    case 'assertPosition': {
      const actual = bot.entity.position;
      check(command.name || `${id} position`, actual.distanceTo(new Vec3(command.x, command.y, command.z)) <= (command.tolerance ?? 0.2), actual, command); return;
    }
    case 'summary': return { passed: assertions.filter(result => result.pass).length,
      failed: assertions.filter(result => !result.pass).length, assertions, log: logfile,
      renderingVerified: false };
    case 'reconnect': connect(id); return;
    case 'quit': if (command.who) bot.quit(); else Object.values(bots).forEach(b => b.quit()); return;
    default: throw new Error(`Unknown operation ${command.op}`);
  }
}

for (const id of process.env.MYGO_TEST_BOTS || 'ABCD') connect(id);
let queue = Promise.resolve();
readline.createInterface({ input: process.stdin }).on('line', line => {
  queue = queue.then(async () => {
    let command;
    try {
      command = JSON.parse(line); emit('commandStart', { command });
      const result = await execute(command); emit('result', { command, result });
    } catch (error) { emit('commandError', { command, message: error.stack }); }
  });
});
